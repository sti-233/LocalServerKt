package localserver.module

import localserver.utils.AiHistory
import localserver.utils.Logger
import localserver.utils.Util

import kotlinx.serialization.json.*
import java.io.File

/**
 * 用户头像的存储层。
 *
 * 头像本身**不复制字节**：用户在网络面板里挑一张自己 `cache/picture/<用户名>/` 里已存在的图，
 * 这里只记「谁的头像用哪个文件名」，图片继续由 `/img/<name>` 提供（本来就带 immutable 长缓存）。
 * 这样换头像不动磁盘体积，删掉那张图也只是落回默认首字头像，不会裂图报错。
 *
 * 记录落在 `cache/avatar/<净化用户名>.json`：
 * `{"user":"<真实用户名>","name":"<sha256>.<ext>","updatedAt":<毫秒>}`。
 *
 * 为什么存真实用户名而不只靠文件名：`/avatars` 要按消息里的 `name`（真实用户名，可能含中文、
 * 空格）给前端配对，而文件名用的是净化后的目录名，两者对不上。反过来读的时候还会校验
 * 记录里的 user 与请求的 user 一致 —— 净化可能把两个不同的名字压成同一个文件名
 * （例如 `a b` 与 `a_b`），校验后这种情况只会「读不到头像」，不会张冠李戴。
 */
object AvatarStore {

    const val DIR = "cache/avatar"

    private fun dir(): File = File(DIR).apply { if (!exists()) mkdirs() }

    private fun fileOf(user: String): File = File(DIR, AiHistory.sanitizeUser(user) + ".json")

    /**
     * 头像**不接受 GIF**：头像位只有 22~32px，动图在那儿既看不清又白费解码，
     * 而且 gif 首帧常常是个空白/占位帧，加载出来就是一块灰。聊天里发 gif 不受限，只是不能拿来当头像。
     */
    private val BANNED_EXT = setOf("gif")

    /** 这个文件名能不能作头像：必须是内容寻址的图片名，且不是 gif */
    fun isAllowedName(name: String): Boolean =
        PictureStore.IMAGE_NAME.matches(name) && name.substringAfterLast('.').lowercase() !in BANNED_EXT

    /**
     * 读一条头像记录；文件不存在、内容损坏、文件名不合法都返回 null。
     * `expect` 非空时还要求记录里的 user 与它相等。
     */
    private fun read(file: File, expect: String? = null): Pair<String, String>? = try {
        if (!file.isFile) null else {
            val obj = Util.historyJson.decodeFromString<JsonObject>(file.readText().trimStart('\uFEFF'))
            val user = obj["user"]?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            when {
                user.isNullOrBlank() || name.isNullOrBlank() -> null
                // 老记录里可能存着 gif：读的时候按「没设置头像」处理，前端自动回落首字头像
                !isAllowedName(name) -> null
                expect != null && user != expect -> null
                else -> user to name
            }
        }
    } catch (e: Exception) {
        // 坏记录当成没设置：宁可显示默认首字头像，也不该让 /avatars 整体 500
        Logger.error("Failed to read avatar ${file.name}: ${e.message}")
        null
    }

    /**
     * 某个用户的头像引用（`/img/<name>`）。
     * 未设置、或那张图已经被删掉时返回 null（前端落回默认首字头像）。
     */
    fun get(user: String): String? {
        val rec = read(fileOf(user), user) ?: return null
        return if (PictureStore.find(rec.second) == null) null else "/img/${rec.second}"
    }

    /**
     * 把 [name] 设为 [user] 的头像，返回引用；不合法返回 null。
     *
     * 只接受**自己图片目录里**已存在的文件：面板本来只列自己的图，这里再校验一遍，
     * 免得有人直接 POST 一个别人目录里的文件名当头像（`/img` 虽然按文件名全局找，
     * 但头像不该由别人的图来定义）。
     */
    @Synchronized
    fun set(user: String, name: String): String? {
        if (!isAllowedName(name)) return null
        if (!File(PictureStore.userDir(user), name).isFile) return null
        dir()
        val body = buildJsonObject {
            put("user", JsonPrimitive(user))
            put("name", JsonPrimitive(name))
            put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
        }
        fileOf(user).writeText(body.toString())
        return "/img/$name"
    }

    /** 清除头像（回默认首字头像）；本来就没有也算成功 */
    @Synchronized
    fun clear(user: String): Boolean {
        val file = fileOf(user)
        return !file.exists() || file.delete()
    }

    /**
     * 全部已设置的头像：真实用户名 → `/img/<name>`，供 `/avatars` 一次性回给前端
     * （渲染别人发的消息时按 `msg.name` 查表）。文件已失效的跳过。
     */
    fun all(): Map<String, String> {
        val files = File(DIR).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        val out = LinkedHashMap<String, String>()
        for (f in files) {
            val rec = read(f) ?: continue
            if (PictureStore.find(rec.second) == null) continue
            out[rec.first] = "/img/${rec.second}"
        }
        return out
    }

    /**
     * 改名迁移：头像记录跟着用户走（`/resetName` 调用）。
     *
     * 目标名已有头像时目标那份优先、旧记录丢弃 —— 与 Prefs/Ai 的合并口径一致（新名字当前在用的配置为准）。
     */
    @Synchronized
    fun renameUser(from: String, to: String): Map<String, Int> {
        val stats = mutableMapOf("avatars" to 0, "failed" to 0)
        if (from == to) return stats
        val src = fileOf(from)
        if (!src.isFile) return stats
        try {
            val dest = fileOf(to)
            if (dest.exists()) {
                if (src.delete()) stats["avatars"] = 1 else stats["failed"] = 1
                return stats
            }
            val rec = read(src, from)
            if (rec == null) {
                // 记录本身是坏的：删掉即可，新名字没必要继承一份坏数据
                src.delete()
                return stats
            }
            dir()
            val body = buildJsonObject {
                put("user", JsonPrimitive(to))
                put("name", JsonPrimitive(rec.second))
                put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            }
            dest.writeText(body.toString())
            // 复制成功即算迁移完；源文件删不掉（被杀软占用之类）也只是留个孤儿文件，不影响功能
            if (!src.delete()) Logger.debug("avatar rename: cannot delete ${src.path}")
            stats["avatars"] = 1
        } catch (e: Exception) {
            Logger.error("renameUser avatar '$from' -> '$to' failed: ${e.message}")
            stats["failed"] = 1
        }
        return stats
    }
}
