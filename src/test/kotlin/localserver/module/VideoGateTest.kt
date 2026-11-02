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
    }

    @AfterTest
    fun closeDown() {
        VideoGate.open = true
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
