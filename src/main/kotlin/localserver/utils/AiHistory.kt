package localserver.utils

import localserver.types.AiConversation
import localserver.types.AiConversationSummary

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
}
