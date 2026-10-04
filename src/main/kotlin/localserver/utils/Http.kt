package localserver.utils

// JDK 自带的 java.net.http.HttpClient，供需要"上游取流代理"的路由复用。
//
// 实测同一路流、同一 Range 连续请求，Ktor CIO 每次都要 ~140-190ms
// （连接未被复用，每次都重新握手），而 JDK 客户端首次 303ms、之后稳定 25-29ms。
// 视频流拖动进度条时每个 Range 都是一次新请求，这个差距直接决定拖动是否跟手，
// 故流代理类路由统一走这里（普通 JSON API 仍用 CIO 版 HttpClient 即可）。
object Http {
    val jdk: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()
    }

    /** 已建立的上游响应：状态码 + 响应头 + 待读的响应体 */
    class Upstream(
        val status: Int,
        val headers: java.net.http.HttpHeaders,
        val body: java.io.InputStream
    ) {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }

    /**
     * 向上游发起请求并拿到响应头。阻塞调用放到 IO 线程池执行。
     * 不设置请求级超时，避免把长时间的视频流掐断（连接超时由 connectTimeout 兜底）。
     */
    suspend fun openUpstream(
        url: String,
        range: String? = null,
        ua: String = "Mozilla/5.0",
        referer: String? = null,
        origin: String? = null
    ): Upstream? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("user-agent", ua)
                    .GET()
                referer?.let { builder.header("referer", it) }
                origin?.let { builder.header("origin", it) }
                range?.let { builder.header("Range", it) }
                jdk.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                    .let { Upstream(it.statusCode(), it.headers(), it.body()) }
            }.onFailure { Logger.debug("openUpstream failed: ${it.message}") }.getOrNull()
        }
}
