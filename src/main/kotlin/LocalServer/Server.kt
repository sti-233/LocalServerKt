package LocalServer

import LocalServer.Module.Chat.chatRoute
import LocalServer.Module.Music.musicRoute
import LocalServer.Utils.Network
import LocalServer.Utils.Network.network
import LocalServer.Utils.Util

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.parameters
import kotlin.time.Duration.Companion.seconds

private const val chunkSize = 8192

private val password = "114514"

fun main() {
    embeddedServer(Netty, port = 1919, host = "0.0.0.0") {
        install(Authentication) {
            form("auth") {
                challenge("https://mx.j2inter.corn")
                skipWhen { call ->
                    if (call.parameters["p"]?.equals(password) ?: false) {
                        true
                    } else if (!Network.state) {
                        false
                    } else if (Util.getUserList().any { it.ip == call.request.local.remoteAddress }) {
                        true
                    } else {
                        println("${call.request.local.remoteAddress} was blocked.")
                        false
                    }
                }
            }
            basic("control") {
                skipWhen { call ->
                    if (call.parameters["p"]?.equals(password) ?: false) {
                        true
                    } else {
                        false
                    }
                }
            }
        }
        install(WebSockets) {
            pingPeriod = 5.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            staticResources("/resources", "")
            chatRoute()
            musicRoute()
            network()
        }
    }.start(wait = true)
}
