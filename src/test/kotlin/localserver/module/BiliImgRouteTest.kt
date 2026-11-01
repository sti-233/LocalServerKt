package localserver.module

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `/biliImg` 的入参校验。
 *
 * 这里只验参数缺失的情况；真正的取图与缓存命中需要真上游，
 * 纯逻辑（扩展名推断、定名、落盘）放在 [localserver.utils.BiliImgTest]。
 */
class BiliImgRouteTest {

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            routing {
                with(Video) { videoRoute() }
            }
        }
        block(client)
    }

    @Test
    fun `missing url is a bad request`() = withApp { client ->
        val resp = client.get("/biliImg")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
