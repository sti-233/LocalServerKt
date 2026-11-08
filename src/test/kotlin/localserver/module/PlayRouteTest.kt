package localserver.module

import localserver.module.Play.playRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Play 模块的路由与 WS 协议测试。
 *
 * 这里走真的 WebSocket 连接，验证的是「协议帧形状」与「按座位裁剪」这两件事 ——
 * 规则本身由 PlayUndercoverTest / PlayWerewolfTest 覆盖。
 */
class PlayRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        Play.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        Play.resetForTest()
        File("userList.json").delete()
    }

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
            }
            install(WebSockets)
            routing { playRoute() }
        }
        // 客户端也必须装 WS 插件，否则 client.webSocket() 直接抛「插件未安装」。
        // followRedirects 必须关掉：testApplication 的默认客户端会自动跟 302，
        // 而测试里没有注册 staticResources，跳过去只会拿到 404。
        block(
            createClient {
                followRedirects = false
                install(ClientWebSockets)
            }
        )
    }

    // ===== HTTP 跳转 =====

    @Test
    fun `game pages redirect to static resources`() = withApp { client ->
        val cases = mapOf(
            "/play" to "/resources/play/index.html",
            "/play/undercover" to "/resources/play/undercover.html",
            "/play/werewolf" to "/resources/play/werewolf.html"
        )
        cases.forEach { (path, target) ->
            val resp = client.get(path)
            assertEquals(HttpStatusCode.Found, resp.status, "$path 应 302")
            assertEquals(target, resp.headers["Location"], "$path 的跳转目标")
        }
    }

    // ===== WS 协议 =====

    private suspend fun DefaultClientWebSocketSession.sendJson(obj: JsonObject) =
        send(Frame.Text(obj.toString()))

    /** 读到第一条指定 type 的帧（跳过中间其它帧）。 */
    private suspend fun DefaultClientWebSocketSession.readUntil(type: String): JsonObject {
        repeat(20) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) return@repeat
            val obj = json.parseToJsonElement(frame.readText()).jsonObject
            if (obj["type"]?.jsonPrimitive?.content == type) return obj
        }
        throw AssertionError("没有收到 type=$type 的帧")
    }

    private fun frame(type: String, vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun `create returns joined then state`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "房主"))

            val joined = readUntil("joined")
            assertEquals("undercover", joined["game"]!!.jsonPrimitive.content)
            assertEquals("房主", joined["name"]!!.jsonPrimitive.content)
            assertEquals(true, joined["isHost"]!!.jsonPrimitive.content.toBoolean())
            val room = joined["room"]!!.jsonPrimitive.content
            assertEquals(4, room.length, "房号是 4 位")

            val state = readUntil("state")["state"]!!.jsonObject
            assertEquals("waiting", state["phase"]!!.jsonPrimitive.content)
            assertEquals(1, state["players"]!!.jsonArray.size)
            assertEquals(true, state["isHost"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `join adds a second player and both see two seats`() = withApp { client ->
        // 第一条连接建房
        var roomId = ""
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            roomId = readUntil("joined")["room"]!!.jsonPrimitive.content
            readUntil("state")
        }

        // 第二条连着进来（同一 client，两个 webSocket 块顺序执行，
        // 第一个块结束时连接已关闭，但房间与座位表还在内存里）
        client.webSocket("/play/ws") {
            sendJson(frame("join", "room" to roomId, "name" to "乙"))
            val joined = readUntil("joined")
            assertEquals("乙", joined["name"]!!.jsonPrimitive.content)
            assertEquals(false, joined["isHost"]!!.jsonPrimitive.content.toBoolean())

            val state = readUntil("state")["state"]!!.jsonObject
            assertEquals(2, state["players"]!!.jsonArray.size)
            val names = state["players"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue(names.containsAll(listOf("甲", "乙")), "两个座位都该在：$names")
        }
    }

    @Test
    fun `join unknown room reports an error frame`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("join", "room" to "ZZZZ"))
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("房间不存在"))
        }
    }

    @Test
    fun `lobby lists rooms filtered by game`() = withApp { client ->
        val uc = Play.Room("AAAA", Play.UNDERCOVER)
        uc.seats["s1"] = Play.Seat("s1").also { it.name = "x" }
        Play.rooms["AAAA"] = uc

        val ww = Play.Room("BBBB", Play.WEREWOLF)
        ww.seats["s1"] = Play.Seat("s1").also { it.name = "y" }
        Play.rooms["BBBB"] = ww

        client.webSocket("/play/ws") {
            sendJson(frame("lobby"))
            val all = readUntil("roomList")["rooms"]!!.jsonArray
            assertEquals(2, all.size)

            sendJson(frame("lobby", "game" to "werewolf"))
            val only = readUntil("roomList")["rooms"]!!.jsonArray
            assertEquals(1, only.size)
            assertEquals("BBBB", only[0].jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `lobby reports player count and phase`() = withApp { client ->
        val room = Play.Room("CCCC", Play.UNDERCOVER)
        repeat(3) { i ->
            room.seats["s$i"] = Play.Seat("s$i").also { it.name = "p$i" }
        }
        Play.rooms["CCCC"] = room

        client.webSocket("/play/ws") {
            sendJson(frame("lobby", "game" to "undercover"))
            val rooms = readUntil("roomList")["rooms"]!!.jsonArray
            assertEquals(1, rooms.size)
            val r = rooms[0].jsonObject
            assertEquals(3, r["players"]!!.jsonPrimitive.int)
            assertEquals("waiting", r["phase"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `actions from an unbound connection are rejected`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("action").let {
                buildJsonObject {
                    put("type", JsonPrimitive("action"))
                    put("action", JsonPrimitive("startVote"))
                    put("data", JsonObject(emptyMap()))
                }
            })
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("尚未加入房间"))
        }
    }

    @Test
    fun `state is cut per seat and never leaks the other player's word`() = withApp { client ->
        var roomId = ""
        var hostSeat = ""
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            val joined = readUntil("joined")
            roomId = joined["room"]!!.jsonPrimitive.content
            hostSeat = joined["seat"]!!.jsonPrimitive.content
            readUntil("state")
        }

        // 补两个座位，凑够开局人数
        val room = Play.rooms[roomId]!!
        room.seats["sX"] = Play.Seat("sX").also { it.name = "乙" }
        room.seats["sY"] = Play.Seat("sY").also { it.name = "丙" }

        client.webSocket("/play/ws") {
            sendJson(frame("resume", "room" to roomId, "seat" to hostSeat))
            readUntil("joined")
            val state = readUntil("state")["state"]!!.jsonObject

            // 顶层只有自己的词
            assertTrue(state.containsKey("myWord"))
            // players 里没有 word 字段
            state["players"]!!.jsonArray.forEach { p ->
                assertTrue(p.jsonObject["word"] == null, "players[] 不允许带 word")
            }
        }
    }

    @Test
    fun `chat is appended to the log`() = withApp { client ->
        var roomId = ""
        var seat = ""
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            val joined = readUntil("joined")
            roomId = joined["room"]!!.jsonPrimitive.content
            seat = joined["seat"]!!.jsonPrimitive.content
            readUntil("state")
        }

        client.webSocket("/play/ws") {
            sendJson(frame("resume", "room" to roomId, "seat" to seat))
            readUntil("joined")
            readUntil("state")

            sendJson(buildJsonObject {
                put("type", JsonPrimitive("chat"))
                put("text", JsonPrimitive("大家好"))
                put("scope", JsonPrimitive("public"))
            })

            val state = readUntil("state")["state"]!!.jsonObject
            val texts = state["log"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
            assertTrue(texts.contains("大家好"), "聊天内容应进日志：$texts")

            val by = state["log"]!!.jsonArray
                .first { it.jsonObject["text"]!!.jsonPrimitive.content == "大家好" }
                .jsonObject["by"]!!.jsonPrimitive.content
            assertEquals("甲", by)
        }
    }

    @Test
    fun `non wolf cannot post to the wolves channel`() = withApp { client ->
        val room = Play.Room("DDDD", Play.WEREWOLF)
        room.seats["s1"] = Play.Seat("s1").also { it.name = "甲"; it.online = true }
        room.hostSeat = "s1"
        Play.rooms["DDDD"] = room

        client.webSocket("/play/ws") {
            sendJson(frame("resume", "room" to "DDDD", "seat" to "s1"))
            readUntil("joined")
            readUntil("state")

            sendJson(buildJsonObject {
                put("type", JsonPrimitive("chat"))
                put("text", JsonPrimitive("我是狼"))
                put("scope", JsonPrimitive("wolves"))
            })
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("频道"))
        }
    }

    @Test
    fun `resume with a stale seat reports an error`() = withApp { client ->
        val room = Play.Room("EEEE", Play.UNDERCOVER)
        room.seats["real"] = Play.Seat("real").also { it.name = "甲" }
        Play.rooms["EEEE"] = room

        client.webSocket("/play/ws") {
            sendJson(frame("resume", "room" to "EEEE", "seat" to "bogus"))
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("座位已失效"))
        }
    }

    @Test
    fun `malformed frame does not kill the connection`() = withApp { client ->
        client.webSocket("/play/ws") {
            send(Frame.Text("{not json"))
            // 坏帧之后照常能干活
            sendJson(frame("create", "game" to "werewolf", "name" to "甲"))
            val joined = readUntil("joined")
            assertEquals("werewolf", joined["game"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown message type reports an error`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("nonsense"))
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("未知消息类型"))
        }
    }

    @Test
    fun `create with unknown game is rejected`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "chess"))
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("未知的游戏"))
        }
    }

    @Test
    fun `disconnect marks the seat offline`() = withApp { client ->
        var roomId = ""
        var seat = ""
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            val joined = readUntil("joined")
            roomId = joined["room"]!!.jsonPrimitive.content
            seat = joined["seat"]!!.jsonPrimitive.content
            readUntil("state")
        }

        // webSocket 块结束 = 连接关闭，finally 里应把 online 置 false
        val room = Play.rooms[roomId]!!
        assertNotNull(room.seats[seat])
        assertTrue(!room.seats[seat]!!.online, "断开后座位应标记为离线")
    }

    @Test
    fun `host start on a too small room reports the reason`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            readUntil("joined")
            readUntil("state")

            sendJson(buildJsonObject {
                put("type", JsonPrimitive("action"))
                put("action", JsonPrimitive("start"))
                put("data", JsonObject(emptyMap()))
            })
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("至少需要"), "应说明人数不够")
        }
    }

    @Test
    fun `custom group config is validated`() = withApp { client ->
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            readUntil("joined")
            readUntil("state")

            // 只有一个词 → 400 级别的错误
            sendJson(buildJsonObject {
                put("type", JsonPrimitive("action"))
                put("action", JsonPrimitive("config"))
                put("data", buildJsonObject { put("group", JsonPrimitive("孤单")) })
            })
            val err = readUntil("err")
            assertTrue(err["text"]!!.jsonPrimitive.content.contains("至少需要 2 个"))
        }
    }

    @Test
    fun `config accepts a valid group and clears with empty string`() = withApp { client ->
        var roomId = ""
        client.webSocket("/play/ws") {
            sendJson(frame("create", "game" to "undercover", "name" to "甲"))
            roomId = readUntil("joined")["room"]!!.jsonPrimitive.content
            readUntil("state")

            sendJson(buildJsonObject {
                put("type", JsonPrimitive("action"))
                put("action", JsonPrimitive("config"))
                put("data", buildJsonObject { put("group", JsonPrimitive("苹果,梨、草莓")) })
            })
            val state = readUntil("state")["state"]!!.jsonObject
            val g = state["customGroup"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("苹果", "梨", "草莓"), g, "三种分隔符都要支持")

            sendJson(buildJsonObject {
                put("type", JsonPrimitive("action"))
                put("action", JsonPrimitive("config"))
                put("data", buildJsonObject { put("group", JsonPrimitive("")) })
            })
            val cleared = readUntil("state")["state"]!!.jsonObject
            assertTrue(cleared["customGroup"] is JsonNull, "空串应恢复内置随机")
        }
        assertTrue(Play.rooms[roomId]!!.customGroup == null)
    }
}
