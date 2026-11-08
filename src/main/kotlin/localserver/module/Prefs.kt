package localserver.module

import localserver.utils.Logger
import localserver.utils.Util

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

import java.io.File

/**
 * 服务端配置存储：把前端页面的偏好设置落盘到服务器，而不是只留在浏览器 Cookie 里。
 *
 * 为什么需要它：单条 Cookie 上限约 4KB，chat.html 的"已读时间表"在私聊对象多时
 * 会撑破这个上限（前端现在只能把老条目裁掉）。放到服务端就没有这个烦恼，
 * 而且清浏览器数据、换浏览器/换设备都还在。
 *
 * 目录结构：cache/prefs/<净化用户名>.json
 * 文件内容是一个"页面 → 键 → 值"的两层对象，值一律是字符串（与 Cookie 语义一致，
 * 前端存的就是 JSON.stringify 后的文本）：
 *     { "chat": { "lastRead_张三": "{\"p:李四\":\"2026-01-01 10:00:00\"}" },
 *       "music": { "settings": "{\"quality\":\"320k\"}" } }
 *
 * 接口（均走 auth 鉴权，用户名取自 IP 白名单，未登记的 IP 退化为 ip_<地址>）：
 *     GET  /api/prefs                       → { user, prefs: { 页面: { 键: 值 } } }
 *     GET  /api/prefs?page=chat             → { user, page, prefs: { 键: 值 } }
 *     POST /api/prefs  {page, values?, remove?} → { ok, user, page, count }
 *
 * 写入是"页面级合并"：只覆盖 values 里出现的键，remove 里列出的键删除，
 * 其它页面/其它键原样保留。写入用临时文件 + rename，避免断电写出半个文件。
 */
object Prefs {
    /** 存放根目录（相对服务器工作目录） */
    const val ROOT = "cache/prefs"

    /** 单个用户的配置总额上限：超过就拒绝（正常页面用量只有几 KB，量大一定是出了 bug） */
    const val MAX_BYTES = 512 * 1024

    /** 页面标识：只允许短 ASCII，由各页面自己决定（chat/music/file/setting…） */
    private val PAGE_REGEX = Regex("^[A-Za-z0-9_-]{1,40}$")

    /** 键名长度上限 */
    private const val MAX_KEY_LENGTH = 120

    /**
     * 键名：不能用"字符白名单"——chat 的键是 `lastRead_<中文用户名>`，必须放行任意文字。
     * 改用黑名单：长度受限、不含控制字符、不含路径分隔符、不是 . / ..（因为键名只会出现在
     * JSON 的键里，不会拼进文件路径，但仍然挡住明显的穿越企图）。
     */
    private fun isValidKey(key: String): Boolean {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return false
        if (key == "." || key == "..") return false
        return key.none { it.isISOControl() || it == '/' || it == '\\' }
    }

    /** 与 AiHistory 同一套净化规则，保证目录名安全 */
    private fun userFile(user: String, base: File = File(ROOT)): File =
        File(base, localserver.utils.AiHistory.sanitizeUser(user) + ".json")

    private val storeJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** 用户名：IP 白名单里有就用真名，否则同 Ai 模块退化为 ip_<地址>，保证每个人的配置互不覆盖 */
    fun userNameOf(ip: String): String {
        val name = try {
            Util.getUserName(ip)
        } catch (e: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: ("ip_" + ip)
    }

    /** 读取某用户的全部配置；文件不存在或损坏返回空对象（损坏时记日志，不抛异常打断页面） */
    @Synchronized
    fun load(user: String, base: File = File(ROOT)): JsonObject {
        val file = userFile(user, base)
        if (!file.exists()) return JsonObject(emptyMap())
        return try {
            val text = file.readText().trimStart('\uFEFF').trim()
            if (text.isEmpty()) JsonObject(emptyMap())
            else {
                val element = storeJson.parseToJsonElement(text)
                element as? JsonObject ?: JsonObject(emptyMap())
            }
        } catch (e: Exception) {
            Logger.error("Prefs: failed to parse ${file.name}: ${e.message}")
            JsonObject(emptyMap())
        }
    }

    /**
     * 页面级合并写入。
     * @param values 要写入的键值（值为 null 视同 remove）
     * @param remove 要删除的键
     * @return 写入后的该页面对象；校验失败或超限返回 null
     */
    @Synchronized
    fun merge(
        user: String,
        page: String,
        values: Map<String, String?>,
        remove: Collection<String>,
        base: File = File(ROOT)
    ): JsonObject? {
        if (!PAGE_REGEX.matches(page)) return null

        val all = load(user, base).toMutableMap()
        val pageObj = (all[page] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        for ((key, value) in values) {
            // 值为 null 的前端会当成"删除"，这里统一走删除分支
            if (value == null) {
                pageObj.remove(key)
                continue
            }
            if (!isValidKey(key)) continue
            pageObj[key] = JsonPrimitive(value)
        }
        for (key in remove) {
            if (isValidKey(key)) pageObj.remove(key)
        }

        // 页面空了就把这个页面整个删掉，别在文件里留空壳
        if (pageObj.isEmpty()) all.remove(page) else all[page] = JsonObject(pageObj)

        val result = JsonObject(all)
        val text = storeJson.encodeToString(JsonObject.serializer(), result)
        if (text.length > MAX_BYTES) {
            Logger.error("Prefs: refuse to save ${userFile(user, base).name}, ${text.length} bytes > $MAX_BYTES")
            return null
        }

        val file = userFile(user, base)
        return try {
            val dir = file.parentFile
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.error("Prefs: cannot create dir ${dir.path}")
                return null
            }
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            JsonObject(pageObj)
        } catch (e: Exception) {
            Logger.error("Prefs: failed to save ${file.name}: ${e.message}")
            null
        }
    }

    /** 删除某个页面的全部配置（前端"恢复默认"用） */
    @Synchronized
    fun clearPage(user: String, page: String, base: File = File(ROOT)): Boolean {
        if (!PAGE_REGEX.matches(page)) return false
        val all = load(user, base).toMutableMap()
        if (all.remove(page) == null) return true // 本来就没有，视为成功
        val text = storeJson.encodeToString(JsonObject.serializer(), JsonObject(all))
        val file = userFile(user, base)
        return try {
            if (all.isEmpty()) {
                file.delete()
            } else {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
            true
        } catch (e: Exception) {
            Logger.error("Prefs: failed to clear $page for $user: ${e.message}")
            false
        }
    }

    fun Route.prefsRoute() {
        prefsGet()
        prefsSet()
        settingPage()
    }

    /**
     * 改名迁移：`<旧名>.json` → `<新名>.json`，并把键名里带旧名字的顺手改掉。
     *
     * 为什么键名也要改：chat 的配置键是 `lastRead_<用户名>`（整张已读表一个键）。
     * 只搬文件的话，新名字的页面去读 `lastRead_<新名>` 是空的 —— 所有会话重新变未读，
     * 而那份旧记录还占着地方。已知的这类键只有 `lastRead_`，其它页面都是固定键名。
     *
     * 新旧文件都存在时按「目标优先」合并：已有的键不覆盖，只补目标缺少的键。
     */
    @Synchronized
    fun renameUser(from: String, to: String, base: File = File(ROOT)): Map<String, Int> {
        val stats = mutableMapOf("keys" to 0, "failed" to 0)
        val src = userFile(from, base)
        if (!src.exists()) return stats
        val dest = userFile(to, base)
        if (src.absolutePath == dest.absolutePath) return stats
        val oldName = localserver.utils.AiHistory.sanitizeUser(from)
        val newName = localserver.utils.AiHistory.sanitizeUser(to)
        try {
            val merged = (if (dest.exists()) load(to, base) else JsonObject(emptyMap())).toMutableMap()
            for ((page, pageValue) in load(from, base)) {
                val pageObj = (pageValue as? JsonObject) ?: continue
                val target = (merged[page] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                for ((key, value) in pageObj) {
                    val renamed = key.replace("lastRead_$oldName", "lastRead_$newName")
                    if (target.containsKey(renamed)) continue
                    target[renamed] = value
                    stats["keys"] = stats["keys"]!! + 1
                }
                if (target.isNotEmpty()) merged[page] = JsonObject(target)
            }
            val text = storeJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
            if (text.length > MAX_BYTES) throw IllegalStateException("$text.length bytes > $MAX_BYTES")
            val dir = dest.parentFile
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create ${dir.path}")
            val tmp = File(dir, dest.name + ".tmp")
            tmp.writeText(text)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                dest.writeText(text)
                tmp.delete()
            }
            src.delete()
        } catch (e: Exception) {
            Logger.error("Prefs.renameUser failed: ${e.message}")
            stats["failed"] = stats["failed"]!! + 1
        }
        return stats
    }

    // 设置页：日间/夜间切换 + 各页面配置存储概览
    private fun Route.settingPage() = authenticate("auth") {
        get("/setting") {
            call.respondRedirect("/resources/setting.html")
        }
    }

    private fun Route.prefsGet() = authenticate("auth") {
        get("/api/prefs") {
            val user = userNameOf(call.request.local.remoteAddress)
            val page = call.parameters["page"]?.trim()
            val all = load(user)
            val payload = if (page.isNullOrEmpty()) {
                buildJsonObject {
                    put("user", user)
                    put("prefs", all)
                }
            } else {
                buildJsonObject {
                    put("user", user)
                    put("page", page)
                    put("prefs", all[page] ?: JsonObject(emptyMap()))
                }
            }
            call.respondText(
                storeJson.encodeToString(JsonObject.serializer(), payload),
                contentType = ContentType.Application.Json
            )
        }
    }

    private fun Route.prefsSet() = authenticate("auth") {
        post("/api/prefs") {
            val user = userNameOf(call.request.local.remoteAddress)
            val raw = runCatching { call.receiveText() }.getOrDefault("")
            val body = try {
                storeJson.parseToJsonElement(raw) as? JsonObject
            } catch (e: Exception) {
                null
            }
            if (body == null) {
                respondJson(call, HttpStatusCode.BadRequest, buildJsonObject {
                    put("ok", false)
                    put("error", "body must be a JSON object")
                })
                return@post
            }

            val page = (body["page"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!PAGE_REGEX.matches(page)) {
                respondJson(call, HttpStatusCode.BadRequest, buildJsonObject {
                    put("ok", false)
                    put("error", "invalid page")
                })
                return@post
            }

            // values：键 → 值；值允许 null（等同删除）。非字符串一律转成文本，前端不用先 encode
            val values = LinkedHashMap<String, String?>()
            (body["values"] as? JsonObject)?.forEach { (k, v) ->
                values[k] = if (v is JsonNull) null else (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            }
            val remove = (body["remove"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()

            if (values.isEmpty() && remove.isEmpty()) {
                respondJson(call, HttpStatusCode.BadRequest, buildJsonObject {
                    put("ok", false)
                    put("error", "nothing to write")
                })
                return@post
            }

            val pageObj = merge(user, page, values, remove)
            if (pageObj == null) {
                respondJson(call, HttpStatusCode.PayloadTooLarge, buildJsonObject {
                    put("ok", false)
                    put("error", "too large or invalid page")
                })
                return@post
            }

            respondJson(call, HttpStatusCode.OK, buildJsonObject {
                put("ok", true)
                put("user", user)
                put("page", page)
                put("count", pageObj.size)
            })
        }
    }

    private suspend fun respondJson(
        call: io.ktor.server.application.ApplicationCall,
        status: HttpStatusCode,
        payload: JsonObject
    ) {
        call.respondText(
            storeJson.encodeToString(JsonObject.serializer(), payload),
            contentType = ContentType.Application.Json,
            status = status
        )
    }
}
