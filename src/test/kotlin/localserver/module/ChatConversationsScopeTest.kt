package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.Message
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertTrue

/**
 * `/conversations` 的可见范围：只能看见「群聊日期归档」与「自己参与的私聊归档」。
 *
 * 不过滤会同时坏两件事：
 * 1. 隐私 —— 每个人的私聊档案名、最后活动时间、发送者、被 @ 名单都被摊给所有登录用户；
 * 2. 未读红点误报 —— 前端 `partnerOf` 取「第一个不等于我的段」，`aa` 收到 `bb-cc` 的
 *    last 就会把 `bb` 那一项顶起来并亮红点，点进去却只有历史消息，怎么点都消不干净。
 *
 * 所以过滤放在**服务端**：前端那份 `privLastMap` 是直接照 `last` 建的，
 * 服务端不发，前端就无从点亮。
 */
class ChatConversationsScopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(
                    User(ip = "localhost", name = "tester"),
                    User(ip = "192.168.3.50", name = "alice"),
                    User(ip = "192.168.3.51", name = "bob"),
                    // 旧用户名遗留：真实存在过含 `-` 的名字，归档名只能靠 partnerIn 回验
                    User(ip = "192.168.3.52", name = "alice-b")
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

    private suspend fun io.ktor.client.HttpClient.names(): List<String> {
        val body = get("/conversations").bodyAsText()
        return json.parseToJsonElement(body).jsonObject["names"]!!
            .jsonArray.map { it.jsonPrimitive.content }
    }

    /** 最核心的一条：别人的私聊连同它的 last 都不该出现 */
    @Test
    fun `other peoples chats never show up`() = withApp { client ->
        Util.addHistory(Message("alice", "2026-11-08 09:00:00", "群里说句话"), "2026-11-08")
        Util.addHistory(Message("alice", "2026-11-08 09:10:00", "我和 tester 私聊"), "alice-tester")
        // 两个都不含 tester：这就是 aa 被亮红点的元凶
        Util.addHistory(Message("bob", "2026-11-08 23:59:00", "bob 找 alice"), "alice-bob")

        val names = client.names()
        assertTrue("2026-11-08" in names, "群聊日期归档人人可见，实际: $names")
        assertTrue("alice-tester" in names, "自己参与的私聊必须可见，实际: $names")
        assertTrue("alice-bob" !in names, "别人的私聊不该出现在名单里，实际: $names")
    }

    /** last 与 names 必须同一套过滤：只藏 names 不藏 last，红点照样会亮 */
    @Test
    fun `last is filtered together with names`() = withApp { client ->
        Util.addHistory(Message("bob", "2026-11-08 23:59:00", "@tester bob 找 alice"), "alice-bob")

        val body = client.get("/conversations").bodyAsText()
        val last = json.parseToJsonElement(body).jsonObject["last"]!!.jsonObject
        assertTrue(last["alice-bob"] == null, "别人的私聊不该带 last，否则前端仍会算出红点: $body")
        assertTrue("tester" !in body, "连被 @ 的名单都不该漏出来: $body")
    }

    /** 自己发给自己的 `我-我` 归档也要可见（partnerIn 对 `me-me` 返回 me 本身） */
    @Test
    fun `self chat archive stays visible`() = withApp { client ->
        Util.addHistory(Message("tester", "2026-11-08 10:00:00", "记一笔"), "tester-tester")
        assertTrue("tester-tester" in client.names(), "自己发给自己的归档不能被过滤掉")
    }

    /**
     * 用户名含 `-` 时不能退回 `split('-')`。
     *
     * `alice-b` 与 `tester` 的归档叫 `alice-b-tester`，split 出来的段里没有 `tester`，
     * 用 `split('-').contains(me)` 判会把这个人的会话整条藏起来。
     */
    @Test
    fun `archive names with hyphens still match by partnerIn`() = withApp { client ->
        Util.addHistory(Message("alice-b", "2026-11-08 11:00:00", "名字里带横杠"), "alice-b-tester")
        assertTrue(
            "alice-b-tester" in client.names(),
            "含 `-` 的用户名归档必须仍能认出来，实际: ${client.names()}"
        )
    }

    /**
     * 解析不出当前用户（`?p=` 或 control 白名单进来的 IP 未必登记在 userList 里，
     * `getUserName` 会抛）时退化成「只有群聊」：宁缺勿漏，私聊一条都不放。
     */
    @Test
    fun `unknown caller only sees group archives`() = withApp { client ->
        // 把 localhost 从名单里摘掉，模拟"能通过 auth 但查不到名字"的客户端
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "192.168.3.50", name = "alice")))
        )
        Util.addHistory(Message("alice", "2026-11-08 09:00:00", "群聊"), "2026-11-08")
        Util.addHistory(Message("alice", "2026-11-08 09:10:00", "私聊"), "alice-tester")

        assertEquals(listOf("2026-11-08"), client.names(), "查不到名字时只能给群聊归档")
    }
}
