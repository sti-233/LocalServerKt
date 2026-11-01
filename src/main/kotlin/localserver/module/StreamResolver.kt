package localserver.module

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import localserver.lib.bilibili.PlayUrl
import localserver.types.bilibili.PlayUrlData
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「要哪一路流」解析成 B 站的临时签名地址，并缓存起来。
 *
 * 背景：B 站取流地址带签名与 `deadline`（实测有效期 120 分钟），每次调
 * `/videoStreamInfo` 拿到的地址都不一样。如果前端直接把上游地址塞进
 * `/videoStream?url=<签名地址>`，那这个 URL 每次播放都会变，浏览器缓存
 * 完全复用不了 —— 换个会话、甚至只是刷新页面，同一段视频都要重新回源。
 *
 * 所以 `/videoStream` 改成接受语义参数（bvid/cid/qn/codecid/kind/format），
 * 由服务端在内部换成真实地址。同一路流的 URL 因此恒定，浏览器可以放心
 * 长期缓存（响应带 `immutable`）。签名过期时这里自动重新协商，
 * 前端完全无感。
 */
internal object StreamResolver {

    /** DASH 视频流 */
    const val KIND_VIDEO = "v"

    /** DASH 音频流（与视频流分开取） */
    const val KIND_AUDIO = "a"

    /** MP4 单流（音画合一） */
    const val KIND_MP4 = "m"

    /** 缓存条数上限：一路视频按「清晰度 × 编码」展开也就几十条，够用 */
    private const val CACHE_MAX = 64

    /** 剩余有效期不足这个数就提前换新地址，避免播到一半过期 */
    private const val SAFETY_MS = 5 * 60_000L

    /** 地址里没带 deadline 时的保守估计 */
    private const val FALLBACK_TTL_MS = 60 * 60_000L

    private class Entry(val url: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 每个 key 一把锁：并发 Range 请求命中同一路流时只回源一次 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 缓存键。音频流的清晰度/编码无意义，统一归零 ——
     * 否则同一个音频会因为传入的 qn 不同而存成好几份。
     */
    fun keyOf(bvid: String, cid: Long, qn: Int, codecid: Int, kind: String, format: String): String {
        val q = if (kind == KIND_AUDIO) 0 else qn
        val c = if (kind == KIND_AUDIO) 0 else codecid
        return "$bvid:$cid:$q:$c:$kind:$format"
    }

    /**
     * 把 `/videoStreamInfo` 刚拿到的一批地址登记进来。
     *
     * 这一步是必要的：`videoStreamInfo` 会顺手预取首段（`headCache` 以上游地址为键），
     * 如果这里不登记，前端随后请求 `/videoStream` 时会再协商一次、拿到另一个签名地址，
     * 首段缓存就白预取了。
     */
    fun remember(
        bvid: String,
        cid: Long,
        qn: Int,
        codecid: Int,
        kind: String,
        format: String,
        url: String
    ) {
        if (url.isBlank()) return
        if (cache.size >= CACHE_MAX) cache.clear()
        cache[keyOf(bvid, cid, qn, codecid, kind, format)] =
            Entry(url, expiresAtOf(url, System.currentTimeMillis()))
    }

    /**
     * 取真实地址：新鲜就返回缓存，过期就重新协商。
     * 协商失败时退回旧地址（可能还剩几秒可用），实在没有才返回 null。
     */
    suspend fun resolve(
        bvid: String,
        cid: Long,
        qn: Int,
        codecid: Int,
        kind: String,
        format: String
    ): String? {
        val key = keyOf(bvid, cid, qn, codecid, kind, format)
        cache[key]?.let { if (isFresh(it, System.currentTimeMillis())) return it.url }

        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            // 等锁期间可能已被别的请求刷新过
            val cached = cache[key]
            if (cached != null && isFresh(cached, System.currentTimeMillis())) return@withLock cached.url

            val fresh = runCatching { fetch(bvid, cid, qn, codecid, kind, format) }.getOrNull()
            if (fresh.isNullOrBlank()) return@withLock cached?.url

            cache[key] = Entry(fresh, expiresAtOf(fresh, System.currentTimeMillis()))
            fresh
        }
    }

    /** 重新向 B 站协商一次 */
    private suspend fun fetch(
        bvid: String,
        cid: Long,
        qn: Int,
        codecid: Int,
        kind: String,
        format: String
    ): String? {
        val json = PlayUrl.get(bvid, cid, qn, dash = format != "mp4", tryLook = true) ?: return null
        if (json.code != 0) return null
        return pickUrl(json.data ?: return null, qn, codecid, kind)
    }

    /**
     * 从取流结果里挑出目标流。
     *
     * **与前端 `watch.html` 的 `applyStream()` 必须保持一致**：前端负责在清晰度/
     * 编码下拉里选中一项并把 qn/codecid 传回来，这里按同样的规则再挑一次。
     * 两边规则不一样的话会播成别的档位。
     */
    fun pickUrl(data: PlayUrlData, qn: Int, codecid: Int, kind: String): String? {
        if (kind == KIND_MP4) return data.durl.firstOrNull()?.url?.takeIf { it.isNotBlank() }

        val dash = data.dash ?: return null
        if (kind == KIND_AUDIO) {
            // 音频取码率最高的一路（前端也是这么挑的）
            return dash.audio.maxByOrNull { it.bandwidth }?.baseUrl?.takeIf { it.isNotBlank() }
        }

        if (dash.video.isEmpty()) return null
        // 优先精确匹配清晰度；匹配不到退回最高档，避免直接失败
        var cands = dash.video.filter { it.id == qn }
        if (cands.isEmpty()) {
            val best = dash.video.maxOf { it.id }
            cands = dash.video.filter { it.id == best }
        }
        val pick = cands.firstOrNull { it.codecid == codecid } ?: cands.first()
        return pick.baseUrl.takeIf { it.isNotBlank() }
    }

    /** 地址里的 `deadline` 是秒级时间戳；没有就按保守 TTL 估 */
    fun expiresAtOf(url: String, now: Long): Long {
        val deadline = Regex("[?&]deadline=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return deadline?.times(1000) ?: (now + FALLBACK_TTL_MS)
    }

    private fun isFresh(entry: Entry, now: Long): Boolean = now + SAFETY_MS < entry.expiresAt

    /** 仅测试用：清空缓存，避免用例之间互相污染 */
    internal fun clearForTest() {
        cache.clear()
        locks.clear()
    }
}
