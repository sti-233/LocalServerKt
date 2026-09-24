package localserver.module

import localserver.types.Content
import localserver.types.Message
import localserver.utils.Logger
import localserver.utils.Time
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Chat {
    private val clients = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    fun Route.chatRoute() {
        chatPage()
        history()
        conversations()
        message()
        
        websocket()
        clients()
        whoami()
    }

    private fun Route.chatPage() = authenticate("auth") {
        get("/chat") {
            call.respondRedirect("/resources/chat.html")
        }
    }

    private fun Route.history() = get("/history") {
        val targetUser = call.parameters["targetuser"]
        val date = call.parameters["date"]?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        val current = Util.getUserName(call.request.local.remoteAddress)
        if (!targetUser.isNullOrEmpty() && !Util.userExists(targetUser)) {
            call.respondText("私聊对象 '$targetUser' 不存在于用户列表，请确认名字正确或先添加该用户", status = HttpStatusCode.BadRequest)
            return@get
        }
        val target = if (!targetUser.isNullOrEmpty()) Util.getTarget(current, targetUser) else date
        call.respondText(Util.getHistory(target))
    }

    // 会话列表：扫描 message/ 下的 history_*.json，返回 {today, names, last} JSON 对象：
    // names 为文件名（不含前缀与扩展名），供 chat.html 侧边栏区分群聊（yyyy-MM-dd）与私聊
    // （"名A-名B"，getTarget 排序后命名）；today 为服务端今天（Asia/Shanghai）；
    // last 为各会话最后一条消息的 {time, by}，供前端按"最后消息晚于已读时间且非自己/系统发送"点亮未读小红点
    private fun Route.conversations() = get("/conversations") {
        val files = File("message")
            .listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }
            .orEmpty()
        val names = files.map { it.name.removePrefix("history_").removeSuffix(".json") }.sorted()
        val last = buildMap {
            for (f in files) {
                put(f.name.removePrefix("history_").removeSuffix(".json"), f.lastMessage())
            }
        }
        call.respondText(Json.encodeToString(buildJsonObject {
            put("today", Time.getCurrentDate())
            put("names", Json.encodeToJsonElement(names))
            put("last", Json.encodeToJsonElement(last))
        }))
    }

    // 读 history 文件最后一条消息（time 与发送者 name）；文件损坏/为空返回 null（前端视为无未读）
    private fun File.lastMessage(): JsonObject? = try {
        val list = Json.decodeFromString<MutableList<Message>>(readText().trimStart('﻿'))
        val m = list.lastOrNull() ?: return null
        buildJsonObject {
            put("time", JsonPrimitive(m.time))
            put("by", JsonPrimitive(m.name))
        }
    } catch (e: Exception) {
        Logger.error("Failed to parse ${name}: ${e.message}")
        null
    }
    
    private fun Route.whoami() = authenticate("auth") {
        get("/whoami") {
            call.respondText(Util.getUserName(call.request.local.remoteAddress))
        }
    }

    private fun Route.message() = webSocket("/message") {
        val clientId = call.request.local.remoteAddress
        clients[clientId] = this
        
        println("Client $clientId connected. Total clients: ${clients.size}")
        
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val json = Json.decodeFromString<Content>(text)
                        val current = Util.getUserName(clientId)
                        if (json.type == "send") {
                            // 私聊对象不在用户列表时直接回 err，避免 getUserIp 抛异常导致整个连接被断开
                            if (!json.sendTo.isNullOrEmpty() && !Util.userExists(json.sendTo)) {
                                send(Json.encodeToString(
                                    Content("err", current, Time.getCurrentTimeWithDate(),
                                        "私聊对象 '$json.sendTo' 不存在于用户列表，请确认名字正确或先添加该用户",
                                        json.sendTo)
                                ))
                                continue
                            }
                            // 图片消息：参考 talk.html 的 ImageB64: 前缀协议，服务端按 10MB 校验后原样转发/存档
                            if (json.text.startsWith("ImageB64:")) {
                                val dataUrl = json.text.removePrefix("ImageB64:")
                                val ok = dataUrl.length <= 10 * 1024 * 1024 &&
                                        Regex("^data:image/[a-z]+;base64,[A-Za-z0-9+/=]+$").matches(dataUrl)
                                if (!ok) {
                                    send(Json.encodeToString(
                                        Content("err", current, Time.getCurrentTimeWithDate(),
                                            "图片消息无效：需为 data:image/...;base64, 形式且不超过 10MB",
                                            json.sendTo)
                                    ))
                                    continue
                                }
                            }
                            val content = Content("send", current, Time.getCurrentTimeWithDate(), json.text, json.sendTo)
                            val message = Message(current, Time.getCurrentTimeWithDate(), json.text)
                            val response = Json.encodeToString(content)
                            if (json.sendTo.isNullOrEmpty()) {
                                clients.forEach { (_, session) ->
                                    session.send(response)
                                }
                                Util.addHistory(message)
                            } else {
                                // sendTo 非空时已确认存在于用户列表
                                listOf(clients[Util.getUserIp(json.sendTo)], this).forEach { session ->
                                    session?.send(response)
                                }
                                Util.addHistory(message, Util.getTarget(current, json.sendTo))
                            }
                        } else if (json.type == "del" && Time.withinTwoMin(json.time)) {
                            if (!json.sendTo.isNullOrEmpty() && !Util.userExists(json.sendTo)) {
                                send(Json.encodeToString(
                                    Content("err", current, Time.getCurrentTimeWithDate(),
                                        "私聊对象 '$json.sendTo' 不存在于用户列表，无法撤回",
                                        json.sendTo)
                                ))
                                continue
                            }
                            val content = Content("del", current, json.time, json.text, json.sendTo)
                            val response = Json.encodeToString(content)
                            if (json.sendTo.isNullOrEmpty()) {
                                clients.forEach { (_, session) ->
                                    session.send(response)
                                }
                                Util.delHistory(current, json.time)
                            } else {
                                // sendTo 非空时已确认存在于用户列表
                                listOf(clients[Util.getUserIp(json.sendTo)], this).forEach { session ->
                                    session?.send(response)
                                }
                                Util.delHistory(current, json.time, Util.getTarget(current, json.sendTo))
                            }
                        }
                    }
                    else -> {
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            println("Client $clientId disconnected")
        } catch (e: Exception) {
            println("Error with client $clientId: ${e.message}")
        } finally {
            clients.remove(clientId)
            println("Client $clientId removed. Total clients: ${clients.size}")
        }
    }

    private fun Route.websocket() = get("/wsSever") {
        call.respondText("""
            WebSocket Chat Server
            Connect to: ws://localhost:1919/ws
            Active clients: ${clients.size}
        """.trimIndent())
    }
    
    private fun Route.clients() = get("/clients") {
        call.respondText("Connected clients: ${clients.keys.joinToString(", ")}")
    }
}
