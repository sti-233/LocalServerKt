package localserver.module

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File as JFile

object File {
    fun Route.fileRoute() {
        page()
        list()
        content()
    }

    @Serializable
    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long
    )

    @Serializable
    data class Listing(
        val path: String,
        val entries: List<Entry>
    )

    private fun Route.page() = authenticate("control") {
        get("/file") {
            call.respondRedirect("/resources/file.html")
        }
    }

    private fun Route.list() = authenticate("control") {
        get("/listFile") {
            val requested = call.parameters["path"].orEmpty()
            // 未指定路径或 "/" 时返回本地磁盘（驱动器）列表
            val showRoots = requested.isEmpty() || requested == "/"
            val dir = if (showRoots) null else JFile(requested)
            if (dir != null && (!dir.exists() || !dir.isDirectory)) {
                call.respondText("目录不存在或不可访问: $requested", status = HttpStatusCode.BadRequest)
                return@get
            }
            val entries = if (showRoots) {
                JFile.listRoots().map { entryOf(it) }
            } else {
                dir!!.listFiles().orEmpty().map { entryOf(it) }
            }
            // 文件夹排在前面，其余按名称（忽略大小写）排序
            val sorted = entries.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
            call.respondText(
                Json.encodeToString(Listing(dir?.absolutePath ?: "", sorted)),
                contentType = ContentType.Application.Json
            )
        }
    }

    private fun Route.content() = authenticate("control") {
        get("/fileContent") {
            val requested = call.parameters["path"].orEmpty()
            val file = JFile(requested)
            if (!file.exists() || file.isDirectory) {
                call.respondText("文件不存在或不可访问: $requested", status = HttpStatusCode.BadRequest)
                return@get
            }
            // 直接把文件原样返回给客户端，参照 Network.download：先写响应头再返回
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${file.name}\""
            )
            call.respondFile(file)
        }
    }

    private fun entryOf(file: JFile): Entry =
        Entry(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            modified = file.lastModified()
        )
}
