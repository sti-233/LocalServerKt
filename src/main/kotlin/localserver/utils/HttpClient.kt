package localserver.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

object HttpClient {
    /** 共享的 Ktor CIO 客户端：所有普通 GET 复用同一实例（连接池复用），勿逐请求新建 */
    val client = HttpClient(CIO)

    /** 长流式（SSE）专用客户端：10 分钟请求超时 + SSE 插件（AI 模块上游转发用） */
    val sse: io.ktor.client.HttpClient = io.ktor.client.HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000L
            connectTimeoutMillis = 30_000L
        }
        install(SSE)
    }

    suspend fun get(url: String, parameter: Map<String, Any>? = null, header: Map<String, Any>? = null): String {
        return try {
                    client.get(url) {
                        parameter?.forEach { (key, value) -> parameter(key, value) }
                        header?.forEach { (key, value) -> header(key, value) }
                    }.bodyAsText()
                } catch (e: Exception) {
                    ""
                }
    }

    suspend inline fun <reified T> getAs(url: String, parameter: Map<String, Any>? = null, header: Map<String, Any>? = null): T {
        return Util.prettyJson.decodeFromString<T>(get(url, parameter, header))
    }
}