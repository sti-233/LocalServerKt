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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 私聊对象校验：只认**用户名**，不认 IP。
 *
 * 背景（线上真实故障）：`userExists` 把 IP 也算命中，于是往「私聊对象」输入框里填
 * `192.168.3.32` 能过校验 —— 消息先落盘成 `history_192.168.3.32-我.json`，
 * 广播时 `getUserIp` 按名字查不到、抛 `User is null!`，读循环的 catch 把整条
 * 连接拆掉。用户看到的是「发一条就断线」，而消息已经进存档、侧边栏还多出一个
 * 叫 IP 的假会话。
 *
 * 两条断言各自独立：
 * 1. `nameExists` 与 `userExists` 的判据确实不同（防止有人把校验改回去）；
 * 2. 端到端：发 IP 得到一个 `err` 帧、**连接不断**、并且**不落盘**。
 */
class ChatTargetValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(
                    User(ip = "localhost", name = "tester"),
                    User(ip = "192.168.3.29", name = "test2")
                )
            )
        )
        File("message").deleteRecursively()
        File("message").mkdirs()
    }

    @AfterTest
    fun tearDown() {
        File("message").deleteRecursively()
        File("userList.json").delete()
    }

    // ===== 判据本身 =====

    /**
     * 两个函数的差别就是这次事故的全部原因：IP 能过 userExists、过不了 nameExists。
     * 这条测试专门盯着它，别人把 handleSend 的校验改回 userExists 时会先在这里红。
     */
    @Test
    fun `an ip passes userExists but not nameExists`() {
        assertTrue(Util.userExists("192.168.3.29"), "userExists 本来就该把 IP 算作命中（改名等场景要用）")
        assertFalse(Util.nameExists("192.168.3.29"), "IP 不是用户名，私聊对象校验必须拒绝它")

        assertTrue(Util.nameExists("test2"), "登记过的名字应当通过")
        assertFalse(Util.nameExists("nobody"), "没登记的名字应当被拒绝")
    }

    // ===== 端到端 =====

    @Test
    fun `sending to an ip replies err without dropping the connection`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(frame(sendTo = "192.168.3.29", text = "hi"))

            // 第一帧必须是 err（不是 send：这条消息根本不该被当成正常消息处理）
            val first = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
            val firstObj = json.parseToJsonElement(first).jsonObject
            assertEquals("err", firstObj["type"]?.jsonPrimitive?.content, "填 IP 应当回 err，实际: $first")
            assertTrue(
                firstObj["text"]?.jsonPrimitive?.content?.contains("用户名") == true,
                "错误提示要说清楚「填名字不是 IP」，实际: $first"
            )

            // 连接必须还活着：再发一条群消息，应当收到正常的 send 回执
            send(frame(sendTo = "", text = "还在吗"))
            val second = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
            val secondObj = json.parseToJsonElement(second).jsonObject
            assertEquals(
                "send", secondObj["type"]?.jsonPrimitive?.content,
                "被拒绝的私聊不能带走连接，实际收到的下一帧: $second"
            )
            assertEquals("还在吗", secondObj["text"]?.jsonPrimitive?.content)
        }
        wsClient.close()

        // 关键：不能留下 history_192.168.3.29-tester.json 这种「发给 IP」的假归档
        val leftovers = File("message").listFiles().orEmpty().map { it.name }
        assertFalse(
            leftovers.any { it.contains("192.168.3.29") },
            "被拒绝的消息不该落盘，实际文件: $leftovers"
        )
    }

    private fun frame(sendTo: String, text: String): Frame.Text =
        Frame.Text(json.encodeToString(
            buildJsonObject {
                put("type", "send")
                put("name", "unused")
                put("time", "unused")
                put("text", text)
                put("sendTo", sendTo)
            }
        ))
}
