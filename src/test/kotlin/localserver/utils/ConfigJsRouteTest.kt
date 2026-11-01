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
 * `/resources/config.js` 由服务端动态生成。
 *
 * 关键点：它和 `staticResources("/resources", "")` 抢同一个前缀。config.js 这个文件已从
 * resources 目录删掉，若静态资源路由抢先命中并回 404，前端所有页面都会拿不到 SERVER_IP。
 * 这个测试就是在模拟 Server.kt 的注册顺序（staticResources 在前）下验证路由能正确落到动态端点。
 */
class ConfigJsRouteTest {

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
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
        block(client)
    }

    @Test
    fun `config js is served dynamically and wins over static resources`() = withApp { client ->
        val resp = client.get("/resources/config.js")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("const SERVER_IP = \""), "missing SERVER_IP: $body")
        assertTrue(body.contains("const VNC_URL = "), "missing VNC_URL: $body")
        // 值是探测出来的真实地址，不是空串/占位符
        val ip = Regex("const SERVER_IP = \"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
        assertTrue(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip), "bad ip: $ip")
        assertEquals(ip, LocalIp.current())
    }

    @Test
    fun `config js is not cached by the browser`() = withApp { client ->
        val resp = client.get("/resources/config.js")
        assertEquals("no-store", resp.headers["Cache-Control"])
        assertTrue(
            resp.headers["Content-Type"].orEmpty().contains("javascript"),
            "unexpected content type: ${resp.headers["Content-Type"]}"
        )
    }

    @Test
    fun `other static resources still work`() = withApp { client ->
        // 动态路由只接管 config.js，其余静态文件不受影响
        val resp = client.get("/resources/help.md")
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
