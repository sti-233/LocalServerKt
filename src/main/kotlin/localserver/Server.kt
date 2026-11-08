package localserver

import localserver.module.Ai.aiRoute
import localserver.module.Chat.chatRoute
import localserver.module.Control
import localserver.module.Control.controlRoute
import localserver.module.File.fileRoute
import localserver.module.Music.musicRoute
import localserver.module.Play.playRoute
import localserver.module.Prefs.prefsRoute
import localserver.module.Terminal.terminalRoute
import localserver.module.Video.videoRoute
import localserver.module.VideoGate
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
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.sse.*
import io.ktor.http.CacheControl
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
        // 正课时段（课表里上午+下午 9 节，体育/自习除外）只关视频模块，不再关整个服务器；
        // 下课自动恢复。只跟随课表切换，不覆盖手动 /start、/exit。
        launch {
            while (true) {
                try {
                    VideoGate.followTimetable()
                } catch (e: Exception) {
                    Logger.error("followTimetable failed: $e")
                }
                delay(30.seconds)
            }
        }
        routing {
            // 静态资源此前既没有 Cache-Control 也没人处理版本头：Ktor 的 LocalFileContent /
            // JarFileContent 内部虽然带了 Last-Modified 的版本信息，但没人把它写回响应头、
            // 也没人校验 If-Modified-Since，于是浏览器每次进页面都要重下全部 JS/CSS（约 48KB）。
            // 这里两件事一起补：ConditionalHeaders 负责回 Last-Modified + 命中时回 304；
            // cacheControl 负责给不同类别合适的 max-age。
            install(ConditionalHeaders)
            staticResources("/resources", "") {
                cacheControl { resource ->
                    // HTML / JSON 用 no-cache：允许存但每次都要带 If-Modified-Since 来验，
                    // 改动页面后刷新立刻生效，不必等 max-age 过期。
                    // JS/CSS 与图片一样给 7 天：正常浏览时零请求，过期后带 If-Modified-Since 来验，
                    // 没改回 304 几乎不花钱。代价是「新 HTML + 旧 common.js」——两边函数名对不上，
                    // 页面直接抛 ReferenceError（video 页的 snapSet 就是这么没的）。这个窗口靠
                    // setting.html 的「强制刷新 JS/CSS」按钮兜底：它走 fetch(cache:'reload') 无条件
                    // 回源，不受这里 max-age 的影响，改完脚本点一下即可，不必逐台设备强刷。
                    // 图片同样 7 天：字节不变就不该变，environment.html 的「图片二次加载走缓存」
                    // 探测靠的就是它 —— 若图片走 no-cache，第二次只会拿到 304、传输量不为 0。
                    val name = resource.path.substringAfterLast('/').lowercase()
                    when {
                        name.endsWith(".html") || name.endsWith(".json") || name.endsWith(".md") ->
                            listOf(CacheControl.NoCache(null))
                        name.endsWith(".js") || name.endsWith(".css") ||
                            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                            name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".avif") ||
                            name.endsWith(".svg") || name.endsWith(".ico") ->
                            listOf(CacheControl.MaxAge(7 * 24 * 3600, mustRevalidate = true))
                        else -> listOf(CacheControl.NoCache(null))
                    }
                }
            }
            // 挂在根节点：视频页面是 staticResources 注册的，与 Video 模块平级，挂子路由拦不到
            with(VideoGate) {
                blockWhenClosed()
                videoStatusRoute()
            }
            aiRoute()
            chatRoute()
            controlRoute()
            musicRoute()
            videoRoute()
            fileRoute()
            terminalRoute()
            playRoute()
            prefsRoute()
            network()
        }
    }.start(wait = true)
}
