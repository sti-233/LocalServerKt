package localserver.module

import localserver.types.Content
import localserver.types.Message
import localserver.utils.Logger
import localserver.utils.Time
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object Chat {
    private val clients = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    // 聊天图片落盘目录：cache/picture/<sha256>.<ext>
    // 以前直接摊在 cache/ 根下（与 ai/、prefs/ 等子目录混在一起），现统一收进 picture/；
    // 存量文件由 GET /migratePictures 一次性搬过来（存档里的 ImageRef 路径不变，仍走 /img/）
    const val PICTURE_DIR = "cache/picture"

    // 图片文件名：64 位十六进制 + 短扩展名。既做 /img/ 的防穿越校验，也做迁移时的过滤条件
    private val IMAGE_NAME = Regex("^[a-f0-9]{64}\\.[a-z]{1,10}$")

    // 允许保存（与 /img 能提供的）图片子类型；不含 svg+xml 等可执行脚本的类型
    private val PICTURE_SUBTYPES = setOf("png", "jpeg", "webp", "gif", "bmp", "avif")

    private const val MAX_PICTURE_BYTES = 10 * 1024 * 1024

    // dataURL 的 base64 比原始字节大 4/3，再加上 data:image/...;base64, 前缀与 JSON 外壳的余量。
    // 用于在解析前先挡掉超大请求体（避免把几十 MB 的 body 读进内存再判大小）
    private const val MAX_PICTURE_B64_BYTES = MAX_PICTURE_BYTES / 3 * 4 + 4096

    fun Route.chatRoute() {
        chatPage()
        history()
        conversations()
        message()
        imageCache()
        pictures()
        savePicture()
        migrateImages()
        migratePictures()

        websocket()
        clients()
        whoami()
    }

    private fun Route.chatPage() = authenticate("auth") {
        get("/chat") {
            call.respondRedirect("/resources/chat.html")
        }
    }

    private fun Route.history() = get("/history") {
        val targetUser = call.parameters["targetuser"]
        val date = call.parameters["date"]?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        val current = Util.getUserName(call.request.local.remoteAddress)
        if (!targetUser.isNullOrEmpty() && !Util.userExists(targetUser)) {
            call.respondText("私聊对象 '$targetUser' 不存在于用户列表，请确认名字正确或先添加该用户", status = HttpStatusCode.BadRequest)
            return@get
        }
        val target = if (!targetUser.isNullOrEmpty()) Util.getTarget(current, targetUser) else date
        call.respondText(Util.getHistory(target))
    }

    // 会话列表：扫描 message/ 下的 history_*.json，返回 {today, names, last} JSON 对象：
    // names 为文件名（不含前缀与扩展名），供 chat.html 侧边栏区分群聊（yyyy-MM-dd）与私聊
    // （"名A-名B"，getTarget 排序后命名）；today 为服务端今天（Asia/Shanghai）；
    // last 为各会话最后一条消息的 {time, by}，供前端按"最后消息晚于已读时间且非自己/系统发送"点亮未读小红点
    private fun Route.conversations() = get("/conversations") {
        val files = File("message")
            .listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }
            .orEmpty()
        val names = files.map { it.name.removePrefix("history_").removeSuffix(".json") }.sorted()
        val last = buildMap {
            for (f in files) {
                put(f.name.removePrefix("history_").removeSuffix(".json"), f.lastMessage())
            }
        }
        call.respondText(Json.encodeToString(buildJsonObject {
            put("today", Time.getCurrentDate())
            put("names", Json.encodeToJsonElement(names))
            put("last", Json.encodeToJsonElement(last))
        }))
    }

    // 读 history 文件最后一条消息（time 与发送者 name）；文件损坏/为空返回 null（前端视为无未读）
    private fun File.lastMessage(): JsonObject? = try {
        val list = Json.decodeFromString<MutableList<Message>>(readText().trimStart('﻿'))
        val m = list.lastOrNull() ?: return null
        buildJsonObject {
            put("time", JsonPrimitive(m.time))
            put("by", JsonPrimitive(m.name))
        }
    } catch (e: Exception) {
        Logger.error("Failed to parse ${name}: ${e.message}")
        null
    }
    
    private fun Route.whoami() = authenticate("auth") {
        get("/whoami") {
            call.respondText(Util.getUserName(call.request.local.remoteAddress))
        }
    }

    // 图片懒加载端点：/img/<sha256>.<ext> → cache/picture/ 目录下的文件
    // 路径必须严格匹配 64 位十六进制 + 短扩展名，防路径穿越；respondFile 自带 ETag/Range
    private fun Route.imageCache() = get("/img/{*}") {
        // 尾段参数名即 "*"（路径里 /img/ 后的整段）
        val name = call.parameters["*"].orEmpty()
        if (!name.matches(IMAGE_NAME)) {
            call.respondText("image not found", status = HttpStatusCode.NotFound)
            return@get
        }
        // 先找新目录；找不到再回落到旧的 cache/ 根目录，
        // 这样还没跑 /migratePictures 的老存档也不会一下子全是裂图
        val file = File(PICTURE_DIR, name).takeIf { it.exists() } ?: File("cache", name)
        if (!file.exists()) {
            call.respondText("image not found", status = HttpStatusCode.NotFound)
            return@get
        }
        // respondFile 默认按文件扩展名（.png/.jpeg/.webp/.gif）推断 Content-Type，
        // Ktor 内置 mime 映射能正确识别这些图片类型，无需手动设
        call.respondFile(file)
    }

    // 图片列表端点：GET /pictures?limit=60 → cache/picture/ 下的图片，供聊天页"从缓存中选取"用。
    // 按修改时间倒序（新存的排前面，用户刚保存的图一打开就能看到）；
    // 只返回名字与元数据，缩略图仍走 /img/<name> 由浏览器缓存，避免把 base64 塞进 JSON。
    private fun Route.pictures() = get("/pictures") {
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 60).coerceIn(1, 200)
        val files = File(PICTURE_DIR)
            .listFiles { f -> f.isFile && IMAGE_NAME.matches(f.name) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
        val items = files.take(limit).map { f ->
            buildJsonObject {
                put("name", JsonPrimitive(f.name))
                put("ref", JsonPrimitive("/img/${f.name}"))
                put("size", JsonPrimitive(f.length()))
                put("mtime", JsonPrimitive(f.lastModified()))
            }
        }
        call.respondText(
            buildJsonObject {
                put("dir", JsonPrimitive(PICTURE_DIR))
                put("total", JsonPrimitive(files.size))
                put("items", Json.encodeToJsonElement(items))
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }

    // 保存图片：全屏查看器「保存」按钮的落盘端点。POST JSON {"data":"data:image/png;base64,..."}
    // 前端把当前查看的图片读成 dataURL 后发来（聊天图与 B 站代理图同一条路径），
    // 复用 extractAndCache 的 mime 白名单 + SHA-256 去重，落到 cache/picture/<sha256>.<ext>；
    // 该图若本来就来自缓存，哈希相同 → 直接命中已存在的文件，不产生副本。
    // 鉴权与 /img/、WS /message 保持一致（不额外加域）：只收白名单图片类型、单张 ≤10MB，
    // 且文件名完全由内容哈希决定，请求无法控制落盘路径。
    private fun Route.savePicture() = post("/savePicture") {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_PICTURE_B64_BYTES) {
            call.respondText(
                """{"ok":false,"error":"图片过大（上限 10MB）"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.PayloadTooLarge
            )
            return@post
        }
        val body = call.receiveText()
        if (body.length > MAX_PICTURE_B64_BYTES) {
            call.respondText(
                """{"ok":false,"error":"图片过大（上限 10MB）"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.PayloadTooLarge
            )
            return@post
        }
        val dataUrl = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content
        }.getOrNull()
        if (dataUrl.isNullOrEmpty()) {
            call.respondText(
                """{"ok":false,"error":"缺少 data 字段"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        val saved = savePictureData(dataUrl)
        if (saved == null) {
            call.respondText(
                """{"ok":false,"error":"无效图片：需为 data:image/(png|jpeg|webp|gif|bmp|avif);base64,，且不超过 10MB"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        Logger.debug("savePicture: ${saved.ref} (existed=${saved.existed})")
        call.respondText(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("ref", JsonPrimitive(saved.ref))
                put("name", JsonPrimitive(saved.ref.substringAfterLast('/')))
                put("dir", JsonPrimitive(PICTURE_DIR))
                put("existed", JsonPrimitive(saved.existed))
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }

    // 一次性迁移端点（control 鉴权）：/migratePictures
    // 把旧版直接摊在 cache/ 根下的 <sha256>.<ext> 图片搬进 cache/picture/（新目录）。
    // 幂等：新目录已有同名文件时只删旧文件（内容哈希相同必然重复）；非图片文件一概不动。
    private fun Route.migratePictures() = authenticate("control") {
        get("/migratePictures") {
            call.respondText(Json.encodeToString(doMigratePictures()))
        }
    }

    // 迁移实现（供端点调用）：返回 {scanned, moved, deduped, failed}
    private fun doMigratePictures(): Map<String, Int> {
        val target = File(PICTURE_DIR).apply { if (!exists()) mkdirs() }
        val files = File("cache")
            .listFiles { f -> f.isFile && IMAGE_NAME.matches(f.name) }
            .orEmpty()
        var moved = 0
        var deduped = 0
        var failed = 0
        for (f in files) {
            val dest = File(target, f.name)
            try {
                if (dest.exists()) {
                    // 新目录已有同内容文件，旧的那份直接删掉
                    if (f.delete()) deduped++ else failed++
                } else if (f.renameTo(dest)) {
                    moved++
                } else {
                    // 跨卷/占用等导致改名失败时退化为复制 + 删除
                    dest.writeBytes(f.readBytes())
                    if (f.delete()) moved++ else failed++
                }
            } catch (e: Exception) {
                Logger.error("migratePictures failed on ${f.name}: ${e.message}")
                failed++
            }
        }
        return mapOf("scanned" to files.size, "moved" to moved, "deduped" to deduped, "failed" to failed)
    }

    // 一次性迁移端点（control 鉴权）：/migrateImages
    // 扫描 message/history_*.json 里的 ImageB64:dataURL 旧消息，把 base64 图片落盘 cache/picture/<sha256>.<ext>，
    // 并把存档 text 改写为 ImageRef:/img/<sha256>.<ext>。幂等：已 ImageRef: 的不重复处理，
    // 同一 base64 去重只落盘一次；只回写有改动的文件。跑完可不再访问。
    private fun Route.migrateImages() = authenticate("control") {
        get("/migrateImages") {
            val result = doMigrate()
            call.respondText(Json.encodeToString(result))
        }
    }

    // 迁移实现（供端点调用）：返回 {files, migrated} 统计
    private fun doMigrate(): Map<String, Int> {
        val files = File("message")
            .listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }
            .orEmpty()
        var migrated = 0
        for (f in files) {
            val text = f.readText().trimStart('﻿')
            if (text.isBlank()) continue
            val original = Util.prettyJson.decodeFromString<MutableList<Message>>(text)
            var changed = false
            val result = original.map { m ->
                if (m.text.startsWith("ImageB64:")) {
                    extractAndCache(m.text.removePrefix("ImageB64:"))?.let { ref ->
                        changed = true
                        migrated++
                        Message(m.name, m.time, ref)
                    } ?: m // 解析不出 mime 保持原样
                } else m
            }
            if (changed) f.writeText(Util.prettyJson.encodeToString(result))
        }
        return mapOf("files" to files.size, "migrated" to migrated)
    }

    // 解码 dataURL → 落盘 cache/picture/<sha256>.<ext>（去重），返回 "ImageRef:/img/<sha256>.<ext>"；
    // 格式不合法或 mime 子类型不在白名单（svg 之类可执行脚本的）返回 null
    private fun extractAndCache(dataUrl: String): String? =
        savePictureData(dataUrl)?.ref

    /** 落盘结果：ref 为存档/广播用的短引用，existed 表示文件此前就存在（命中已有缓存） */
    private class PictureSaved(val ref: String, val existed: Boolean)

    // 解析 dataURL 并落盘，返回引用与"是否本就存在"；格式/mime/大小不合规返回 null
    private fun savePictureData(dataUrl: String): PictureSaved? {
        val mimeMatch = Regex("^data:([a-z+/]+);base64,([A-Za-z0-9+/=]+)$").find(dataUrl) ?: return null
        val mime = mimeMatch.groupValues[1]
        if (!mime.startsWith("image/")) return null
        val ext = mime.substringAfter('/', "")
        if (ext !in PICTURE_SUBTYPES) return null
        val bytes = try {
            Base64.getDecoder().decode(mimeMatch.groupValues[2])
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > MAX_PICTURE_BYTES) return null
        val (name, existed) = storePicture(bytes, ext)
        return PictureSaved("ImageRef:/img/$name", existed)
    }

    // 落盘图片字节到 cache/picture/<sha256>.<ext>（同名即去重），返回 "<sha256>.<ext>" 与是否本就存在。
    // 文件名只由内容哈希决定，调用方无法影响路径
    private fun storePicture(bytes: ByteArray, ext: String): Pair<String, Boolean> {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val dir = File(PICTURE_DIR).apply { if (!exists()) mkdirs() }
        val file = File(dir, "$hex.$ext")
        val existed = file.exists()
        if (!existed) file.writeBytes(bytes)
        return "$hex.$ext" to existed
    }

    private fun Route.message() = webSocket("/message") {
        val clientId = call.request.local.remoteAddress
        clients[clientId] = this
        
        println("Client $clientId connected. Total clients: ${clients.size}")
        
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val json = Json.decodeFromString<Content>(text)
                        val current = Util.getUserName(clientId)
                        if (json.type == "send") {
                            // 私聊对象不在用户列表时直接回 err，避免 getUserIp 抛异常导致整个连接被断开
                            if (!json.sendTo.isNullOrEmpty() && !Util.userExists(json.sendTo)) {
                                send(Json.encodeToString(
                                    Content("err", current, Time.getCurrentTimeWithDate(),
                                        "私聊对象 '$json.sendTo' 不存在于用户列表，请确认名字正确或先添加该用户",
                                        json.sendTo)
                                ))
                                continue
                            }
                            // 图片消息：ImageB64: 前缀 → 解码 base64 落盘 cache/picture/<sha256>.<ext>，
                            // 存档与广播只用短引用 ImageRef:/img/<sha256>.<ext>（前端经 /img/ 懒加载）
                            var imageRef: String? = null
                            if (json.text.startsWith("ImageB64:")) {
                                val dataUrl = json.text.removePrefix("ImageB64:")
                                val mimeMatch = Regex("^data:([a-z+/]+);base64,([A-Za-z0-9+/=]+)$").find(dataUrl)
                                if (mimeMatch == null) {
                                    send(Json.encodeToString(
                                        Content("err", current, Time.getCurrentTimeWithDate(),
                                            "图片消息无效：需为 data:image/...;base64, 形式",
                                            json.sendTo)
                                    ))
                                    continue
                                }
                                val mime = mimeMatch.groupValues[1]
                                val bytes = try {
                                    Base64.getDecoder().decode(mimeMatch.groupValues[2])
                                } catch (e: Exception) {
                                    null
                                }
                                // 子类型也走白名单：svg 这类能带脚本的类型不进缓存（否则会被 /img 当图片发回去）
                                val ext = mime.substringAfter('/', "")
                                if (bytes == null || bytes.size > MAX_PICTURE_BYTES || ext !in PICTURE_SUBTYPES) {
                                    send(Json.encodeToString(
                                        Content("err", current, Time.getCurrentTimeWithDate(),
                                            "图片消息无效：仅支持 png/jpeg/webp/gif/bmp/avif，且不超过 10MB",
                                            json.sendTo)
                                    ))
                                    continue
                                }
                                // SHA-256 落盘，天然去重；扩展名取自 mime 子类型（image/png → png）
                                val name = storePicture(bytes, ext).first
                                imageRef = "ImageRef:/img/$name"
                            } else if (json.text.startsWith("ImageRef:")) {
                                // 从 cache/picture 里直接挑一张已存在的图发出去：
                                // 前端选的本来就是服务端已有的文件，不必再走一遍 base64 上传，
                                // 只校验名字合法（防穿越）且文件真在，然后原样转发引用
                                val name = json.text.removePrefix("ImageRef:").substringAfterLast('/')
                                val cached = IMAGE_NAME.matches(name) &&
                                    (File(PICTURE_DIR, name).exists() || File("cache", name).exists())
                                if (!cached) {
                                    send(Json.encodeToString(
                                        Content("err", current, Time.getCurrentTimeWithDate(),
                                            "缓存里没有这张图片了，请刷新列表后重选",
                                            json.sendTo)
                                    ))
                                    continue
                                }
                                imageRef = "ImageRef:/img/$name"
                            }
                            val textToSend = imageRef ?: json.text
                            val content = Content("send", current, Time.getCurrentTimeWithDate(), textToSend, json.sendTo)
                            val message = Message(current, Time.getCurrentTimeWithDate(), textToSend)
                            val response = Json.encodeToString(content)
                            if (json.sendTo.isNullOrEmpty()) {
                                clients.forEach { (_, session) ->
                                    session.send(response)
                                }
                                Util.addHistory(message)
                            } else {
                                // sendTo 非空时已确认存在于用户列表
                                listOf(clients[Util.getUserIp(json.sendTo)], this).forEach { session ->
                                    session?.send(response)
                                }
                                Util.addHistory(message, Util.getTarget(current, json.sendTo))
                            }
                        } else if (json.type == "del" && Time.withinTwoMin(json.time)) {
                            if (!json.sendTo.isNullOrEmpty() && !Util.userExists(json.sendTo)) {
                                send(Json.encodeToString(
                                    Content("err", current, Time.getCurrentTimeWithDate(),
                                        "私聊对象 '$json.sendTo' 不存在于用户列表，无法撤回",
                                        json.sendTo)
                                ))
                                continue
                            }
                            val content = Content("del", current, json.time, json.text, json.sendTo)
                            val response = Json.encodeToString(content)
                            if (json.sendTo.isNullOrEmpty()) {
                                clients.forEach { (_, session) ->
                                    session.send(response)
                                }
                                Util.delHistory(current, json.time)
                            } else {
                                // sendTo 非空时已确认存在于用户列表
                                listOf(clients[Util.getUserIp(json.sendTo)], this).forEach { session ->
                                    session?.send(response)
                                }
                                Util.delHistory(current, json.time, Util.getTarget(current, json.sendTo))
                            }
                        }
                    }
                    else -> {
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            println("Client $clientId disconnected")
        } catch (e: Exception) {
            println("Error with client $clientId: ${e.message}")
        } finally {
            clients.remove(clientId)
            println("Client $clientId removed. Total clients: ${clients.size}")
        }
    }

    private fun Route.websocket() = get("/wsSever") {
        call.respondText("""
            WebSocket Chat Server
            Connect to: ws://localhost:1919/ws
            Active clients: ${clients.size}
        """.trimIndent())
    }
    
    private fun Route.clients() = get("/clients") {
        call.respondText("Connected clients: ${clients.keys.joinToString(", ")}")
    }
}
