package localserver.lib.bilibili

import localserver.lib.bilibili.utils.WbiSign
import localserver.types.bilibili.Official
import localserver.types.bilibili.ReplyList
import localserver.types.bilibili.SpaceArchive
import localserver.types.bilibili.UserCard
import localserver.types.bilibili.UserInfo
import localserver.types.bilibili.UserInfoData
import localserver.types.bilibili.VideoInfo
import localserver.utils.HttpClient
import localserver.utils.Logger

internal const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/**
 * B 站的公共请求头。
 *
 * 文档要求：WBI 签名接口的 Referer 需在 .bilibili.com 下，且 UA 不能为空、不含敏感子串；
 * 图片等静态资源则要求 Referer 留空，故封面/头像统一走 /download 代理并加 no-referrer。
 *
 * 关于 Cookie（实测结论，勿随意改动）：
 *  - search/view/playurl 带上 buvid3 可降低触发风控(-352)的概率；
 *  - 但评论区 /x/v2/reply/wbi/main **不能**带 buvid3：
 *    带上后 B 站会把请求当成已指纹化的客户端，只回 3 条置顶热评且 is_end=true，翻页失效；
 *    不带 cookie 时才会返回完整 20 条并能用 cursor 连续翻页。
 *  因此评论区单独用 noCookieHeader()。
 */
internal fun biliHeader(referer: String = "https://www.bilibili.com/"): Map<String, Any> = mapOf(
    "referer" to referer,
    "user-agent" to USER_AGENT
)

/** 需要带访客指纹的请求头（搜索/取流/空间等接口用） */
internal suspend fun biliHeaderWithFinger(referer: String = "https://www.bilibili.com/"): Map<String, Any> = mapOf(
    "referer" to referer,
    "user-agent" to USER_AGENT,
    "cookie" to Finger.cookie()
)

/**
 * 访客指纹。B 站对多数 Web 接口做风控（-352 风控校验失败），
 * 带上 buvid3 + buvid4 + b_nut 可显著降低触发概率（空间投稿接口由此从 -412 变为可用）。
 * 取自 /x/frontend/finger/spi（一次返回 b_3/b_4）。
 */
internal object Finger {
    @kotlinx.serialization.Serializable
    data class Spi(val code: Int = 0, val data: SpiData? = null)

    @kotlinx.serialization.Serializable
    data class SpiData(val b_3: String = "", val b_4: String = "")

    // 指纹有效期较长，缓存 30 分钟即可，避免每次请求都多打一次接口
    @Volatile private var cached: Pair<String, String>? = null
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 30 * 60 * 1000L

    private suspend fun fetch(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < TTL_MS) return it }
        val spi = runCatching {
            HttpClient.getAs<Spi>(
                "https://api.bilibili.com/x/frontend/finger/spi",
                null,
                mapOf("user-agent" to USER_AGENT)
            )
        }.onFailure {
            Logger.error("bilibili finger/spi failed: ${it.message}")
        }.getOrNull()
        val b3 = spi?.data?.b_3?.takeIf { it.isNotBlank() } ?: return null
        val pair = b3 to spi.data.b_4
        cached = pair
        cachedAt = now
        return pair
    }

    /** 组装 cookie 串；拿不到指纹时返回空串，不阻塞请求 */
    suspend fun cookie(): String {
        val (b3, b4) = fetch() ?: return ""
        val nut = System.currentTimeMillis() / 1000
        return buildString {
            append("buvid3=").append(b3)
            if (b4.isNotBlank()) append("; buvid4=").append(b4)
            append("; b_nut=").append(nut)
        }
    }
}

/**
 * 统一的接口调用包装：把「网络异常/空响应/JSON 解析失败」都收敛成 null，
 * 并记录日志，避免像 getAs 那样抛出后只剩一个 500 空响应体。
 * 注意 B 站风控(412)时会返回 HTML 而非 JSON，这里识别出来给出更明确的日志。
 */
internal suspend inline fun <reified T> safeGetAs(
    label: String,
    url: String,
    parameters: Map<String, Any>? = null,
    headers: Map<String, Any>? = null
): T? = runCatching {
    HttpClient.getAs<T>(url, parameters, headers)
}.onFailure { e ->
    val msg = e.message.orEmpty()
    if (msg.contains("Expected start of the object") || msg.contains("<!DOCTYPE")) {
        Logger.error("bilibili $label 被风控拦截（返回 HTML 而非 JSON），请稍后重试")
    } else {
        Logger.error("bilibili $label failed: ${e.javaClass.simpleName}: $msg")
    }
}.getOrNull()

/** 视频详细信息（含分P列表、UP主、统计数据） */
object View {
    suspend fun get(bvid: String? = null, aid: Long? = null): VideoInfo? {
        if (bvid.isNullOrBlank() && (aid == null || aid <= 0)) return null
        val params = mutableMapOf<String, Any>()
        if (!bvid.isNullOrBlank()) params["bvid"] = bvid else params["aid"] = aid!!
        return safeGetAs(
            label = "view",
            url = "https://api.bilibili.com/x/web-interface/view",
            parameters = params,
            headers = biliHeader()
        )
    }
}

/**
 * 首页推荐流。
 * 走 /x/web-interface/wbi/index/top/feed/rcmd（需 WBI 签名），翻页靠递增 fresh_idx；
 * 实测连续三次 fresh_idx=0/1/2 可拿到 36 条互不重复的视频。
 * 若推荐流被风控，降级到热门榜 /x/web-interface/popular（无需签名、较稳定）。
 */
object Recommend {
    const val MAX_PS = 30

    suspend fun feed(freshIdx: Int = 0, ps: Int = 12): Pair<List<localserver.types.bilibili.RecommendItem>, Boolean> {
        val signed = WbiSign.sign(mapOf(
            "version" to 1,
            "feed_version" to "V8",
            "homepage_ver" to 1,
            "ps" to ps.coerceIn(1, MAX_PS),
            "fresh_idx" to freshIdx.coerceAtLeast(0),
            "fresh_type" to 4
        ))
        val json = safeGetAs<localserver.types.bilibili.Recommend>(
            label = "rcmd",
            url = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd",
            parameters = signed,
            headers = biliHeaderWithFinger("https://www.bilibili.com/")
        )
        // 只保留普通视频（goto=av），过滤直播/广告等
        val items: List<localserver.types.bilibili.RecommendItem> =
            json?.data?.item.orEmpty().filter { it.goto == "av" && it.bvid.isNotBlank() }
        if (json != null && json.code == 0 && items.isNotEmpty()) return items to true
        return emptyList<localserver.types.bilibili.RecommendItem>() to false
    }

    /** 兜底：热门视频榜，按 pn 翻页 */
    suspend fun popular(pn: Int = 1, ps: Int = 12): List<localserver.types.bilibili.RecommendItem> {
        val json = safeGetAs<localserver.types.bilibili.Popular>(
            label = "popular",
            url = "https://api.bilibili.com/x/web-interface/popular",
            parameters = mapOf("pn" to pn.coerceAtLeast(1), "ps" to ps.coerceIn(1, 50)),
            headers = biliHeaderWithFinger("https://www.bilibili.com/")
        )
        if (json == null || json.code != 0) return emptyList()
        // 转成统一结构，前端无需区分两个接口
        return json.data?.list.orEmpty().filter { it.bvid.isNotBlank() }.map {
            localserver.types.bilibili.RecommendItem(
                id = it.aid,
                bvid = it.bvid,
                cid = it.cid,
                goto = "av",
                pic = it.pic,
                title = it.title,
                duration = it.duration,
                pubdate = it.pubdate,
                owner = it.owner,
                stat = localserver.types.bilibili.RecommendStat(
                    view = it.stat?.view ?: 0,
                    like = it.stat?.like ?: 0,
                    danmaku = it.stat?.danmaku ?: 0
                )
            )
        }
    }
}

/**
 * 随机指纹串生成。
 * PiliPlus 用 `Utils.base64EncodeRandomString(16, 64)` 生成 dm_img_str 等参数，
 * 即"随机字节数在 [min, max) 之间，再整体 base64 编码"，用于伪装播放器环境。
 */
internal object RandomFinger {
    private val random = java.util.Random()

    fun base64(minBytes: Int, maxBytes: Int): String {
        val len = minBytes + random.nextInt((maxBytes - minBytes).coerceAtLeast(1))
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * 视频取流。
 *
 * 免登录 1080P（参考 PiliPlus 的 lib/http/video.dart）依赖三个条件，缺一不可：
 *  1. `try_look=1` —— 让 B 站对未登录用户开放 1080P（qn=80）试看；
 *  2. `fnval=4048` —— 一次性返回**全部** DASH 格式流，否则只给低清晰度；
 *  3. `fourk=1` 与一组 `dm_img_*` 设备指纹参数，缺了容易被降级。
 * 实测：同一条视频，不带 try_look 只有 [64,32,16]（最高 720P），
 * 带上后变为 [80,64,32,16]（最高 1080P），且取回的字节真实可播。
 * 注意 1080P+ / 4K 仍需大会员登录，此处只解决"免登录 1080P"。
 */
object PlayUrl {
    /** 只要 MP4（与 DASH 互斥） */
    const val FNVAL_MP4 = 1
    /** 只要 DASH */
    const val FNVAL_DASH = 16
    /** 全部可用 DASH 视频流（免登录 1080P 的关键） */
    const val FNVAL_DASH_ALL = 4048

    /** qn 清晰度代码 */
    const val QN_360P = 16
    const val QN_480P = 32
    const val QN_720P = 64
    const val QN_1080P = 80

    suspend fun get(
        bvid: String,
        cid: Long,
        qn: Int = QN_1080P,
        dash: Boolean = true,
        platform: String? = null,
        tryLook: Boolean = true
    ): localserver.types.bilibili.PlayUrl? {
        val params = mutableMapOf<String, Any>(
            "bvid" to bvid,
            "cid" to cid,
            "qn" to qn,
            "fnver" to 0,
            // 允许 4K 档位参与协商；实际能否拿到仍取决于登录态
            "fourk" to 1,
            // 这两个字段用于规避「预加载」链路的风控
            "gaia_source" to "pre-load",
            "isGaiaAvoided" to true,
            "web_location" to 1315873
        )
        params["fnval"] = if (dash) FNVAL_DASH_ALL else FNVAL_MP4
        if (tryLook) {
            // 免登录 1080P 开关
            params["try_look"] = 1
            // 设备指纹参数：伪造一个弱化的播放器环境，降低被风控降级的概率
            params["dm_img_list"] = "[]"
            params["dm_img_str"] = RandomFinger.base64(16, 64)
            params["dm_cover_img_str"] = RandomFinger.base64(32, 128)
            params["dm_img_inter"] = """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}"""
        }
        if (platform != null) params["platform"] = platform
        val signed = WbiSign.sign(params)
        return safeGetAs(
            label = "playurl",
            url = "https://api.bilibili.com/x/player/wbi/playurl",
            parameters = signed,
            headers = biliHeaderWithFinger("https://www.bilibili.com/")
        )
    }

    /** 从取流结果里挑出实际可用的最高清晰度，供前端默认选中 */
    fun bestQuality(data: localserver.types.bilibili.PlayUrlData?): Int {
        val ids = data?.dash?.video?.map { it.id }?.distinct().orEmpty()
        if (ids.isNotEmpty()) return ids.max()
        return data?.quality ?: QN_360P
    }
}

/**
 * 评论区明细（懒加载接口，需 WBI 签名）。
 * 旧的 /x/v2/reply 已废弃，无 SESSDATA 时固定返回空 replies，故不使用。
 * type=1 表示视频稿件，oid 为 avid；翻页把上一页 cursor.next 作为 next 传回。
 *
 * 注意：此接口必须用 biliHeader()（不带 buvid cookie），
 * 否则 B 站只返回 3 条热评且 is_end=true、翻页失效（实测）。
 */
object Reply {
    const val TYPE_VIDEO = 1

    suspend fun list(
        oid: Long,
        page: Int = 1,
        sort: Int = 3,
        type: Int = TYPE_VIDEO
    ): ReplyList? {
        val params = mutableMapOf<String, Any>(
            "type" to type,
            "oid" to oid,
            // mode: 3 仅按热度 / 2 仅按时间 / 1 热度+时间
            "mode" to sort.coerceIn(0, 3),
            "plat" to 1,
            "web_location" to "1315875"
        )
        // 第一页不带 next；后续页以服务端返回的 cursor.next 作为 next
        if (page > 1) params["next"] = page
        val signed = WbiSign.sign(params)
        return safeGetAs(
            label = "reply/wbi/main",
            url = "https://api.bilibili.com/x/v2/reply/wbi/main",
            parameters = signed,
            headers = biliHeader("https://www.bilibili.com/")
        )
    }
}

/**
 * 用户资料。
 * 优先用 /x/space/wbi/acc/info（信息最全，含直播间、硬核会员等），
 * 但该接口风控极严，游客态经常返回 -352；此时降级到 /x/web-interface/card
 * （无需签名、风控松），它同样能给出昵称/头像/签名/等级/粉丝数/认证。
 */
object User {
    suspend fun info(mid: Long): UserInfo? {
        if (mid <= 0) return null
        // 参考 PiliPlus 的 memberInfo：除 mid 外还要 platform/web_location 与 dm_img_* 指纹，
        // 并带 origin/referer，缺这些会被判为爬虫直接 -352。
        val signed = WbiSign.sign(mapOf(
            "mid" to mid,
            "token" to "",
            "platform" to "web",
            "web_location" to 1550101,
            "dm_img_list" to "[]",
            "dm_img_str" to RandomFinger.base64(16, 64),
            "dm_cover_img_str" to RandomFinger.base64(32, 128),
            "dm_img_inter" to """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}"""
        ))
        val spaceHeader = biliHeaderWithFinger("https://space.bilibili.com/$mid/dynamic") +
            ("origin" to "https://space.bilibili.com")
        val primary = safeGetAs<UserInfo>(
            label = "acc/info",
            url = "https://api.bilibili.com/x/space/wbi/acc/info",
            parameters = signed,
            headers = spaceHeader
        )
        if (primary != null && primary.code == 0 && primary.data != null) return primary

        // 降级：名片接口（风控较松，昵称/头像/签名/等级/粉丝/认证都能拿到）
        val card = safeGetAs<UserCard>(
            label = "card",
            url = "https://api.bilibili.com/x/web-interface/card",
            parameters = mapOf("mid" to mid, "photo" to true),
            headers = spaceHeader
        )
        val c = card?.data?.card ?: return primary
        Logger.debug("bilibili acc/info fallback to card for mid=$mid")
        return UserInfo(
            code = 0,
            message = "OK",
            data = UserInfoData(
                mid = c.mid.toLongOrNull() ?: mid,
                name = c.name,
                sex = c.sex,
                face = c.face,
                sign = c.sign,
                level = c.level_info?.current_level ?: 0,
                silence = if (c.spacesta == -2) 1 else 0,
                official = c.official ?: Official(
                    desc = c.official_verify?.desc.orEmpty(),
                    type = c.official_verify?.type ?: -1
                ),
                vip = c.vip
            )
        )
    }

    /** 关注/粉丝数，用于空间页补充展示（relation/stat 风控较松） */
    suspend fun relation(mid: Long): Pair<Long, Long>? {
        val r = safeGetAs<RelationStat>(
            label = "relation/stat",
            url = "https://api.bilibili.com/x/relation/stat",
            parameters = mapOf("vmid" to mid),
            headers = biliHeaderWithFinger("https://space.bilibili.com/$mid")
        )
        val d = r?.data ?: return null
        return d.follower to d.following
    }

    /** 总播放/总点赞（upstat），空对象表示该用户没有公开数据 */
    suspend fun upstat(mid: Long): UpStatData? = safeGetAs<UpStat>(
        label = "upstat",
        url = "https://api.bilibili.com/x/space/upstat",
        parameters = mapOf("mid" to mid),
        headers = biliHeaderWithFinger("https://space.bilibili.com/$mid")
    )?.takeIf { it.code == 0 }?.data

    /**
     * 空间页所需的资料聚合：一次性把 card + relation + upstat 都取好，
     * 任意一项失败都不影响其余项（各项独立 runCatching）。
     */
    suspend fun profile(mid: Long): SpaceProfile? {
        if (mid <= 0) return null
        // info 可能因为风控/无此用户而解析成"全默认值"的空对象，
        // 这里用 name 是否为空判断其是否真正可用，避免把空资料当成功返回。
        val rawInfo = info(mid)?.data
        val usable = rawInfo?.takeIf { it.name.isNotBlank() }
        val relation = relation(mid)
        val upstat = upstat(mid)
        if (usable == null && relation == null) return null
        return SpaceProfile(
            info = usable,
            follower = relation?.first,
            following = relation?.second,
            view = upstat?.view,
            likes = upstat?.likes
        )
    }
}

@kotlinx.serialization.Serializable
data class RelationStat(val code: Int = 0, val data: RelationStatData? = null)

@kotlinx.serialization.Serializable
data class RelationStatData(val mid: Long = 0, val following: Long = 0, val follower: Long = 0)

@kotlinx.serialization.Serializable
data class UpStat(val code: Int = 0, val data: UpStatData? = null)

@kotlinx.serialization.Serializable
data class UpStatData(val view: Long = 0, val likes: Long = 0)

/** 空间页资料聚合结果；各字段可空，取不到就不展示 */
data class SpaceProfile(
    val info: localserver.types.bilibili.UserInfoData?,
    val follower: Long?,
    val following: Long?,
    val view: Long?,
    val likes: Long?
)

/** 用户投稿视频明细 */
object Space {
    suspend fun archives(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 30,
        order: String = "pubdate",
        keyword: String? = null
    ): SpaceArchive? {
        if (mid <= 0) return null
        val params = mutableMapOf<String, Any>(
            "mid" to mid,
            "pn" to page,
            "ps" to pageSize.coerceIn(1, 50),
            "order" to order
        )
        if (!keyword.isNullOrBlank()) params["keyword"] = keyword
        val signed = WbiSign.sign(params)
        return safeGetAs(
            label = "space/arc/search",
            url = "https://api.bilibili.com/x/space/wbi/arc/search",
            parameters = signed,
            headers = biliHeaderWithFinger("https://space.bilibili.com/$mid")
        )
    }
}
