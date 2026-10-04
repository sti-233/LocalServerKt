package localserver.lib.bilibili

import localserver.lib.bilibili.utils.AppSign
import localserver.types.bilibili.AppSpace
import localserver.types.bilibili.AppSpaceArchives
import localserver.utils.HttpClient
import localserver.utils.Logger

/**
 * APP 端空间接口（app.bilibili.com）。
 *
 * 为什么单独做一套：Web 端的 /x/space/wbi/acc/info 与 /x/space/wbi/arc/search
 * 风控极严（实测同一 IP 下长期返回 -352 风控校验失败 / 412），
 * 而 APP 端接口只需 appkey+sign（见 AppSign），无需 Cookie/WBI，
 * 同一 IP 下稳定可用——这正是 PiliPlus 空间页的做法。
 */
object AppSpace {
    private val appHeader = mapOf(
        "user-agent" to AppSign.USER_AGENT,
        "bili-http-engine" to "cronet",
        "env" to "prod",
        "app-key" to "android64"
    )

    /** 空间资料：昵称/头像/签名/等级/粉丝/关注/认证/直播/投稿数 */
    suspend fun info(mid: Long): AppSpace? {
        if (mid <= 0) return null
        val params = AppSign.baseParams().apply { put("vmid", mid) }
        AppSign.sign(params)
        return safeGetAs(
            label = "app/space",
            url = "https://app.bilibili.com/x/v2/space",
            parameters = params,
            headers = appHeader
        )
    }

    /**
     * 空间投稿（游标翻页）。
     *
     * 分页机制（实测确认）：接口不接受 pn/next，而是用 **aid** 作游标——
     * 取上一页最后一条的 `param`（即 avid）作为 `aid` 传入即可拿下一页；
     * 返回 has_next 表示还有更多。带 `sort=asc` 可反向取上一页。
     */
    suspend fun archives(
        mid: Long,
        aid: String? = null,
        order: String = "pubdate",
        pageSize: Int = 20,
        asc: Boolean = false
    ): AppSpaceArchives? {
        if (mid <= 0) return null
        val params = AppSign.baseParams().apply {
            put("vmid", mid)
            put("ps", pageSize.coerceIn(1, 50))
            // qn=80 让接口顺带返回 1080P 的 playurl（我们不用，但保持与官方一致）
            put("qn", 80)
            put("order", if (order == "click") "click" else "pubdate")
            if (!aid.isNullOrBlank()) put("aid", aid)
            if (asc) put("sort", "asc")
        }
        AppSign.sign(params)
        return safeGetAs(
            label = "app/space/archive",
            url = "https://app.bilibili.com/x/v2/space/archive/cursor",
            parameters = params,
            headers = appHeader
        )
    }
}
