package localserver.lib.bilibili

import kotlinx.coroutines.delay

import localserver.types.bilibili.DynFeed
import localserver.utils.Logger

/**
 * 用户空间动态。
 *
 * 接口选择（实测结论）：
 *  - `/x/polymer/web-dynamic/v1/feed/space`：本机网络下**恒定返回 HTML 风控页(412)**，不可用；
 *  - `/x/polymer/web-dynamic/desktop/v1/feed/space`：可用，但**有运气成分**——
 *    官方文档也注明"该接口现在有一些奇奇怪怪的校验"。同一参数连续请求会出现
 *    13, 13, 0, 0, 0 这种 code=0 但 items 为空的"软限流"。
 *    因此这里实现短重试：最多 3 次、每次间隔 800ms，通常第 1~2 次就能拿到数据。
 *
 * 翻页用 `offset`（上一页返回的 data.offset）。
 * 注意不要对该接口做 WBI 签名——实测签名后恒定返回 0 条。
 */
object Dyn {
    // 该接口有"软限流"：会以 code=0 + 空 items 的形式**随机**出现，
    // 且请求头组合几乎不影响（实测同参数同 header 连发 4 次可能是 0,0,0,13）。
    // 官方文档也注明"该接口现在有一些奇奇怪怪的校验，存在一定运气成分"。
    // 应对方式：较多次数 + 递增退避的重试。
    private const val MAX_RETRY = 6
    private const val RETRY_DELAY_MS = 500L

    suspend fun space(mid: Long, offset: String? = null): DynFeed? {
        if (mid <= 0) return null
        var last: DynFeed? = null
        repeat(MAX_RETRY) { attempt ->
            // 关键坑：timezone_offset 必须传**百分号编码**的 %2D480。
            // 传字面量 -480 时接口会返回 code=0 但 items 为空（静默空结果，不报错）。
            // Ktor 的 parameter() 不会编码 '-'（它是 URL 非保留字符），所以这里手工拼查询串。
            val query = buildString {
                append("?host_mid=").append(mid)
                append("&timezone_offset=%2D480")
                append("&platform=web")
                if (!offset.isNullOrBlank()) {
                    append("&offset=").append(java.net.URLEncoder.encode(offset, "UTF-8"))
                }
            }

            val json = safeGetAs<DynFeed>(
                label = "space/dynamic",
                url = "https://api.bilibili.com/x/polymer/web-dynamic/desktop/v1/feed/space" + query,
                parameters = null,
                // 该接口需要 buvid3 cookie，但**不能**做 WBI 签名
                headers = biliHeaderWithFinger("https://space.bilibili.com/$mid/dynamic") +
                    ("origin" to "https://space.bilibili.com")
            )
            if (json != null && json.code == 0 && json.data != null) {
                last = json
                // 只有重试过才记日志，避免正常路径刷屏
                if (attempt > 0) Logger.debug("space/dynamic mid=$mid 第 ${attempt + 1} 次尝试拿到 ${json.data.items.size} 条")
                if (json.data.items.isNotEmpty()) return json
            }
            if (attempt < MAX_RETRY - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        if (last == null) Logger.debug("space/dynamic all retries failed for mid=$mid")
        return last
    }
}
