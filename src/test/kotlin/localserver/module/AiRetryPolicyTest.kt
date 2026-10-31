package localserver.module

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiRetryPolicyTest {

    @Test
    fun ioFailureIsRetryable() {
        assertTrue(isRetryableUpstreamError(IOException("connection reset")))
    }

    @Test
    fun socketTimeoutIsRetryable() {
        assertTrue(isRetryableUpstreamError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun requestTimeoutIsRetryable() {
        assertTrue(isRetryableUpstreamError(HttpRequestTimeoutException("https://example", 600_000)))
    }

    @Test
    fun connectTimeoutIsRetryable() {
        assertTrue(isRetryableUpstreamError(ConnectTimeoutException("connect timed out")))
    }

    @Test
    fun nestedCauseIsInspected() {
        assertTrue(isRetryableUpstreamError(RuntimeException("wrapper", IOException("broken pipe"))))
    }

    @Test
    fun cancellationIsNotRetryable() {
        assertFalse(isRetryableUpstreamError(CancellationException("client gone")))
    }

    @Test
    fun plainBusinessErrorIsNotRetryable() {
        assertFalse(isRetryableUpstreamError(IllegalArgumentException("bad json")))
    }

    @Test
    fun retryIsAllowedUntilAttemptCapReached() {
        // 断言跟着常量走：改了 upstreamMaxAttempts 不用再动这个用例
        val err = IOException("connection reset")
        for (attempt in 1 until upstreamMaxAttempts) {
            assertTrue(canRetryUpstream(attempt, false, err), "第 $attempt 次失败应可重试")
        }
        assertFalse(
            canRetryUpstream(upstreamMaxAttempts, false, err),
            "第 $upstreamMaxAttempts 次失败应放弃（最多 $upstreamMaxAttempts 次尝试）"
        )
    }

    @Test
    fun noRetryAfterAnythingWasForwardedToClient() {
        // 已经往浏览器转发过片段，重开上游会让前端收到重复内容
        assertFalse(canRetryUpstream(1, true, IOException("reset")))
    }

    @Test
    fun noRetryForNonRetryableError() {
        assertFalse(canRetryUpstream(1, false, IllegalArgumentException("bad request")))
    }

    @Test
    fun noRetryWhenClientCancelled() {
        assertFalse(canRetryUpstream(1, false, CancellationException("client gone")))
    }
}
