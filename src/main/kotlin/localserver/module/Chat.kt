package localserver.module

import localserver.types.Content
import localserver.types.Message
import localserver.utils.AiHistory
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

    // 聊天图片落盘根目录。当前布局：cache/picture/<用户名>/<sha256>.<ext>
    // —— 每个用户只在自己的子目录里存取，/pictures 与 /savePicture 都按当前 IP 对应的用户名分目录，
    //    所以「从缓存里选图」只会看到自己保存过的图。
    // 历史布局：更早直接摊在 cache/ 根下，后来收进 cache/picture/ 根（不区分用户）。
    // 存量文件一律不搬，/img/ 仍会从这两个旧位置兜底取到，
    // 因此老存档里的 ImageRef:/img/<sha256>.<ext> 不会裂图。
    const val PICTURE_DIR = "cache/picture"

    // 图片文件名：64 位十六进制 + 短扩展名。既做 /img/ 的防穿越校验，也做迁移时的过滤条件
    private val IMAGE_NAME = Regex("^[a-f0-9]{64}\\.[a-z]{1,10}$")

    // 允许保存（与 /img 能提供的）图片子类型；不含 svg+xml 等可执行脚本的类型
    private val PICTURE_SUBTYPES = setOf("png", "jpeg", "webp", "gif", "bmp", "avif")

    private const val MAX_PICTURE_BYTES = 10 * 1024 * 1024

    // 当前请求对应的用户名，用作 cache/picture/<用户名>/ 的目录名。
    // 与 Ai / Prefs 用同一套净化规则（AiHistory.sanitizeUser）：userList.json 里有登记就用真名，
    // 没登记（例如开发机 127.0.0.1）退化为 ip_<地址>，这样不同 IP 的图不会混在一起，
    // 也保证中文用户名能安全当目录名（非法字符替换为下划线、去掉路径穿越片段）。
    private fun pictureUserOf(ip: String): String {
        val name = try {
            Util.getUserName(ip)
        } catch (e: Exception) {
            null
        }
        return AiHistory.sanitizeUser(name?.takeIf { it.isNotBlank() } ?: ("ip_" + ip))
    }

    // 某个用户的图片目录：cache/picture/<用户名>/
    private fun userPictureDir(user: String): File =
        File(PICTURE_DIR, user).apply { if (!exists()) mkdirs() }

    /**
     * 按文件名找图片，找不到返回 null。
     *
     * 顺序：cache/picture/<用户名>/ 各子目录 → cache/picture/ 根（旧版不区分用户时存的）
     * → cache/ 根（更早的布局）。文件名是内容哈希，同一份内容落在哪个目录都一样，
     * 所以老存档里的 ImageRef 不会因为这次改成按用户分目录而裂图。
     */
    private fun findPicture(name: String): File? {
        val root = File(PICTURE_DIR)
        File(root, name).takeIf { it.isFile }?.let { return it }
        root.listFiles()?.forEach { sub ->
            if (sub.isDirectory) File(sub, name).takeIf { it.isFile }?.let { return it }
        }
        return File("cache", name).takeIf { it.isFile }
    }

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
        deletePicture()
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

    // 图片懒加载端点：/img/<sha256>.<ext>
    // 路径必须严格匹配 64 位十六进制 + 短扩展名，防路径穿越。
    // 图片现在按用户分目录存（cache/picture/<用户名>/），但 /img 是公开端点、URL 里也不带用户，
    // 所以按文件名在各子目录里找（见 findPicture），老位置 cache/picture/ 与 cache/ 也会兜底
    private fun Route.imageCache() = get("/img/{*}") {
        // 尾段参数名即 "*"（路径里 /img/ 后的整段）
        val name = call.parameters["*"].orEmpty()
        if (!name.matches(IMAGE_NAME)) {
            call.respondText("image not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val file = findPicture(name)
        if (file == null) {
            call.respondText("image not found", status = HttpStatusCode.NotFound)
            return@get
        }
        // 文件名是内容寻址的（sha256），同一 URL 的字节永不改变，所以可以放心长缓存。
        // 不加这条时 Ktor 的 respondFile 只回 Content-Type/Content-Length，浏览器拿不到
        // Cache-Control/ETag/Last-Modified 就认为不可缓存 —— 列表里缩略图已经下过一遍的字节
        // 全白费，点开全屏查看器（新 <img> 用同一个 src）还得整张重新下。
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        // respondFile 默认按文件扩展名（.png/.jpeg/.webp/.gif）推断 Content-Type，
        // Ktor 内置 mime 映射能正确识别这些图片类型，无需手动设
        call.respondFile(file)
    }

    // 图片列表端点：GET /pictures?limit=60 → 当前用户自己目录（cache/picture/<用户名>/）下的图片，
    // 供聊天页"从缓存中选取"用。只列自己的：每个人只能看到/发送自己保存过的图。
    // 按修改时间倒序（新存的排前面，用户刚保存的图一打开就能看到）；
    // 只返回名字与元数据，缩略图仍走 /img/<name> 由浏览器缓存，避免把 base64 塞进 JSON。
    private fun Route.pictures() = get("/pictures") {
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 60).coerceIn(1, 200)
        val user = pictureUserOf(call.request.local.remoteAddress)
        val dir = userPictureDir(user)
        val files = dir
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
                // 回给前端的 dir 是相对路径（不含绝对盘符），仅用于提示文案
                put("dir", JsonPrimitive(PICTURE_DIR + "/" + user))
                put("user", JsonPrimitive(user))
                put("total", JsonPrimitive(files.size))
                put("items", Json.encodeToJsonElement(items))
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }

    // 保存图片：全屏查看器「保存」按钮的落盘端点。POST JSON {"data":"data:image/png;base64,..."}
    // 前端把当前查看的图片读成 dataURL 后发来（聊天图与 B 站代理图同一条路径），
    // 复用 extractAndCache 的 mime 白名单 + SHA-256 去重，落到 cache/picture/<用户名>/<sha256>.<ext>；
    // 该图若本来就来自缓存，哈希相同 → 直接命中已存在的文件，不产生副本。
    // 鉴权与 /img/、WS /message 保持一致（不额外加域）：只收白名单图片类型、单张 ≤10MB，
    // 且目录名由当前 IP 推导、文件名完全由内容哈希决定，请求无法控制落盘路径。
    private fun Route.savePicture() = post("/savePicture") {
        val user = pictureUserOf(call.request.local.remoteAddress)
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
        val saved = savePictureData(dataUrl, user)
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
                put("dir", JsonPrimitive(PICTURE_DIR + "/" + user))
                put("user", JsonPrimitive(user))
                put("existed", JsonPrimitive(saved.existed))
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }

    // 删除图片：图片选择面板里点「删除」时调用。POST JSON {"name":"<sha256>.<ext>"}
    // 只删当前用户自己目录里的文件 —— 别人目录里即使有同名文件也不碰（文件名是内容哈希，
    // 同一份内容可能被多个人各自保存过，删自己的那份不该影响别人）。
    // 用 POST 而非 GET：这是有副作用的操作，避免被预取/图片标签顺手触发。
    private fun Route.deletePicture() = post("/deletePicture") {
        val user = pictureUserOf(call.request.local.remoteAddress)
        val body = call.receiveText()
        val name = runCatching {
            Json.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.content
        }.getOrNull()
        // 文件名必须符合内容寻址格式，顺带挡掉 ../ 之类的路径穿越
        if (name.isNullOrEmpty() || !IMAGE_NAME.matches(name)) {
            call.respondText(
                """{"ok":false,"error":"图片名不合法"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        val file = File(userPictureDir(user), name)
        if (!file.isFile) {
            call.respondText(
                """{"ok":false,"error":"图片不存在（可能已被删除）"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.NotFound
            )
            return@post
        }
        val deleted = file.delete()
        Logger.debug("deletePicture: $user/$name deleted=$deleted")
        call.respondText(
            buildJsonObject {
                put("ok", JsonPrimitive(deleted))
                put("name", JsonPrimitive(name))
                put("user", JsonPrimitive(user))
                // 删失败（文件被占用等）时给个原因，前端好提示
                if (!deleted) put("error", JsonPrimitive("文件被占用，删除失败"))
            }.toString(),
            contentType = ContentType.Application.Json,
            status = if (deleted) HttpStatusCode.OK else HttpStatusCode.InternalServerError
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

    // 解码 dataURL → 落盘（去重），返回 "ImageRef:/img/<sha256>.<ext>"；
    // 格式不合法或 mime 子类型不在白名单（svg 之类可执行脚本的）返回 null。
    // user 为 null 时落到 cache/picture/ 根（迁移旧存档用：那些图不属于任何单个用户，
    // 留在根上由 findPicture 兜底提供，也不会出现在任何人的"从缓存选取"列表里）
    private fun extractAndCache(dataUrl: String, user: String? = null): String? =
        savePictureData(dataUrl, user)?.ref

    /** 落盘结果：ref 为存档/广播用的短引用，existed 表示文件此前就存在（命中已有缓存） */
    private class PictureSaved(val ref: String, val existed: Boolean)

    // 解析 dataURL 并落盘，返回引用与"是否本就存在"；格式/mime/大小不合规返回 null
    private fun savePictureData(dataUrl: String, user: String? = null): PictureSaved? {
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
        val (name, existed) = storePicture(bytes, ext, user)
        return PictureSaved("ImageRef:/img/$name", existed)
    }

    // 落盘图片字节到 <目录>/<sha256>.<ext>（同名即去重），返回 "<sha256>.<ext>" 与是否本就存在。
    // user 非空时目录是 cache/picture/<用户名>/，否则是 cache/picture/ 根（迁移用）。
    // 文件名只由内容哈希决定，调用方无法影响路径
    private fun storePicture(bytes: ByteArray, ext: String, user: String? = null): Pair<String, Boolean> {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val dir = if (user == null) File(PICTURE_DIR).apply { if (!exists()) mkdirs() } else userPictureDir(user)
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
                                // SHA-256 落盘到自己的目录（cache/picture/<用户名>/），天然去重；
                                // 扩展名取自 mime 子类型（image/png → png）
                                val name = storePicture(bytes, ext, pictureUserOf(clientId)).first
                                imageRef = "ImageRef:/img/$name"
                            } else if (json.text.startsWith("ImageRef:")) {
                                // 从自己的图片目录里挑一张已存在的图发出去：
                                // 前端选的本来就是服务端已有的文件，不必再走一遍 base64 上传，
                                // 只校验名字合法（防穿越）且文件真在自己目录里，然后原样转发引用。
                                // 只允许发自己的图 —— 别人的图连名字都不该被拿来转发。
                                val name = json.text.removePrefix("ImageRef:").substringAfterLast('/')
                                val cached = IMAGE_NAME.matches(name) &&
                                    File(userPictureDir(pictureUserOf(clientId)), name).isFile
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
                                // sendTo 非空时已确认存在于用户列表。
                                // 自己发给自己时 clients[自己的 IP] 就是 this（同一个 WS 会话），
                                // 用 !== 挡掉重复推送，否则前端同一条消息会显示两遍。
                                val peer = clients[Util.getUserIp(json.sendTo)]
                                if (peer != null && peer !== this) peer.send(response)
                                this.send(response)
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
                                // sendTo 非空时已确认存在于用户列表；自己发给自己时同一个会话只推一次
                                val peer = clients[Util.getUserIp(json.sendTo)]
                                if (peer != null && peer !== this) peer.send(response)
                                this.send(response)
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
