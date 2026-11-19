package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.Message
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.websocket.Frame
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
 * 图片举报的端到端行为：`report` 帧的处理、封禁后撤消息/删图/禁重存。
 *
 * 身份上的限制决定了这里怎么测：
 * - 测试宿主的 `remoteAddress` 恒为 `localhost`，所有 WS 连接都是同一个用户（tester），
 *   所以「3 个不同用户凑票」没法在这里走 WS（那部分由 [ReportStoreTest] 直接测计数）；
 * - `Control.allowed("localhost")` 为 false，WS 里的举报者永远不是 control；
 * - 于是「已封禁」这条分支用 `ReportStore.report(..., control = true)` 预置，
 *   再走 WS 举报一次，验证的是**封禁之后那半段**（删图、清存档、广播撤回）确实跑通。
 */
class ChatReportTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val pictureDir = File(PictureStore.DIR)
    private val testerDir = File(pictureDir, "tester")

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pngName(): String = sha256Hex(pngBytes) + ".png"

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        File("message").deleteRecursively()
        pictureDir.deleteRecursively()
        File(ReportStore.DIR).deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        File("message").deleteRecursively()
        File("userList.json").delete()
        pictureDir.deleteRecursively()
        File(ReportStore.DIR).deleteRecursively()
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

    private fun payload(frame: JsonObject): Frame.Text = Frame.Text(json.encodeToString(frame))

    private fun reportFrame(name: String, time: String) = buildJsonObject {
        put("type", "report")
        put("name", name)
        put("time", time)
        put("text", "unused")
        put("sendTo", "")
    }

    /** 直接往今天的群聊归档里塞一条别人发的图片消息（绕过 WS，便于构造"别人的图"） */
    private fun seedImageMessage(sender: String, time: String, imageName: String) {
        Util.addHistory(Message(sender, time, "ImageRef:/img/$imageName"), null)
    }

    private fun archiveText(): String = Util.getHistory(null)

    @Test
    fun `report only shows a receipt while the tally is under the threshold`() = withApp { client ->
        val name = pngName()
        seedImageMessage("other", "2026-11-19 10:00:00", name)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(reportFrame("other", "2026-11-19 10:00:00")))
            val obj = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("reported", obj["type"]?.jsonPrimitive?.content, "第一票只该收到回执")
            assertFalse(ReportStore.isBanned(name), "一个人举报不足以封禁")
            // 图与消息都还在
            assertTrue(archiveText().contains("ImageRef:/img/$name"), "未达标不该撤消息")
        }
        wsClient.close()
    }

    @Test
    fun `reporting your own image is refused`() = withApp { client ->
        val name = pngName()
        seedImageMessage("tester", "2026-11-19 10:00:00", name)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(reportFrame("tester", "2026-11-19 10:00:00")))
            val obj = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("err", obj["type"]?.jsonPrimitive?.content, "举报自己的图应被拒")
            assertFalse(ReportStore.isBanned(name))
        }
        wsClient.close()
    }

    @Test
    fun `reporting a text message is refused`() = withApp { client ->
        Util.addHistory(Message("other", "2026-11-19 10:05:00", "just words"), null)
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(reportFrame("other", "2026-11-19 10:05:00")))
            val obj = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("err", obj["type"]?.jsonPrimitive?.content, "本功能只管图片")
        }
        wsClient.close()
    }

    @Test
    fun `a banned image is purged from the archive and a del frame is broadcast`() = withApp { client ->
        val name = pngName()
        testerDir.mkdirs()
        File(testerDir, name).writeBytes(pngBytes)
        seedImageMessage("other", "2026-11-19 10:10:00", name)

        // 预置"已达阈值"（control 一票即封），随后这次举报走的是封禁之后那半段
        assertTrue(ReportStore.report(name, "admin", control = true).banned)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(payload(reportFrame("other", "2026-11-19 10:10:00")))
            val obj = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("del", obj["type"]?.jsonPrimitive?.content, "封禁时应广播撤回")
            assertEquals("other", obj["name"]?.jsonPrimitive?.content)
            assertEquals("2026-11-19 10:10:00", obj["time"]?.jsonPrimitive?.content)
            assertEquals("", obj["sendTo"]?.jsonPrimitive?.content, "群聊归档的 sendTo 是空串")
        }
        wsClient.close()

        // 存档里那条消息没了
        assertFalse(archiveText().contains("ImageRef:/img/$name"), "被举报的图片消息应从存档里抹掉")
        // 磁盘上所有副本都没了
        assertFalse(File(testerDir, name).exists(), "服务端的图片文件应被删除")
    }

    @Test
    fun `a banned image cannot be served saved or listed`() = withApp { client ->
        val name = pngName()
        testerDir.mkdirs()
        File(testerDir, name).writeBytes(pngBytes)
        ReportStore.report(name, "admin", control = true)

        // /img 不再提供（即便文件还在）
        assertEquals(HttpStatusCode.NotFound, client.get("/img/$name").status)

        // /savePicture 拒绝：用户点"保存"也存不回来
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
        val saved = client.post("/savePicture") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", dataUrl) }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, saved.status)
        assertTrue(saved.bodyAsText().contains("已被举报"), "提示要说清是被撤下了")

        // 从缓存选取的列表里也不再出现
        val list = json.parseToJsonElement(client.get("/pictures").bodyAsText()).jsonObject
        assertEquals(0, list["total"]?.jsonPrimitive?.int, "封禁的图不列进自己的图片库")
    }

    @Test
    fun `sending an already banned image is refused`() = withApp { client ->
        val name = pngName()
        testerDir.mkdirs()
        File(testerDir, name).writeBytes(pngBytes)
        ReportStore.report(name, "admin", control = true)

        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            // 走 ImageRef（从缓存选取）：文件可能还在浏览器缓存里，但服务端不许转发
            send(payload(buildJsonObject {
                put("type", "send")
                put("name", "unused")
                put("time", "unused")
                put("text", "ImageRef:/img/$name")
                put("sendTo", "")
            }))
            val obj = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("err", obj["type"]?.jsonPrimitive?.content)
            assertTrue(
                (obj["text"]?.jsonPrimitive?.content ?: "").contains("已被举报"),
                "要说清是被撤下了，而不是笼统的无效图片"
            )

            // 走 ImageB64（重新上传同一张）：哈希命中黑名单，同样发不出去
            val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
            send(payload(buildJsonObject {
                put("type", "send")
                put("name", "unused")
                put("time", "unused")
                put("text", "ImageB64:$dataUrl")
                put("sendTo", "")
            }))
            val again = json.parseToJsonElement(nextBusinessFrame()).jsonObject
            assertEquals("err", again["type"]?.jsonPrimitive?.content)
            assertTrue((again["text"]?.jsonPrimitive?.content ?: "").contains("已被举报"))
        }
        wsClient.close()

        assertFalse(archiveText().contains("ImageRef:/img/$name"), "被拒的消息不该进存档")
    }
}
