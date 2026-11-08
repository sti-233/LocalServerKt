package localserver.module

import localserver.module.Chat.chatRoute
import localserver.module.Control.controlRoute
import localserver.types.Message
import localserver.types.User
import localserver.utils.AiHistory
import localserver.utils.Util

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
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/resetName` 的端到端行为：改名要连带迁移各类存档，并把统计回给调用方。
 *
 * 这层的价值在于「响应能被序列化」：`migrated` 是「步骤 → 统计」的两层 Map，
 * 一开始写成 `Map<String, Any>` 编译能过、运行时 kotlinx 找不到 `Any` 的序列化器直接 500。
 * 单测只测 Util/Prefs 那几个函数是发现不了的，必须真打一次路由。
 */
class ResetNameRouteTest {

    private val msgDir = File("message")

    @BeforeTest
    fun setUp() {
        msgDir.deleteRecursively()
        msgDir.mkdirs()
        File(PictureStore.DIR).deleteRecursively()
        File(AiHistory.DEFAULT_ROOT).deleteRecursively()
        File(Prefs.ROOT).deleteRecursively()
        Util.setUserList(
            mutableListOf(User("localhost", "tester"), User("10.0.0.2", "bob"))
        )
    }

    @AfterTest
    fun tearDown() {
        listOf(msgDir, File(PictureStore.DIR), File(AiHistory.DEFAULT_ROOT), File(Prefs.ROOT))
            .forEach { it.deleteRecursively() }
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            install(WebSockets)
            routing {
                controlRoute()
                chatRoute()
            }
        }
        block(client)
    }

    @Test
    fun `renaming migrates archives and reports what it did`() = withApp { client ->
        File(msgDir, "history_bob-tester.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(Message("tester", "2026-01-01 10:00:00", "hi"))
            )
        )

        val text = client.get("/resetName?ip=localhost&username=tester2").bodyAsText()
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals("tester2", json["users"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val migrated = json["migrated"]!!.jsonObject
        assertEquals(1, migrated["history"]!!.jsonObject["historyRenamed"]!!.jsonPrimitive.int)
        assertTrue(File(msgDir, "history_bob-tester2.json").isFile)
        assertFalse(File(msgDir, "history_bob-tester.json").exists())
        // 名单本身也要落盘，否则重启后又变回旧名字
        assertEquals("tester2", Util.getUserName("localhost"))
    }

    @Test
    fun `renaming to the same name does nothing`() = withApp { client ->
        val json = Json.parseToJsonElement(
            client.get("/resetName?ip=localhost&username=tester").bodyAsText()
        ).jsonObject

        assertTrue(json["migrated"]!!.jsonObject.isEmpty(), "同名不该触发任何迁移")
    }

    @Test
    fun `an unknown ip reports an error instead of crashing`() = withApp { client ->
        val text = client.get("/resetName?ip=127.0.0.9&username=x").bodyAsText()
        assertEquals("No user was found.", text)
    }

    @Test
    fun `renaming onto a name already in use is refused`() = withApp { client ->
        File(msgDir, "history_bob-tester.json").writeText(
            Util.prettyJson.encodeToString(listOf(Message("tester", "2026-01-01 10:00:00", "mine")))
        )
        File(msgDir, "history_alice-bob.json").writeText(
            Util.prettyJson.encodeToString(listOf(Message("alice", "2026-01-01 10:00:00", "hers")))
        )

        val resp = client.get("/resetName?ip=localhost&username=bob")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // 名单和存档都不该动
        assertEquals("tester", Util.getUserName("localhost"))
        assertTrue(File(msgDir, "history_bob-tester.json").isFile)
        assertEquals(
            listOf("hers"),
            Util.historyJson.decodeFromString<List<Message>>(
                File(msgDir, "history_alice-bob.json").readText()
            ).map { it.text }
        )
    }

    @Test
    fun `renaming to a name with a hyphen is refused`() = withApp { client ->
        File(msgDir, "history_bob-tester.json").writeText(
            Util.prettyJson.encodeToString(listOf(Message("tester", "2026-01-01 10:00:00", "hi")))
        )

        val resp = client.get("/resetName?ip=localhost&username=tester-2")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // 名字没改成，存档也没动
        assertEquals("tester", Util.getUserName("localhost"))
        assertTrue(File(msgDir, "history_bob-tester.json").isFile)
    }

    @Test
    fun `adding a user with a hyphen in the name is refused`() = withApp { client ->
        val resp = client.get("/addUser?ip=10.0.0.9&username=dave-2")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertFalse(Util.getUserList().any { it.ip == "10.0.0.9" })
    }
}
