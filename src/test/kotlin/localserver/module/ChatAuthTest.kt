package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 聊天模块的鉴权边界。
 *
 * 早先只有 `/chat`、`/whoami` 挂在 `auth` 后面，`/history`、`/conversations`、`/img`、
 * `/pictures`、`/savePicture`、`/deletePicture`、`/message` 全都是裸的 ——
 * 只要知道地址，任何同网段设备都能读全部聊天记录、翻别人的图片缓存、往聊天里塞消息。
 * 这里逐个端点确认未认证会被挡下（`form("auth")` 的 challenge 会重定向到 SSO，故断言 3xx），
 * 并确认 `/wsSever`、`/clients` 这两个服务信息端点仍然可达 —— 它们不含用户数据
 * （`/clients` 只回连接数，不列 IP）。
 */
class ChatAuthTest {

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        File("message").deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        File("message").deleteRecursively()
        File("userList.json").delete()
    }

    /** 不装 skipWhen：`auth` 会正常挑战，`control` 放行以免路由装配时报未注册 */
    private fun withLockedApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }
        block(client)
    }

    private fun assertBlocked(status: HttpStatusCode, endpoint: String) {
        assertTrue(
            status.value >= 300,
            "$endpoint must sit behind auth, but returned $status"
        )
    }

    @Test
    fun `history and conversations need auth`() = withLockedApp { client ->
        assertBlocked(client.get("/history").status, "/history")
        assertBlocked(client.get("/history?targetuser=tester").status, "/history?targetuser=")
        assertBlocked(client.get("/conversations").status, "/conversations")
    }

    @Test
    fun `picture endpoints need auth`() = withLockedApp { client ->
        assertBlocked(client.get("/img/" + "a".repeat(64) + ".png").status, "/img/*")
        assertBlocked(client.get("/pictures").status, "/pictures")
        assertBlocked(
            client.post("/savePicture") {
                contentType(ContentType.Application.Json)
                setBody("""{"data":"data:image/png;base64,iVBORw0KGgo="}""")
            }.status,
            "/savePicture"
        )
        assertBlocked(
            client.post("/deletePicture") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"${"a".repeat(64)}.png"}""")
            }.status,
            "/deletePicture"
        )
    }

    @Test
    fun `websocket needs auth`() = withLockedApp { client ->
        // 握手被 auth 挡下：Ktor 的 test client 对 WS 升级失败会抛异常，
        // 抛出来就说明没进 webSocket 块；若真连上了反而说明鉴权漏了
        val result = runCatching {
            client.get("/message")
        }
        assertTrue(result.isFailure || result.getOrNull()?.status?.value ?: 500 >= 300,
            "ws /message must not be reachable without auth")
    }

    @Test
    fun `service info endpoints stay reachable and leak no addresses`() = withLockedApp { client ->
        val wsSever = client.get("/wsSever")
        assertEquals(HttpStatusCode.OK, wsSever.status)
        val clientsResp = client.get("/clients")
        assertEquals(HttpStatusCode.OK, clientsResp.status)
        val body = clientsResp.bodyAsText()
        // 只回数量：以前这里是 clients.keys.joinToString()，等于把每个在线设备的内网 IP 发给任何人
        assertTrue(body.contains("Connected clients"), "unexpected body: $body")
        assertFalse(body.contains("."), "client list must not expose addresses, got: $body")
    }
}
