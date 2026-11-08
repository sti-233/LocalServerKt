package localserver.module

import localserver.module.Chat.chatRoute
import localserver.types.Message
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 聊天链路的并发/多连接回归：
 *
 * 1. 同一个 IP 的两个标签页（两条 WS 连接）都要收到群消息 —— 旧实现以 IP 为键，
 *    后连的覆盖先连的，第一条从此收不到任何消息。
 * 2. 关掉其中一条连接不影响另一条 —— 旧实现无条件 `clients.remove(ip)`，
 *    会把还活着的那条从表里摘掉，之后静默失联。
 * 3. 坏帧（非 JSON）只丢这一帧，不能断连接。
 * 4. 并发写聊天记录不丢消息 —— 旧实现「读全文 → 改 → 覆写」不加锁。
 */
class ChatConcurrencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun frameOf(text: String) = Frame.Text(json.encodeToString(
        buildJsonObject {
            put("type", "send")
            put("name", "unused")
            put("time", "unused")
            put("text", text)
            put("sendTo", "")
        }
    ))

    @BeforeTest
    fun setUp() {
        File("userList.json").writeText(
            Util.prettyJson.encodeToString(listOf(User(ip = "localhost", name = "tester")))
        )
        File("message").deleteRecursively()
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

    @Test
    fun `two connections from the same ip both receive group messages`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        coroutineScope {
            val first = async {
                wsClient.webSocket("/message") {
                    // 先把收到的第一条业务帧消费掉：这条是第二条连接自己发的那条群消息
                    // 旧实现里第一条连接早被覆盖，这里会一直等到超时。
                    // 连接建立时的 presence 帧由 nextBusinessFrame 滤掉（见 WsTestSupport）
                    val got = nextBusinessFrame()
                    assertTrue(
                        got.contains("broadcast to both"),
                        "the first tab must receive group messages sent from the second tab"
                    )
                }
            }
            val second = async {
                wsClient.webSocket("/message") {
                    delay(300)
                    send(frameOf("broadcast to both"))
                    // 自己也会收到回显，说明投递链路正常（presence 帧不算）
                    nextBusinessFrame()
                }
            }
            // 第一条连接必须也能收到第二条连接发的群消息
            first.await()
            second.await()
        }
        wsClient.close()
    }

    @Test
    fun `closing one connection keeps the other alive`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        coroutineScope {
            // 第二条先连、先断；之后再从第一条发消息，第一条必须仍在表里
            val dying = async {
                wsClient.webSocket("/message") {
                    send(frameOf("from dying connection"))
                    delay(200)
                }
            }
            val survivor = async {
                wsClient.webSocket("/message") {
                    delay(800) // 等 dying 断开
                    send(frameOf("survivor still here"))
                    // dying 断开前发的群消息也会进这条连接的收件箱，所以要读到这一条为止
                    var seen = false
                    withTimeout(5000) {
                        while (!seen) {
                            val text = (incoming.receive() as Frame.Text).readText()
                            seen = text.contains("survivor still here")
                        }
                    }
                    assertTrue(seen, "survivor must still receive its own echo after the other tab closed")
                }
            }
            dying.await()
            survivor.await()
        }
        wsClient.close()
    }

    @Test
    fun `a malformed frame does not close the connection`() = withApp { client ->
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            send(Frame.Text("this is not json"))
            delay(200)
            // 连接还活着才能发出这一条并收回显
            send(frameOf("after bad frame"))
            val got = nextBusinessFrame()
            assertTrue(
                got.contains("after bad frame"),
                "connection must survive a malformed frame"
            )
        }
        wsClient.close()
    }

    @Test
    fun `messages arrive in send order on one connection`() = withApp { client ->
        // 投递不能再退回「每条消息各自 launch 一个 send」：那样一个慢客户端上的
        // 第 1 条还在挂起时第 2 条已经发出去了，同一会话的消息会乱序。
        // 现在每条连接一个出站队列 + 一个发送协程，顺序即入队顺序。
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            val total = 20
            repeat(total) { i -> send(frameOf("seq-$i")) }
            val got = withTimeout(10_000) {
                val ordered = mutableListOf<Int>()
                while (ordered.size < total) {
                    val text = (incoming.receive() as Frame.Text).readText()
                    Regex("""seq-(\d+)""").find(text)?.let {
                        // sendTo 为空的群消息会回显给发送者自己，这里收的就是回显
                        ordered += it.groupValues[1].toInt()
                    }
                }
                ordered
            }
            assertEquals(
                (0 until total).toList(), got,
                "frames must be delivered in the order they were sent, got $got"
            )
        }
        wsClient.close()
    }

    @Test
    fun `revoking a message past the window reports an error`() = withApp { client ->
        // 超窗撤回早先是彻底静默：服务端不处理、也不回话，用户点完什么都没发生。
        // 现在回一条 err，前端 showError 会把它显示出来。
        val wsClient = client.config { install(ClientWebSockets) }
        wsClient.webSocket("/message") {
            val stale = Frame.Text(json.encodeToString(
                buildJsonObject {
                    put("type", "del")
                    put("name", "unused")
                    put("time", "2020-01-01 00:00:00") // 远早于 2 分钟窗口
                    put("text", "unused")
                    put("sendTo", "")
                }
            ))
            send(stale)
            val got = nextBusinessFrame()
            assertTrue(got.contains("\"err\""), "expected an err frame, got: $got")
            assertTrue(got.contains("2 分钟"), "the error should explain the window, got: $got")
        }
        wsClient.close()
    }

    @Test
    fun `concurrent history writes do not lose messages`() {
        val threads = 8
        val perThread = 15
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            repeat(threads) { t ->
                pool.submit {
                    start.await()
                    repeat(perThread) { i ->
                        Util.addHistory(Message("tester", "2026-01-01 00:00:0$i", "t$t-$i"), "concurrency")
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers should finish")

            val list = Util.getHistoryList("concurrency")
            val sent = list.filter { it.text.startsWith("t") && it.text.contains("-") }
            assertEquals(
                threads * perThread, sent.size,
                "every concurrently written message must survive, got ${sent.size}"
            )
        } finally {
            pool.shutdownNow()
            File("message", "history_concurrency.json").delete()
        }
    }
}
