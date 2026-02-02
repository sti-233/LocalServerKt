package LocalSever

import LocalSever.NeteaseMusicApi.network.MusicNetwork
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.http.content.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.content.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption


val clients = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

private const val chunkSize = 8192

val cookie = ""

fun main() {
    embeddedServer(Netty, port = 1919, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriod = 5.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        
        routing {
            webSocket("/ws") {
                val clientId = generateClientId()
                clients[clientId] = this
                
                println("Client $clientId connected. Total clients: ${clients.size}")
                
                try {
                    send("Welcome! Your ID: $clientId")
                    
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                println("[$clientId] Received: $text")
                                clients.forEach { (client, session) -> 
                                    if (client != clientId) {
                                        session.send("[$client]: $text")
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
            
            get("/") {
                call.respondText("""
                    WebSocket Chat Server
                    Connect to: ws://localhost:1919/ws
                    Active clients: ${clients.size}
                """.trimIndent())
            }
            
            get("/clients") {
                call.respondText("Connected clients: ${clients.keys.joinToString(", ")}")
            }

            get("/started") {
                call.respondText("True")
            }

            get("/searchMusic") {
                val keyword = call.parameters["keyword"]!!
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 10
                val result = MusicNetwork.searchMusic(keyword, offset, limit, cookie)
                call.respondText(Json.encodeToString(result))
            }

            get("/getLyrics") {
                val id = call.parameters["id"]!!
                val result = MusicNetwork.getLyrics(id, cookie)
                call.respondText(Json.encodeToString(result))
            }

            get("/getMusicUrl") {
                val id = call.parameters["id"]!!
                val level = call.parameters["level"]!!
                val result = MusicNetwork.getMusicUrl(id, level, cookie)
                call.respondText(Json.encodeToString(result))
            }

            get("/music") {
                call.respondFile(File("./res/WebPages/music.html"))
            }

            get("/download") {
                val fileUrl = call.request.queryParameters["url"]
                    ?: return@get call.respondText("Please provide URL parameter", status = HttpStatusCode.BadRequest)
                println(fileUrl)
                val client = HttpClient(CIO)
                val tempFile: Path = Files.createTempFile("download_", ".tmp")
                try {
                    val response = client.get(fileUrl)
                    if (response.status.isSuccess()) {
                        val bytes: ByteArray = response.bodyAsBytes()
                        Files.write(tempFile, bytes, StandardOpenOption.WRITE)
                        val originalFileName = extractFileName(fileUrl, response)
                        call.response.headers.apply {
                            append(HttpHeaders.ContentDisposition, 
                                "attachment; filename=\"$originalFileName\"")
                            append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                        }
                        call.respondFile(tempFile.toFile())
                    } else {
                        call.respondText("Failed to download: ${response.status}", 
                                        status = HttpStatusCode.InternalServerError)
                    }
                } catch (e: Exception) {
                    call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                } finally {
                    client.close()
                    Files.deleteIfExists(tempFile)

                }
            }
        }
    }.start(wait = true)
}

private fun generateClientId(): String {
    return "client_${System.currentTimeMillis()}_${(1000..9999).random()}"
}

private fun extractFileName(url: String, response: HttpResponse): String {
    val contentDisposition = response.headers[HttpHeaders.ContentDisposition]
    if (contentDisposition != null) {
        val regex = "filename=\"?(.*?)\"?[;\\s]".toRegex()
        regex.find(contentDisposition)?.let {
            return it.groupValues[1]
        }
    }
    val fromUrl = url.substringAfterLast("/").substringBefore("?")
    if (fromUrl.isNotBlank()) {
        return fromUrl
    }
    return "downloaded_file"
}