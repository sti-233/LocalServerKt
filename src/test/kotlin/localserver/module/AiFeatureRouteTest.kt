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
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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
 * 三个新功能的端点测试：回溯（rewind）、匿名会话、分享链接。
 *
 * 和 AiConversationRouteTest 一样用 Ktor testApplication，请求来源 IP 固定为 localhost，
 * 白名单里登记为 tester，因此存档目录是 cache/ai/tester/。
 */
class AiFeatureRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun writeUserList() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
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
        install(SSE)
        install(Authentication) {
            form("auth") { skipWhen { true } }
        }
        routing { aiRoute() }
    }

    /* ==================== 回溯 ==================== */

    @Test
    fun rewindTruncatesEverythingAfterThatUserMessage() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc001"
        AiHistory.save("tester", conversation(convId))
        // 服务端内存里可能还留着上一个测试的会话，先清掉，强制走"从存档加载"
        client.post("/ai/clear")

        val resp = client.post("/ai/conversation/rewind") {
            contentType(ContentType.Application.Json)
            setBody(rewindBody(convId, 1))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        // ord=1 是第二条用户消息：返回它的原文，并把它及之后全部删掉
        assertEquals("第二个问题", obj["removed"]!!.jsonPrimitive.content)
        assertEquals(3, obj["count"]!!.jsonPrimitive.int)

        // 落盘的存档也应同步截断
        val stored = AiHistory.load("tester", convId)
        assertNotNull(stored)
        assertEquals(3, stored.messages.size)
        assertEquals("第一个问题", stored.messages[0].content)
    }

    @Test
    fun rewindOrdZeroClearsConversation() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc002"
        AiHistory.save("tester", conversation(convId))
        client.post("/ai/clear")

        val resp = client.post("/ai/conversation/rewind") {
            contentType(ContentType.Application.Json)
            setBody(rewindBody(convId, 0))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("第一个问题", obj["removed"]!!.jsonPrimitive.content)
        assertEquals(0, obj["count"]!!.jsonPrimitive.int)
        assertEquals(0, AiHistory.load("tester", convId)!!.messages.size)
    }

    @Test
    fun rewindRejectsOutOfRangeOrdinal() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc003"
        AiHistory.save("tester", conversation(convId))
        client.post("/ai/clear")

        val resp = client.post("/ai/conversation/rewind") {
            contentType(ContentType.Application.Json)
            setBody(rewindBody(convId, 9))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        // 没打到的那次不该改动存档
        assertEquals(6, AiHistory.load("tester", convId)!!.messages.size)
    }

    @Test
    fun rewindRejectsInvalidInput() = testApplication {
        application { aiApp() }
        val badId = client.post("/ai/conversation/rewind") {
            contentType(ContentType.Application.Json)
            setBody(rewindBody("../evil", 0))
        }
        assertEquals(HttpStatusCode.BadRequest, badId.status)

        val convId = "20260101_100000_abc004"
        AiHistory.save("tester", conversation(convId))
        val badOrd = client.post("/ai/conversation/rewind") {
            contentType(ContentType.Application.Json)
            setBody(rewindBody(convId, -1))
        }
        assertEquals(HttpStatusCode.BadRequest, badOrd.status)
    }

    /* ==================== 匿名会话 ==================== */

    @Test
    fun anonymousConversationIsNotPersisted() = testApplication {
        application { aiApp() }
        client.post("/ai/clear")

        val resp = client.post("/ai/conversation/anonymous") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val id = obj["id"]!!.jsonPrimitive.content
        assertEquals(true, obj["anonymous"]!!.jsonPrimitive.boolean)
        assertTrue(AiHistory.isValidId(id))

        // 目录里不应出现它的文件
        assertFalse(File("cache/ai/tester/$id.json").exists(), "匿名会话不能落盘")

        // 列表里应能看到，并带 anonymous 标记
        val items = json.parseToJsonElement(client.get("/ai/conversations").bodyAsText())
            .jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(true, items[0].jsonObject["anonymous"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun endingAnonymousConversationDropsIt() = testApplication {
        application { aiApp() }
        client.post("/ai/clear")
        val id = createAnonymous()

        val end = client.post("/ai/conversation/anonymous/end") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id)))
        }
        assertEquals(HttpStatusCode.OK, end.status)
        assertEquals(true, json.parseToJsonElement(end.bodyAsText())
            .jsonObject["ended"]!!.jsonPrimitive.boolean)

        val items = json.parseToJsonElement(client.get("/ai/conversations").bodyAsText())
            .jsonObject["items"]!!.jsonArray
        assertTrue(items.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test
    fun endingNonAnonymousConversationIsIgnored() = testApplication {
        application { aiApp() }
        client.post("/ai/clear")
        val resp = client.post("/ai/conversation/new") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val id = json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val end = client.post("/ai/conversation/anonymous/end") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id)))
        }
        assertEquals(false, json.parseToJsonElement(end.bodyAsText())
            .jsonObject["ended"]!!.jsonPrimitive.boolean)
        // 普通会话不受影响，文件还在
        assertTrue(File("cache/ai/tester/$id.json").exists())
    }

    @Test
    fun anonymousConversationCannotBeShared() = testApplication {
        application { aiApp() }
        client.post("/ai/clear")
        val id = createAnonymous()
        val resp = client.post("/ai/share") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to id)))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    /* ==================== 分享链接 ==================== */

    @Test
    fun shareLinkIsReadonlyAndRevocable() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc101"
        AiHistory.save("tester", conversation(convId))
        client.post("/ai/clear")

        val shareResp = client.post("/ai/share") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to convId)))
        }
        assertEquals(HttpStatusCode.OK, shareResp.status)
        val shareObj = json.parseToJsonElement(shareResp.bodyAsText()).jsonObject
        val token = shareObj["token"]!!.jsonPrimitive.content
        assertTrue(AiHistory.isValidToken(token))
        assertTrue(shareObj["url"]!!.jsonPrimitive.content.endsWith("/s/" + token))

        // 分享页读得到内容，且标记只读
        val info = client.get("/ai/share/info?t=" + token)
        assertEquals(HttpStatusCode.OK, info.status)
        val infoObj = json.parseToJsonElement(info.bodyAsText()).jsonObject
        assertEquals(true, infoObj["readonly"]!!.jsonPrimitive.boolean)
        assertEquals(6, infoObj["messages"]!!.jsonArray.size)
        assertEquals("第一个问题", infoObj["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)

        // 撤销后即 404
        val revoke = client.post("/ai/share/revoke") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to convId)))
        }
        assertEquals(HttpStatusCode.OK, revoke.status)
        assertEquals(true, json.parseToJsonElement(revoke.bodyAsText())
            .jsonObject["revoked"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, client.get("/ai/share/info?t=" + token).status)
    }

    @Test
    fun unknownOrInvalidTokenIs404() = testApplication {
        application { aiApp() }
        assertEquals(HttpStatusCode.NotFound, client.get("/ai/share/info?t=deadbeef").status)
        assertEquals(HttpStatusCode.NotFound,
            client.get("/ai/share/info?t=" + "f".repeat(24)).status)
    }

    @Test
    fun deletingConversationRevokesItsShare() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc102"
        AiHistory.save("tester", conversation(convId))
        client.post("/ai/clear")
        val shareResp = client.post("/ai/share") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to convId)))
        }
        val token = json.parseToJsonElement(shareResp.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        client.post("/ai/conversation/delete") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to convId)))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/ai/share/info?t=" + token).status,
            "会话删除后它的分享链接也应失效")
    }

    @Test
    fun cloneSharedConversationCopiesMessagesToOwnAccount() = testApplication {
        application { aiApp() }
        val convId = "20260101_100000_abc103"
        AiHistory.save("tester", conversation(convId))
        client.post("/ai/clear")
        val shareResp = client.post("/ai/share") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("id" to convId)))
        }
        val token = json.parseToJsonElement(shareResp.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        client.post("/ai/clear") // 模拟"另一个人"打开：内存里的当前会话清空

        val cloneResp = client.post("/ai/share/clone") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(mapOf("t" to token)))
        }
        assertEquals(HttpStatusCode.OK, cloneResp.status)
        val cloneObj = json.parseToJsonElement(cloneResp.bodyAsText()).jsonObject
        val newId = cloneObj["id"]!!.jsonPrimitive.content
        assertTrue(newId != convId)
        assertTrue(cloneObj["name"]!!.jsonPrimitive.content.startsWith("副本"))

        val saved = AiHistory.load("tester", newId)
        assertNotNull(saved)
        assertEquals(6, saved.messages.size)
        // 原会话不受影响
        assertEquals(6, AiHistory.load("tester", convId)!!.messages.size)
    }

    /* ==================== 辅助 ==================== */

    /** 回溯请求体（id 与 ord 类型不同，必须用 buildJsonObject，混类型 map 会要求 Any 的序列化器） */
    private fun rewindBody(id: String, ord: Int): String = buildJsonObject {
        put("id", id)
        put("ord", ord)
    }.toString()

    /** 6 条消息、含 2 条用户消息的示例会话 */
    private fun conversation(id: String): AiConversation {
        val msgs = listOf(
            AiMessage(role = "user", content = "第一个问题", time = "2026-01-01 10:00:00"),
            AiMessage(role = "assistant", reasoning = "想一想", time = "2026-01-01 10:00:01"),
            AiMessage(role = "assistant", content = "第一个回答", time = "2026-01-01 10:00:02"),
            AiMessage(role = "user", content = "第二个问题", time = "2026-01-01 10:01:00"),
            AiMessage(role = "assistant", reasoning = "再想想", time = "2026-01-01 10:01:01"),
            AiMessage(role = "assistant", content = "第二个回答", time = "2026-01-01 10:01:02")
        )
        return AiConversation(
            id = id,
            name = "示例会话",
            createdAt = "2026-01-01 10:00:00",
            updatedAt = "2026-01-01 10:01:02",
            messages = msgs
        )
    }

    private suspend fun ApplicationTestBuilder.createAnonymous(): String {
        val resp = client.post("/ai/conversation/anonymous") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }
}
