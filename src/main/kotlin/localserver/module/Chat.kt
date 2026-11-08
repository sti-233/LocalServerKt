package localserver.module

import localserver.types.Content
import localserver.types.Message
import localserver.types.ReplyRef
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天模块的路由与会话管理。
 *
 * 图片的落盘/查找/迁移在 [PictureStore]（按用户分目录、内容寻址）。这里负责：
 * - WS 连接表与投递（一个 IP 可以有多条连接）
 * - 消息收发、撤回、历史读写
 * - 图片端点的 HTTP 外壳（`/img`、`/pictures`、`/savePicture`、`/deletePicture`）
 *
 * 鉴权：聊天页、历史读写、图片端点、WS 全在 `auth` 后面（`userList.json` 登记过的 IP，
 * 或 control 白名单，或带 `?p=` 口令）。放在同一层是为了「能进页面」与「能读写数据」用同一套判据 ——
 * 早先只有 `/chat`、`/whoami` 在 auth 后，其余全裸，等于页面白锁。
 * 例外：`/wsSever`、`/clients` 只是裸的服务信息文本，不含任何用户数据（连接数都不带 IP）。
 */
object Chat {

    /**
     * 在线连接。键是「每个 WS 连接一个」的 id，不是 IP。
     *
     * 早先用 IP 当键时，同一个人的第二个标签页会把第一个覆盖掉（第一个从此收不到任何消息），
     * 关掉任一标签页又会把还活着的那条连接从表里摘掉 —— 表现为静默失联直到 ping 超时。
     * 现在 IP 只是连接上的属性，一个 IP 可以有多条连接。
     *
     * 每条连接自带一个出站队列：投递方只入队、不等待，由该连接自己的发送协程按序写出。
     * 这同时解决两件事 —— 慢客户端拖不住别人（早先是 `clients.forEach { it.send() }` 串行 await），
     * 同一条连接上的消息也不会因为并发 send 而乱序（每次 `launch` 一个 send 就有这问题）。
     */
    private class Client(val id: String, val ip: String, val session: DefaultWebSocketServerSession) {
        /** 出站帧。容量给足：图片消息只是短引用，一条几十字节 */
        val outbox = Channel<String>(capacity = OUTBOX_CAPACITY)
    }

    /** 单连接的待发上限。排到这里还发不出去，说明对端已经不读了 */
    private const val OUTBOX_CAPACITY = 512

    private val clients = ConcurrentHashMap<String, Client>()

    /** 某个 IP 当前的所有连接（含自己发给自己时的那条） */
    private fun clientsOf(ip: String): List<Client> = clients.values.filter { it.ip == ip }

    fun Route.chatRoute() {
        chatPage()
        whoami()
        history()
        conversations()
        message()
        image()
        pictures()
        savePicture()
        deletePicture()
        avatars()
        setAvatar()
        deleteAvatar()
        members()
        migrateImages()
        migratePictures()
        serverInfo()
        clientCount()
    }

    // ===== 页面与历史 =====

    private fun Route.chatPage() = authenticate("auth") {
        get("/chat") {
            call.respondRedirect("/resources/chat.html")
        }
    }

    private fun Route.whoami() = authenticate("auth") {
        get("/whoami") {
            call.respondText(Util.getUserName(call.request.local.remoteAddress))
        }
    }

    private fun Route.history() = authenticate("auth") {
        get("/history") {
            val targetUser = call.parameters["targetuser"]
            val date = call.parameters["date"]?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            val current = Util.getUserName(call.request.local.remoteAddress)
            if (!targetUser.isNullOrEmpty() && !Util.userExists(targetUser)) {
                call.respondText(
                    "私聊对象 '$targetUser' 不存在于用户列表，请确认名字正确或先添加该用户",
                    status = HttpStatusCode.BadRequest
                )
                return@get
            }
            val target = if (!targetUser.isNullOrEmpty()) Util.getTarget(current, targetUser) else date
            call.respondText(Util.getHistory(target))
        }
    }

    // 各历史文件的「最后一条消息」缓存：/conversations 会被前端每次 WS 重连调用，
    // 而它原本要把 message/ 下每个文件整份读盘 + 反序列化。归档一天一个文件、只增不减，
    // 久了就是每次重连都解析几十上百个文件。按 (长度, mtime) 命中即可复用。
    private val lastMsgCache = ConcurrentHashMap<String, Pair<Pair<Long, Long>, JsonObject?>>()

    // 会话列表：扫描 message/ 下的 history_*.json，返回 {today, names, last}：
    // names 为文件名（不含前缀与扩展名），供 chat.html 侧边栏区分群聊（yyyy-MM-dd）与私聊
    // （"名A-名B"，getTarget 排序后命名）；today 为服务端今天（Asia/Shanghai）；
    // last 为各会话最后一条消息的 {time, by}，供前端按"最后消息晚于已读时间且非自己/系统发送"点亮未读小红点
    private fun Route.conversations() = authenticate("auth") {
        get("/conversations") {
            val files = File("message")
                .listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }
                .orEmpty()
            val names = files.map { it.historyName() }.sorted()
            val last = buildMap {
                for (f in files) put(f.historyName(), f.lastMessageCached())
            }
            // 归档被删（deleteEmptyHistory）后别让缓存一直留着
            lastMsgCache.keys.retainAll(files.map { it.path }.toSet())
            call.respondText(
                buildJsonObject {
                    put("today", JsonPrimitive(Time.getCurrentDate()))
                    put("names", Json.encodeToJsonElement(names))
                    put("last", Json.encodeToJsonElement(last))
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    /** `history_<目标>.json` → `<目标>` */
    private fun File.historyName(): String = name.removePrefix("history_").removeSuffix(".json")

    /** 读 history 文件最后一条消息（time 与发送者 name）；文件损坏/为空返回 null（前端视为无未读） */
    private fun File.lastMessageCached(): JsonObject? {
        val stamp = length() to lastModified()
        lastMsgCache[path]?.let { if (it.first == stamp) return it.second }
        val value = lastMessage()
        lastMsgCache[path] = stamp to value
        return value
    }

    private fun File.lastMessage(): JsonObject? = try {
        val list = Util.historyJson.decodeFromString<MutableList<Message>>(readText().trimStart('\uFEFF'))
        val m = list.lastOrNull() ?: return null
        buildJsonObject {
            put("time", JsonPrimitive(m.time))
            put("by", JsonPrimitive(m.name))
            // 最后一条里 @ 了谁：只回名字集合、不回正文。
            // 侧边栏据此把红点升级成「@」胶囊；不回整段文本是为了别把所有人的
            // 私聊内容随 /conversations 一遍遍摊给每个登录用户（虽然 /history 本来就能读）
            put("at", Json.encodeToJsonElement(mentionsIn(m.text)))
        }
    } catch (e: Exception) {
        Logger.error("Failed to parse ${name}: ${e.message}")
        null
    }

    /// 从文本里找出 @了哪些已登记用户
    ///
    /// 两条规则与前端 renderText 保持一致（两边算出来的结果不同步会出现
    /// 「侧边栏挂了 @ 胶囊、气泡里却没高亮」这种自相矛盾的画面）：
    /// - 长名优先：`@张三丰` 不能被当成 `@张三`
    /// - 名字后面紧跟字母/数字/下划线/汉字就不算命中：`@bob123` 不是 `@bob`
    private fun mentionsIn(text: String): List<String> {
        if (!text.contains('@')) return emptyList()
        val names = Util.getUserList().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            .distinct()
            .sortedByDescending { it.length }
        // 输出按名字排序：长度倒序只用于"长名优先"的匹配过程，
        // 结果顺序若也跟着长度走，前端对比/测试都得猜，没必要
        return names.filter { name -> isMentioned(text, name) }.sorted()
    }

    private fun isMentioned(text: String, name: String): Boolean {
        val token = "@$name"
        var from = 0
        while (from <= text.length - token.length) {
            val at = text.indexOf(token, from)
            if (at < 0) return false
            val next = at + token.length
            if (next >= text.length || !isWordChar(text[next])) return true
            from = at + 1
        }
        return false
    }

    /// 与前端 `/[A-Za-z0-9_\u4e00-\u9fa5]/` 对齐：ASCII 字母数字下划线 + 中日韩统一表意文字
    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c in '\u4e00'..'\u9fa5'

    // ===== 图片 HTTP 端点 =====

    // 图片懒加载：/img/<sha256>.<ext>
    // 路径必须严格匹配 64 位十六进制 + 短扩展名，防路径穿越。
    // 图片按用户分目录存，但 URL 里不带用户，所以按文件名在各子目录里找（见 PictureStore.find）
    private fun Route.image() = authenticate("auth") {
        get("/img/{*}") {
            // 尾段参数名即 "*"（路径里 /img/ 后的整段）
            val name = call.parameters["*"].orEmpty()
            if (!PictureStore.IMAGE_NAME.matches(name)) {
                call.respondText("image not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val file = PictureStore.find(name)
            if (file == null) {
                // 图片被删后文件名是内容哈希、永远不会再出现，但浏览器没有缓存头就会每次进页面
                // 重新请求一遍 404（实测聊天页每轮都发）。给个短缓存，删图后最多 5 分钟内不再问。
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                call.respondText("image not found", status = HttpStatusCode.NotFound)
                return@get
            }
            // 文件名是内容寻址的（sha256），同一 URL 的字节永不改变，所以可以放心长缓存。
            // 不加这条时 Ktor 的 respondFile 只回 Content-Type/Content-Length，浏览器拿不到
            // Cache-Control/ETag/Last-Modified 就认为不可缓存 —— 列表里缩略图已经下过一遍的字节
            // 全白费，点开全屏查看器（新 <img> 用同一个 src）还得整张重新下。
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            // 用户的字节由服务端原样返回，强制浏览器按 Content-Type 处理、不许嗅探成 HTML
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }
    }

    // 图片列表：GET /pictures?limit=60 → 当前用户自己目录下的图片，供聊天页"从缓存中选取"用。
    // 只列自己的：每个人只能看到/发送自己保存过的图。按修改时间倒序（新存的排前面）；
    // 只返回名字与元数据，缩略图仍走 /img/<name> 由浏览器缓存，避免把 base64 塞进 JSON。
    private fun Route.pictures() = authenticate("auth") {
        get("/pictures") {
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 60).coerceIn(1, 200)
            val user = PictureStore.userOf(call.request.local.remoteAddress)
            val files = PictureStore.userDir(user)
                .listFiles { f -> f.isFile && PictureStore.IMAGE_NAME.matches(f.name) }
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
                    put("dir", JsonPrimitive(PictureStore.DIR + "/" + user))
                    put("user", JsonPrimitive(user))
                    put("total", JsonPrimitive(files.size))
                    put("items", Json.encodeToJsonElement(items))
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    // 保存图片：全屏查看器「保存」按钮的落盘端点。POST JSON {"data":"data:image/png;base64,..."}
    // 前端把当前查看的图片读成 dataURL 后发来（聊天图与 B 站代理图同一条路径），
    // 复用 PictureStore 的 mime 白名单 + SHA-256 去重；
    // 该图若本来就来自缓存，哈希相同 → 直接命中已存在的文件，不产生副本。
    // 只收白名单图片类型、单张 ≤10MB，目录名由当前 IP 推导、文件名由内容哈希决定，请求无法控制落盘路径。
    private fun Route.savePicture() = authenticate("auth") {
        post("/savePicture") {
            val tooLarge = """{"ok":false,"error":"图片过大（上限 10MB）"}"""
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > PictureStore.MAX_B64_BYTES) {
                call.respondText(tooLarge, contentType = ContentType.Application.Json,
                    status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val body = call.receiveText()
            if (body.length > PictureStore.MAX_B64_BYTES) {
                call.respondText(tooLarge, contentType = ContentType.Application.Json,
                    status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val dataUrl = runCatching {
                Json.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content
            }.getOrNull()
            if (dataUrl.isNullOrEmpty()) {
                call.respondText("""{"ok":false,"error":"缺少 data 字段"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.BadRequest)
                return@post
            }
            val user = PictureStore.userOf(call.request.local.remoteAddress)
            val saved = PictureStore.save(dataUrl, user)
            if (saved == null) {
                call.respondText(
                    """{"ok":false,"error":"无效图片：需为 data:image/(png|jpeg|webp|gif|bmp|avif);base64,，且不超过 10MB"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.BadRequest
                )
                return@post
            }
            Logger.debug("savePicture: ${saved.ref} (existed=${saved.existed})")
            call.respondText(
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("ref", JsonPrimitive(saved.ref))
                    put("name", JsonPrimitive(saved.ref.substringAfterLast('/')))
                    put("dir", JsonPrimitive(PictureStore.DIR + "/" + user))
                    put("user", JsonPrimitive(user))
                    put("existed", JsonPrimitive(saved.existed))
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    // 删除图片：图片选择面板里点「删除」时调用。POST JSON {"name":"<sha256>.<ext>"}
    // 只删当前用户自己目录里的文件 —— 别人目录里即使有同名文件也不碰（文件名是内容哈希，
    // 同一份内容可能被多个人各自保存过，删自己的那份不该影响别人）。
    // 用 POST 而非 GET：这是有副作用的操作，避免被预取/图片标签顺手触发。
    private fun Route.deletePicture() = authenticate("auth") {
        post("/deletePicture") {
            val user = PictureStore.userOf(call.request.local.remoteAddress)
            val name = runCatching {
                Json.parseToJsonElement(call.receiveText()).jsonObject["name"]?.jsonPrimitive?.content
            }.getOrNull()
            // 文件名必须符合内容寻址格式，顺带挡掉 ../ 之类的路径穿越
            if (name.isNullOrEmpty() || !PictureStore.IMAGE_NAME.matches(name)) {
                call.respondText("""{"ok":false,"error":"图片名不合法"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.BadRequest)
                return@post
            }
            val file = File(PictureStore.userDir(user), name)
            if (!file.isFile) {
                call.respondText("""{"ok":false,"error":"图片不存在（可能已被删除）"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.NotFound)
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
    }

    // ===== 头像 =====

    // 头像列表：GET /avatars → {"me":"<我的用户名>","items":{"<用户名>":"/img/<name>"}}
    // 前端渲染每条消息时按 msg.name 查这张表；查不到就用默认首字头像。
    // 一次回全部（用户数是个位到十位数，几十字节一条），比每条消息单独请求划算得多。
    private fun Route.avatars() = authenticate("auth") {
        get("/avatars") {
            val me = runCatching { Util.getUserName(call.request.local.remoteAddress) }.getOrNull()
            val items = AvatarStore.all()
            call.respondText(
                buildJsonObject {
                    put("me", JsonPrimitive(me.orEmpty()))
                    put("items", buildJsonObject {
                        items.forEach { (user, ref) -> put(user, JsonPrimitive(ref)) }
                    })
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    // 设置头像：POST /setAvatar JSON {"name":"<sha256>.<ext>"}
    // 只接受自己 cache/picture/<用户名>/ 里已有的文件（面板里挑的那张），不复制字节、不接收 base64。
    private fun Route.setAvatar() = authenticate("auth") {
        post("/setAvatar") {
            val user = PictureStore.userOf(call.request.local.remoteAddress)
            val name = runCatching {
                Json.parseToJsonElement(call.receiveText()).jsonObject["name"]?.jsonPrimitive?.content
            }.getOrNull()
            if (name.isNullOrEmpty() || !PictureStore.IMAGE_NAME.matches(name)) {
                call.respondText("""{"ok":false,"error":"图片名不合法"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.BadRequest)
                return@post
            }
            val ref = AvatarStore.set(user, name)
            if (ref == null) {
                call.respondText("""{"ok":false,"error":"这张图不在你的图片库里，请先保存再设为头像"}""",
                    contentType = ContentType.Application.Json, status = HttpStatusCode.NotFound)
                return@post
            }
            Logger.debug("setAvatar: $user -> $name")
            call.respondText(
                """{"ok":true,"user":${jsonStr(user)},"ref":${jsonStr(ref)}}""",
                contentType = ContentType.Application.Json
            )
        }
    }

    // 清除头像：POST /deleteAvatar → 回默认首字头像
    private fun Route.deleteAvatar() = authenticate("auth") {
        post("/deleteAvatar") {
            val user = PictureStore.userOf(call.request.local.remoteAddress)
            val ok = AvatarStore.clear(user)
            call.respondText(
                """{"ok":$ok,"user":${jsonStr(user)}}""",
                contentType = ContentType.Application.Json,
                status = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError
            )
        }
    }

    // ===== @ 提及 =====

    // 成员名单：GET /members → {"me":"<我的名字>","names":["alice","bob"]}
    //
    // 只回**名字**，不回 IP。/userList 那份是 IP→名字的完整映射（而且要 control 才该看），
    // 输入框里补全 @ 只需要一串名字，没理由把内网地址摊给每个聊天的人。
    // 名字本身已经是全站公开的：谁发过言，气泡上就写着谁。
    private fun Route.members() = authenticate("auth") {
        get("/members") {
            val me = runCatching { Util.getUserName(call.request.local.remoteAddress) }.getOrNull()
            val names = Util.getUserList()
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .distinct()
                .sorted()
            call.respondText(
                buildJsonObject {
                    put("me", JsonPrimitive(me.orEmpty()))
                    put("names", Json.encodeToJsonElement(names))
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }

    /** 把一个字符串编成 JSON 字面量（用户名里可能有引号、中文） */
    private fun jsonStr(s: String): String = JsonPrimitive(s).toString()

    // 一次性迁移端点（control 鉴权）：/migratePictures
    // 把旧版直接摊在 cache/ 根下的 <sha256>.<ext> 图片搬进 cache/picture/（新目录）。
    // 幂等：新目录已有同名文件时只删旧文件（内容哈希相同必然重复）；非图片文件一概不动。
    private fun Route.migratePictures() = authenticate("control") {
        get("/migratePictures") {
            call.respondText(Json.encodeToString(PictureStore.migrateFromCacheRoot()))
        }
    }

    // 一次性迁移端点（control 鉴权）：/migrateImages
    // 扫描 message/history_*.json 里的 ImageB64:dataURL 旧消息，把 base64 图片落盘，
    // 并把存档 text 改写为 ImageRef:/img/<sha256>.<ext>。幂等：已 ImageRef: 的不重复处理，
    // 同一 base64 去重只落盘一次；只回写有改动的文件。跑完可不再访问。
    private fun Route.migrateImages() = authenticate("control") {
        get("/migrateImages") {
            val files = File("message")
                .listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }
                .orEmpty()
            var migrated = 0
            for (f in files) {
                val text = f.readText().trimStart('\uFEFF')
                if (text.isBlank()) continue
                val original = Util.historyJson.decodeFromString<MutableList<Message>>(text)
                var changed = false
                val result = original.map { m ->
                    if (m.text.startsWith("ImageB64:")) {
                        PictureStore.save(m.text.removePrefix("ImageB64:"))?.let { saved ->
                            changed = true
                            migrated++
                            Message(m.name, m.time, saved.ref)
                        } ?: m // 解析不出 mime 保持原样
                    } else m
                }
                if (changed) f.writeText(Util.prettyJson.encodeToString(result))
            }
            call.respondText(Json.encodeToString(mapOf("files" to files.size, "migrated" to migrated)))
        }
    }

    private fun Route.serverInfo() = get("/wsSever") {
        call.respondText(
            """
            WebSocket Chat Server
            Connect to: ws://localhost:1919/ws
            Active clients: ${clients.size}
            """.trimIndent()
        )
    }

    // 在线连接数（不列 IP：这个端点没有鉴权，内网 IP 列表没必要暴露给任何访问者）
    private fun Route.clientCount() = get("/clients") {
        call.respondText("Connected clients: ${clients.size}")
    }

    // ===== WebSocket =====

    private fun Route.message() = authenticate("auth") {
        webSocket("/message") {
            val ip = call.request.local.remoteAddress
            val clientId = ip + "#" + java.util.UUID.randomUUID().toString().take(8)
            val me = Client(clientId, ip, this)
            clients[clientId] = me
            Logger.debug("Client $clientId connected. Total clients: ${clients.size}")

            // 本连接的发送协程：按入队顺序逐条写出（保证同一连接上的消息不乱序）。
            // 写失败（客户端已断）就关掉队列并摘表；读循环随后会因 incoming 关闭而退出
            val writer = launch {
                try {
                    for (frame in me.outbox) me.session.send(frame)
                } catch (e: Exception) {
                    Logger.debug("Writer for $clientId stopped: ${e.message}")
                } finally {
                    clients.remove(clientId)
                    me.outbox.close()
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    // 单帧解析失败只丢这一帧：整块 try 会把一个坏帧升级成整条连接断开
                    val json = try {
                        Json.decodeFromString<Content>(frame.readText())
                    } catch (e: Exception) {
                        Logger.error("Bad frame from $clientId: ${e.message}")
                        continue
                    }
                    val current = try {
                        Util.getUserName(ip)
                    } catch (e: Exception) {
                        err(me, "", "当前 IP 未登记用户名，无法发送消息")
                        continue
                    }
                    when (json.type) {
                        "send" -> handleSend(me, json, current)
                        // 超窗的撤回不回 err 就成了「点了没反应」：前端渲染时不会挂按钮，
                        // 但一直开着的页面里那个旧按钮还在，点下去总得给个说法
                        "del" -> if (Time.withinTwoMin(json.time)) {
                            handleDel(me, json, current)
                        } else {
                            err(me, current, "超过 2 分钟，无法撤回", json.sendTo)
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                Logger.debug("Client $clientId disconnected")
            } catch (e: Exception) {
                Logger.error("Error with client $clientId: ${e.message}")
            } finally {
                // 只摘自己这一条：早先按 IP 删会把同一个人的其他标签页一起摘掉
                clients.remove(clientId)
                // 读循环结束 = 这条连接不会再有人读：关掉队列并停掉发送协程，避免协程泄漏
                me.outbox.close()
                writer.cancel()
                Logger.debug("Client $clientId removed. Total clients: ${clients.size}")
            }
        }
    }

    // ===== 消息处理 =====

    /**
     * 给若干条连接投递一帧：只入队，不等待对方的写。
     *
     * 一个卡住（或已死）的连接不会拖住其他人的消息，也不会拖住发送者自己的读循环。
     * 队列满了说明对端已经不读了，摘掉它 —— 留着只会一直堆积。
     */
    private fun deliver(targets: Collection<Client>, frame: String) {
        for (c in targets) {
            if (c.outbox.trySend(frame).isFailure) {
                Logger.debug("Outbox full for ${c.id}, dropping connection")
                clients.remove(c.id, c)
                c.outbox.close()
            }
        }
    }

    /** 只回给发送者一个人的错误提示（同样走出站队列，保证与消息的顺序一致） */
    private fun err(
        to: Client,
        current: String,
        text: String,
        sendTo: String? = null
    ) = deliver(listOf(to), Json.encodeToString(Content("err", current, Time.getCurrentTimeWithDate(), text, sendTo)))

    /** 这条消息该发给谁：群聊是所有人；私聊是对方的全部连接 ∪ 自己的全部连接 */
    private fun targetsFor(me: Client, sendTo: String?): List<Client> =
        if (sendTo.isNullOrEmpty()) clients.values.toList()
        // 自己发给自己时两边是同一批 IP，按 id 去重后每部设备只收一次；
        // 多标签页时自己的另一个标签页也能同步看到。
        else (clientsOf(Util.getUserIp(sendTo)) + clientsOf(me.ip)).distinctBy { it.id }

    /**
     * 一条 `send` 消息：校验私聊对象与图片引用，落历史并广播。
     *
     * 图片只存档与广播短引用 `ImageRef:/img/<name>`：
     * - `ImageB64:` 解码落盘到发送者自己的目录（内容哈希去重）
     * - `ImageRef:` 从「缓存图片」面板挑的图，只校验文件真在自己目录里、原样转发
     */
    private fun handleSend(me: Client, json: Content, current: String) {
        val sendTo = json.sendTo
        // 私聊对象不在用户列表时直接回 err，避免 getUserIp 抛异常导致整个连接被断开
        if (!sendTo.isNullOrEmpty() && !Util.userExists(sendTo)) {
            err(me, current, "私聊对象 '$sendTo' 不存在于用户列表，请确认名字正确或先添加该用户", sendTo)
            return
        }

        var imageRef: String? = null
        if (json.text.startsWith("ImageB64:")) {
            val saved = PictureStore.save(json.text.removePrefix("ImageB64:"), PictureStore.userOf(me.ip))
            if (saved == null) {
                err(me, current, "图片消息无效：仅支持 png/jpeg/webp/gif/bmp/avif，且不超过 10MB", sendTo)
                return
            }
            imageRef = saved.ref
        } else if (json.text.startsWith("ImageRef:")) {
            // 只允许发自己的图 —— 别人的图连名字都不该被拿来转发
            val name = json.text.removePrefix("ImageRef:").substringAfterLast('/')
            val cached = PictureStore.IMAGE_NAME.matches(name) &&
                File(PictureStore.userDir(PictureStore.userOf(me.ip)), name).isFile
            if (!cached) {
                err(me, current, "缓存里没有这张图片了，请刷新列表后重选", sendTo)
                return
            }
            imageRef = "ImageRef:/img/$name"
        }

        val textToSend = imageRef ?: json.text
        val now = Time.getCurrentTimeWithDate()
        // 回复引用：只留一小段文本快照，并做长度截断。发送者自己伪造的 reply 也照收 ——
        // 它只是气泡里的一块引用文字（前端用 textContent 渲染），不参与任何权限判断。
        val reply = json.reply?.takeIf { it.name.isNotBlank() && it.time.isNotBlank() }?.let {
            ReplyRef(it.name, it.time, it.text.take(ReplyRef.TEXT_LIMIT))
        }
        // 先落盘再广播：反过来的话，收到消息的一方立刻点「刷新」会看不到这条
        // （/history 读的正是还没写进去的归档），看着像消息丢了。
        // 顺序反过来也意味着进程在两步之间挂掉时，这条消息在存档里凭空消失。
        //
        // 但存档失败不能把连接带走：读写异常若直接冒到读循环的 catch(Exception)，
        // 那条连接就被关了，之后每次重连再发还是撞同一个坏文件、还是断 —— 表现为
        // 「聊天突然发不出任何消息」。这里兜住、回一条 err 让用户看见原因。
        val target = if (sendTo.isNullOrEmpty()) null else Util.getTarget(current, sendTo)
        try {
            Util.addHistory(Message(current, now, textToSend, reply), target)
        } catch (e: Exception) {
            Logger.error("Failed to archive message from $current: ${e.message}")
            err(me, current, "消息未能存档（聊天记录文件损坏或不可写），已取消发送", sendTo)
            return
        }
        deliver(targetsFor(me, sendTo), Json.encodeToString(Content("send", current, now, textToSend, sendTo, reply)))
    }

    /**
     * 一条 `del` 消息：从历史里抹掉并广播撤回。
     *
     * 撤回窗口由调用方用 [Time.withinTwoMin] 校验（超窗的消息连处理都不进来，
     * 前端也不再挂撤回按钮）。
     */
    private fun handleDel(me: Client, json: Content, current: String) {
        val sendTo = json.sendTo
        if (!sendTo.isNullOrEmpty() && !Util.userExists(sendTo)) {
            err(me, current, "私聊对象 '$sendTo' 不存在于用户列表，无法撤回", sendTo)
            return
        }
        // 同样先改存档再广播：撤回后立刻刷新不该又看见那条消息。
        // 存档失败只回 err，不能让连接跟着陪葬（见 handleSend 里的说明）。
        val target = if (sendTo.isNullOrEmpty()) null else Util.getTarget(current, sendTo)
        try {
            Util.delHistory(current, json.time, target)
        } catch (e: Exception) {
            Logger.error("Failed to revoke message from $current: ${e.message}")
            err(me, current, "撤回未能写入存档（聊天记录文件损坏或不可写），已取消", sendTo)
            return
        }
        deliver(targetsFor(me, sendTo), Json.encodeToString(Content("del", current, json.time, json.text, sendTo)))
    }
}
