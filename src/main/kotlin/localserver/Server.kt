package localserver

import localserver.module.Ai.aiRoute
import localserver.module.Chat.chatRoute
import localserver.module.Control
import localserver.module.Control.controlRoute
import localserver.module.File.fileRoute
import localserver.module.Music.musicRoute
import localserver.module.Prefs.prefsRoute
import localserver.module.Terminal.terminalRoute
import localserver.module.Video.videoRoute
import localserver.utils.BiliImg
import localserver.utils.LocalIp
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
import io.ktor.server.sse.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val chunkSize = 8192

// 本机内网地址：启动时自动探测（见 utils/LocalIp.kt），不再手工改。
// 探测不准时用 -Dlocalserver.ip=192.168.3.32 或环境变量 LOCALSERVER_IP 覆盖。
// 属性每次访问都走 LocalIp 的 30 秒缓存，DHCP 换地址后无需重启。
val serverIp: String get() = LocalIp.current()
private const val password = "114514"

fun main() {
    // B 站图片缓存只在单次运行内有效（URL 带签名与尺寸参数，跨启动复用意义不大），
    // 启动时清空，避免目录无限增长。
    BiliImg.reset()
    embeddedServer(Netty, port = 80, host = "0.0.0.0") {
        install(Authentication) {
            form("auth") {
                challenge("http://$serverIp/resources/timetable.html")
                skipWhen { call ->
                    call.parameters["p"]?.equals(password) ?: false || Control.allowed(call.request.local.remoteAddress) || if (!Control.state) {
                        Logger.error("${call.request.local.remoteAddress} request when server is closing.")
                        false
                    } else if (Util.getUserList().any { it.ip == call.request.local.remoteAddress }) {
                        true
                    } else {
                        Logger.debug("${call.request.local.remoteAddress} was blocked by auth.")
                        false
                    }
                }
            }
            basic("control") {
                skipWhen { call ->
                    if (call.parameters["p"]?.equals(password) ?: false || Control.allowed(call.request.local.remoteAddress)) {
                        true
                    } else {
                        Logger.debug("${call.request.local.remoteAddress} was blocked by control.")
                        false
                    }
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
        install(SSE)
        // 正课时段（课表里上午+下午 9 节，体育/自习除外）自动把 Control.state 置 false，
        // 让 auth 拒绝非白名单 IP；下课自动恢复。只跟随课表切换，不覆盖手动 /start、/exit。
        launch {
            while (true) {
                try {
                    Control.followTimetable()
                } catch (e: Exception) {
                    Logger.error("followTimetable failed: $e")
                }
                delay(30.seconds)
            }
        }
        routing {
            staticResources("/resources", "")
            aiRoute()
            chatRoute()
            controlRoute()
            musicRoute()
            videoRoute()
            fileRoute()
            terminalRoute()
            prefsRoute()
            network()
        }
    }.start(wait = true)
}
