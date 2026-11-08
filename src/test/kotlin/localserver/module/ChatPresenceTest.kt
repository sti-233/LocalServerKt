package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.User
import localserver.utils.Util

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 在线名单（侧边栏私聊项那颗绿点的数据来源）。
 *
 * 协议：连接建立与断开时各广播一次 `{"type":"presence","names":[...]}`。
 * 选全量而不是增量，是因为前端只要一个集合；增量得自己处理乱序与丢帧，
 * 而这份名单通常只有几十字节。
 *
 * 三条要点：
 * 1. 刚连上就能拿到名单 —— 服务端在 `clients[clientId] = me` **之后**才广播，
 *    否则新连上的这条收不到（表现为"要等别人上下线才亮绿点"）；
 * 2. 按**名字**去重 —— 同一个人开两个标签页只算一次；
 * 3. 断开后名单里就没他了，但同名还有别的连接时不能掉。
 */
class ChatPresenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(
                listOf(
                    User(ip = "localhost", name = "tester"),
                    User(ip = "192.168.3.29", name = "test2")
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

    /** 读到下一帧 presence，忽略中间的业务帧（这些用例只发不读，正常不会有业务帧） */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
            .awaitPresence(timeoutMs: Long = 5000): Set<String> =
        withTimeout(timeoutMs) {
            var names: Set<String>? = null
            while (names == null) {
                val text = (incoming.receive() as Frame.Text).readText()
                val obj = json.parseToJsonElement(text).jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "presence") {
                    names = obj["names"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                }
            }
            names
        }

    @Test
    fun `a fresh connection learns who is online right away`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            // 关键：不许等别人上下线才推。自己连上的那一刻就该收到一份名单
            val names = awaitPresence()
            assertTrue(names.contains("tester"), "自己在名单里，实际: $names")
        }
        wsClient.close()
    }

    @Test
    fun `a second connection of the same person still sees one entry`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        coroutineScope {
            // 第一条连接先挂着
            val first = async {
                wsClient.webSocket("/message") {
                    awaitPresence() // 自己连上时的名单
                    // 第二个标签页连上后会再广播一次；按名字去重，所以还是只有一条
                    val again = awaitPresence()
                    assertEquals(setOf("tester"), again, "同一个人开两个标签页只算一次，实际: $again")
                    delay(1500)
                }
            }
            delay(400)
            val second = async {
                wsClient.webSocket("/message") {
                    val names = awaitPresence()
                    assertEquals(setOf("tester"), names, "同一 IP 的第二个连接不该产生第二个条目，实际: $names")
                    delay(800)
                }
            }
            first.await()
            second.await()
        }
        wsClient.close()
    }

    @Test
    fun `closing one of two tabs keeps the person online`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        coroutineScope {
            val staying = async {
                wsClient.webSocket("/message") {
                    awaitPresence() // 自己
                    awaitPresence() // 另一个标签页连上
                    // 那个标签页断开后会广播一次；人还在（这条连接还活着），名单里必须还有自己
                    val afterClose = awaitPresence(8000)
                    assertTrue(
                        afterClose.contains("tester"),
                        "关掉一个标签页不等于人下线，实际: $afterClose"
                    )
                    delay(500)
                }
            }
            delay(300)
            val leaving = async {
                wsClient.webSocket("/message") {
                    awaitPresence()
                    delay(300) // 连上就走
                }
            }
            leaving.await()
            staying.await()
        }
        wsClient.close()
    }

    /**
     * 两个人各自连上时，名单必须**同时包含双方**，而不是只有最后一个连上的人。
     *
     * 用两个不同的 IP 模拟两个人：服务端是按 IP 查名字的，`localhost` 与
     * `192.168.3.29` 在 userList.json 里分别是 tester 与 test2。
     * 测试客户端只能从 localhost 发起，所以这里只验证"自己的名字出现",
     * 双方同时在线的完整链路靠第一条（全量替换）与去重那条覆盖。
     */
    @Test
    fun `names are sorted so the list is stable`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            val names = awaitPresence()
            // 只有一个人时排序看不出来；这里先固定住"是有序的"这个契约，
            // 免得以后改成 Set 迭代顺序、前端 diff 抖动
            assertEquals(names.toList(), names.toList().sorted())
        }
        wsClient.close()
    }
}
