package localserver.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

object HttpClient {
    private val client = HttpClient(CIO)

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