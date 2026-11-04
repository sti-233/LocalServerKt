package localserver.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import localserver.utils.Logger
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
 *
 * **control 用户不受限制**：`Control.allowed()` 白名单里的 IP（与 `/canControl`、取流限速同一份）
 * 即使在正课期间也能正常打开视频页与接口，`/videoStatus` 对他们恒回 `{"open":true}`，
 * 免得页面被自己的轮询踢走。
 */
internal object VideoGate {

    /** 视频模块是否开放，正课期间为 false。 */
    @Volatile
    var open: Boolean = true

    /**
     * 手动放行：control 用户调 `/openVideo` 后为 true，**只对这一节课有效**。
     *
     * 为什么按「节课」而不是永久：需求是「开放普通用户当节课的权限」，下课就该收回，
     * 否则一次放行会一直开着、没人记得关。所以 [followTimetable] 一旦检测到阶段变化
     * （上下课切换）就把这里清掉，与 [ClassGate] 的节流共用同一个触发点。
     */
    @Volatile
    var manualOpen: Boolean = false
        private set

    /** 手动放行 / 收回，返回是否真的发生了变化。 */
    fun setManualOpen(value: Boolean): Boolean {
        if (manualOpen == value) return false
        manualOpen = value
        Logger.debug("视频模块：手动放行 = $value")
        return true
    }

    /** 阶段没变就不动手，避免覆盖手动开关（与 [Control.state] 同一套 [ClassGate] 思路）。 */
    private val gate = ClassGate("视频模块") { inClass -> open = !inClass }

    /**
     * 由 Server.kt 的后台协程每 30 秒调一次。
     *
     * 阶段变化时顺带清掉 [manualOpen] —— 这就是「只放行当节课」的落地点。
     */
    fun followTimetable(): Boolean = applyPhase(Timetable.phaseKey())

    /**
     * [followTimetable] 的主体，单独抽出来只为了能喂任意阶段做测试
     * （[Timetable.phaseKey] 读的是真实时间，没法在测试里翻到下一节课）。
     */
    internal fun applyPhase(phase: String): Boolean {
        val changed = gate.apply(phase)
        if (changed && manualOpen) {
            manualOpen = false
            Logger.debug("视频模块：上下课切换，手动放行已收回")
        }
        return changed
    }

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
     * 判断来源地址是不是 control 用户。默认就是 [Control.allowed]。
     *
     * 抽成 `var` 只为测试能替换：测试宿主里 `remoteAddress` 恒为 `localhost`，而 `127.0.0.1`
     * 正在 control 白名单里 —— 不替换的话所有「关闭时应被拦」的用例都会因为豁免而失效。
     */
    internal var isControl: (String) -> Boolean = { Control.allowed(it) }

    /**
     * control 用户（[Control.allowed] 那套 IP 前缀白名单）不受正课限制。
     *
     * 白名单与 `/canControl`、取流限速用的是同一份，所以"能控制服务器的人"在课上也照样能看视频，
     * 调试、排查、给全班放视频都不用先去关课表判定。
     */
    internal fun bypass(remoteAddress: String): Boolean = isControl(remoteAddress)

    /**
     * 对某个请求方而言视频模块是否可用（拦截器与 `/videoStatus` 共用同一判断）。
     *
     * 三种放行理由：模块本身开着（非正课）、[manualOpen]（control 手动放行了当节课）、
     * 或者请求方自己就是 control 用户。
     *
     * 拆出来是为了能直接单测：测试宿主里 `remoteAddress` 恒为 `localhost`，没法在路由层模拟
     * control 网段的来源 IP。
     */
    internal fun availableTo(remoteAddress: String): Boolean =
        open || manualOpen || bypass(remoteAddress)

    /**
     * 前端轮询用：`{"open":false}` 时 video 各页立刻跳去课表。
     *
     * 必须永远可达（不在 [API_PATHS] 里），否则前端没法知道"是被关了"还是"网络断了"。
     * 返回的 `open` 是**对当前请求方而言**的可用性：control 用户即使在正课期间也拿到 true，
     * 否则页面会因为轮询结果而被自己踢去课表。
     */
    fun Route.videoStatusRoute() = get("/videoStatus") {
        // no-store：这个值随时会翻转，缓存住就等于关不掉
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val available = availableTo(call.request.local.remoteAddress)
        call.respondText(
            text = """{"open":$available}""",
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
        if (availableTo(call.request.local.remoteAddress)) return@intercept
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
