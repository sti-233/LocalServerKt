package localserver.module

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.CacheControl
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 静态资源的缓存头与条件请求。
 *
 * 背景：这些资源此前既没有 Cache-Control 也没人处理版本头，浏览器每次进页面都要重下
 * 全部 JS/CSS（实测聊天页每轮固定重下约 48KB）。这里把行为固定下来，避免以后又被改回去。
 */
class StaticCacheTest {

    /** 与 Server.kt 里的配置保持一致；改那边记得同步这里。 */
    private fun io.ktor.server.routing.Route.staticWithCache() {
        install(ConditionalHeaders)
        staticResources("/resources", "") {
            cacheControl { resource ->
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
    }

    @Test
    fun `javascript and css are cached as long as images`() = testApplication {
        application { routing { staticWithCache() } }

        // JS/CSS 曾经只给 5 小时以防「新 HTML + 旧 JS」，现在改成 7 天：那个窗口由设置页的
        // 「强制刷新 JS/CSS」按钮兜底（fetch cache:'reload' 无条件回源，与 max-age 无关）。
        for (path in listOf("/resources/lib/theme.css", "/resources/lib/lightbox.js")) {
            val res = client.get(path)
            assertEquals(HttpStatusCode.OK, res.status, "expected 200 for $path")

            val cacheControl = res.headers[HttpHeaders.CacheControl]
            assertNotNull(cacheControl, "$path should carry Cache-Control")
            assertTrue(
                cacheControl.contains("max-age=604800"),
                "$path should be cached for a week, got: $cacheControl"
            )
            assertTrue(
                cacheControl.contains("must-revalidate"),
                "$path must revalidate once stale, got: $cacheControl"
            )

            assertNotNull(
                res.headers[HttpHeaders.LastModified],
                "$path should carry Last-Modified, otherwise the browser can never revalidate"
            )
        }
    }

    @Test
    fun `html is revalidated every time instead of being cached blind`() = testApplication {
        application { routing { staticWithCache() } }

        val res = client.get("/resources/chat.html")
        assertEquals(HttpStatusCode.OK, res.status)

        val cacheControl = res.headers[HttpHeaders.CacheControl].orEmpty()
        assertTrue(
            cacheControl.contains("no-cache"),
            "html must be revalidated so editing a page shows up on refresh, got: $cacheControl"
        )
        assertTrue(
            !cacheControl.contains("max-age") || cacheControl.contains("no-cache"),
            "html should not be blindly cached, got: $cacheControl"
        )
    }

    @Test
    fun `an unchanged resource answers 304 instead of resending the body`() = testApplication {
        application { routing { staticWithCache() } }

        val first = client.get("/resources/lib/theme.css")
        val lastModified = first.headers[HttpHeaders.LastModified]
        assertNotNull(lastModified, "first response must carry Last-Modified for this test to mean anything")
        assertTrue(first.bodyAsText().isNotEmpty(), "first response should carry the body")

        val second = client.get("/resources/lib/theme.css") {
            header(HttpHeaders.IfModifiedSince, lastModified)
        }
        assertEquals(
            HttpStatusCode.NotModified,
            second.status,
            "with If-Modified-Since matching Last-Modified the server must answer 304"
        )
        assertTrue(
            second.bodyAsText().isEmpty(),
            "a 304 must not carry a body, otherwise the client re-downloads it anyway"
        )
    }

    @Test
    fun `an older If-Modified-Since still gets the body`() = testApplication {
        application { routing { staticWithCache() } }

        val res = client.get("/resources/lib/theme.css") {
            header(HttpHeaders.IfModifiedSince, "Wed, 21 Oct 2015 07:28:00 GMT")
        }
        assertEquals(HttpStatusCode.OK, res.status, "a stale If-Modified-Since must not short circuit the body")
        assertTrue(res.bodyAsText().isNotEmpty())
    }

    @Test
    fun `images get the same long max age so a second load costs nothing`() = testApplication {
        application { routing { staticWithCache() } }

        // environment.html 的「图片二次加载走缓存」探测依赖这条：图片若仍走 no-cache，
        // 第二次只会拿到 304，transferSize 不为 0，探测就会误报「强缓存没生效」。
        val res = client.get("/resources/lib/img/probe.png")
        assertEquals(HttpStatusCode.OK, res.status)

        val cacheControl = res.headers[HttpHeaders.CacheControl].orEmpty()
        assertTrue(
            cacheControl.contains("max-age=604800"),
            "images should be strongly cacheable, got: $cacheControl"
        )
        assertTrue(
            !cacheControl.contains("no-cache") && !cacheControl.contains("no-store"),
            "images must not be revalidated on every load, got: $cacheControl"
        )
        assertNotNull(res.headers[HttpHeaders.LastModified], "images still need Last-Modified for revalidation")
    }
}
