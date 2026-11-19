package localserver.module

import localserver.utils.AiHistory
import localserver.utils.Logger
import localserver.utils.Util

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * 聊天图片的落盘、查找与去重（从 Chat 里拆出来的存储层）。
 *
 * 目录布局：`cache/picture/<用户名>/<sha256>.<ext>` —— 每个用户只在自己的子目录里存取，
 * `/pictures` 与 `/savePicture` 都按请求 IP 对应的用户名分目录，所以「从缓存里选图」
 * 只会看到自己保存过的图。历史布局（`cache/picture/` 根、更早的 `cache/` 根）一律不搬，
 * [find] 会兜底找到，老存档里的 `ImageRef:` 不会裂图。
 */
object PictureStore {

    const val DIR = "cache/picture"

    /** 图片文件名：64 位十六进制 + 短扩展名。既做 `/img/` 的防穿越校验，也做迁移时的过滤条件 */
    val IMAGE_NAME = Regex("^[a-f0-9]{64}\\.[a-z]{1,10}$")

    /** 允许保存（与 `/img` 能提供的）图片子类型；不含 svg+xml 等可执行脚本的类型 */
    private val SUBTYPES = setOf("png", "jpeg", "webp", "gif", "bmp", "avif")

    /** 单张图片上限 */
    const val MAX_BYTES = 10 * 1024 * 1024

    /**
     * dataURL 的 base64 比原始字节大 4/3，再加上 `data:image/...;base64,` 前缀与 JSON 外壳的余量。
     * 用于在解析前先挡掉超大请求体（避免把几十 MB 的 body 读进内存再判大小）。
     */
    const val MAX_B64_BYTES = MAX_BYTES / 3 * 4 + 4096

    /** 落盘结果：ref 为存档/广播用的短引用，existed 表示文件此前就存在（命中已有缓存） */
    class Saved(val ref: String, val existed: Boolean)

    /**
     * 保存的结果。
     *
     * 之前用 `Saved?` 表示成败，加了举报之后「失败」有两种互不相同的原因：
     * 格式不对（图本身有问题）与**这张图已被举报封禁**（图没问题，但服务器不许它再进来）。
     * 两者给用户的提示完全不同，混成一个 null 只能说一句含混的「无效图片」，故分开。
     */
    sealed class SaveResult {
        class Ok(val saved: Saved) : SaveResult()
        object Invalid : SaveResult()
        object Banned : SaveResult()
    }

    /**
     * 当前请求对应的用户名，用作 `cache/picture/<用户名>/` 的目录名。
     *
     * 与 Ai / Prefs 用同一套净化规则（[AiHistory.sanitizeUser]）：userList.json 里有登记就用真名，
     * 没登记（例如开发机 127.0.0.1）退化为 `ip_<地址>`，这样不同 IP 的图不会混在一起，
     * 也保证中文用户名能安全当目录名（非法字符替换为下划线、去掉路径穿越片段）。
     */
    fun userOf(ip: String): String {
        val name = try {
            Util.getUserName(ip)
        } catch (e: Exception) {
            null
        }
        return AiHistory.sanitizeUser(name?.takeIf { it.isNotBlank() } ?: ("ip_" + ip))
    }

    /** 某个用户的图片目录：`cache/picture/<用户名>/` */
    fun userDir(user: String): File =
        File(DIR, user).apply { if (!exists()) mkdirs() }

    /**
     * 按文件名找图片，找不到返回 null。
     *
     * 顺序：`cache/picture/<用户名>/` 各子目录 → `cache/picture/` 根（旧版不区分用户时存的）
     * → `cache/` 根（更早的布局）。文件名是内容哈希，同一份内容落在哪个目录都一样，
     * 所以老存档里的 `ImageRef` 不会因为改成按用户分目录而裂图。
     */
    fun find(name: String): File? {
        val root = File(DIR)
        File(root, name).takeIf { it.isFile }?.let { return it }
        root.listFiles()?.forEach { sub ->
            if (sub.isDirectory) File(sub, name).takeIf { it.isFile }?.let { return it }
        }
        return File("cache", name).takeIf { it.isFile }
    }

    /**
     * 解码 dataURL → 落盘（去重），返回引用与「是否本就存在」；
     * 格式不合法或 mime 子类型不在白名单（svg 之类可执行脚本的）返回 [SaveResult.Invalid]，
     * 内容哈希命中举报黑名单时返回 [SaveResult.Banned]。
     *
     * `user` 为 null 时落到 `cache/picture/` 根（迁移旧存档用：那些图不属于任何单个用户，
     * 留在根上由 [find] 兜底提供，也不会出现在任何人的「从缓存选取」列表里）。
     */
    fun save(dataUrl: String, user: String? = null): SaveResult {
        val mimeMatch = Regex("^data:([a-z+/]+);base64,([A-Za-z0-9+/=]+)$").find(dataUrl)
            ?: return SaveResult.Invalid
        val mime = mimeMatch.groupValues[1]
        if (!mime.startsWith("image/")) return SaveResult.Invalid
        val ext = mime.substringAfter('/', "")
        if (ext !in SUBTYPES) return SaveResult.Invalid
        val bytes = try {
            Base64.getDecoder().decode(mimeMatch.groupValues[2])
        } catch (e: Exception) {
            return SaveResult.Invalid
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return SaveResult.Invalid
        return when (val r = saveBytes(bytes, ext, user)) {
            null -> SaveResult.Banned
            else -> SaveResult.Ok(Saved("ImageRef:/img/${r.first}", r.second))
        }
    }

    /**
     * 落盘图片字节到 `<目录>/<sha256>.<ext>`（同名即去重），返回 `"<sha256>.<ext>"` 与是否本就存在；
     * 内容哈希落在举报黑名单里时返回 null（这张图已被撤下，谁都不许再存回来）。
     *
     * `user` 非空时目录是 `cache/picture/<用户名>/`，否则是 `cache/picture/` 根（迁移用）。
     * 文件名只由内容哈希决定，调用方无法影响路径。
     */
    fun saveBytes(bytes: ByteArray, ext: String, user: String? = null): Pair<String, Boolean>? {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val name = "$hex.$ext"
        // 先查黑名单再落盘：否则删掉的图会被任何一个人重新上传一次就复活
        if (ReportStore.isBanned(name)) return null
        val dir = if (user == null) File(DIR).apply { if (!exists()) mkdirs() } else userDir(user)
        val file = File(dir, name)
        val existed = file.exists()
        if (!existed) file.writeBytes(bytes)
        return name to existed
    }

    /** mime 子类型是否可保存/可提供（`image/png` 的 `png` 部分） */
    fun isAllowedSubtype(ext: String): Boolean = ext in SUBTYPES

    /**
     * 把某个文件名在**所有**目录里的副本全部删掉，返回删掉的文件数（封禁时调用）。
     *
     * 必须扫全部位置：同一份内容可能被多个人各自存进自己的目录，也可能还躺在
     * `cache/picture/` 根或更早的 `cache/` 根里（见 [find] 的三处兜底）。
     * 只删发起举报的那个人目录里的那份，别人再从自己的图片库里发一次就复活了。
     */
    fun deleteEverywhere(name: String): Int {
        if (!IMAGE_NAME.matches(name)) return 0
        var removed = 0
        val root = File(DIR)
        // 根 + 每个用户子目录
        val candidates = mutableListOf(File(root, name))
        root.listFiles()?.forEach { sub -> if (sub.isDirectory) candidates.add(File(sub, name)) }
        candidates.add(File("cache", name))
        for (f in candidates) {
            try {
                if (f.isFile && f.delete()) removed++
            } catch (e: Exception) {
                Logger.error("deleteEverywhere failed on ${f.path}: ${e.message}")
            }
        }
        return removed
    }

    /**
     * 把旧版直接摊在 `cache/` 根下的 `<sha256>.<ext>` 搬进 `cache/picture/`。
     * 幂等：新目录已有同名文件时只删旧文件（内容哈希相同必然重复）；非图片文件一概不动。
     */
    fun migrateFromCacheRoot(): Map<String, Int> {
        val target = File(DIR).apply { if (!exists()) mkdirs() }
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

    /**
     * 改名迁移：把 `<旧名>/` 里的图片并进 `<新名>/`。
     *
     * 文件名是内容哈希，同名必然同字节，所以合并就是「目标没有就搬过去，有了就删掉多余的」，
     * 不需要比内容。两个人各自的图库里同名的那些图本来也就是同一份。
     * 幂等：跑第二遍时旧目录已经不存在（或空），返回 0。
     */
    fun renameUser(from: String, to: String): Map<String, Int> {
        val oldName = AiHistory.sanitizeUser(from)
        val newName = AiHistory.sanitizeUser(to)
        val stats = mutableMapOf("pictures" to 0, "failed" to 0)
        if (oldName == newName) return stats
        val src = File(DIR, oldName)
        if (!src.isDirectory) return stats
        val dest = File(DIR, newName)
        val files = src.listFiles { f -> f.isFile } ?: emptyArray()
        for (f in files) {
            try {
                val target = File(dest, f.name)
                if (!dest.exists() && !dest.mkdirs()) throw IllegalStateException("cannot create ${dest.path}")
                if (target.exists()) {
                    // 同内容已经在新目录里了，旧的那份只能是重复
                    if (!f.delete()) throw IllegalStateException("cannot delete ${f.path}")
                } else {
                    if (!f.renameTo(target)) {
                        target.writeBytes(f.readBytes())
                        if (!f.delete()) throw IllegalStateException("cannot delete ${f.path}")
                    }
                }
                stats["pictures"] = stats["pictures"]!! + 1
            } catch (e: Exception) {
                Logger.error("renameUser picture failed on ${f.name}: ${e.message}")
                stats["failed"] = stats["failed"]!! + 1
            }
        }
        // 目录清空了才删，留着空的也无害，但会出现在 /pictures 的扫描里
        src.delete()
        return stats
    }
}
