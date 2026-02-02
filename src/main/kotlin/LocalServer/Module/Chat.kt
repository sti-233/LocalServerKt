package LocalServer.Module

import LocalServer.Types.Content
import LocalServer.Types.Message
import LocalServer.Utils.Time
import LocalServer.Utils.Util

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

object Chat {
    private val clients = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    public fun Route.chatRoute() {
        chatPage()
        setName()
        history()
        message()
        
        websocket()
        clients()
    }

    private fun Route.chatPage() = authenticate("auth") {
        get("/chat") {
            call.respondRedirect("/resources/talk.html")
        }
        get("/login") {
            call.respondRedirect("/resources/login.html")
        }
    }

    private fun Route.setName() = get("/setName") {
        val userList = Util.getUserList()
        userList.firstOrNull { it.ip.equals(call.request.local.remoteAddress).and(it.name.isNullOrEmpty()) }?.also { it.name = call.parameters["username"] } ?: return@get
        Util.setUserList(userList)
        call.respondText(Json.encodeToString(userList))
    }

    private fun Route.history() = get("/history") {
        val targetUser = call.parameters["targetuser"]
        val current = Util.getUserName(call.request.local.remoteAddress)
        val target = if (!targetUser.isNullOrEmpty()) Util.getTarget(current, targetUser) else null
        call.respondText(Util.getHistory(target))
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
                        println("[$clientId] Received: $text")
                        val json = Json.decodeFromString<Content>(text)
                        val current = Util.getUserName(clientId)
                        val message = Message(current, Time.getCurrentTime(), json.text, json.target)
                        val response = Json.encodeToString(message)
                        if (json.target.isNullOrEmpty()) {
                            clients.forEach { (_, session) -> 
                                session.send(response)
                            }
                            Util.addHistory(message)
                        } else {
                            listOf(clients[Util.getUserIp(json.target)], this).forEach { session ->
                                session?.send(response)
                            }
                            Util.addHistory(message, Util.getTarget(current, json.target))
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

    private fun Route.websocket() = get("/") {
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