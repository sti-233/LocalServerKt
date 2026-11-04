package localserver.module

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VideoGate] 的行为约定：正课时段只挡视频模块，聊天/课表等照常可用。
 */
class VideoGateTest {

    @BeforeTest
    fun openUp() {
        VideoGate.open = true
        // 手动放行是全局 mutable 状态，不重置会污染其它用例（单跑通过、全跑失败）
        VideoGate.setManualOpen(false)
        // 测试宿主的 remoteAddress 恒为 localhost（在 control 白名单里），默认当成普通用户，
        // 否则「关闭时应该被拦」的用例全会被 control 豁免掉；需要测豁免的用例自己换回来。
        VideoGate.isControl = { false }
    }

    @AfterTest
    fun closeDown() {
        VideoGate.open = true
        VideoGate.setManualOpen(false)
        VideoGate.isControl = { Control.allowed(it) }
    }

    @Test
    fun `video pages and apis are blocked while closed`() = testApplication {
        VideoGate.open = false
        application {
            routing {
                staticResources("/resources", "")
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
                // 冒充 Video 模块的接口，验证拦截器确实在路由之前生效
                get("/recommend") { call.respondText("handler") }
                get("/biliImg") { call.respondText("handler") }
                // 非视频接口
                get("/whoami") { call.respondText("handler") }
            }
        }

        // 页面：重定向到课表（默认 client 会跟随 302，必须关掉才看得到真正的响应）
        // 测试 client 会自动跟随 302，所以看最终落地的页面内容：应该是课表而不是视频首页
        val page = client.get("/resources/video/index.html")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("课表"), "video page should bounce to the timetable, got: ${html.take(80)}")
        assertFalse(html.contains("vcard"), "should not serve the video index page")

        // 接口：403 且是 JSON 错误，不是空响应
        val api = client.get("/recommend")
        assertEquals(HttpStatusCode.Forbidden, api.status)
        assertTrue(api.bodyAsText().contains("正课"), "should explain why, got: ${api.bodyAsText()}")

        assertEquals(HttpStatusCode.Forbidden, client.get("/biliImg").status)

        // 其它模块不受影响
        assertEquals(HttpStatusCode.OK, client.get("/whoami").status)
    }

    @Test
    fun `everything passes while open`() = testApplication {
        application {
            routing {
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
                get("/recommend") { call.respondText("handler") }
            }
        }

        assertEquals("handler", client.get("/recommend").bodyAsText())
    }

    @Test
    fun `status route stays reachable while closed`() = testApplication {
        VideoGate.open = false
        application {
            routing {
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
            }
        }

        val res = client.get("/videoStatus")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"open":false}""", res.bodyAsText())
    }

    @Test
    fun `status route reports open`() = testApplication {
        application {
            routing {
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
            }
        }

        assertEquals("""{"open":true}""", client.get("/videoStatus").bodyAsText())
    }

    @Test
    fun `control clients stay available even while the gate is closed`() {
        // 这个用例要测真实的 Control.allowed 白名单，换回来
        VideoGate.isControl = { Control.allowed(it) }
        VideoGate.open = false
        // 白名单里的是 control 用户（与 /canControl、取流限速同一份）
        assertTrue(VideoGate.availableTo("127.0.0.1"), "localhost is control")
        assertTrue(VideoGate.availableTo("192.168.3.32"), "home lan is control")
        assertTrue(VideoGate.availableTo("192.168.100.7"), "control prefix")
        assertTrue(VideoGate.availableTo("192.168.20.11"), "control prefix")
        // 其它人照旧被挡
        assertFalse(VideoGate.availableTo("192.168.1.50"), "ordinary client")
        assertFalse(VideoGate.availableTo("10.0.0.8"), "ordinary client")
        assertFalse(VideoGate.availableTo("203.0.113.9"), "public client")

        // 开着的时候谁都可用，豁免逻辑不能反过来把别人关掉
        VideoGate.open = true
        assertTrue(VideoGate.availableTo("203.0.113.9"))
    }

    @Test
    fun `status route tells a control client it is open while closed for others`() = testApplication {
        // 测试宿主里 remoteAddress 恒为 localhost，这里让所有来源都算 control
        VideoGate.isControl = { true }
        VideoGate.open = false
        application {
            routing {
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
                get("/recommend") { call.respondText("handler") }
            }
        }

        // 页面与接口都不该被拦（control 豁免）
        assertEquals("handler", client.get("/recommend").bodyAsText())
        assertEquals("""{"open":true}""", client.get("/videoStatus").bodyAsText())
    }

    @Test
    fun `manual open lets ordinary clients in until the period changes`() = testApplication {
        VideoGate.open = false
        application {
            routing {
                with(VideoGate) {
                    blockWhenClosed()
                    videoStatusRoute()
                }
                // 冒充 control 路由，验证放行状态确实被 setManualOpen 改了
                get("/openVideo") { call.respondText(VideoGate.setManualOpen(true).toString()) }
                get("/closeVideo") { call.respondText(VideoGate.setManualOpen(false).toString()) }
                get("/recommend") { call.respondText("handler") }
            }
        }

        // 关闸 + 无手动放行：普通用户被拦
        assertEquals(HttpStatusCode.Forbidden, client.get("/recommend").status)
        assertEquals("""{"open":false}""", client.get("/videoStatus").bodyAsText())

        // 手动放行后普通用户能进，且 /videoStatus 对他也报 open
        assertEquals("true", client.get("/openVideo").bodyAsText())
        assertEquals("handler", client.get("/recommend").bodyAsText())
        assertEquals("""{"open":true}""", client.get("/videoStatus").bodyAsText())

        // 重复放行不算变化
        assertEquals("false", client.get("/openVideo").bodyAsText())

        // 手动收回
        assertEquals("true", client.get("/closeVideo").bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.get("/recommend").status)
        assertEquals("false", client.get("/closeVideo").bodyAsText())
    }

    @Test
    fun `manual open is cleared when the period changes`() {
        // 阶段切换的判定依赖真实时间，这里直接喂阶段值（applyPhase 就是为测试抽出来的）
        VideoGate.open = false

        // 先把"当前阶段"垫定，否则第一次 applyPhase 算首次同步、会当成变化
        VideoGate.applyPhase("class:2026-11-04:3")
        VideoGate.setManualOpen(true)
        assertTrue(VideoGate.availableTo("203.0.113.9"), "manual open should let an ordinary client in")

        // 同一阶段内不动手动开关
        VideoGate.applyPhase("class:2026-11-04:3")
        assertTrue(VideoGate.manualOpen, "same period must keep the manual open")

        // 换到下一节课：自动收回
        VideoGate.applyPhase("class:2026-11-04:4")
        assertFalse(VideoGate.manualOpen, "changing period should drop the manual open")
        assertFalse(VideoGate.availableTo("203.0.113.9"), "ordinary client is blocked again after the period ends")
    }

    @Test
    fun `only video paths are in the block list`() {
        // 视频相关
        assertTrue(VideoGate.blocks("/video"))
        assertTrue(VideoGate.blocks("/recommend"))
        assertTrue(VideoGate.blocks("/searchByType"))
        assertTrue(VideoGate.blocks("/searchUser"))
        assertTrue(VideoGate.blocks("/videoInfo"))
        assertTrue(VideoGate.blocks("/videoStream"))
        assertTrue(VideoGate.blocks("/videoStreamInfo"))
        assertTrue(VideoGate.blocks("/videoComments"))
        assertTrue(VideoGate.blocks("/userInfo"))
        assertTrue(VideoGate.blocks("/userSpace"))
        assertTrue(VideoGate.blocks("/userDynamic"))
        assertTrue(VideoGate.blocks("/biliImg"))
        assertTrue(VideoGate.blocks("/resources/video/watch.html"))
        assertTrue(VideoGate.blocks("/resources/video/common.js"))

        // 前端要靠它得知已关闭，绝不能被挡
        assertFalse(VideoGate.blocks("/videoStatus"))

        // 其它模块
        assertFalse(VideoGate.blocks("/"))
        assertFalse(VideoGate.blocks("/chat"))
        assertFalse(VideoGate.blocks("/message"))
        assertFalse(VideoGate.blocks("/resources/timetable.html"))
        assertFalse(VideoGate.blocks("/resources/chat.html"))
        assertFalse(VideoGate.blocks("/timetable"))
        assertFalse(VideoGate.blocks("/timetable/now"))
        assertFalse(VideoGate.blocks("/img/x.png"))
        assertFalse(VideoGate.blocks("/savePicture"))
    }
}
