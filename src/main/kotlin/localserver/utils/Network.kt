package localserver.utils

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object Network {
    fun Route.network() {
        download()
        browser()
    }

    private fun Route.browser() = authenticate("auth") {
        get("/") {
            call.respondRedirect("/resources/browser.html")
        }
        get("/client-lzysso/h5-sso") {
            val headers = mutableListOf<Pair<String, List<String>?>>()
            val header = call.request.headers
            header.names().forEach {
                headers.add(it to header.getAll(it))
            }
            Logger.debug("""
                |parameters ${call.request.queryParameters.entries()}
                |headers $headers
                |cookies ${call.request.cookies.rawCookies}
            """.trimMargin())
            call.respondRedirect("/resources/browser.html")
        }
    }

    private fun Route.download() = get("/download") {
        val fileUrl = call.request.queryParameters["url"]
            ?: return@get call.respondText("Please provide URL parameter", status = HttpStatusCode.BadRequest)
        println(fileUrl)
        val client = HttpClient(CIO)
        val tempFile: Path = Files.createTempFile("download_", ".tmp")
        try {
            val response = client.get(fileUrl)
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.bodyAsBytes()
                Files.write(tempFile, bytes, StandardOpenOption.WRITE)
                val originalFileName = extractFileName(fileUrl, response)
                call.response.headers.apply {
                    append(HttpHeaders.ContentDisposition, 
                        "attachment; filename=\"$originalFileName\"")
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                }
                call.respondFile(tempFile.toFile())
            } else {
                call.respondText("Failed to download: ${response.status}", 
                                status = HttpStatusCode.InternalServerError)
            }
        } catch (e: Exception) {
            call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
        } finally {
            client.close()
            Files.deleteIfExists(tempFile)

        }
    }

    private fun extractFileName(url: String, response: HttpResponse): String {
        val contentDisposition = response.headers[HttpHeaders.ContentDisposition]
        if (contentDisposition != null) {
            val regex = "filename=\"?(.*?)\"?[;\\s]".toRegex()
            regex.find(contentDisposition)?.let {
                return it.groupValues[1]
            }
        }
        val fromUrl = url.substringAfterLast("/").substringBefore("?")
        if (fromUrl.isNotBlank()) {
            return fromUrl
        }
        return "downloaded_file"
    }
}