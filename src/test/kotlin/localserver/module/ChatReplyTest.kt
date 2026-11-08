package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.Message
import localserver.types.ReplyRef
import localserver.types.User
import localserver.utils.Time
import localserver.utils.Util

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
 * 回复（引用）功能：存档格式、WS 端到端、@ 名单与侧边栏「@」提示。
 *
 * 三件事各自独立的失败方式，所以分开断言：
 * 1. 存档里 reply 没写进去 → 刷新页面回复块就没了（只是显示问题，但用户眼里是"丢了"）；
 * 2. WS 广播里没带 reply → 对方要刷新才看得到，实时消息是残的；
 * 3. 旧存档读不出来 → 这一条会顺着 addHistory 冒到读循环，整条连接被拆（见 ChatHistoryCompatTest）。
 */
class ChatReplyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sendFrame(text: String, reply: JsonObject? = null): Frame.Text =
        Frame.Text(json.encodeToString(
            buildJsonObject {
                put("type", "send")
                put("name", "unused")
                put("time", "unused")
                put("text", text)
                put("sendTo", "")
                if (reply != null) put("reply", reply)
            }
        ))

    private fun replyOf(name: String, time: String, text: String): JsonObject = buildJsonObject {
        put("name", name)
        put("time", time)
        put("text", text)
    }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(
                    User(ip = "localhost", name = "tester"),
                    User(ip = "192.168.3.50", name = "alice"),
                    User(ip = "192.168.3.51", name = "bob")
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

    // ===== 存档格式 =====

    /** 带 reply 的消息写进去再读出来，快照三要素原样保留 */
    @Test
    fun `reply snapshot survives a write and read`() {
        val ref = ReplyRef("alice", "2026-11-08 10:00:00", "原话")
        Util.addHistory(Message("tester", "2026-11-08 10:01:00", "回复你", ref), "2026-11-08")

        val list = Util.getHistoryList("2026-11-08")
        val last = list.last()
        assertEquals("回复你", last.text)
        assertEquals("alice", last.reply?.name, "reply 快照必须落盘，否则刷新就丢: $list")
        assertEquals("2026-11-08 10:00:00", last.reply?.time)
        assertEquals("原话", last.reply?.text)
    }

    /**
     * 不回复的消息**不能**在文件里多出 "reply": null。
     * 存档是纯文本、一天一个文件，无意义地给每条都加一行会让所有人的历史文件一起变大。
     */
    @Test
    fun `a message without reply writes no reply key`() {
        Util.addHistory(Message("tester", "2026-11-08 10:00:00", "普通消息"), "2026-11-08")
        val raw = File("message/history_2026-11-08.json").readText()
        assertTrue(!raw.contains("\"reply\""), "不该给普通消息写 reply 字段，实际内容:\n$raw")
    }

    /** 旧版本写下的存档（没有 reply 字段）必须照旧能读，reply 读成 null */
    @Test
    fun `an old archive without reply still reads back null`() {
        File("message/history_2026-11-09.json").writeText(
            """[{"name":"alice","time":"2026-11-09 08:00:00","text":"旧消息"}]"""
        )
        val list = Util.getHistoryList("2026-11-09")
        assertEquals(1, list.size)
        assertEquals(null, list[0].reply, "旧存档的 reply 应当是 null，不能因此抛异常")
    }

    /** 未来版本写下的、带未知子字段的 reply 也要能读（前后兼容另一半） */
    @Test
    fun `a reply with unknown subkeys is still readable`() {
        File("message/history_2026-11-10.json").writeText(
            """
            [{"name":"alice","time":"2026-11-10 08:00:00","text":"新",
              "reply":{"name":"bob","time":"2026-11-10 07:59:00","text":"旧","quoteId":"x"}}]
            """.trimIndent()
        )
        val list = Util.getHistoryList("2026-11-10")
        assertEquals("bob", list[0].reply?.name)
    }

    // ===== WS 端到端 =====

    /** 发一条带 reply 的消息，服务端既要落盘也要把 reply 原样广播回来 */
    @Test
    fun `sending with reply persists it and echoes it back`() = testApplication {
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
            send(sendFrame("收到", replyOf("alice", "2026-11-08 10:00:00", "在吗")))
            val raw = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
            val obj = json.parseToJsonElement(raw).jsonObject
            assertEquals("send", obj["type"]?.jsonPrimitive?.content)
            val reply = obj["reply"]?.jsonObject
            assertEquals("alice", reply?.get("name")?.jsonPrimitive?.content, "广播必须带 reply: $raw")
            assertEquals("在吗", reply?.get("text")?.jsonPrimitive?.content)
        }
        wsClient.close()

        val stored = Util.getHistoryList(Time.getCurrentDate())
        assertEquals("alice", stored.last().reply?.name, "落盘也要带 reply，实际: $stored")
    }

    /**
     * 超长的引用文本要被截到 [ReplyRef.TEXT_LIMIT] 以内。
     *
     * 客户端只传摘要，但客户端是浏览器 —— 直接 POST/发帧的人可以让它有多长就多长，
     * 而它会跟着每一条回复一起写进所有人的历史文件。所以截断必须发生在服务端。
     */
    @Test
    fun `an overlong reply text is truncated by the server`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }

        val long = "字".repeat(ReplyRef.TEXT_LIMIT + 500)
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(sendFrame("这么长", replyOf("alice", "2026-11-08 10:00:00", long)))
            withTimeout(5000) { incoming.receive() }
        }
        wsClient.close()

        val stored = Util.getHistoryList(Time.getCurrentDate()).last()
        assertEquals(
            ReplyRef.TEXT_LIMIT, stored.reply?.text?.length,
            "服务端必须把引用文本截到 ${ReplyRef.TEXT_LIMIT}，否则一条回复能把存档撑大"
        )
    }

    /** reply 缺 name/time 时当作没回复处理，不能存一条指不到任何东西的引用 */
    @Test
    fun `an incomplete reply is dropped instead of stored`() = testApplication {
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
            send(sendFrame("空引用", replyOf("", "", "无主")))
            withTimeout(5000) { incoming.receive() }
        }
        wsClient.close()

        val stored = Util.getHistoryList(Time.getCurrentDate()).last()
        assertEquals(null, stored.reply, "name/time 为空的 reply 不该落盘: $stored")
    }

    // ===== @ 与侧边栏提示 =====

    /** /members 只回名字，不回 IP（内网地址不该摊给每个聊天用户） */
    @Test
    fun `members returns names but never addresses`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }

        val body = client.get("/members").bodyAsText()
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("tester", obj["me"]?.jsonPrimitive?.content)
        val names = obj["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("alice", "bob", "tester"), names)
        assertTrue(!body.contains("192.168"), "/members 不应泄漏内网地址: $body")
        assertTrue(!body.contains("localhost"), "/members 不应泄漏内网地址: $body")
    }

    /**
     * /conversations 的 last 要带上这条消息 @ 了谁，前端才能把红点升级成「@」胶囊。
     * 只回名字集合、不回正文 —— 正文里可能有别的内容，没必要跟着会话列表一起发。
     */
    @Test
    fun `conversations reports who the last message mentioned`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }

        Util.addHistory(Message("alice", "2026-11-08 09:00:00", "@tester @bob 看这里"), "2026-11-08")
        Util.addHistory(Message("alice", "2026-11-08 09:05:00", "没有点名的消息"), "2026-11-09")

        val body = client.get("/conversations").bodyAsText()
        val last = json.parseToJsonElement(body).jsonObject["last"]!!.jsonObject

        val withMention = last["2026-11-08"]!!.jsonObject["at"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("bob", "tester"), withMention, "应报出被点名的两个名字，实际: $withMention")

        val without = last["2026-11-09"]!!.jsonObject["at"]!!.jsonArray
        assertEquals(0, without.size, "没 @ 人时应当是空数组，不是 null")
    }

    /** @ 后面跟英文/数字时不该被当成点名（`@bob123` 不是 `@bob`） */
    @Test
    fun `a mention immediately followed by word characters is not counted`() = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing { chatRoute() }
        }

        Util.addHistory(Message("alice", "2026-11-08 09:00:00", "@bob123 这不算"), "2026-11-11")

        val body = client.get("/conversations").bodyAsText()
        val at = json.parseToJsonElement(body).jsonObject["last"]!!.jsonObject["2026-11-11"]!!
            .jsonObject["at"]!!.jsonArray
        assertEquals(0, at.size, "@bob123 不该被当成 @bob，实际: $at")
    }

    // ===== 改名连带 =====

    /** 改名时回复快照里的名字也要跟着改，否则引用块显示成另一个人 */
    @Test
    fun `renaming a user rewrites the name inside reply snapshots`() {
        val ref = ReplyRef("alice", "2026-11-08 10:00:00", "原话")
        Util.addHistory(Message("bob", "2026-11-08 10:01:00", "回复 alice", ref), "2026-11-12")

        Util.migrateRename("alice", "alice2")

        val list = Util.getHistoryList("2026-11-12")
        assertEquals("alice2", list.last().reply?.name, "引用快照里的名字必须跟着改: $list")
        assertEquals("原话", list.last().reply?.text, "快照正文不该被动")
    }
}
