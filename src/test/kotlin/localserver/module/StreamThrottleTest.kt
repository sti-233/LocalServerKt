package localserver.module

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamThrottleTest {

    /** 可控时钟：手动推进纳秒 */
    private class Clock(var nanos: Long = 0L) {
        fun advance(ms: Long) { nanos += ms * 1_000_000 }
    }

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    @Test
    fun `burst is free so playback start is not slowed down`() {
        val clock = Clock()
        // 1MB 突发额度、300KB/s 补充
        val bucket = TokenBucket(300.0 * 1024, 1024.0 * 1024, now = { clock.nanos })

        // 头 1MB 一口气吃掉，全程不等待
        assertEquals(0L, bucket.take(512 * 1024))
        assertEquals(0L, bucket.take(512 * 1024))
        // 额度用尽，紧接着再要就一定要等
        assertTrue(bucket.take(64 * 1024) > 0L, "burst 用完后必须开始等待")
    }

    @Test
    fun `steady state paces at the configured rate`() {
        val clock = Clock()
        val rate = 300.0 * 1024
        val burst = 256.0 * 1024
        val bucket = TokenBucket(rate, burst, now = { clock.nanos })

        // 先花掉突发额度
        assertEquals(0L, bucket.take(burst.toInt()))

        // 之后连续要 1MB（按 128KB 一块），把每块的等待时间累加
        val chunk = 128 * 1024
        val want = 1024 * 1024
        var waitedNanos = 0L
        var got = 0
        while (got < want) {
            waitedNanos += bucket.take(chunk)
            got += chunk
            clock.advance(ms(waitedNanos).toLong().let { waitedNanos / 1_000_000 }) // 睡够再回来
            waitedNanos = 0
        }
        // 1MB / 300KBps ≈ 3.4s。允许 10% 误差（整数毫秒取整的累计误差）
        val expectMs = want / rate * 1000.0
        val actualMs = clock.nanos / 1_000_000.0
        assertTrue(
            actualMs in expectMs * 0.9..expectMs * 1.1,
            "expected ~${expectMs.toInt()}ms for ${want / 1024}KB, got ${actualMs.toInt()}ms"
        )
    }

    @Test
    fun `a chunk bigger than the bucket capacity still eventually passes`() {
        val clock = Clock()
        // 桶只有 64KB，但一次要 128KB：不能卡死，按缺口算等待时间
        val bucket = TokenBucket(300.0 * 1024, 64.0 * 1024, now = { clock.nanos })
        val wait = ms(bucket.take(128 * 1024))
        // 缺口 = 128KB - 64KB = 64KB，按 300KB/s ≈ 213ms
        assertTrue(wait in 205.0..220.0, "expected ~213ms, got $wait")

        // 睡过这段之后进入稳态：每 128KB 一块要 128KB/300KBps ≈ 437ms
        clock.advance(213)
        val next = ms(bucket.take(128 * 1024))
        assertTrue(next in 425.0..445.0, "steady state should be ~437ms, got $next")
    }

    @Test
    fun `control clients are not throttled`() {
        // Control.allowed 的白名单：本机与家里网段
        assertTrue(!StreamThrottle.limited("127.0.0.1"))
        assertTrue(!StreamThrottle.limited("192.168.3.32"))
        assertTrue(!StreamThrottle.limited("192.168.100.7"))
        assertTrue(!StreamThrottle.limited("192.168.20.11"))
        // 其它来源限速
        assertTrue(StreamThrottle.limited("192.168.1.50"))
        assertTrue(StreamThrottle.limited("10.0.0.8"))
        assertTrue(StreamThrottle.limited("203.0.113.9"))

        assertNull(StreamThrottle.bucketFor("127.0.0.1"), "control 客户端不建桶")
        assertNotNull(StreamThrottle.bucketFor("10.0.0.8"), "其它来源要建桶")
    }

    @Test
    fun `one bucket per address so range requests share the quota`() {
        val a1 = StreamThrottle.bucketFor("10.1.1.1")
        val a2 = StreamThrottle.bucketFor("10.1.1.1")
        val b = StreamThrottle.bucketFor("10.1.1.2")
        assertNotNull(a1)
        assertNotNull(b)
        assertTrue(a1 === a2, "同一 IP 必须复用同一个桶，否则每个 Range 请求都能重新吃满突发")
        assertTrue(a1 !== b, "不同 IP 各用各的桶")
    }

    @Test
    fun `null bucket means unlimited`() {
        // await 对 null 桶直接返回，不能抛错也不能真的睡
        kotlinx.coroutines.runBlocking {
            StreamThrottle.await(null, 10 * 1024 * 1024)
        }
    }
}
