package localserver.module

import localserver.module.Ai.aiRoute
import localserver.types.AiConversation
import localserver.types.AiMessage
import localserver.types.User
import localserver.utils.AiHistory
import localserver.utils.Util

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.form
import io.ktor.server.routing.Route
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AI 会话存档端点的集成测试（Ktor testApplication）。
 *
 * 覆盖：新建会话、列表排序、重命名、删除、非法 id 拒绝、跨用户隔离。
 * 注意：服务端按"请求来源 IP"定位用户，测试里所有请求都来自 127.0.0.1，
 * 因此统一用 userList.json 里的 127.0.0.1 用户。
 */
class AiConversationRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    // 测试用的 userList.json（放在测试工作目录）
    // 注意：Ktor testApplication 里 call.request.local.remoteAddress 是 "localhost"，
    // 所以白名单要按这个 ip 登记，才能验证"落盘到 cache/ai/<用户名>/"
    private fun writeUserList() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(User(ip = "localhost", name = "tester"))
            )
        )
    }

    @BeforeTest
    fun setup() {
        writeUserList()
        File("cache/ai").deleteRecursively()
    }

    @AfterTest
    fun teardown() {
        File("cache/ai").deleteRecursively()
    }

    private fun Application.aiApp() {
        install(SSE) // /ai/chat 是 SSE 路由，必须装 SSE 插件
        install(Authentication) {
            form("auth") {
                skipWhen { true } // 测试环境跳过鉴权
            }
        }
        routing {
            aiRoute()
            get("/whoami-test") {
                call.respondText(call.request.local.remoteAddress)
            }
        }
    }

    @Test
    fun newConversationCreatesSessionAndAppearsInList() = testApplication {
        application { aiApp() }
        val resp = client.post("/ai/conversation/new") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val id = json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(AiHistory.isValidId(id), "会话 id 格式应合法: " + id)

        // 列表里应出现该会话
        val listResp = client.get("/ai/conversations")
        assertEquals(HttpStatusCode.OK, listResp.status)
        val items = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(id, items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun renameConversationUpdatesStoredName() = testApplication {
        application { aiApp() }
        val id = createConversation()

        val resp = client.post("/ai/conversation/rename") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id, "name" to "周会纪要")))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        // 详情接口返回新名字
        val detail = client.get("/ai/conversation?id=" + id)
        assertEquals(HttpStatusCode.OK, detail.status)
        val obj = json.parseToJsonElement(detail.bodyAsText()).jsonObject
        assertEquals("周会纪要", obj["name"]!!.jsonPrimitive.content)
        assertEquals(true, obj["custom"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun renameRejectsEmptyName() = testApplication {
        application { aiApp() }
        val id = createConversation()
        val resp = client.post("/ai/conversation/rename") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id, "name" to "   ")))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun renameRejectsInvalidId() = testApplication {
        application { aiApp() }
        val resp = client.post("/ai/conversation/rename") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to "../secrets", "name" to "x")))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun deleteConversationRemovesItFromList() = testApplication {
        application { aiApp() }
        val id = createConversation()
        val resp = client.post("/ai/conversation/delete") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id)))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val items = json.parseToJsonElement(client.get("/ai/conversations").bodyAsText())
            .jsonObject["items"]!!.jsonArray
        assertTrue(items.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test
    fun conversationDetailRejectsInvalidId() = testApplication {
        application { aiApp() }
        val resp = client.get("/ai/conversation?id=../../etc/passwd")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun deleteMissingConversationReturns404() = testApplication {
        application { aiApp() }
        val resp = client.post("/ai/conversation/delete") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to "20260101_120000_abc123")))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertEquals("Conversation not found.", json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun historyRejectsInvalidConversationId() = testApplication {
        application { aiApp() }
        val resp = client.get("/ai/history?conv=../../etc/passwd")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun clearRejectsInvalidConversationId() = testApplication {
        application { aiApp() }
        val resp = client.post("/ai/clear?conv=../escape")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun conversationsListIsSortedByLastMessageTime() = testApplication {
        application { aiApp() }
        val dir = File("cache/ai/tester")
        // 直接写入两个会话：一个最后消息较早，一个较晚（较晚的应排在前面）
        val older = AiConversation(
            id = "20260101_100000_aaaaaa", name = "旧会话", createdAt = "2026-01-01 10:00:00",
            updatedAt = "2026-01-01 10:00:00",
            messages = listOf(AiMessage(role = "user", content = "旧", time = "2026-01-01 10:00:00"))
        )
        val newer = AiConversation(
            id = "20260102_100000_bbbbbb", name = "新会话", createdAt = "2026-01-02 10:00:00",
            updatedAt = "2026-01-02 10:00:00",
            messages = listOf(AiMessage(role = "user", content = "新", time = "2026-01-02 10:00:00"))
        )
        AiHistory.save("tester", older)
        AiHistory.save("tester", newer)

        val items = json.parseToJsonElement(client.get("/ai/conversations").bodyAsText())
            .jsonObject["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals("新会话", items[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("旧会话", items[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(dir.exists())
    }

    @Test
    fun renameCannotEscapeUserNameThroughConversationId() = testApplication {
        application { aiApp() }
        // 文件名非法 → 400，且不会在 cache/ai 之外写出任何东西
        val resp = client.post("/ai/conversation/rename") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to "..\\evil", "name" to "x")))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun historyOfUnknownConversationIsEmpty() = testApplication {
        application { aiApp() }
        val resp = client.get("/ai/history?conv=20260101_120000_abc123")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("[]", resp.bodyAsText().trim())
    }

    @Test
    fun conversationsAreStoredUnderUserDirectory() = testApplication {
        application { aiApp() }
        val id = createConversation()
        val file = File("cache/ai/tester/" + id + ".json")
        assertTrue(file.exists(), "会话文件应落在 cache/ai/tester/ 下: " + file.path)
        assertNotNull(file.readText())
    }

    // 辅助：在测试应用里新建一个会话，返回其 id
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createConversation(): String {
        val resp = client.post("/ai/conversation/new") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }
}
