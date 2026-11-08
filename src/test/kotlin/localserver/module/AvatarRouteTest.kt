package localserver.module

import localserver.module.Chat.chatRoute
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 头像端点与存储层的测试（/avatars、/setAvatar、/deleteAvatar、[AvatarStore]）。
 *
 * 关键设计约束（也是这里要钉住的东西）：
 * - 头像**只存引用**，不复制图片字节 —— 换头像不增加磁盘占用；
 * - 只能选自己 `cache/picture/<用户名>/` 里已有的图，别人的图连名字都不接受；
 * - 记录里的真实用户名与请求方不一致时读不出来（净化可能把两个名字压成同一个文件名）；
 * - 图被删掉后 get() 返回 null，前端回落默认首字头像，不会裂图；
 * - 改名迁移跟着用户走，目标已有头像时目标优先。
 */
class AvatarRouteTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val pictureDir = File(PictureStore.DIR)
    private val avatarDir = File(AvatarStore.DIR)
    private val testerDir = File(pictureDir, "tester")
    private val aliceDir = File(pictureDir, "alice")

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private val pngName: String get() = sha256Hex(pngBytes) + ".png"

    private fun writeUserList(vararg users: User) {
        File("userList.json").writeText(Util.prettyJson.encodeToString(users.toList()))
    }

    @BeforeTest
    fun setUp() {
        writeUserList(User(ip = "localhost", name = "tester"))
        pictureDir.deleteRecursively()
        avatarDir.deleteRecursively()
        // 两个用户的图片目录各放一张图（内容不同，文件名不同）
        testerDir.mkdirs()
        File(testerDir, pngName).writeBytes(pngBytes)
        aliceDir.mkdirs()
        File(aliceDir, sha256Hex(pngBytes + byteArrayOf(1)) + ".png").writeBytes(pngBytes + byteArrayOf(1))
    }

    @AfterTest
    fun tearDown() {
        pictureDir.deleteRecursively()
        avatarDir.deleteRecursively()
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

    // ===== 存储层 =====

    @Test
    fun `avatar is stored as a reference without copying bytes`() {
        val before = testerDir.listFiles()!!.size
        val ref = AvatarStore.set("tester", pngName)
        assertEquals("/img/$pngName", ref)
        // 关键：没有产生任何新图片文件
        assertEquals(before, testerDir.listFiles()!!.size)
        // 记录本身是 JSON，且只写了名字
        val rec = File(AvatarStore.DIR, "tester.json")
        assertTrue(rec.isFile, "记录文件应存在")
        val obj = json.parseToJsonElement(rec.readText()).jsonObject
        assertEquals("tester", obj["user"]?.jsonPrimitive?.content)
        assertEquals(pngName, obj["name"]?.jsonPrimitive?.content)
        assertNotNull(obj["updatedAt"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `set rejects images outside the caller directory`() {
        val alien = aliceDir.listFiles()!!.first().name
        assertNull(AvatarStore.set("tester", alien), "别人的图不能当自己的头像")
        assertNull(AvatarStore.get("tester"))
    }

    @Test
    fun `gif is not allowed as an avatar`() {
        // 同一份内容换个扩展名：名字完全合法，只是类型被头像拒收
        val gifName = sha256Hex(pngBytes) + ".gif"
        File(testerDir, gifName).writeBytes(pngBytes)
        assertFalse(AvatarStore.isAllowedName(gifName), "gif 不能当头像")
        assertNull(AvatarStore.set("tester", gifName), "set 也不该接受 gif")
        assertNull(AvatarStore.get("tester"))
        // 大小写不敏感：.GIF 一样拒
        assertFalse(AvatarStore.isAllowedName(sha256Hex(pngBytes) + ".GIF"))
        // png/jpeg 照旧可用
        assertTrue(AvatarStore.isAllowedName(pngName))
        assertTrue(AvatarStore.isAllowedName(sha256Hex(pngBytes) + ".jpeg"))
    }

    @Test
    fun `an old gif record reads as no avatar`() {
        // 老记录里可能已经存了 gif（禁令之前设的）：读时按"没设置"处理，前端回落首字头像
        val gifName = sha256Hex(pngBytes) + ".gif"
        File(testerDir, gifName).writeBytes(pngBytes)
        avatarDir.mkdirs()
        File(avatarDir, "tester.json").writeText(
            """{"user":"tester","name":"$gifName","updatedAt":1}"""
        )
        assertNull(AvatarStore.get("tester"))
        assertTrue(AvatarStore.all().isEmpty(), "all() 也要跳过 gif 记录")
    }

    @Test
    fun `set rejects names that are not content addressed`() {
        assertNull(AvatarStore.set("tester", "../../etc/passwd"))
        assertNull(AvatarStore.set("tester", "notahash.png"))
        assertNull(AvatarStore.set("tester", ""))
    }

    @Test
    fun `get falls back to null when the picture was deleted`() {
        assertEquals("/img/$pngName", AvatarStore.set("tester", pngName))
        assertEquals("/img/$pngName", AvatarStore.get("tester"))
        File(testerDir, pngName).delete()
        assertNull(AvatarStore.get("tester"), "图没了就该回落默认，而不是返回一个 404 链接")
        // all() 也要跳过失效记录
        assertTrue(AvatarStore.all().isEmpty())
    }

    @Test
    fun `record whose user does not match is ignored`() {
        // 故意写一条 user 对不上的记录：目录名被净化成同一个文件时不能张冠李戴
        avatarDir.mkdirs()
        File(avatarDir, "tester.json").writeText(
            """{"user":"someone-else","name":"$pngName","updatedAt":1}"""
        )
        assertNull(AvatarStore.get("tester"))
    }

    @Test
    fun `corrupted record is treated as no avatar`() {
        avatarDir.mkdirs()
        File(avatarDir, "tester.json").writeText("{ this is not json")
        assertNull(AvatarStore.get("tester"))
        assertTrue(AvatarStore.all().isEmpty(), "坏记录不能让 all() 抛异常")
    }

    @Test
    fun `clear removes the record and is idempotent`() {
        AvatarStore.set("tester", pngName)
        assertTrue(AvatarStore.clear("tester"))
        assertNull(AvatarStore.get("tester"))
        assertTrue(AvatarStore.clear("tester"), "本来就没有也算成功")
    }

    @Test
    fun `all maps real user names to image refs`() {
        AvatarStore.set("tester", pngName)
        val alicePic = aliceDir.listFiles()!!.first().name
        AvatarStore.set("alice", alicePic)
        val all = AvatarStore.all()
        // 键是真实用户名（前端按 msg.name 配对），不是净化后的目录名
        assertEquals("/img/$pngName", all["tester"])
        assertEquals("/img/$alicePic", all["alice"])
    }

    @Test
    fun `rename follows the user and target wins on conflict`() {
        AvatarStore.set("tester", pngName)
        val stats = AvatarStore.renameUser("tester", "tester2")
        assertEquals(1, stats["avatars"])
        assertNull(AvatarStore.get("tester"))
        assertEquals("/img/$pngName", AvatarStore.get("tester2"))

        // 目标名已有头像时：目标优先，旧记录丢弃
        val alicePic = aliceDir.listFiles()!!.first().name
        AvatarStore.set("alice", alicePic)
        AvatarStore.set("tester2", pngName)
        AvatarStore.renameUser("tester2", "alice")
        assertEquals("/img/$alicePic", AvatarStore.get("alice"), "新名字当前在用的那份为准")
    }

    @Test
    fun `rename of a user without avatar is a no-op`() {
        val stats = AvatarStore.renameUser("nobody", "somebody")
        assertEquals(0, stats["avatars"])
        assertEquals(0, stats["failed"])
    }

    // ===== 路由 =====

    @Test
    fun `setAvatar endpoint stores the reference`() = withApp { client ->
        val resp = client.post("/setAvatar") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$pngName"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(true, body["ok"]?.jsonPrimitive?.boolean)
        assertEquals("/img/$pngName", body["ref"]?.jsonPrimitive?.content)
        assertEquals("/img/$pngName", AvatarStore.get("tester"))
    }

    @Test
    fun `setAvatar rejects a picture from another user`() = withApp { client ->
        val alien = aliceDir.listFiles()!!.first().name
        val resp = client.post("/setAvatar") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$alien"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(false, body["ok"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `setAvatar rejects an illegal name`() = withApp { client ->
        val resp = client.post("/setAvatar") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"../../secret.png"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `setAvatar answers gif with a clear reason`() = withApp { client ->
        val gifName = sha256Hex(pngBytes) + ".gif"
        File(testerDir, gifName).writeBytes(pngBytes)
        val resp = client.post("/setAvatar") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$gifName"}""")
        }
        // 名字合法、文件也在自己库里，只是类型被拒 —— 必须 400 而不是 404，
        // 否则前端会提示"这张图不在你的图片库里"，让人以为文件没了
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("GIF"), "错误信息要点明是 GIF 的问题")
        assertNull(AvatarStore.get("tester"))
    }

    @Test
    fun `avatars endpoint returns me and the full table`() = withApp { client ->
        AvatarStore.set("tester", pngName)
        val alicePic = aliceDir.listFiles()!!.first().name
        AvatarStore.set("alice", alicePic)

        val resp = client.get("/avatars")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("tester", body["me"]?.jsonPrimitive?.content)
        val items = body["items"]!!.jsonObject
        assertEquals("/img/$pngName", items["tester"]?.jsonPrimitive?.content)
        assertEquals("/img/$alicePic", items["alice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `avatars endpoint is empty rather than failing when nothing is set`() = withApp { client ->
        val resp = client.get("/avatars")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["items"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `deleteAvatar clears the record`() = withApp { client ->
        AvatarStore.set("tester", pngName)
        val resp = client.post("/deleteAvatar")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(true, body["ok"]?.jsonPrimitive?.boolean)
        assertNull(AvatarStore.get("tester"))
    }

    @Test
    fun `deleting the picture clears the avatar too`() = withApp { client ->
        AvatarStore.set("tester", pngName)
        val resp = client.post("/deletePicture") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$pngName"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        // 图删了，头像记录还在，但 get() 已经回 null —— 前端看到的就是默认首字头像
        assertNull(AvatarStore.get("tester"))
        // 而 /avatars 也不再列出它（否则前端会拿到一个 404 的 img src）
        val body = json.parseToJsonElement(client.get("/avatars").bodyAsText()).jsonObject
        assertTrue(body["items"]!!.jsonObject.isEmpty())
    }
}
