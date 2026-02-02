package LocalServer.Utils

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
    public var state: Boolean = false

    public fun Route.network() {
        control()
        download()
    }

    private fun Route.control() = authenticate("control") {
        get("/start") {
            if (!state) {
                state = true
                call.respondText("Server started")
            } else {
                call.respondText("Server already started")
            }
        }
        get("/exit") {
            if (state) {
                state = false
                call.respondText("Server stopped")
            } else {
                call.respondText("Server already stopped")
            }
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