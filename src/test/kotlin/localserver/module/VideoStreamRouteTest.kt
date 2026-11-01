package localserver.module

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/videoStream` 的入参校验。
 *
 * 只测"不触网"的分支：真正取流要连 B 站 CDN，单测里不该依赖外网。
 * 这里要守住的是 —— 两种入参形式（语义参数 / 旧的上游地址）缺一不可地给出清晰错误，
 * 免得前端传错时只看到一个莫名其妙的 502。
 */
class VideoStreamRouteTest {

    @Test
    fun `missing both forms is a bad request that names both`() = testApplication {
        application { routing { with(Video) { videoRoute() } } }
        val res = client.get("/videoStream")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val text = res.bodyAsText()
        assertTrue(text.contains("bvid"), "should mention the semantic form, got: $text")
        assertTrue(text.contains("cid"), "should mention cid, got: $text")
        assertTrue(text.contains("url"), "should mention the legacy form, got: $text")
    }

    @Test
    fun `bvid without cid is still a bad request`() = testApplication {
        application { routing { with(Video) { videoRoute() } } }
        val res = client.get("/videoStream?bvid=BV1xx411c7mD")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
