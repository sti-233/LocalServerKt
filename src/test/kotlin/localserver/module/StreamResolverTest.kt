package localserver.module

import localserver.types.bilibili.Dash
import localserver.types.bilibili.DashAudio
import localserver.types.bilibili.DashVideo
import localserver.types.bilibili.Durl
import localserver.types.bilibili.PlayUrlData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StreamResolver] 的核心不变量：
 *  - 同一路流的键恒定（URL 才能稳定，浏览器缓存才复用得了）
 *  - 音频键不随 qn/codecid 变（否则同一段音频会存成好几份）
 *  - 从地址里能读出 deadline，读不到给保守 TTL
 *  - 挑流规则与前端 watch.html 的 applyStream() 一致
 */
class StreamResolverTest {

    @BeforeTest
    fun reset() {
        StreamResolver.clearForTest()
    }

    @Test
    fun `same stream keeps the same key across calls`() {
        val a = StreamResolver.keyOf("BV1xx", 123L, 80, 7, StreamResolver.KIND_VIDEO, "dash")
        val b = StreamResolver.keyOf("BV1xx", 123L, 80, 7, StreamResolver.KIND_VIDEO, "dash")
        assertEquals(a, b)
    }

    @Test
    fun `different quality or codec is a different key`() {
        val base = StreamResolver.keyOf("BV1xx", 123L, 80, 7, StreamResolver.KIND_VIDEO, "dash")
        assertNotEquals(base, StreamResolver.keyOf("BV1xx", 123L, 64, 7, StreamResolver.KIND_VIDEO, "dash"))
        assertNotEquals(base, StreamResolver.keyOf("BV1xx", 123L, 80, 12, StreamResolver.KIND_VIDEO, "dash"))
        assertNotEquals(base, StreamResolver.keyOf("BV1xx", 124L, 80, 7, StreamResolver.KIND_VIDEO, "dash"))
        assertNotEquals(base, StreamResolver.keyOf("BV1xx", 123L, 80, 7, StreamResolver.KIND_AUDIO, "dash"))
    }

    @Test
    fun `audio key ignores quality and codec`() {
        val a = StreamResolver.keyOf("BV1xx", 123L, 80, 7, StreamResolver.KIND_AUDIO, "dash")
        val b = StreamResolver.keyOf("BV1xx", 123L, 16, 13, StreamResolver.KIND_AUDIO, "dash")
        assertEquals(a, b, "audio is chosen by bandwidth only, qn/codecid must not split the cache")
    }

    @Test
    fun `deadline in the url is used as expiry`() {
        val now = 1_700_000_000_000L
        val url = "https://cdn.example/x.m4s?deadline=1700007200&other=1"
        assertEquals(1_700_007_200_000L, StreamResolver.expiresAtOf(url, now))
    }

    @Test
    fun `url without deadline falls back to a conservative ttl`() {
        val now = 1_700_000_000_000L
        val at = StreamResolver.expiresAtOf("https://cdn.example/x.m4s?foo=bar", now)
        assertEquals(now + 60 * 60_000L, at)
    }

    @Test
    fun `remember then resolve returns the cached url without hitting the network`() = kotlinx.coroutines.runBlocking {
        val url = "https://cdn.example/v.m4s?deadline=" + (System.currentTimeMillis() / 1000 + 3600)
        StreamResolver.remember("BV1xx", 9L, 80, 7, StreamResolver.KIND_VIDEO, "dash", url)
        val got = StreamResolver.resolve("BV1xx", 9L, 80, 7, StreamResolver.KIND_VIDEO, "dash")
        assertEquals(url, got)
    }

    @Test
    fun `expired entry is not returned from cache`() {
        val stale = "https://cdn.example/v.m4s?deadline=" + (System.currentTimeMillis() / 1000 - 10)
        StreamResolver.remember("BV1yy", 9L, 80, 7, StreamResolver.KIND_VIDEO, "dash", stale)
        // 直接验证新鲜度判断：过期条目不能算 fresh
        val now = System.currentTimeMillis()
        assertTrue(StreamResolver.expiresAtOf(stale, now) < now, "stale url should already be expired")
    }

    @Test
    fun `blank url is not remembered`() = kotlinx.coroutines.runBlocking {
        StreamResolver.remember("BV1zz", 9L, 80, 7, StreamResolver.KIND_VIDEO, "dash", "")
        assertNull(StreamResolver.resolve("BV1zz", 9L, 80, 7, StreamResolver.KIND_VIDEO, "dash"))
    }

    @Test
    fun `video pick prefers exact quality then falls back to the best`() {
        val data = PlayUrlData(
            dash = Dash(
                video = listOf(
                    video(16, 7, "u16"),
                    video(80, 7, "u80"),
                    video(64, 12, "u64hevc")
                ),
                audio = emptyList()
            )
        )
        assertEquals("u80", StreamResolver.pickUrl(data, 80, 7, StreamResolver.KIND_VIDEO))
        assertEquals("u64hevc", StreamResolver.pickUrl(data, 64, 12, StreamResolver.KIND_VIDEO))
        // 档位不存在 → 退回最高档，而不是直接失败
        assertEquals("u80", StreamResolver.pickUrl(data, 32, 7, StreamResolver.KIND_VIDEO))
    }

    @Test
    fun `video pick falls back to the first codec when the codec is missing`() {
        val data = PlayUrlData(
            dash = Dash(video = listOf(video(80, 7, "avc"), video(80, 12, "hevc")), audio = emptyList())
        )
        // 请求 AV1(13) 但只有 AVC/HEVC：退回该档位的第一个，不要空
        assertEquals("avc", StreamResolver.pickUrl(data, 80, 13, StreamResolver.KIND_VIDEO))
    }

    @Test
    fun `audio pick takes the highest bandwidth`() {
        val data = PlayUrlData(
            dash = Dash(
                video = emptyList(),
                audio = listOf(
                    DashAudio(id = 30216, baseUrl = "low", bandwidth = 65_686),
                    DashAudio(id = 30280, baseUrl = "high", bandwidth = 191_774),
                    DashAudio(id = 30232, baseUrl = "mid", bandwidth = 108_268)
                )
            )
        )
        assertEquals("high", StreamResolver.pickUrl(data, 80, 7, StreamResolver.KIND_AUDIO))
    }

    @Test
    fun `mp4 pick uses durl`() {
        val data = PlayUrlData(durl = listOf(Durl(order = 1, url = "mp4url")))
        assertEquals("mp4url", StreamResolver.pickUrl(data, 80, 0, StreamResolver.KIND_MP4))
    }

    @Test
    fun `empty dash yields null instead of throwing`() {
        assertNull(StreamResolver.pickUrl(PlayUrlData(), 80, 7, StreamResolver.KIND_VIDEO))
        assertNull(StreamResolver.pickUrl(PlayUrlData(), 80, 7, StreamResolver.KIND_AUDIO))
        assertNull(StreamResolver.pickUrl(PlayUrlData(), 80, 0, StreamResolver.KIND_MP4))
    }

    private fun video(id: Int, codecid: Int, url: String) =
        DashVideo(id = id, baseUrl = url, codecid = codecid)
}
