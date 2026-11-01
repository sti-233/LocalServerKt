package localserver.utils

import java.io.File
import localserver.utils.Hashs.sha256Hex

/**
 * B 站图片的磁盘缓存（`cache/bilibili/`）。
 *
 * 为什么需要：video 模块的封面/头像/动态图都走服务端代理（B 站图片有 Referer 防盗链，
 * 浏览器直接请求会 403）。原先统一走 `/download`，而那个端点每次都新建临时文件、
 * 全量拉取后立刻删掉，等于零缓存 —— 首页翻一页就要重下一二十张图，来回滚还重复下。
 *
 * 这里按 URL 的 sha256 落盘，文件名 `<sha256>.<ext>`：同一个 URL 只拉一次，
 * 后续请求直接 `respondFile`（自带 ETag/Range）。目录在**每次启动时清空**，
 * 因为 B 站图片 URL 带签名和尺寸参数，跨启动复用意义不大，留着只会越堆越多。
 */
object BiliImg {
    /** 缓存目录（相对工作目录，与 cache/picture 一致） */
    const val DIR = "cache/bilibili"

    /** 单张图上限，超出的不缓存（B 站封面正常在 200KB 内，留足余量） */
    internal const val MAX_BYTES = 8 * 1024 * 1024

    /** 超过这个数量就按修改时间清掉最旧的一批，防止长时间运行后撑爆磁盘 */
    private const val MAX_FILES = 1200
    private const val PRUNE_TO = 900

    /** 允许缓存的扩展名，其余一律按 jpg 处理（B 站多数图片实际是 jpg/webp） */
    private val EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "ico")

    /**
     * 从 URL 推断扩展名。B 站图片常带处理参数，如
     * `.../face/abc.jpg@100w_100h.webp`，取最后一段更接近实际格式。
     */
    fun extOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val tail = path.substringAfterLast('/')
        val ext = tail.substringAfterLast('.', "").lowercase()
        return if (ext in EXTS) ext else "jpg"
    }

    /** 该 URL 对应的缓存文件（不保证存在） */
    fun fileFor(url: String): File = File(dir(), url.sha256Hex + "." + extOf(url))

    /** 缓存目录，首次访问时创建 */
    fun dir(): File = File(DIR).apply { if (!isDirectory) mkdirs() }

    /** 清空缓存目录，启动时调一次 */
    fun reset() {
        val d = File(DIR)
        if (d.exists()) d.deleteRecursively()
        d.mkdirs()
        Logger.debug("bili image cache cleared: $DIR")
    }

    /** 写入一张图；先写临时文件再改名，避免并发读到半截内容 */
    fun store(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            // 每张图都列一遍目录太浪费（一页就要存十几张），攒够一批再清
            if (stored.incrementAndGet() % PRUNE_EVERY == 0) prune()
        }.onFailure { Logger.debug("bili image cache write failed: ${it.message}") }
    }

    private val stored = java.util.concurrent.atomic.AtomicInteger(0)
    private const val PRUNE_EVERY = 64

    /** 数量超限时按修改时间清掉最旧的，删到 PRUNE_TO 为止 */
    private fun prune() {
        val files = dir().listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - PRUNE_TO)
            .forEach { it.delete() }
    }
}
