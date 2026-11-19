package localserver.module

import localserver.module.Chat.chatRoute
import localserver.module.Control.controlRoute
import localserver.types.Message
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 禁言的端到端行为：control 端点改状态 → WS 拦截发送 → 页面拿得到剩余时长。
 *
 * 测试宿主里所有请求的 `remoteAddress` 都是 `localhost`，`userList.json` 把它登记成
 * `tester`，所以「被禁的人」与「发请求的人」在这个测试里是同一个账号 —— 这正好够用：
 * 禁言是按用户名查的，禁用 tester 再以 tester 发消息，就是真实链路。
 */
class ChatMuteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        File("message").deleteRecursively()
        File(MuteStore.DIR).deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        File("message").deleteRecursively()
        File("userList.json").delete()
        File(MuteStore.DIR).deleteRecursively()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing {
                chatRoute()
                controlRoute()
            }
        }
        block(client)
    }

    private fun sendFrame(text: String) = buildJsonObject {
        put("type", "send")
        put("name", "unused")
        put("time", "unused")
        put("text", text)
        put("sendTo", "")
    }

    private fun payload(frame: JsonObject): Frame.Text = Frame.Text(json.encodeToString(frame))

    private fun archiveText(): String = Util.getHistory(null)

    @Test
    fun `mute state starts clear`() = withApp { client ->
        val resp = client.get("/mute")
        assertEquals(HttpStatusCode.OK, resp.status)

        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertFalse(obj["muted"]!!.jsonPrimitive.boolean, "默认没人被禁")
    }

    @Test
    fun `a muted user cannot send but the history stays readable`() = withApp { client ->
        client.get("/control/mute?name=tester&minutes=10&reason=" + "刷屏")

        // /mute 先告诉页面：还有多久、为什么
        val state = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject
        assertTrue(state["muted"]!!.jsonPrimitive.boolean)
        assertEquals("刷屏", state["reason"]!!.jsonPrimitive.content)
        assertTrue(
            state["left"]!!.jsonPrimitive.content.endsWith("分钟"),
            "剩余时长要说人话，实际是 ${state["left"]}"
        )
        assertTrue(state["until"]!!.jsonPrimitive.long > System.currentTimeMillis())

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(sendFrame("我被禁言了吗")))
            val obj = Json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("err", obj["type"]!!.jsonPrimitive.content)
            assertTrue(
                obj["text"]!!.jsonPrimitive.content.contains("禁言"),
                "提示要说清是被禁言了，而不是笼统的失败"
            )
        }
        wsClient.close()

        // 被拦的消息不该进存档
        assertFalse(archiveText().contains("我被禁言了吗"), "禁言期间发的消息不能落进历史")
        // 但历史照常能读（禁言只拦「发」不拦「看」）
        assertEquals(HttpStatusCode.OK, client.get("/history").status)
    }

    @Test
    fun `unmuting lets the same user speak again`() = withApp { client ->
        client.get("/control/mute?name=tester&minutes=10")
        val muted = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject
        assertTrue(muted["muted"]!!.jsonPrimitive.boolean)

        client.get("/control/mute?action=unmute&name=tester")

        val cleared = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject
        assertFalse(cleared["muted"]!!.jsonPrimitive.boolean, "解除后立刻恢复")

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(sendFrame("现在能说了")))
            val obj = Json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("send", obj["type"]!!.jsonPrimitive.content, "解除后应正常发出")
        }
        wsClient.close()

        assertTrue(archiveText().contains("现在能说了"))
    }

    @Test
    fun `muting again changes the deadline instead of stacking`() = withApp { client ->
        client.get("/control/mute?name=tester&minutes=60")
        val first = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject["until"]!!.jsonPrimitive.long

        client.get("/control/mute?name=tester&minutes=5")
        val second = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject["until"]!!.jsonPrimitive.long

        // 改短了就该真的变短；累加的话第二次会更晚
        assertTrue(second < first, "重新禁言应覆盖解禁时刻（$second 应早于 $first）")
    }

    @Test
    fun `changing the mute pushes a frame to the open chat page`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            // 页面开着的时候被禁：不推这一帧，他要打完整段话点发送才知道
            client.get("/control/mute?name=tester&minutes=10&reason=测试")
            val frame = Json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("muted", frame["type"]!!.jsonPrimitive.content)
            assertTrue(frame["muted"]!!.jsonPrimitive.boolean)
            assertEquals("测试", frame["reason"]!!.jsonPrimitive.content)

            // 解除也推：输入区该立刻恢复
            client.get("/control/mute?action=unmute&name=tester")
            val off = Json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("muted", off["type"]!!.jsonPrimitive.content)
            assertFalse(off["muted"]!!.jsonPrimitive.boolean)
        }
        wsClient.close()
    }

    @Test
    fun `muting someone who is not registered is refused`() = withApp { client ->
        val resp = client.get("/control/mute?name=ghost&minutes=10")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("不在白名单"), "要知道是不是把名字打错了")
        assertEquals(null, MuteStore.active("ghost"))
    }

    @Test
    fun `a bad duration is refused`() = withApp { client ->
        val resp = client.get("/control/mute?name=tester&minutes=0")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(null, MuteStore.active("tester"), "0 分钟不该被解释成永久禁言")
    }

    @Test
    fun `an expired mute does not block sending`() = withApp { client ->
        // 直接塞一条已过期的记录：真实场景是「禁言到点了，但页面没刷新」
        File(MuteStore.DIR).mkdirs()
        File(MuteStore.DIR, "mutes.json").writeText(
            """{"mutes":{"tester":{"until":${System.currentTimeMillis() - 60_000},"by":"control","reason":"","createdAt":0}}}"""
        )

        val state = Json.parseToJsonElement(client.get("/mute").bodyAsText()).jsonObject
        assertFalse(state["muted"]!!.jsonPrimitive.boolean, "到点之后不该还报禁言")

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(sendFrame("早就解禁了")))
            val obj = Json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("send", obj["type"]!!.jsonPrimitive.content)
        }
        wsClient.close()
    }

    @Test
    fun `the control status lists who is muted and for how long`() = withApp { client ->
        MuteStore.mute("tester", 30, "control", "刷屏")
        // 另一个人（10.0.0.2 未在线）也禁一个，验证列表是多条而不是只有自己
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(User(ip = "localhost", name = "tester"), User(ip = "10.0.0.2", name = "bob"))
            )
        )
        MuteStore.mute("bob", 5, "control")

        val status = Json.parseToJsonElement(client.get("/control/status").bodyAsText()).jsonObject
        val mutes = status["mutes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, mutes.size, "面板要能列出所有被禁的人")
        // 按解禁时刻升序：先到期的人排在前面，处理时不用自己排
        assertEquals("bob", mutes.first()["name"]!!.jsonPrimitive.content)
        val tester = mutes.first { it["name"]!!.jsonPrimitive.content == "tester" }
        assertEquals("刷屏", tester["reason"]!!.jsonPrimitive.content)
        assertTrue(assertNotNull(tester["left"]).jsonPrimitive.content.isNotEmpty())
    }
}
