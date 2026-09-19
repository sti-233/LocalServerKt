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
            call.respondRedirect("/resources/browser.html")
        }
    }

    private fun Route.download() = get("/download") {
        val fileUrl = call.request.queryParameters["url"]
            ?: return@get call.respondText("Please provide URL parameter", status = HttpStatusCode.BadRequest)
        val inline = call.request.queryParameters["inline"] == "1"
        val client = HttpClient(CIO)
        val tempFile: Path = Files.createTempFile("download_", ".tmp")
        try {
            val response = client.get(fileUrl)
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.bodyAsBytes()
                val originalFileName = extractFileName(fileUrl, response)
                val contentType = response.headers[HttpHeaders.ContentType]
                    ?: when {
                        originalFileName.endsWith(".css") -> "text/css"
                        originalFileName.endsWith(".js") -> "application/javascript"
                        originalFileName.endsWith(".html") -> "text/html"
                        else -> "application/octet-stream"
                    }
                var body = bytes
                // Rewrite relative URLs in CSS/HTML to absolute CDN URLs
                if (inline && (contentType == "text/css" || contentType == "text/html" || contentType.contains("css") || contentType.contains("html"))) {
                    val text = bytes.toString(Charsets.UTF_8)
                    // Handle url() patterns in CSS: url(path), url('path'), url("path")
                    val rewritten = text.replace(Regex("""url\(\s*['"]?([^)'"\s]+)['"]?\s*\)""")) { match ->
                        val relPath = match.groupValues[1]
                        if (relPath.startsWith("http://") || relPath.startsWith("https://") || relPath.startsWith("data:")) {
                            match.value // Already absolute, skip
                        } else {
                            val absUrl = resolveRelativeUrl(fileUrl, relPath)
                            "url($absUrl)"
                        }
                    }
                    // Handle src="" and href="" attributes (only relative paths)
                    val fullyRewritten = rewritten.replace(Regex("""(\s(?:src|href)\s*=\s*['"])(\.\.?/[^'"]+)['"]""")) { match ->
                        val relPath = match.groupValues[2]
                        if (relPath.startsWith("http://") || relPath.startsWith("https://") || relPath.startsWith("data:")) {
                            match.value
                        } else {
                            val absUrl = resolveRelativeUrl(fileUrl, relPath)
                            "${match.groupValues[1]}$absUrl'"
                        }
                    }
                    body = fullyRewritten.toByteArray(Charsets.UTF_8)
                }
                Files.write(tempFile, body, StandardOpenOption.WRITE)
                call.response.headers.apply {
                    append(HttpHeaders.ContentDisposition,
                        if (inline) "inline; filename=\"$originalFileName\""
                        else "attachment; filename=\"$originalFileName\"")
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    append(HttpHeaders.ContentType, contentType)
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

    private fun resolveRelativeUrl(base: String, relative: String): String {
        val baseUri = java.net.URI(base)
        val resolved = baseUri.resolve(relative)
        return resolved.toString()
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