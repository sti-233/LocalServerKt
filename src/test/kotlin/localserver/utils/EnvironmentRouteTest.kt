package localserver.utils

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/environment` 只是给「环境检测」页一条干净路径，页面本身全在前端。
 *
 * 验三件事：不带尾斜杠的短路径能落到页面（302 → staticResources，测试里会跟随重定向）、
 * 落地的确实是 environment.html、以及它和课表页一样挂在 `authenticate("auth")` 后面。
 */
class EnvironmentRouteTest {

    @Test
    fun `environment redirects to the page and the page is served`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            routing {
                staticResources("/resources", "")
                with(Network) { network() }
            }
        }

        // client 默认跟随 302，最终应是页面本身
        val resp = client.get("/environment")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("环境检测"), "should land on the environment page")
        // 页面自己的几个关键标识，防止误落到别的页面
        assertTrue(body.contains("一、存储"), "page should keep the detection groups")
        assertTrue(body.contains("copyJson"), "page should keep the copy button")
    }

    @Test
    fun `environment is behind auth like the timetable page`() {
        // 不装 skipWhen：未认证必须被挡下，证明斜杠前的路径不是裸奔的
        testApplication {
            application {
                install(Authentication) {
                    form("auth") { }
                    basic("control") { skipWhen { true } }
                }
                routing {
                    staticResources("/resources", "")
                    with(Network) { network() }
                }
            }
            val resp = client.get("/environment")
            assertTrue(resp.status.value >= 300, "expected auth challenge, got ${resp.status}")
        }
    }
}
