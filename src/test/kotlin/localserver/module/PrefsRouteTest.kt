package localserver.module

import localserver.module.Prefs.prefsRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /api/prefs 的集成测试。
 *
 * Ktor testApplication 里 call.request.local.remoteAddress 是 "localhost"，
 * 所以 userList.json 按这个 ip 登记，落盘文件名是净化后的用户名 tester.json。
 */
class PrefsRouteTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val root = File("cache/prefs")

    private fun writeUserList() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
    }

    @BeforeTest
    fun setUp() {
        writeUserList()
        root.deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
            }
            routing { prefsRoute() }
        }
        block(client)
    }

    @Test
    fun `empty prefs returns empty object`() = withApp { client ->
        val resp = client.get("/api/prefs")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("tester", body["user"]?.jsonPrimitive?.content)
        assertTrue(body["prefs"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `write then read roundtrip`() = withApp { client ->
        val payload = buildJsonObject {
            put("page", "music")
            put("values", buildJsonObject { put("settings", "{\"quality\":\"320k\"}") })
        }
        val write = client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        assertEquals(HttpStatusCode.OK, write.status)

        val read = client.get("/api/prefs?page=music")
        val prefs = json.parseToJsonElement(read.bodyAsText()).jsonObject["prefs"]!!.jsonObject
        assertEquals("{\"quality\":\"320k\"}", prefs["settings"]?.jsonPrimitive?.content)

        // 落盘文件确实生成，且文件名按用户名净化
        val file = File(root, "tester.json")
        assertTrue(file.exists(), "cache/prefs/tester.json should exist")
        assertTrue(file.readText().contains("320k"))
    }

    @Test
    fun `merge keeps other keys and other pages`() = withApp { client ->
        suspend fun write(page: String, key: String, value: String) {
            val body = buildJsonObject {
                put("page", page)
                put("values", buildJsonObject { put(key, value) })
            }
            client.post("/api/prefs") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        write("chat", "lastRead_a", "t1")
        write("chat", "lastRead_b", "t2")
        write("music", "settings", "s1")

        val all = json.parseToJsonElement(client.get("/api/prefs").bodyAsText()).jsonObject["prefs"]!!.jsonObject
        assertEquals(2, all["chat"]!!.jsonObject.size, "两次写入应合并到同一页面")
        assertEquals("t1", all["chat"]!!.jsonObject["lastRead_a"]?.jsonPrimitive?.content)
        assertEquals("s1", all["music"]!!.jsonObject["settings"]?.jsonPrimitive?.content)
    }

    @Test
    fun `remove deletes keys and drops empty page`() = withApp { client ->
        suspend fun write(page: String, key: String, value: String) {
            client.post("/api/prefs") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("page", page)
                    put("values", buildJsonObject { put(key, value) })
                }.toString())
            }
        }
        write("file", "showHidden", "1")

        val del = client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("page", "file")
                put("remove", buildJsonArray { add("showHidden") })
            }.toString())
        }
        assertEquals(HttpStatusCode.OK, del.status)
        val all = json.parseToJsonElement(client.get("/api/prefs").bodyAsText()).jsonObject["prefs"]!!.jsonObject
        assertNull(all["file"], "页面被清空后不应留下空壳")
    }

    @Test
    fun `null value behaves like remove`() = withApp { client ->
        client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("page", "chat")
                put("values", buildJsonObject {
                    put("k1", "v1")
                    put("k2", "v2")
                })
            }.toString())
        }
        client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("page", "chat")
                put("values", buildJsonObject { put("k1", JsonNull) })
            }.toString())
        }
        val prefs = json.parseToJsonElement(client.get("/api/prefs?page=chat").bodyAsText())
            .jsonObject["prefs"]!!.jsonObject
        assertNull(prefs["k1"])
        assertEquals("v2", prefs["k2"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rejects invalid page and empty payload`() = withApp { client ->
        val bad = client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"../etc","values":{"a":"b"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val empty = client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"chat","values":{}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        val notJson = client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }
        assertEquals(HttpStatusCode.BadRequest, notJson.status)
    }

    @Test
    fun `non-ascii keys survive`() = withApp { client ->
        val key = "lastRead_张三"
        client.post("/api/prefs") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("page", "chat")
                put("values", buildJsonObject { put(key, "{\"p:李四\":\"2026-11-01 05:00:00\"}") })
            }.toString())
        }
        val prefs = json.parseToJsonElement(client.get("/api/prefs?page=chat").bodyAsText())
            .jsonObject["prefs"]!!.jsonObject
        assertTrue(prefs.containsKey(key), "中文键名必须原样保留")
    }
}
