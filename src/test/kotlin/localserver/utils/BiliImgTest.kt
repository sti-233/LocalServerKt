package localserver.utils

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B 站图片磁盘缓存的基础逻辑。
 *
 * 路由层（`/biliImg` 的取图与回源）需要真上游，没法在单测里跑，
 * 这里只覆盖纯逻辑：扩展名推断、落盘路径、写入与清理。
 */
class BiliImgTest {

    private val created = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        created.forEach { runCatching { it.delete() } }
    }

    @Test
    fun `extension comes from the url tail`() {
        assertEquals("jpg", BiliImg.extOf("https://i0.hdslb.com/bfs/face/abc.jpg"))
        assertEquals("png", BiliImg.extOf("https://i0.hdslb.com/a/b.png?x=1"))
        assertEquals("webp", BiliImg.extOf("https://i0.hdslb.com/a/b.webp#frag"))
        // B 站会给图片加处理参数：@100w_100h.webp
        assertEquals("webp", BiliImg.extOf("https://i0.hdslb.com/a/b.jpg@100w_100h.webp"))
        // 认不出来的一律按 jpg，避免生成没有扩展名的文件
        assertEquals("jpg", BiliImg.extOf("https://i0.hdslb.com/a/b"))
        assertEquals("jpg", BiliImg.extOf("https://i0.hdslb.com/a/b.php"))
    }

    @Test
    fun `same url maps to a stable file name`() {
        val a = BiliImg.fileFor("https://i0.hdslb.com/bfs/face/abc.jpg")
        val b = BiliImg.fileFor("https://i0.hdslb.com/bfs/face/abc.jpg")
        val c = BiliImg.fileFor("https://i0.hdslb.com/bfs/face/xyz.jpg")
        assertEquals(a.name, b.name)
        assertFalse(a.name == c.name)
        assertEquals(64 + 1 + 3, a.name.length) // sha256 hex + '.' + ext
        assertEquals(File(BiliImg.DIR).absolutePath, a.parentFile.absolutePath)
    }

    @Test
    fun `store writes the bytes and keeps one file per url`() {
        val file = BiliImg.fileFor("https://i0.hdslb.com/bfs/test-store-${System.nanoTime()}.jpg")
        created += file
        BiliImg.store(file, byteArrayOf(1, 2, 3))
        assertTrue(file.isFile)
        assertEquals(3, file.length().toInt())
        // 重复写同名文件不应失败（先写临时文件再改名）
        BiliImg.store(file, byteArrayOf(4, 5))
        assertEquals(2, file.length().toInt())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun `reset clears the cache directory`() {
        val dir = BiliImg.dir()
        val probe = File(dir, "probe-${System.nanoTime()}.jpg")
        probe.writeBytes(byteArrayOf(9))
        assertTrue(probe.isFile)
        BiliImg.reset()
        assertTrue(dir.isDirectory)
        assertFalse(probe.exists())
    }
}
