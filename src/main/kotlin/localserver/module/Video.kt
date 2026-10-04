package localserver.module

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

import localserver.lib.bilibili.AppSpace
import localserver.lib.bilibili.Dyn
import localserver.lib.bilibili.Reply
import localserver.lib.bilibili.Recommend
import localserver.lib.bilibili.Search
import localserver.lib.bilibili.Space
import localserver.lib.bilibili.User
import localserver.lib.bilibili.View
import localserver.lib.bilibili.PlayUrl
import localserver.lib.bilibili.USER_AGENT
import localserver.utils.Http
import localserver.utils.Logger
import localserver.utils.Util.prettyJson

object Video {
    private const val DEFAULT_PAGE = 1

    fun Route.videoRoute() {
        searchByType()
        searchUser()
        page()
        recommend()
        videoInfo()
        videoStream()
        videoStreamInfo()
        videoComments()
        userInfo()
        userSpace()
        userDynamic()
    }

    // ---------- 页面 ----------

    private fun Route.page() = get("/video") {
        call.respondRedirect("/resources/video/index.html")
    }

    // ---------- 首页推荐流 ----------

    /**
     * 首页推荐流。`fresh` 递增即可不断拿到新内容（对应接口的 fresh_idx）。
     * 推荐流被风控时自动降级到热门榜，保证首页永远有内容。
     */
    private fun Route.recommend() = get("/recommend") {
        val fresh = call.parameters["fresh"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val ps = call.parameters["pageSize"]?.toIntOrNull()?.coerceIn(1, Recommend.MAX_PS) ?: 12
        val (items, ok) = Recommend.feed(fresh, ps)
        if (ok) {
            call.respondJsonObject(buildJsonObject {
                put("source", JsonPrimitive("rcmd"))
                put("fresh", JsonPrimitive(fresh))
                put("items", prettyJson.encodeToJsonElement(items))
            })
            return@get
        }
        // 降级：热门榜按页码翻
        Logger.debug("Video recommend fallback to popular, fresh=$fresh")
        val popular = Recommend.popular(fresh + 1, ps)
        if (popular.isEmpty()) {
            return@get call.respondJson("Failed to fetch recommend feed.", HttpStatusCode.BadGateway)
        }
        call.respondJsonObject(buildJsonObject {
            put("source", JsonPrimitive("popular"))
            put("fresh", JsonPrimitive(fresh))
            put("items", prettyJson.encodeToJsonElement(popular))
        })
    }

    // ---------- 搜索 ----------

    /**
     * 统一搜索入口，只用 keyword。
     * type=video 搜视频（默认），type=user 搜用户/UP主。
     * 两者共用同一套字段（page/numResults/numPages），前端可复用分页逻辑。
     */
    private fun Route.searchByType() = get("/searchByType") {
        val keyword = call.parameters["keyword"]?.takeIf { it.isNotBlank() }
            ?: return@get call.respondJson("Please provide \"keyword\".", HttpStatusCode.BadRequest)
        val page = call.parameters["page"]?.toIntOrNull()?.coerceIn(1, Search.MAX_PAGE) ?: DEFAULT_PAGE
        // 对外用简短名称 video/user，内部再映射成 B 站的 search_type
        val publicType = call.parameters["type"]?.takeIf { it.isNotBlank() } ?: Search.PUBLIC_VIDEO
        val isUser = publicType == Search.PUBLIC_USER
        if (Search.apiTypeOf(publicType) == null) {
            return@get call.respondJson("Unknown type \"$publicType\".", HttpStatusCode.BadRequest)
        }
        Logger.debug("Search: type=$publicType keyword=$keyword, page=$page")

        val data = if (isUser) {
            Search.searchUser(keyword, page)
        } else {
            Search.searchVideo(
                keyword = keyword,
                page = page,
                order = call.parameters["order"]?.takeIf { it.isNotBlank() },
                duration = call.parameters["duration"]?.toIntOrNull(),
                tids = call.parameters["tids"]?.toIntOrNull()
            )
        } ?: return@get call.respondJson("Failed to search on bilibili.", HttpStatusCode.BadGateway)

        // 两个分支的列表类型不同，各自直接转成 JsonElement，
        // 不能先赋给同一个变量（会退化成 List<Any>，kotlinx 无法序列化 Any）
        val resultEl: JsonElement = if (isUser) {
            prettyJson.encodeToJsonElement(Search.parseUsers(data))
        } else {
            prettyJson.encodeToJsonElement(Search.parseVideos(data))
        }

        call.respondJsonObject(buildJsonObject {
            put("type", JsonPrimitive(publicType))
            put("page", JsonPrimitive(data.page))
            put("numResults", JsonPrimitive(data.numResults))
            put("numPages", JsonPrimitive(data.numPages.coerceAtMost(Search.MAX_PAGE)))
            put("result", resultEl)
        })
    }

    /** 专门的用户搜索入口（等价于 /searchByType?type=user），便于前端语义清晰 */
    private fun Route.searchUser() = get("/searchUser") {
        val keyword = call.parameters["keyword"]?.takeIf { it.isNotBlank() }
            ?: return@get call.respondJson("Please provide \"keyword\".", HttpStatusCode.BadRequest)
        val page = call.parameters["page"]?.toIntOrNull()?.coerceIn(1, Search.MAX_PAGE) ?: DEFAULT_PAGE
        val userType = call.parameters["userType"]?.toIntOrNull()
        Logger.debug("User search: keyword=$keyword, page=$page")
        val data = Search.searchUser(keyword, page, userType)
            ?: return@get call.respondJson("Failed to search users.", HttpStatusCode.BadGateway)
        call.respondJsonObject(buildJsonObject {
            put("type", JsonPrimitive(Search.TYPE_USER))
            put("page", JsonPrimitive(data.page))
            put("numResults", JsonPrimitive(data.numResults))
            put("numPages", JsonPrimitive(data.numPages.coerceAtMost(Search.MAX_PAGE)))
            put("result", prettyJson.encodeToJsonElement(Search.parseUsers(data)))
        })
    }

    // ---------- 视频信息 ----------

    private fun Route.videoInfo() = get("/videoInfo") {
        val bvid = call.parameters["bvid"]?.takeIf { it.isNotBlank() }
        val aid = call.parameters["aid"]?.toLongOrNull()
        if (bvid == null && (aid == null || aid <= 0)) {
            return@get call.respondJson("Please provide \"bvid\" or \"aid\".", HttpStatusCode.BadRequest)
        }
        val info = View.get(bvid, aid)
            ?: return@get call.respondJson("Failed to fetch video info.", HttpStatusCode.BadGateway)
        // code != 0 时把 B 站原始错误透出去，便于前端提示
        if (info.code != 0) {
            return@get call.respondJson(info.message.ifBlank { "code ${info.code}" }, HttpStatusCode.BadGateway)
        }
        call.respondText(prettyJson.encodeToString(info.data))
    }

    // ---------- 评论 ----------

    private fun Route.videoComments() = get("/videoComments") {
        val aid = call.parameters["aid"]?.toLongOrNull()
            ?: return@get call.respondJson("Please provide numeric \"aid\".", HttpStatusCode.BadRequest)
        // 该接口用游标翻页：第一页不传 pn，之后把上一页 cursor.next 当作 pn 传回
        val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        // mode: 3 仅按热度 / 2 仅按时间 / 1 热度+时间
        val sort = call.parameters["sort"]?.toIntOrNull()?.coerceIn(0, 3) ?: 3
        Logger.debug("Video comments: aid=$aid, page=$page, sort=$sort")
        val json = Reply.list(aid, page, sort)
            ?: return@get call.respondJson("Failed to fetch comments.", HttpStatusCode.BadGateway)
        if (json.code != 0) {
            return@get call.respondJson(json.message.ifBlank { "code ${json.code}" }, HttpStatusCode.BadGateway)
        }
        call.respondText(prettyJson.encodeToString(json.data))
    }

    // ---------- 账号查询 ----------

    /**
     * 用户资料。优先走 APP 端 /x/v2/space（只需 appkey+sign，风控宽松、字段全），
     * 失败再退回 Web 端的 card/acc/info 聚合。
     */
    private fun Route.userInfo() = get("/userInfo") {
        val mid = call.parameters["mid"]?.toLongOrNull()
            ?: return@get call.respondJson("Please provide numeric \"mid\".", HttpStatusCode.BadRequest)

        // 主路径：APP 接口
        val app = AppSpace.info(mid)
        if (app != null && app.code == 0 && app.data?.card != null) {
            val d = app.data
            val c = d.card!!
            val obj = buildJsonObject {
                put("source", JsonPrimitive("app"))
                put("mid", JsonPrimitive(c.mid.toLongOrNull() ?: mid))
                put("name", JsonPrimitive(c.name))
                put("sex", JsonPrimitive(c.sex))
                put("face", JsonPrimitive(c.face))
                put("sign", JsonPrimitive(c.sign))
                put("level", JsonPrimitive(c.level_info?.current_level ?: 0))
                put("fans", JsonPrimitive(c.fans))
                put("following", JsonPrimitive(if (c.attention > 0) c.attention else c.friend))
                put("birthday", JsonPrimitive(c.birthday))
                put("silence", JsonPrimitive(if (c.spacesta == -2) 1 else 0))
                put("is_senior_member", JsonPrimitive(c.is_senior_member))
                put("archiveCount", JsonPrimitive(d.archive?.count ?: 0))
                // 注意 APP 端把认证放在 official_verify（不是 Official）。
                // 两个类型不同，不能写成 `?:` 后统一序列化（会退化成 Any 而抛异常），故分开处理。
                val officialEl: JsonElement? = c.official?.let { prettyJson.encodeToJsonElement(it) }
                    ?: c.official_verify?.let { prettyJson.encodeToJsonElement(it) }
                officialEl?.let { put("official", it) }
                c.vip?.let {
                    put("vip", buildJsonObject {
                        put("status", JsonPrimitive(it.status))
                        put("type", JsonPrimitive(it.typeId))
                        it.label?.text?.takeIf { t -> t.isNotBlank() }
                            ?.let { t -> put("labelText", JsonPrimitive(t)) }
                    })
                }
                c.nameplate?.takeIf { it.name.isNotBlank() }
                    ?.let { put("nameplate", prettyJson.encodeToJsonElement(it)) }
                // 直播间信息（APP 端字段名与 Web 不同，这里统一成 Web 的形状给前端）
                d.live?.let {
                    put("live_room", buildJsonObject {
                        put("roomid", JsonPrimitive(it.roomid))
                        put("liveStatus", JsonPrimitive(it.liveStatus))
                        put("title", JsonPrimitive(it.title))
                        put("cover", JsonPrimitive(it.cover))
                    })
                }
            }
            call.respondJsonObject(obj)
            return@get
        }

        // 降级：Web 端聚合
        Logger.debug("app/space failed for mid=$mid, fallback to web")
        val profile = User.profile(mid)
            ?: return@get call.respondJson("用户不存在或获取失败。", HttpStatusCode.BadGateway)
        val webInfo = profile.info
        val obj: MutableMap<String, JsonElement> = webInfo?.let {
            prettyJson.encodeToJsonElement(it).jsonObject.toMutableMap()
        } ?: mutableMapOf<String, JsonElement>("mid" to JsonPrimitive(mid))
        obj["source"] = JsonPrimitive("web")
        profile.follower?.let { obj["fans"] = JsonPrimitive(it) }
        profile.following?.let { obj["following"] = JsonPrimitive(it) }
        profile.view?.let { obj["totalView"] = JsonPrimitive(it) }
        profile.likes?.let { obj["totalLike"] = JsonPrimitive(it) }
        // 资料本身没拿到（只有粉丝数等零散数据）时明确告知，前端据此走降级展示
        obj["profileAvailable"] = JsonPrimitive(webInfo != null)
        call.respondJsonObject(JsonObject(obj))
    }

    // ---------- 空间投稿 ----------

    /**
     * 空间投稿。优先 APP 端 /x/v2/space/archive/cursor（aid 游标翻页），
     * 失败退回 Web 端 /x/space/wbi/arc/search（页码翻页）。
     * 响应统一成 {items, count, hasNext, nextCursor, source} 供前端消费。
     */
    private fun Route.userSpace() = get("/userSpace") {
        val mid = call.parameters["mid"]?.toLongOrNull()
            ?: return@get call.respondJson("Please provide numeric \"mid\".", HttpStatusCode.BadRequest)
        val pageSize = call.parameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
        val order = call.parameters["order"]?.takeIf { it in setOf("pubdate", "click") } ?: "pubdate"
        // aid 游标：传上一页返回的 nextCursor
        val cursor = call.parameters["cursor"]?.takeIf { it.isNotBlank() }

        val app = AppSpace.archives(mid, cursor, order, pageSize)
        if (app != null && app.code == 0 && app.data != null) {
            val d = app.data
            // 取最后一条的 param(aid) 作为下一页游标
            val nextCursor = d.item.lastOrNull()?.param?.takeIf { it.isNotBlank() }
            call.respondJsonObject(buildJsonObject {
                put("source", JsonPrimitive("app"))
                put("count", JsonPrimitive(d.count))
                put("hasNext", JsonPrimitive(d.has_next))
                put("nextCursor", nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                put("order", prettyJson.encodeToJsonElement(d.order))
                put("items", prettyJson.encodeToJsonElement(d.item))
            })
            return@get
        }

        // 降级：Web 端（页码翻页）
        Logger.debug("app/space/archive failed for mid=$mid, fallback to web")
        val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_PAGE
        val keyword = call.parameters["keyword"]?.takeIf { it.isNotBlank() }
        val webOrder = if (order == "click") "click" else "pubdate"
        val json = Space.archives(mid, page, pageSize, webOrder, keyword)
            ?: return@get call.respondJson("获取投稿失败（可能被B站风控，稍后重试）。", HttpStatusCode.BadGateway)
        if (json.code != 0) {
            return@get call.respondJson(json.message.ifBlank { "code ${json.code}" }, HttpStatusCode.BadGateway)
        }
        val list = json.data?.list?.vlist.orEmpty()
        // 转成与 APP 分支同构的条目，前端只认一套字段
        val items = list.map {
            buildJsonObject {
                put("bvid", JsonPrimitive(it.bvid))
                put("title", JsonPrimitive(it.title))
                put("cover", JsonPrimitive(it.pic))
                put("duration", JsonPrimitive(it.length.toIntOrNull() ?: 0))
                put("play", JsonPrimitive(it.play))
                put("danmaku", JsonPrimitive(it.video_review))
                put("ctime", JsonPrimitive(it.created))
                put("author", JsonPrimitive(""))
                put("param", JsonPrimitive(it.aid.toString()))
                put("first_cid", JsonPrimitive(0))
                put("videos", JsonPrimitive(1))
            }
        }
        call.respondJsonObject(buildJsonObject {
            put("source", JsonPrimitive("web"))
            put("count", JsonPrimitive(json.data?.page?.count ?: 0))
            put("hasNext", JsonPrimitive((json.data?.page?.count ?: 0) > page * pageSize))
            put("nextCursor", JsonNull)
            put("order", JsonArray(emptyList()))
            put("items", JsonArray(items))
        })
    }

    // ---------- 空间动态 ----------

    /**
     * 用户空间动态。把接口的 modules 数组摊平成前端易用的扁平结构，
     * 并按类型（图文 / 视频投稿 / 转发）给出统一字段。
     */
    private fun Route.userDynamic() = get("/userDynamic") {
        val mid = call.parameters["mid"]?.toLongOrNull()
            ?: return@get call.respondJson("Please provide numeric \"mid\".", HttpStatusCode.BadRequest)
        val offset = call.parameters["cursor"]?.takeIf { it.isNotBlank() }
        val json = Dyn.space(mid, offset)
            ?: return@get call.respondJson("获取动态失败（B站风控较严，稍后重试）。", HttpStatusCode.BadGateway)
        if (json.code != 0) {
            return@get call.respondJson(json.message.ifBlank { "code ${json.code}" }, HttpStatusCode.BadGateway)
        }
        val d = json.data!!
        val items = d.items.map { it.toJson() }
        call.respondJsonObject(buildJsonObject {
            put("hasMore", JsonPrimitive(d.has_more))
            put("nextCursor", if (d.offset.isBlank()) JsonNull else JsonPrimitive(d.offset))
            put("updateNum", JsonPrimitive(d.update_num))
            put("items", JsonArray(items))
        })
    }

    /** 把一条动态（modules 数组）摊平成扁平 JSON */
    private fun localserver.types.bilibili.DynItem.toJson(): JsonObject {
        val author = modules.firstNotNullOfOrNull { it.module_author }
        val desc = modules.firstNotNullOfOrNull { it.module_desc }
        val dynamic = modules.firstNotNullOfOrNull { it.module_dynamic }
        val stat = modules.firstNotNullOfOrNull { it.module_stat }

        // 文本：优先 module_desc.text，其次拼接富文本节点
        val text = desc?.text?.takeIf { it.isNotBlank() }
            ?: desc?.rich_text_nodes.orEmpty().joinToString("") { it.display }

        return buildJsonObject {
            put("id", JsonPrimitive(id_str))
            put("type", JsonPrimitive(type))
            put("pubTs", JsonPrimitive(author?.pub_ts ?: 0))
            put("pubText", JsonPrimitive(author?.pub_text.orEmpty()))
            put("isTop", JsonPrimitive(author?.is_top ?: false))
            put("text", JsonPrimitive(text))
            put("like", JsonPrimitive(stat?.like?.count ?: 0))
            put("comment", JsonPrimitive(stat?.comment?.count ?: 0))
            put("forward", JsonPrimitive(stat?.forward?.count ?: 0))

            // 图片（带图动态）
            val pics = dynamic?.dyn_draw?.items.orEmpty()
            put("pics", JsonArray(pics.map { pic ->
                buildJsonObject {
                    put("src", JsonPrimitive(pic.src))
                    put("width", JsonPrimitive(pic.width))
                    put("height", JsonPrimitive(pic.height))
                }
            }))

            // 投稿视频
            dynamic?.dyn_archive?.let { a ->
                put("archive", buildJsonObject {
                    put("bvid", JsonPrimitive(a.bvid))
                    put("title", JsonPrimitive(a.title))
                    put("cover", JsonPrimitive(a.cover))
                    put("duration", JsonPrimitive(a.duration_text))
                    put("desc", JsonPrimitive(a.desc))
                    put("play", JsonPrimitive(a.stat?.play.orEmpty()))
                    put("danmaku", JsonPrimitive(a.stat?.danmaku.orEmpty()))
                })
            }

            // 转发动态：只取被转发内容的作者与文本/图片摘要
            dynamic?.dyn_forward?.item?.let { orig ->
                val oAuthor = orig.modules.firstNotNullOfOrNull { it.module_author }
                val oDesc = orig.modules.firstNotNullOfOrNull { it.module_desc }
                val oDyn = orig.modules.firstNotNullOfOrNull { it.module_dynamic }
                val oText = oDesc?.text?.takeIf { it.isNotBlank() }
                    ?: oDesc?.rich_text_nodes.orEmpty().joinToString("") { it.display }
                put("forwarded", buildJsonObject {
                    put("id", JsonPrimitive(orig.id_str))
                    put("author", JsonPrimitive(oAuthor?.user?.name.orEmpty()))
                    put("authorFace", JsonPrimitive(oAuthor?.user?.face.orEmpty()))
                    put("text", JsonPrimitive(oText))
                    put("pics", JsonArray(oDyn?.dyn_draw?.items.orEmpty().map { pic ->
                        JsonPrimitive(pic.src)
                    }))
                    oDyn?.dyn_archive?.let { a ->
                        put("archive", buildJsonObject {
                            put("bvid", JsonPrimitive(a.bvid))
                            put("title", JsonPrimitive(a.title))
                            put("cover", JsonPrimitive(a.cover))
                        })
                    }
                })
            }
        }
    }

    // ---------- 取流信息（返回 DASH/MP4 流地址供前端选择） ----------

    /**
     * 返回播放地址列表。前端拿到 baseUrl 后再逐个交给 /videoStream 转发播放，
     * 之所以分两步：URL 有 120 分钟时效且带签名，需按分P/清晰度即时获取。
     *
     * 默认按 1080P(80) 协商，配合 PlayUrl 里的 try_look=1 + fnval=4048 实现免登录 1080P。
     * 响应里额外给出 availableQualities 与 bestQuality，前端据此直接选中能用的最高档。
     */
    private fun Route.videoStreamInfo() = get("/videoStreamInfo") {
        val bvid = call.parameters["bvid"]?.takeIf { it.isNotBlank() }
            ?: return@get call.respondJson("Please provide \"bvid\".", HttpStatusCode.BadRequest)
        val cid = call.parameters["cid"]?.toLongOrNull()
            ?: return@get call.respondJson("Please provide numeric \"cid\".", HttpStatusCode.BadRequest)
        val qn = call.parameters["qn"]?.toIntOrNull()?.coerceAtLeast(6) ?: PlayUrl.QN_1080P
        // 是否启用免登录 1080P（默认开启，可用 tryLook=0 关闭以便对比）
        val tryLook = call.parameters["tryLook"] != "0"
        val json = PlayUrl.get(bvid, cid, qn, dash = true, tryLook = tryLook)
            ?: return@get call.respondJson("Failed to fetch playurl.", HttpStatusCode.BadGateway)
        if (json.code != 0) {
            return@get call.respondJson(json.message.ifBlank { "code ${json.code}" }, HttpStatusCode.BadGateway)
        }
        val data = json.data
            ?: return@get call.respondJson("Empty playurl data.", HttpStatusCode.BadGateway)
        // 附上"实际可用清晰度"，前端不必再自己推导，避免选中不存在的档位
        val qualities = data.dash?.video?.map { it.id }?.distinct()?.sortedDescending().orEmpty()
        val best = PlayUrl.bestQuality(data)
        val obj = prettyJson.encodeToJsonElement(data).jsonObject.toMutableMap()
        obj["availableQualities"] = JsonArray(qualities.map { JsonPrimitive(it) })
        obj["bestQuality"] = JsonPrimitive(best)

        // 提前预取"默认档位的视频流 + 最高码率音频流"的前若干字节，
        // 这样前端紧接着请求 /videoStream 时能直接命中内存、瞬时起播。
        data.dash?.let { dash ->
            dash.video.firstOrNull { it.id == best }?.baseUrl?.takeIf { it.isNotBlank() }?.let(::prefetchHead)
            dash.audio.maxByOrNull { it.bandwidth ?: 0 }?.baseUrl?.takeIf { it.isNotBlank() }?.let(::prefetchHead)
        }

        call.respondText(prettyJson.encodeToString(JsonObject(obj)))
    }

    // ---------- 首段预取 ----------
    //
    // 实测：B站 CDN 的 TTFB 在"新建连接(含 TLS 握手)"时约 91ms，复用连接后仅 19ms，
    // 而播放器首次起播必须等第一批字节到达。这里在 /videoStreamInfo 阶段就
    // 预取每路流的前若干字节放进内存，等浏览器真正来取时直接命中、瞬时返回，
    // 同时把与 CDN 的连接预热好，后续续传也更快。

    private const val HEAD_PREFETCH_BYTES = 768 * 1024
    private const val PREFETCH_TTL_MS = 180_000L
    private const val HEAD_CACHE_MAX = 32

    private class Head(
        val bytes: ByteArray,
        val total: Long,
        val contentType: String?,
        val at: Long
    )

    private val headCache = java.util.concurrent.ConcurrentHashMap<String, Head>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 异步预取某路流的前 HEAD_PREFETCH_BYTES 字节；已有新鲜缓存则跳过 */
    private fun prefetchHead(url: String) {
        if (!isAllowedStreamHost(url)) return
        val cached = headCache[url]
        if (cached != null && System.currentTimeMillis() - cached.at < PREFETCH_TTL_MS) return
        prefetchScope.launch {
            val up = Http.openUpstream(url, "bytes=0-${HEAD_PREFETCH_BYTES - 1}", USER_AGENT, "https://www.bilibili.com/", "https://www.bilibili.com") ?: return@launch
            try {
                if (up.status !in 200..299) return@launch
                // 总长度优先取 Content-Range 的分母（Range 请求下 Content-Length 只是本次片段的长度）
                val total = up.header(HttpHeaders.ContentRange)?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    ?: up.header(HttpHeaders.ContentLength)?.toLongOrNull()
                    ?: return@launch
                val body = withContext(Dispatchers.IO) { up.body.readNBytes(HEAD_PREFETCH_BYTES) }
                if (body.isEmpty()) return@launch
                if (headCache.size >= HEAD_CACHE_MAX) headCache.clear()
                headCache[url] = Head(body, total, up.header(HttpHeaders.ContentType), System.currentTimeMillis())
                Logger.debug("prefetch ok: ${body.size}B / total ${total}B")
            } catch (e: Exception) {
                // 预取失败不影响正常播放，静默降级
                Logger.debug("prefetch skipped: ${e.message}")
            } finally {
                runCatching { up.body.close() }
            }
        }
    }

    // ---------- 取流代理（支持 Range） ----------

    /**
     * 转发视频字节。B 站视频流有 Referer 防盗链，浏览器 <video> 直接播会 403，
     * 故由服务端带 Referer/UA 取流；同时必须透传 Range 才能拖动进度条。
     */
    private fun Route.videoStream() = get("/videoStream") {
        val url = call.parameters["url"]
            ?: return@get call.respondText("Please provide \"url\".", status = HttpStatusCode.BadRequest)
        // 只允许转发 B 站 CDN，避免变成任意 URL 的开放代理
        if (!isAllowedStreamHost(url)) {
            Logger.error("videoStream rejected non-bilibili stream url: $url")
            return@get call.respondText("Only bilibili stream urls are allowed.", status = HttpStatusCode.Forbidden)
        }
        val range = call.request.headers[HttpHeaders.Range]

        // 快路径：命中预取的首段缓存，且是"起播"场景（无 Range / 开放式 bytes=0-）。
        // 先把缓存字节瞬时写出去，再从缓存末尾继续向上游续传，
        // 播放器无需等待 CDN 的首字节/TLS 握手即可起播。
        // 注意：有界 Range（bytes=0-N）不能走整文件路径，否则会超出客户端请求的长度。
        val head = headCache[url]
        if (head != null && System.currentTimeMillis() - head.at < PREFETCH_TTL_MS) {
            when (val mode = headServeMode(range, head.bytes.size)) {
                is HeadMode.Whole -> {
                    if (call.serveWholeFromHead(url, head, range != null)) return@get
                }
                is HeadMode.Slice -> {
                    if (call.serveSliceFromHead(head, mode.endInclusive)) return@get
                }
                null -> { /* 不适用，回落普通代理 */ }
            }
        }

        val response = Http.openUpstream(url, range, USER_AGENT, "https://www.bilibili.com/", "https://www.bilibili.com")
            ?: return@get call.respondText("Failed to fetch stream.", status = HttpStatusCode.BadGateway)

        // 透传关键响应头，Range 场景可能是 200 或 206。
        // Content-Length 交给下面的 respondBytesWriter 统一设置，避免重复头。
        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        response.header(HttpHeaders.ContentType)?.let { call.response.headers.append(HttpHeaders.ContentType, it) }
        response.header(HttpHeaders.ContentRange)?.let { call.response.headers.append(HttpHeaders.ContentRange, it) }
        // 视频分片是不可变内容，允许浏览器缓存：拖动进度条时重复请求可直接命中本地缓存
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")

        val status = HttpStatusCode.fromValue(response.status)
        call.respondBytesWriter(
            contentType = ContentType.parse(response.header(HttpHeaders.ContentType) ?: "application/octet-stream"),
            status = status,
            contentLength = response.header(HttpHeaders.ContentLength)?.toLongOrNull()
        ) {
            pipeInput(response.body, this)
        }
    }

    /** 首段缓存能覆盖哪种请求 */
    private sealed interface HeadMode {
        /** 无 Range 或开放式 bytes=0-：缓存首段 + 续传整个文件 */
        data object Whole : HeadMode
        /** 有界 range 且完全落在缓存内：直接切片返回 */
        data class Slice(val endInclusive: Long) : HeadMode
    }

    /**
     * 判断首段缓存能否用于该 Range 请求。
     * 只认"从 0 开始"的请求；有界区间仅在完全落在缓存内时才用切片，
     * 否则返回 null 让调用方走普通代理（避免把超出请求长度的数据发出去）。
     */
    private fun headServeMode(range: String?, headLen: Int): HeadMode? {
        if (range == null) return HeadMode.Whole
        val r = range.trim().removePrefix("bytes=")
        // 只支持单区间，形如 "0-" 或 "0-N"
        if (!r.startsWith("0-")) return null
        val endPart = r.substringAfter("0-").substringBefore(",")
        if (endPart.isBlank()) return HeadMode.Whole          // "0-" 开放式
        val end = endPart.toLongOrNull() ?: return null
        return if (end < headLen) HeadMode.Slice(end) else null
    }

    /** 有界区间完全命中缓存：直接从内存切片返回 */
    private suspend fun io.ktor.server.application.ApplicationCall.serveSliceFromHead(
        head: Head,
        endInclusive: Long
    ): Boolean {
        val len = (endInclusive + 1).toInt().coerceAtMost(head.bytes.size)
        val slice = head.bytes.copyOfRange(0, len)
        response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
        response.headers.append(HttpHeaders.ContentRange, "bytes 0-$endInclusive/${head.total}")
        return try {
            respondBytes(
                bytes = slice,
                contentType = ContentType.parse(head.contentType ?: "application/octet-stream"),
                status = HttpStatusCode.PartialContent
            )
            true
        } catch (e: Exception) {
            Logger.debug("serveSliceFromHead failed: ${e.message}")
            false
        }
    }

    /**
     * 用预取的首段缓存应答整个文件，然后从缓存末尾续传剩余部分。
     * 返回 false 表示无法走缓存（调用方回落普通代理）。
     */
    private suspend fun io.ktor.server.application.ApplicationCall.serveWholeFromHead(
        url: String,
        head: Head,
        ranged: Boolean
    ): Boolean {
        val total = head.total
        if (total <= 0) return false
        val headLen = head.bytes.size
        val contentType = ContentType.parse(head.contentType ?: "application/octet-stream")

        response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
        if (ranged) {
            response.headers.append(HttpHeaders.ContentRange, "bytes 0-${total - 1}/$total")
        }

        return try {
            respondBytesWriter(
                contentType = contentType,
                // 客户端带了 Range 就按 206 回（部分内容），否则 200
                status = if (ranged) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                contentLength = total
            ) {
                // 1) 先把缓存的首段瞬时写出
                writeFully(head.bytes, 0, headLen)
                flush()

                // 小文件：缓存已覆盖全部内容
                if (headLen.toLong() >= total) return@respondBytesWriter

                // 2) 从缓存末尾继续向上游续传
                val cont = Http.openUpstream(url, "bytes=$headLen-", USER_AGENT, "https://www.bilibili.com/", "https://www.bilibili.com")
                if (cont == null || cont.status !in 200..299) {
                    Logger.debug("head 续传失败，剩余部分可能缺失")
                    return@respondBytesWriter
                }
                pipeInput(cont.body, this)
            }
            true
        } catch (e: Exception) {
            Logger.debug("serveWholeFromHead failed: ${e.message}")
            false
        }
    }

    /**
     * 把上游字节逐段转发给客户端，每段显式 flush。
     * 必须显式 flush：否则数据会被整段缓冲，播放器要等全部读完才能起播。
     * 阻塞的 InputStream 读取放到 IO 线程池，避免占用事件循环。
     */
    private suspend fun pipeInput(
        input: java.io.InputStream,
        out: ByteWriteChannel
    ) {
        withContext(Dispatchers.IO) {
            input.use { ins ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = ins.read(buffer)
                    if (read <= 0) break
                    out.writeFully(buffer, 0, read)
                    out.flush()
                }
            }
        }
    }

    /** 校验目标主机确实是 B 站视频 CDN 域名 */
    private fun isAllowedStreamHost(rawUrl: String): Boolean = runCatching {
        val host = java.net.URI(rawUrl).host?.lowercase() ?: return false
        host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn") ||
            host.endsWith(".hdslb.com") || host.endsWith(".bilibili.com") || host == "bilibili.com"
    }.getOrDefault(false)

    /** 统一的错误响应：返回 JSON，前端可直接取 error 字段 */
    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(text: String, status: HttpStatusCode) {
        respondText(
            text = Json.encodeToString(buildJsonObject { put("error", text) }),
            contentType = ContentType.Application.Json,
            status = status
        )
    }

    /** 返回 JSON 对象（成功路径），与 respondJson 分开以免混淆 */
    private suspend fun io.ktor.server.application.ApplicationCall.respondJsonObject(obj: JsonObject) {
        respondText(
            text = prettyJson.encodeToString(obj),
            contentType = ContentType.Application.Json
        )
    }
}
