package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.*

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 图片按用户分目录后的隔离性：发送 `ImageRef:` 时只能引用**自己**目录里的图。
 *
 * 图片落在 `cache/picture/<用户名>/`，但 `/img/{*}` 是公开端点、URL 里不带用户，
 * 所以「别人目录里的图能不能被拿来发送」只能靠 WS 发送分支自己校验。
 * 这里用真的 WebSocket 走一遍发送分支，确认：
 * 1. 自己目录里的图可以发（原样转发引用，不重传 base64）；
 * 2. 别人目录里的图会被拒（回 err，不转发）。
 */
class ChatPictureScopeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val pictureDir = File(PictureStore.DIR)
    private val myDir = File(pictureDir, "tester")
    private val otherDir = File(pictureDir, "someone-else")

    // 1x1 透明 PNG，只用来占位（发送分支不校验内容，只校验名字与文件存在）
    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        pictureDir.deleteRecursively()
        File("message").deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        pictureDir.deleteRecursively()
        File("message").deleteRecursively()
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }
        block(client)
    }

    // 发一条 ImageRef，收回一条事件（成功是 send、被拒是 err）
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
            .sendImageRef(name: String): JsonObject {
        send(Frame.Text(json.encodeToString(
            buildJsonObject {
                put("type", "send")
                put("name", "unused")
                put("time", "unused")
                put("text", "ImageRef:/img/$name")
                put("sendTo", "")
            }
        )))
        // 连接建立时服务端会先推一帧 presence（在线名单），这里只认业务帧
        return json.parseToJsonElement(nextBusinessFrame()).jsonObject
    }

    @Test
    fun `my own picture can be sent by reference`() = withApp { client ->
        val name = sha256Hex(pngBytes) + ".png"
        myDir.mkdirs()
        File(myDir, name).writeBytes(pngBytes)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            val obj = sendImageRef(name)
            assertEquals("send", obj["type"]?.jsonPrimitive?.content)
            assertEquals("ImageRef:/img/$name", obj["text"]?.jsonPrimitive?.content)
        }
        wsClient.close()
    }

    @Test
    fun `another users picture is rejected`() = withApp { client ->
        val name = sha256Hex("someone-else".toByteArray()) + ".png"
        otherDir.mkdirs()
        File(otherDir, name).writeBytes(pngBytes)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            val obj = sendImageRef(name)
            assertEquals("err", obj["type"]?.jsonPrimitive?.content, "别人的图不该被转发：$obj")
        }
        wsClient.close()
    }
}
