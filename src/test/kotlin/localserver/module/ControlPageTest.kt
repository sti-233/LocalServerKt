package localserver.module

import localserver.module.Control.controlRoute
import localserver.types.User
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
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Control 面板的端点：`/control` 跳转、`/control/status` 的状态汇总、`/control/unban` 解封。
 *
 * 这层的价值在于「响应能被序列化」：`/control/status` 手搓 JsonObject，里面混着
 * `Json.encodeToJsonElement(...)` 与 `JsonPrimitive(...)`，一层写错就是运行时 500 ——
 * 单测 ReportStore 发现不了，必须真打一次路由。`/control/unban` 同理，回的是
 * `Json.encodeToString(mapOf(...))`，`Boolean` 与 `String` 混在一个 Map 里。
 */
class ControlPageTest {

    private val reportDir = File(ReportStore.DIR)

    @BeforeTest
    fun setUp() {
        reportDir.deleteRecursively()
        Util.setUserList(
            mutableListOf(User("localhost", "tester"), User("10.0.0.2", "bob"))
        )
    }

    @AfterTest
    fun tearDown() {
        reportDir.deleteRecursively()
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            routing { controlRoute() }
        }
        // followRedirects 必须关掉：默认客户端会自动跟 302，而测试里没有注册
        // staticResources，跳到 /resources/control.html 只会拿到 404
        block(createClient { followRedirects = false })
    }

    @Test
    fun `the control page redirects to its html`() = withApp { client ->
        val resp = client.get("/control")

        assertEquals(HttpStatusCode.Found, resp.status)
        assertEquals("/resources/control.html", resp.headers["Location"])
    }

    @Test
    fun `status reports state users video and timetable`() = withApp { client ->
        val resp = client.get("/control/status")
        assertEquals(HttpStatusCode.OK, resp.status)
        // 这些值随时在变，缓存住面板就会显示上一分钟的状态
        assertEquals("no-store", resp.headers["Cache-Control"])

        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject

        assertTrue(json["state"]!!.jsonPrimitive.boolean, "默认是启动状态")
        // video 三项：open = 课表判定，manual = 有没有被人为覆盖，available = 普通用户实际能不能看。
        // 只给一个布尔值时，"关着"是正课还是被手动关的，面板上分不清。
        val video = json["video"]!!.jsonObject
        assertTrue(video.containsKey("open") && video.containsKey("manual") && video.containsKey("available"))
        assertTrue(
            video["manual"]!!.jsonPrimitive.content in setOf("auto", "open", "closed"),
            "unexpected manual state: ${video["manual"]}"
        )
        // 课表三项必须都在：前端据此显示「正课 · 第 N 节」还是「非正课」
        val tt = json["timetable"]!!.jsonObject
        assertTrue(tt.containsKey("inClass") && tt.containsKey("period") && tt.containsKey("week"))
        assertTrue(json["online"]!!.jsonPrimitive.int >= 0)

        // users 要能序列化出完整名单（含 IP 与名字），面板靠它渲染改名/移除按钮
        val users = json["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, users.size)
        assertEquals("tester", users.first { it["ip"]!!.jsonPrimitive.content == "localhost" }["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `status lists banned images and pending reports`() = withApp { client ->
        val bannedName = "a".repeat(64) + ".png"
        ReportStore.report(bannedName, "tester", control = true)
        val pendingName = "b".repeat(64) + ".jpg"
        ReportStore.report(pendingName, "bob", control = false)

        val json = Json.parseToJsonElement(client.get("/control/status").bodyAsText()).jsonObject

        assertEquals(listOf(bannedName), json["banned"]!!.jsonArray.map { it.jsonPrimitive.content })
        val pending = json["pending"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, pending.size)
        assertEquals(pendingName, pending[0]["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("bob"), pending[0]["reporters"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `unban removes only the ban entry`() = withApp { client ->
        val name = "c".repeat(64) + ".png"
        ReportStore.report(name, "tester", control = true)
        assertTrue(ReportStore.isBanned(name))

        val resp = client.get("/control/unban?name=$name")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(Json.parseToJsonElement(resp.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertFalse(ReportStore.isBanned(name))
    }

    @Test
    fun `unban rejects a malformed name`() = withApp { client ->
        val resp = client.get("/control/unban?name=../secret.png")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `unban on an image that is not banned reports 404`() = withApp { client ->
        val resp = client.get("/control/unban?name=" + "d".repeat(64) + ".png")

        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertFalse(Json.parseToJsonElement(resp.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.boolean)
    }
}
