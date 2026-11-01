package localserver.module

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * 令牌桶：按字节数发放额度，额度不够时返回"该等多久"。
 *
 * 用桶而不是"每块固定 sleep"的原因：视频起播要的是首段尽快到，
 * 起播后才是持续带宽。桶容量当作突发额度，允许开头一口气吃掉一整段，
 * 之后按 [ratePerSec] 匀速补充 —— 首屏不受影响，长视频照样被压在限速上。
 */
internal class TokenBucket(
    private val ratePerSec: Double,
    private val capacity: Double,
    private val now: () -> Long = { System.nanoTime() }
) {

    private var tokens: Double = capacity
    private var last: Long = now()

    /** 领取 [bytes] 字节额度；返回需要等待的纳秒数，0 表示可以直接写。 */
    fun take(bytes: Int): Long {
        val t = now()
        tokens = minOf(capacity, tokens + (t - last) / 1_000_000_000.0 * ratePerSec)
        last = t
        if (tokens >= bytes) {
            tokens -= bytes
            return 0L
        }
        // 额度不够：把零头花光，算出还差多久。
        // 关键是把 last 一起往前推到"睡醒那一刻"——这段等待正好攒出缺的额度，
        // 已经算在这次领取里了。若只返回等待时间而不推 last，调用方睡完回来
        // 会把这批额度当成白送的，实际吞吐翻倍（实测 2MB 跑到 1175KB/s 而不是 300KB/s）。
        val deficit = bytes - tokens
        tokens = 0.0
        val waitNanos = (deficit / ratePerSec * 1_000_000_000.0).toLong()
        last = t + waitNanos
        return waitNanos
    }
}

/**
 * `/videoStream` 的限速：非 control 客户端按 IP 限到 [RATE_BYTES_PER_SEC]。
 *
 * 谁算 control 沿用 [Control.allowed]（与 `/canControl` 同一份 IP 前缀白名单，
 * 也就是前端据此决定要不要显示控制按钮的那套判断），所以自己家的机器不限、
 * 客人和外网来的设备限速。注意 `?p=114514` 不走这条路：取流 URL 由前端自己拼，
 * 带上密码参数既容易被分享出去，也没法按 IP 累计额度。
 *
 * 桶按 IP 保存而不是按请求新建：播放器拖动进度条会连发多个 Range 请求，
 * 每个请求都新桶等于每次都能重新吃满突发额度，限速就形同虚设。
 */
internal object StreamThrottle {

    /** 300 KB/s（字节，不是比特；要改成 300kbps 就把这里写成 300 * 1024 / 8） */
    const val RATE_BYTES_PER_SEC = 300 * 1024

    /** 突发额度：覆盖一次起播的首段，避免限速把首屏拖慢 */
    private const val BURST_BYTES = 1024 * 1024

    /** 桶本身很小，但别让随机 IP 扫端点把内存撑起来 */
    private const val MAX_CLIENTS = 64

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    /** 该来源是否需要限速 */
    fun limited(remoteAddress: String): Boolean = !Control.allowed(remoteAddress)

    /** 取该来源的桶；control 客户端返回 null（不限速） */
    fun bucketFor(remoteAddress: String): TokenBucket? {
        if (!limited(remoteAddress)) return null
        if (buckets.size >= MAX_CLIENTS) buckets.clear()
        return buckets.computeIfAbsent(remoteAddress) {
            TokenBucket(RATE_BYTES_PER_SEC.toDouble(), BURST_BYTES.toDouble())
        }
    }

    /** 为即将写出的 [bytes] 字节等够额度；[bucket] 为 null 时立即返回 */
    suspend fun await(bucket: TokenBucket?, bytes: Int) {
        if (bucket == null) return
        val waitNanos = bucket.take(bytes)
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }
}
