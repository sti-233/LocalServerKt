package localserver.module

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import localserver.utils.Logger
import localserver.utils.Secrets
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap

object Ai {
    private const val modelName = "agnes-3.0-flash"
    private const val baseUrl = "https://apihub.agnes-ai.com/v1/chat/completions"
    private const val apiKey = Secrets.agnesApiKey

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000L
            connectTimeoutMillis = 30_000L
        }
        // 客户端 SSE 插件（io.ktor.client.plugins.sse.SSE），
        // 服务端 SSE 插件在 Server.kt 中已 install(SSE)
        install(SSE)
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // 会话历史：key 为客户端 IP；纯内存，进程重启后丢失
    private val sessions = ConcurrentHashMap<String, MutableList<JsonObject>>()

    fun Route.aiRoute() {
        aiPage()
        chatSse()
        getHistory()
        clearHistory()
    }

    private fun Route.aiPage() = authenticate("auth") {
        get("/ai") {
            call.respondRedirect("/resources/ai.html")
        }
    }

    private fun Route.chatSse() = authenticate("auth") {
        sse("/ai/chat") {
            heartbeat { 
                period = 2.seconds
                event = ServerSentEvent("heartbeat")
            }
            val text = call.parameters["text"]?.trim().orEmpty()
            if (text.isEmpty()) {
                send(ServerSentEvent(event = "error", data = """{"error":"Message is empty."}"""))
                close()
                return@sse
            }

            val clientIp = call.request.local.remoteAddress
            val history = sessions.getOrPut(clientIp) { mutableListOf() }

            synchronized(history) {
                history.add(buildJsonObject {
                    put("role", "user")
                    put("content", text)
                })
            }

            // 发往模型的 messages 只保留 role/content（思考过程与中断标记不回传）；
            // 加锁取快照，避免与并发写入互相干扰
            val messages = synchronized(history) { history.toModelMessages() }

            // 旁路累积上游 chunk 中的回复与思考过程，用于流结束后写入 history
            val reply = StringBuilder()
            val thinking = StringBuilder()

            fun recordAssistant(interrupted: Boolean) {
                if (reply.isEmpty() && thinking.isEmpty()) return
                val entry = buildJsonObject {
                    put("role", "assistant")
                    put("content", reply.toString())
                    if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
                    if (interrupted) put("interrupted", JsonPrimitive(true))
                }
                synchronized(history) { history.add(entry) }
            }

            val body = buildJsonObject {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 65536)
                put("stream", true)
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", true) })
            }

            try {
                // 客户端 SSE 会话（io.ktor.client.plugins.sse.serverSentEventsSession），
                // request builder 设 method/header/body
                val upstream = client.serverSentEventsSession(urlString = baseUrl) {
                    method = HttpMethod.Post
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                // 把上游事件逐条转发给服务端 SSE 会话 s，并旁路解析 chunk 累积回复文本
                upstream.incoming.collect { event ->
                    parseDelta(event, reply, thinking)
                    send(event)
                }
                // 正常结束：记录完整的 assistant 回复与思考过程
                recordAssistant(false)
            } catch (e: CancellationException) {
                // 客户端断开 / 请求被取消：输出通道不可再写，静默结束即可
                Logger.error("AI request cancelled (client disconnected): $e")
                recordAssistant(true)
                throw e
            } catch (e: Exception) {
                Logger.error("AI upstream error: $e")
                recordAssistant(true)
                // 通道可能已不可写（例如客户端已断开），send 失败时静默忽略
                try {
                    send(ServerSentEvent(event = "error", data = """{"error":"AI service error: $e"}"""))
                } catch (writeError: Exception) {
                    Logger.error("Failed to send error event to client: $writeError")
                }
            } finally {
                try {
                    close()
                } catch (closeError: Exception) {
                    // 通道可能已关闭，忽略
                }
            }
        }
    }

    private fun Route.getHistory() = get("/ai/history") {
        val clientIp = call.request.local.remoteAddress
        // 加锁取快照，避免在序列化期间列表被并发修改
        val history = sessions[clientIp]?.let { synchronized(it) { it.toList() } } ?: emptyList<JsonObject>()
        call.respondText(json.encodeToString(history), contentType = ContentType.Application.Json)
    }

    private fun Route.clearHistory() = post("/ai/clear") {
        val clientIp = call.request.local.remoteAddress
        sessions.remove(clientIp)
        call.respondText("History cleared.")
    }
}

// 顶层 Json 实例：供顶层函数 parseDelta 解析上游 SSE chunk
private val chunkJson = Json { ignoreUnknownKeys = true }

fun JsonObject.getString(key: String): String? = this[key]?.toString()?.removeSurrounding("\"")

// 发往模型的消息：只保留 role/content 两个字段，丢弃 reasoning_content / interrupted
private fun List<JsonObject>.toModelMessages(): JsonArray =
    buildJsonArray {
        this@toModelMessages.forEach { msg ->
            add(buildJsonObject {
                put("role", msg["role"] ?: return@buildJsonObject)
                msg["content"]?.let { put("content", it) }
            })
        }
    }

// 从上游 SSE chunk 中解析 delta.content / delta.reasoning_content 并累积到缓冲区。
// 解析失败（如 [DONE] 或非 JSON 事件）静默跳过，不影响原样转发。
private fun parseDelta(event: ServerSentEvent, reply: StringBuilder, thinking: StringBuilder) {
    val data = event.data ?: return
    if (data == "[DONE]") return
    val parsed = runCatching { chunkJson.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
    val choices = parsed["choices"] as? JsonArray
    val first = choices?.getOrNull(0) as? JsonObject
    val delta = first?.get("delta") as? JsonObject
    if (delta == null) return
    (delta["content"] as? JsonPrimitive)?.contentOrNull?.let(reply::append)
    (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.let(thinking::append)
}
