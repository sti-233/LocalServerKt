package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.*

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 图片保存与目录迁移的集成测试（/savePicture、/img/{*}、/migratePictures）。
 *
 * 测试的 workingDir 是 build/test-work（见 build.gradle.kts），
 * 所以这里操作的 cache/ 与 cache/picture/ 都在构建目录里，不碰仓库真实缓存。
 */
class ChatPictureRouteTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File("cache")
    private val pictureDir = File(Chat.PICTURE_DIR)

    // 1x1 透明 PNG，用作"合法图片"的字节来源
    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun pngDataUrl(): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeUserList() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
    }

    @BeforeTest
    fun setUp() {
        writeUserList()
        pictureDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        pictureDir.deleteRecursively()
        // 清掉本测试在 cache/ 根下造的旧版图片，但不碰 ai/、prefs/ 这些别人的目录
        cacheDir.listFiles { f -> f.isFile }?.forEach { it.delete() }
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                // 两个域都无条件放行：测试里 remoteAddress 恒为 localhost。
                // chatRoute 里同时用了 auth（/chat、/whoami）与 control（迁移端点），
                // 少装任何一个都会在路由装配阶段报"未注册的 provider"
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            // chatRoute 里挂着 WS 路由 /message，测试宿主也必须装 WebSockets 才能装配
            install(WebSockets)
            routing { chatRoute() }
        }
        block(client)
    }

    @Test
    fun `savePicture writes into cache-picture and is idempotent`() = withApp { client ->
        val expected = sha256Hex(pngBytes) + ".png"

        val resp = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", pngDataUrl()) }.toString())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(true, body["ok"]?.jsonPrimitive?.boolean)
        assertEquals("ImageRef:/img/$expected", body["ref"]?.jsonPrimitive?.content)
        assertEquals("cache/picture", body["dir"]?.jsonPrimitive?.content)
        assertEquals(false, body["existed"]?.jsonPrimitive?.boolean, "首次保存不该命中已有缓存")

        val file = File(pictureDir, expected)
        assertTrue(file.exists(), "图片应落在 cache/picture/$expected")
        assertTrue(file.readBytes().contentEquals(pngBytes), "落盘必须是原始字节")

        // 同一张图再存一次：哈希相同，命中已有文件，不产生副本
        val again = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", pngDataUrl()) }.toString())
        }
        val againBody = json.parseToJsonElement(again.bodyAsText()).jsonObject
        assertEquals(true, againBody["existed"]?.jsonPrimitive?.boolean, "重复保存应命中已有缓存")
        assertEquals(
            1,
            pictureDir.listFiles()?.size,
            "重复保存不能产生第二份文件"
        )
    }

    @Test
    fun `savePicture rejects svg and oversized payloads`() = withApp { client ->
        // svg 能在浏览器里执行脚本，不在白名单内
        val svg = "data:image/svg+xml;base64," + Base64.getEncoder()
            .encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())
        val bad = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", svg) }.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        // 缺 data 字段
        val empty = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        // 声明长度超限：不等 body 读完就回 413
        val huge = "data:image/png;base64," + "A".repeat(15 * 1024 * 1024)
        val tooBig = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", huge) }.toString())
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertFalse(pictureDir.exists() && (pictureDir.listFiles()?.isNotEmpty() == true), "非法请求不该落盘")
    }

    @Test
    fun `img endpoint serves from new dir and falls back to legacy cache root`() = withApp { client ->
        val name = sha256Hex(pngBytes) + ".png"
        pictureDir.mkdirs()
        File(pictureDir, name).writeBytes(pngBytes)

        val fromNew = client.get("/img/$name")
        assertEquals(HttpStatusCode.OK, fromNew.status)
        assertTrue(fromNew.bodyAsBytes().contentEquals(pngBytes))

        // 旧目录里的文件（还没跑迁移）也要能取到，避免存档整片裂图
        val legacy = sha256Hex("legacy".toByteArray()) + ".png"
        File(cacheDir, legacy).writeBytes(pngBytes)
        val fromLegacy = client.get("/img/$legacy")
        assertEquals(HttpStatusCode.OK, fromLegacy.status)
        assertTrue(fromLegacy.bodyAsBytes().contentEquals(pngBytes))

        // 路径校验仍然生效
        assertEquals(HttpStatusCode.NotFound, client.get("/img/notahash.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/img/${"a".repeat(64)}.png").status)
    }

    @Test
    fun `migratePictures moves legacy images and is idempotent`() = withApp { client ->
        val name = sha256Hex(pngBytes) + ".png"
        File(cacheDir, name).writeBytes(pngBytes)
        // 混入一个非图片文件，迁移必须无视它
        File(cacheDir, "logs.txt").writeText("keep me")

        val first = client.get("/migratePictures")
        assertEquals(HttpStatusCode.OK, first.status)
        val stat = json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals(1, stat["moved"]?.jsonPrimitive?.int, "旧图片应被搬进 cache/picture")

        assertTrue(File(pictureDir, name).exists(), "迁移后新目录应有该文件")
        assertFalse(File(cacheDir, name).exists(), "旧位置上不该再留副本")
        assertTrue(File(cacheDir, "logs.txt").exists(), "非图片文件不能被搬走")

        // 再跑一次：没有可搬的，统计归零
        val second = client.get("/migratePictures")
        val stat2 = json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(0, stat2["moved"]?.jsonPrimitive?.int)
        assertEquals(0, stat2["failed"]?.jsonPrimitive?.int)
    }

    @Test
    fun `migratePictures dedupes when new dir already has the file`() = withApp { client ->
        val name = sha256Hex(pngBytes) + ".png"
        pictureDir.mkdirs()
        File(pictureDir, name).writeBytes(pngBytes)
        File(cacheDir, name).writeBytes(pngBytes)

        val resp = client.get("/migratePictures")
        val stat = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(0, stat["moved"]?.jsonPrimitive?.int)
        assertEquals(1, stat["deduped"]?.jsonPrimitive?.int, "新目录已有同内容文件，旧的直接删掉")

        assertTrue(File(pictureDir, name).exists())
        assertFalse(File(cacheDir, name).exists())
    }

    @Test
    fun `pictures lists cache dir newest first and skips non images`() = withApp { client ->
        // 空目录：合法响应但列表为空，前端据此显示"缓存里还没有图片"
        val empty = client.get("/pictures")
        assertEquals(HttpStatusCode.OK, empty.status)
        val emptyBody = json.parseToJsonElement(empty.bodyAsText()).jsonObject
        assertEquals("cache/picture", emptyBody["dir"]?.jsonPrimitive?.content)
        assertEquals(0, emptyBody["total"]?.jsonPrimitive?.int)
        assertEquals(0, emptyBody["items"]?.jsonArray?.size)

        pictureDir.mkdirs()
        val older = sha256Hex("older".toByteArray()) + ".png"
        val newer = sha256Hex("newer".toByteArray()) + ".jpg"
        File(pictureDir, older).writeBytes(pngBytes)
        File(pictureDir, newer).writeBytes(pngBytes)
        File(pictureDir, older).setLastModified(1_600_000_000_000)
        File(pictureDir, newer).setLastModified(1_700_000_000_000)
        // 非图片文件名不该出现在列表里
        File(pictureDir, "notes.txt").writeText("not a picture")

        val resp = client.get("/pictures")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.int, "只统计符合 <sha256>.<ext> 的文件")
        val items = body["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size)
        assertEquals(newer, items[0]["name"]?.jsonPrimitive?.content, "按修改时间倒序")
        assertEquals(older, items[1]["name"]?.jsonPrimitive?.content)
        assertEquals("/img/$newer", items[0]["ref"]?.jsonPrimitive?.content)
        assertEquals(pngBytes.size.toLong(), items[0]["size"]?.jsonPrimitive?.long)

        // limit 生效：只要一条时按时间取最新的那条
        val limited = client.get("/pictures?limit=1")
        val limitedItems = json.parseToJsonElement(limited.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, limitedItems.size)
        assertEquals(newer, limitedItems[0].jsonObject["name"]?.jsonPrimitive?.content)
    }
}
