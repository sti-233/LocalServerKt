package localserver.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import localserver.utils.Timetable

/**
 * 正课时段的视频模块开关。
 *
 * 原先「正课自动关闭」是把 [Control.state] 置 false，等于整个服务器对非白名单 IP 关门
 * （聊天、课表、文件全进不去）。实际需求只是「上课别刷视频」，所以收窄到只关 Video：
 *
 * - 手动 `/start`、`/exit` 仍然控制全局 [Control.state]，课表不再碰它；
 * - 课表只改这里的 [open]，由 [blockWhenClosed] 统一挡掉视频相关的请求。
 *
 * 关的只是「新请求」；已经在播的页面靠前端轮询 `/videoStatus` 自动跳去课表（见 common.js）。
 */
internal object VideoGate {

    /** 视频模块是否开放，正课期间为 false。 */
    @Volatile
    var open: Boolean = true

    /** 阶段没变就不动手，避免覆盖手动开关（与 [Control.state] 同一套 [ClassGate] 思路）。 */
    private val gate = ClassGate("视频模块") { inClass -> open = !inClass }

    /** 由 Server.kt 的后台协程每 30 秒调一次。 */
    fun followTimetable() = gate.apply(Timetable.phaseKey())

    /** 需要挡住的接口路径（精确匹配）。`/videoStatus` 故意不在里面 —— 前端要靠它得知已关闭。 */
    private val API_PATHS = setOf(
        "/video",
        "/recommend",
        "/searchByType",
        "/searchUser",
        "/videoInfo",
        "/videoStream",
        "/videoStreamInfo",
        "/videoComments",
        "/userInfo",
        "/userSpace",
        "/userDynamic",
        "/biliImg",
    )

    /** 页面与静态资源前缀（`staticResources("/resources", "")` 注册的那批）。 */
    private const val PAGE_PREFIX = "/resources/video/"

    internal fun blocks(path: String): Boolean = path in API_PATHS || path.startsWith(PAGE_PREFIX)

    /**
     * 前端轮询用：`{"open":false}` 时 video 各页立刻跳去课表。
     *
     * 必须永远可达（不在 [API_PATHS] 里），否则前端没法知道"是被关了"还是"网络断了"。
     */
    fun Route.videoStatusRoute() = get("/videoStatus") {
        // no-store：这个值随时会翻转，缓存住就等于关不掉
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text = """{"open":$open}""",
            contentType = ContentType.Application.Json,
        )
    }

    /**
     * 挂在 routing 根节点上的拦截器。
     *
     * 必须挂在根节点而不是 videoRoute() 的子路由上：视频页面是 `staticResources("/resources", "")`
     * 注册的，和 Video 模块平级，挂子路由拦不到。Ktor 的拦截器与路由注册顺序无关，
     * 即使 staticResources 先注册也能拦住（已实测）。
     */
    fun Route.blockWhenClosed() = intercept(ApplicationCallPipeline.Call) {
        if (open) return@intercept
        val path = call.request.path()
        if (!blocks(path)) return@intercept
        if (path.startsWith(PAGE_PREFIX)) {
            // 页面直接踢回课表：用户看到的不是一片空白，而是"现在是上课时间"
            call.respondRedirect("/resources/timetable.html")
        } else {
            call.respondText(
                text = """{"error":"现在是正课时间，视频模块已关闭"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Forbidden,
            )
        }
        finish()
    }
}
