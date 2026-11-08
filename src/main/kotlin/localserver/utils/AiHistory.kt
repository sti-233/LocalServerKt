package localserver.utils

import localserver.types.AiConversation
import localserver.types.AiConversationSummary
import localserver.types.AiShare
import localserver.types.AiShareStore

import kotlinx.serialization.json.Json

import java.io.File
import java.util.UUID

/**
 * AI 对话记录的落盘存储。
 *
 * 目录结构：cache/ai/<ip 对应的用户名>/<会话 id>.json
 * - 目录名由用户名净化而来（只保留字母/数字/中日韩文字/下划线/点/横线），防目录穿越；
 * - 会话 id 由服务端生成（yyyyMMdd_HHmmss_随机 hex），前端传入的 id 一律校验后使用；
 * - 每个文件是一个完整会话（AiConversation），列表接口扫描目录读取并汇总为摘要列表。
 */
object AiHistory {
    /** 存放根目录（相对服务器工作目录） */
    const val DEFAULT_ROOT = "cache/ai"

    /** 会话 id 格式：8 位日期 + _ + 6 位时间 + _ + 6 位随机 hex */
    private val ID_REGEX = Regex("^[0-9]{8}_[0-9]{6}_[0-9a-f]{6}$")

    /** 名称最大长度 */
    const val MAX_NAME_LENGTH = 60

    /** 摘要（预览文本）最大长度 */
    const val MAX_PREVIEW_LENGTH = 60

    /** 存档文件专用序列化器：encodeDefaults 保证 messages/custom 等默认值也写进文件，格式稳定可预期 */
    private val storeJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 用户目录：cache/ai/<用户名> */
    fun userDir(user: String, base: File = File(DEFAULT_ROOT)): File = File(base, sanitizeUser(user))

    /**
     * 改名迁移：把 `<旧名>/` 里的会话并进 `<新名>/`，并改掉 `_shares.json` 里的 owner。
     *
     * 会话 id 含到秒的时间戳 + 随机 hex，两个目录里撞 id 的概率可以忽略；真撞上了
     * 以目标目录那份为准（不覆盖已有文件），旧文件改名成 `<id>_dup` 也不清掉 ——
     * 会话内容是用户数据，宁可留个看不懂的文件也不能悄悄丢。
     */
    fun renameUser(from: String, to: String, base: File = File(DEFAULT_ROOT)): Map<String, Int> {
        val oldName = sanitizeUser(from)
        val newName = sanitizeUser(to)
        val stats = mutableMapOf("conversations" to 0, "shares" to 0, "failed" to 0)
        if (oldName == newName) return stats
        val src = File(base, oldName)
        if (src.isDirectory) {
            val dest = File(base, newName)
            for (f in src.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()) {
                try {
                    if (!dest.exists() && !dest.mkdirs()) throw IllegalStateException("cannot create ${dest.path}")
                    var target = File(dest, f.name)
                    if (target.exists()) target = File(dest, f.name.removeSuffix(".json") + "_dup.json")
                    if (!f.renameTo(target)) {
                        target.writeBytes(f.readBytes())
                        if (!f.delete()) throw IllegalStateException("cannot delete ${f.path}")
                    }
                    stats["conversations"] = stats["conversations"]!! + 1
                } catch (e: Exception) {
                    Logger.error("AiHistory.renameUser failed on ${f.name}: ${e.message}")
                    stats["failed"] = stats["failed"]!! + 1
                }
            }
            src.delete()
        }
        // 分享记录：owner 存的是净化后的名字，不改就查不到自己的分享链接了
        val shares = loadShares(base)
        val touched = shares.filter { it.owner == oldName }
        if (touched.isNotEmpty()) {
            val updated = shares.map { if (it.owner == oldName) AiShare(it.token, newName, it.convId, it.createdAt) else it }
            if (saveShares(updated, base)) stats["shares"] = touched.size else stats["failed"] = stats["failed"]!! + 1
        }
        return stats
    }

    /** Windows 保留设备名（CON/NUL/COM1…）不能作目录名，前缀下划线规避 */
    private val RESERVED_NAMES = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")

    /** 用户名净化：只保留安全字符，非法字符替换为下划线；结果一定可安全用作目录名 */
    fun sanitizeUser(user: String): String {
        val cleaned = user.trim()
            // 只保留字母（中日韩等）/数字/下划线/点/横线，其余替换为下划线
            .replace(Regex("[^\\p{L}\\p{N}_.-]"), "_")
            // 连续的点（".." 等）可能构成路径穿越片段，整体替换为下划线
            .replace(Regex("\\.{2,}"), "_")
            .take(64)
            // 目录名以点/空格结尾在 Windows 上非法（会被静默改写），一律削掉
            .trimEnd('.', ' ')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "unknown"
        return if (RESERVED_NAMES.matches(cleaned)) "_" + cleaned else cleaned
    }

    /** 会话 id 是否合法（防路径穿越） */
    fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

    /** 生成新的会话 id（始终满足 [ID_REGEX]：时间串异常时截断/补零，不会产出非法 id） */
    fun newId(now: String = Time.getCurrentTimeWithDate()): String {
        // "yyyy-MM-dd HH:mm:ss" → "yyyyMMddHHmmss"（14 位；不足补 0、超长截断）
        val stamp = now.filter { it.isDigit() }.padEnd(14, '0').take(14)
        val rand = UUID.randomUUID().toString().replace("-", "").take(6)
        return "${stamp.substring(0, 8)}_${stamp.substring(8, 14)}_${rand}"
    }

    /** 默认会话名："新对话 MM-dd HH:mm" */
    fun defaultName(now: String = Time.getCurrentTimeWithDate()): String {
        val sub = if (now.length >= 16) now.substring(5, 16) else now
        return "新对话 ${sub}"
    }

    /** 读取单个会话；不存在或损坏返回 null */
    fun load(user: String, id: String, base: File = File(DEFAULT_ROOT)): AiConversation? {
        if (!isValidId(id)) return null
        val file = File(userDir(user, base), "${id}.json")
        if (!file.exists()) return null
        return read(file)
    }

    /** 保存会话（整体覆盖）；失败返回 false 并记日志。id 非法直接拒绝（防路径穿越写出目录） */
    fun save(user: String, conv: AiConversation, base: File = File(DEFAULT_ROOT)): Boolean {
        if (!isValidId(conv.id)) {
            Logger.error("AiHistory: refuse to save invalid id: " + conv.id)
            return false
        }
        return try {
            val dir = userDir(user, base)
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.error("AiHistory: cannot create dir ${dir.path}")
                return false
            }
            val file = File(dir, "${conv.id}.json")
            val text = storeJson.encodeToString(conv)
            val tmp = File(dir, "${conv.id}.json.tmp")
            tmp.writeText(text)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            Logger.error("AiHistory: failed to save ${conv.id}: ${e.message}")
            false
        }
    }

    /** 新建会话并落盘 */
    fun create(
        user: String,
        name: String? = null,
        now: String = Time.getCurrentTimeWithDate(),
        base: File = File(DEFAULT_ROOT)
    ): AiConversation {
        val conv = AiConversation(
            id = newId(now),
            name = sanitizeName(name) ?: defaultName(now),
            custom = name != null,
            createdAt = now,
            updatedAt = now
        )
        save(user, conv, base)
        return conv
    }

    /** 重命名会话；返回改名后的会话（不存在返回 null） */
    fun rename(user: String, id: String, name: String, base: File = File(DEFAULT_ROOT)): AiConversation? {
        val conv = load(user, id, base) ?: return null
        val clean = sanitizeName(name) ?: return null
        conv.name = clean
        conv.custom = true
        save(user, conv, base)
        return conv
    }

    /** 删除会话；返回是否删除成功 */
    fun delete(user: String, id: String, base: File = File(DEFAULT_ROOT)): Boolean {
        if (!isValidId(id)) return false
        val file = File(userDir(user, base), "${id}.json")
        if (!file.exists()) return false
        return try {
            file.delete()
        } catch (e: Exception) {
            Logger.error("AiHistory: failed to delete ${id}: ${e.message}")
            false
        }
    }

    /** 会话列表：按最后一条消息时间从新到旧排序 */
    fun list(user: String, base: File = File(DEFAULT_ROOT)): List<AiConversationSummary> {
        val dir = userDir(user, base)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            if (!isValidId(id)) return@mapNotNull null
            val conv = read(f) ?: return@mapNotNull null
            summarize(conv)
        }.sortedWith(compareByDescending<AiConversationSummary> { it.lastTime }.thenByDescending { it.id })
    }

    /** 会话 → 摘要（不含消息正文） */
    fun summarize(conv: AiConversation): AiConversationSummary {
        val last = conv.messages.lastOrNull()
        val lastTime = last?.time?.takeIf { it.isNotBlank() } ?: conv.updatedAt
        val preview = last?.content?.replace(Regex("\\s+"), " ")?.trim().orEmpty().take(MAX_PREVIEW_LENGTH)
        return AiConversationSummary(
            id = conv.id,
            name = conv.name,
            lastTime = lastTime,
            createdAt = conv.createdAt,
            updatedAt = conv.updatedAt,
            count = conv.messages.size,
            preview = preview
        )
    }

    /**
     * 由首条用户消息自动生成会话名：去掉换行、截断到 MAX_NAME_LENGTH。
     * 内容为空时返回 null（保持默认名）。
     */
    fun autoName(text: String): String? {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        return flat.take(MAX_NAME_LENGTH)
    }

    /** 名称净化：去空白、限制长度；空名称返回 null（表示非法） */
    fun sanitizeName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.replace(Regex("[\\r\\n\\t]"), " ").take(MAX_NAME_LENGTH)
    }

    // 读单个会话文件；损坏时记日志并跳过（不抛异常）
    private fun read(file: File): AiConversation? = try {
        val conv = storeJson.decodeFromString<AiConversation>(file.readText().trimStart('\uFEFF'))
        if (isValidId(conv.id)) conv else null
    } catch (e: Exception) {
        Logger.error("AiHistory: failed to parse ${file.name}: ${e.message}")
        null
    }

    /* ==================== 分享链接 ==================== */

    /** 分享记录文件名：放在 cache/ai 根目录，下划线前缀避免与用户名目录同名 */
    private const val SHARE_FILE = "_shares.json"

    /** 分享令牌长度（hex 字符数）：24 位 = 96 bit，无法枚举 */
    private const val SHARE_TOKEN_HEX = 24

    private val tokenRegex = Regex("^[0-9a-f]{$SHARE_TOKEN_HEX}$")

    /** 令牌是否合法（防注入，也避免拿任意串去扫表） */
    fun isValidToken(token: String): Boolean = tokenRegex.matches(token)

    private fun shareStoreFile(base: File = File(DEFAULT_ROOT)): File = File(base, SHARE_FILE)

    /** 读取全部分享记录；文件缺失或损坏时返回空表（分享挂了不影响聊天主流程） */
    fun loadShares(base: File = File(DEFAULT_ROOT)): List<AiShare> {
        val file = shareStoreFile(base)
        if (!file.exists()) return emptyList()
        return try {
            storeJson.decodeFromString<AiShareStore>(file.readText().trimStart('\uFEFF')).items
        } catch (e: Exception) {
            Logger.error("AiHistory: failed to parse share store: ${e.message}")
            emptyList()
        }
    }

    /** 写入全部分享记录（整体覆盖；失败只记日志） */
    fun saveShares(items: List<AiShare>, base: File = File(DEFAULT_ROOT)): Boolean = try {
        val file = shareStoreFile(base)
        val dir = file.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Logger.error("AiHistory: cannot create dir ${dir.path}")
            false
        } else {
            file.writeText(storeJson.encodeToString(AiShareStore(items)))
            true
        }
    } catch (e: Exception) {
        Logger.error("AiHistory: failed to save share store: ${e.message}")
        false
    }

    /** 为某会话创建分享令牌；同一会话重复创建时复用旧令牌（链接不会越点越多） */
    fun createShare(
        user: String,
        convId: String,
        now: String = Time.getCurrentTimeWithDate(),
        base: File = File(DEFAULT_ROOT)
    ): AiShare? {
        if (!isValidId(convId)) return null
        val owner = sanitizeUser(user)
        val items = loadShares(base).toMutableList()
        // 同一 owner+会话已有令牌就复用，避免每点一次分享就多出一条记录
        items.firstOrNull { it.owner == owner && it.convId == convId }?.let { return it }
        val token = UUID.randomUUID().toString().replace("-", "").padEnd(SHARE_TOKEN_HEX, '0').take(SHARE_TOKEN_HEX)
        val share = AiShare(token = token, owner = owner, convId = convId, createdAt = now)
        items.add(share)
        return if (saveShares(items, base)) share else null
    }

    /** 按令牌查分享记录 */
    fun findShare(token: String, base: File = File(DEFAULT_ROOT)): AiShare? {
        if (!isValidToken(token)) return null
        return loadShares(base).firstOrNull { it.token == token }
    }

    /** 撤销某会话的分享；返回是否真的删掉了记录 */
    fun revokeShare(user: String, convId: String, base: File = File(DEFAULT_ROOT)): Boolean {
        val owner = sanitizeUser(user)
        val items = loadShares(base)
        val rest = items.filterNot { it.owner == owner && it.convId == convId }
        if (rest.size == items.size) return false
        return saveShares(rest, base)
    }
}
