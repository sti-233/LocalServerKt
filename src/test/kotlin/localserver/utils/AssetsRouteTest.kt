package localserver.utils

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api/assets` 是设置页「强制刷新 JS/CSS」用的清单。
 *
 * 它存在的理由：`.js`/`.css` 走 `max-age` 长缓存，服务端改配置对已缓存的那份没有追溯力，
 * 只能让客户端逐个回源。清单必须由服务端枚举 —— 前端写死一份，以后新增的文件一定会漏，
 * 而漏掉的那个恰好就是下次报 `ReferenceError` 的那个（snapSet 事故就是这么来的）。
 *
 * 所以这里除了验内容，还要**逐个请求一遍**：清单里的路径必须真的能取到，
 * 否则按钮点了会静默少刷几个文件。
 */
class AssetsRouteTest {

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            routing {
                staticResources("/resources", "")
                with(Network) { network() }
            }
        }
        block(client)
    }

    private suspend fun fetchAssets(client: io.ktor.client.HttpClient): List<String> {
        val resp = client.get("/api/assets")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        return Regex("\"([^\"]+\\.(?:js|css))\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `asset list covers every directory that ships scripts`() = withApp { client ->
        val assets = fetchAssets(client)
        assertTrue(assets.isNotEmpty(), "expected a non-empty asset list")

        // 三个目录各有代表，漏掉整个目录会表现为「那一个模块怎么刷都是旧的」
        for (expected in listOf("lib/theme.js", "lib/lightbox.js", "lib/idb-store.js", "video/common.js", "play/play.js")) {
            assertTrue(expected in assets, "missing $expected in $assets")
        }
        // 只有 js/css 需要手动回源，html/json 本来就每次验证
        assertTrue(
            assets.none { it.endsWith(".html") || it.endsWith(".json") || it.endsWith(".md") },
            "only js/css belong here, got: $assets"
        )
    }

    @Test
    fun `every listed asset is actually fetchable`() = withApp { client ->
        val assets = fetchAssets(client)
        val broken = mutableListOf<String>()
        for (rel in assets) {
            val resp = client.get("/resources/$rel")
            if (resp.status != HttpStatusCode.OK) broken += "$rel → ${resp.status}"
        }
        assertTrue(broken.isEmpty(), "listed but not served: $broken")
    }

    @Test
    fun `asset list itself is never cached`() = withApp { client ->
        // 清单会随源码增删变化，若浏览器缓存它，新加的 js 就永远刷不到
        val resp = client.get("/api/assets")
        assertEquals("no-store", resp.headers["Cache-Control"])
        assertTrue(
            resp.headers["Content-Type"].orEmpty().contains("json"),
            "unexpected content type: ${resp.headers["Content-Type"]}"
        )
    }
}
