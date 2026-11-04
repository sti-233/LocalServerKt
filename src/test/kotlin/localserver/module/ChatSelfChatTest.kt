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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自己发给自己（sendTo == 自己）的两条回归：
 *
 * 1. 服务端广播不能重复 —— 旧实现 `listOf(clients[对方IP], this)` 在自己发给自己时
 *    两项是同一个 WS 会话，同一条消息被推两遍，前端就显示两条。
 * 2. 归档名是 "我-我"，前端按"找不等于自己的那段"解析对方会落空、整个会话被跳过，
 *    侧边栏看不到（测试 2 校验服务端确实按 "我-我" 落档，前端 partnerOf 的解析在
 *    chat.html 内联脚本里，靠 partnerOf 的 node 单测覆盖）。
 */
class ChatSelfChatTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    @Test
    fun `sending to myself delivers exactly one copy`() = withApp { client ->
        // 测试宿主的 client 默认没装 WebSockets，单独建一条带插件的
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(Frame.Text(json.encodeToString(
                buildJsonObject {
                    put("type", "send")
                    put("name", "unused")
                    put("time", "unused")
                    put("text", "hello self")
                    put("sendTo", "tester") // 自己
                }
            )))

            val first = withTimeout(5000) { incoming.receive() }
            val text = (first as Frame.Text).readText()
            val obj = json.parseToJsonElement(text).jsonObject
            assertEquals("send", obj["type"]?.jsonPrimitive?.content)
            assertEquals("hello self", obj["text"]?.jsonPrimitive?.content)

            // 关键断言：不该有第二条。用一个短超时等，收到任何东西都算失败
            val dup = runCatching { withTimeout(1200) { incoming.receive() } }.getOrNull()
            assertTrue(dup == null, "self chat must be delivered once, got a duplicate: $dup")
        }
        wsClient.close()
    }

    @Test
    fun `self chat is archived under a doubled name`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(Frame.Text(json.encodeToString(
                buildJsonObject {
                    put("type", "send")
                    put("name", "unused")
                    put("time", "unused")
                    put("text", "archived")
                    put("sendTo", "tester")
                }
            )))
            withTimeout(5000) { incoming.receive() }
        }
        wsClient.close()

        // getTarget 排序后拼接：两个人同名时就是 "tester-tester"
        val file = File("message", "history_tester-tester.json")
        assertTrue(file.isFile, "self chat should land in ${file.path}, dir=${File("message").list()?.toList()}")

        // 服务端是先 send 再 addHistory，客户端收到帧时归档可能还没落盘
        // （getHistory 会先写入一条 System 占位消息），所以这里轮询等一下，别断言中间态
        var last: String? = null
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val list = runCatching { json.parseToJsonElement(file.readText()).jsonArray }.getOrNull()
            last = list?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            if (last == "archived") break
            Thread.sleep(50)
        }
        assertEquals("archived", last)
    }
}
