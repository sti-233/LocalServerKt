package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.Message
import localserver.types.User
import localserver.utils.Time
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 聊天存档的互相兼容性：旧版本写下的文件，新版本还能不能读、还能不能往里追加。
 *
 * 这条链路上「读全文 → 改 → 整份覆写」，所以读这一步一旦抛异常，
 * 抛的不是一个只影响显示的小错——它会顺着 `Util.addHistory` 冒到 WS 读循环的
 * `catch (e: Exception)`，把整条连接拆掉。表现是「发一条消息，连接就断了，
 * 重连后再发又断」，而原因是某天存档里多了一个本版本不认识的字段。
 */
class ChatHistoryCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun frameOf(text: String) = Frame.Text(json.encodeToString(
        buildJsonObject {
            put("type", "send")
            put("name", "unused")
            put("time", "unused")
            put("text", text)
            put("sendTo", "")
        }
    ))

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        File("message").deleteRecursively()
        File("message").mkdirs()
    }

    @AfterTest
    fun tearDown() {
        File("message").deleteRecursively()
        File("userList.json").delete()
    }

    /** 存档里出现本版本不认识的字段（将来加的 id / edited / revoked / reactions…）时必须能读 */
    @Test
    fun `a history file with unknown keys is still readable`() {
        File("message/history_2026-01-01.json").writeText(
            """
            [
              {"name": "Alice", "time": "2026-01-01 10:00:00", "text": "hi", "id": "m-1", "edited": false},
              {"name": "Bob", "time": "2026-01-01 10:01:00", "text": "yo", "reactions": [{"emoji": "x"}]}
            ]
            """.trimIndent()
        )

        val list = Util.getHistoryList("2026-01-01")
        assertEquals(2, list.size, "unknown keys must not make the archive unreadable")
        assertEquals("Alice", list[0].name)
        assertEquals("yo", list[1].text)
    }

    /** 同上，但走「追加一条消息」这条真实写路径：读不出来就等于这条消息发不出去 */
    @Test
    fun `appending to a history file with unknown keys keeps the old records`() {
        File("message/history_2026-01-01.json").writeText(
            """
            [
              {"name": "Alice", "time": "2026-01-01 10:00:00", "text": "hi", "id": "m-1"}
            ]
            """.trimIndent()
        )

        Util.addHistory(Message("tester", "2026-01-01 10:02:00", "appended"), "2026-01-01")

        val after = Util.getHistoryList("2026-01-01")
        assertEquals(2, after.size, "the old record must survive the rewrite, got $after")
        assertEquals("hi", after[0].text)
        assertEquals("appended", after[1].text)
    }

    /** 缺字段（更老的存档或被手工编辑过）应当由调用方兜住，而不是炸掉读循环 */
    @Test
    fun `reading a history file with an extra key does not throw`() {
        File("message/history_2026-01-02.json").writeText(
            """[{"name":"A","time":"2026-01-02 10:00:00","text":"x","sendTo":"B"}]"""
        )
        // 这里只要求不抛异常；内容本身（多出来的 sendTo 被丢弃）不是断言重点
        assertEquals(1, Util.getHistoryList("2026-01-02").size)
    }

    /**
     * 真的坏掉的存档（不是多字段，是根本解析不了）：写入口要抛，但**读循环不能跟着断**。
     *
     * 这里断言的是 Util 层的行为契约 —— `addHistory` 抛异常是可接受的（调用方
     * `handleSend` 会把它转成一条 err 回复），但异常必须是可捕获的普通异常，
     * 不能是会把进程或协程直接带走的 Error。
     */
    @Test
    fun `a corrupt history file fails the write with a catchable exception`() {
        File("message/history_2026-01-03.json").writeText("[{\"name\": \"A\", \"time\": ")

        val thrown = runCatching {
            Util.addHistory(Message("tester", "2026-01-03 10:00:00", "x"), "2026-01-03")
        }.exceptionOrNull()

        assertEquals(
            true, thrown is Exception && thrown !is Error,
            "a corrupt archive must surface as a catchable Exception so the caller can reply with err, got $thrown"
        )
    }

    /**
     * 端到端：当天的存档坏了，发消息应当回一条 `err`，**而不是把 WS 连接拆掉**。
     *
     * 这是最早那一版的真实故障：`Util.addHistory` 抛的异常一路冒到读循环的
     * `catch (e: Exception)`，连接被关；前端自动重连、再发、又断。用户看到的是
     * 「聊天突然发不出消息」，而日志里只有一行 parse 失败。
     */
    @Test
    fun `a corrupt archive replies with an error instead of closing the websocket`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }
        // 把「今天」的群聊归档弄坏：handleSend 会先读它再追加
        File("message/history_${Time.getCurrentDate()}.json").writeText("[{\"name\": \"A\", ")

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(frameOf("this should come back as an error"))
            // 跳过连接建立时的 presence 帧，只等业务帧（见 WsTestSupport）
            val first = nextBusinessFrame()
            assertTrue(first.contains("\"err\""), "expected an err reply, got: $first")

            // 连接必须还活着：再发一帧仍然能收到回复（同样的 err），而不是 Channel 已关闭
            send(frameOf("still connected"))
            val second = nextBusinessFrame()
            assertTrue(second.contains("\"err\""), "the connection must survive, got: $second")
        }
        wsClient.close()
    }

    /**
     * 缺字段的存档（更老的版本，或被手工编辑过）必须读得出来。
     *
     * `Message` 的三个字段都没有默认值，所以缺任何一个都会 `MissingFieldException` ——
     * 这同样是能顺着 addHistory 冒到读循环的路径。这里断言的是「当前实现确实会抛」，
     * 用来固定住契约：**要么给 Message 补默认值，要么在读取处包 try 兜住**。
     * 目前的选择是后者（见 handleSend 的 catch），所以这里验证异常确实是可捕获的。
     */
    @Test
    fun `a history entry missing a field surfaces a catchable exception`() {
        File("message/history_2026-01-04.json").writeText(
            """[{"name":"A","time":"2026-01-04 10:00:00"}]"""
        )

        val thrown = runCatching {
            Util.getHistoryList("2026-01-04")
        }.exceptionOrNull()

        assertTrue(
            thrown is Exception,
            "a missing field must be a catchable Exception (handled by the caller), got $thrown"
        )
    }
}
