package localserver

import localserver.module.Chat.chatRoute
import localserver.module.Control
import localserver.module.Control.controlRoute
import localserver.module.Music.musicRoute
import localserver.module.Video.videoRoute
import localserver.utils.Logger
import localserver.utils.Network.network
import localserver.utils.Util

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds

private const val chunkSize = 8192

private const val password = "114514"

fun main() {
    embeddedServer(Netty, port = 80, host = "0.0.0.0") {
        install(Authentication) {
            form("auth") {
                challenge("https://h5.lezhiyun.com/multi_wjdc/?host=www.lezhiyun.com&token=")
                skipWhen { call ->
                    call.parameters["p"]?.equals(password) ?: false || call.request.local.remoteAddress.startsWith("192.168.20.10") || !call.request.local.remoteAddress.startsWith("192.168.125.202") && if (!Control.state) {
                        Logger.error("${call.request.local.remoteAddress} request when server is closing.")
                        false
                    } else if (Util.getUserList().any { it.ip == call.request.local.remoteAddress }) {
                        true
                    } else {
                        Logger.debug("${call.request.local.remoteAddress} was blocked.")
                        false
                    }
                }
            }
            basic("control") {
                skipWhen { call ->
                    call.parameters["p"]?.equals(password) ?: false || call.request.local.remoteAddress.startsWith("192.168.20.10")
                }
            }
        }
        install(StatusPages) {
            exception<NotFoundException> { call, cause ->
                call.respondText(text = "404 \n\n $cause" , status = HttpStatusCode.NotFound)
            }
            exception<Exception> { _, e ->
                Logger.error(e.toString())
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
            controlRoute()
            musicRoute()
            videoRoute()
            network()
        }
    }.start(wait = true)
}
