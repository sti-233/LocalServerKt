package localserver.module

import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 图片举报的计数规则（[ReportStore]）。
 *
 * 这里只测"多少票算数"这一层，落盘、撤消息、删文件那些在 [ChatReportTest] 里走 WS 端到端。
 */
class ReportStoreTest {

    private fun name(tag: String): String =
        MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".png"

    @BeforeTest
    fun setUp() {
        File(ReportStore.DIR).deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        File(ReportStore.DIR).deleteRecursively()
    }

    @Test
    fun `three distinct reporters ban the image`() {
        val img = name("three")

        val first = ReportStore.report(img, "alice", control = false)
        assertFalse(first.banned)
        assertEquals(1, first.count)
        assertFalse(ReportStore.isBanned(img))

        val second = ReportStore.report(img, "bob", control = false)
        assertFalse(second.banned)
        assertEquals(2, second.count)

        // 第 3 个人才触发
        val third = ReportStore.report(img, "carol", control = false)
        assertTrue(third.banned)
        assertTrue(ReportStore.isBanned(img))
    }

    @Test
    fun `the same user reporting twice only counts once`() {
        val img = name("dup")
        ReportStore.report(img, "alice", control = false)
        val again = ReportStore.report(img, "alice", control = false)
        assertFalse(again.banned, "一个人重复点举报不能凑满阈值")
        assertTrue(again.duplicate)
        assertEquals(1, again.count)

        // 另外两个人仍然能把它推到阈值（重复举报没有把 alice 那份弄丢）
        ReportStore.report(img, "bob", control = false)
        val third = ReportStore.report(img, "carol", control = false)
        assertTrue(third.banned)
    }

    @Test
    fun `a control user bans on a single report`() {
        val img = name("control")
        val r = ReportStore.report(img, "admin", control = true)
        assertTrue(r.banned, "control 用户一票即封")
        assertTrue(ReportStore.isBanned(img))
    }

    @Test
    fun `banning clears the votes entry and is remembered on disk`() {
        val img = name("persist")
        ReportStore.report(img, "alice", control = false)
        ReportStore.report(img, "bob", control = false)
        ReportStore.report(img, "carol", control = false)

        // 文件里只剩 banned：逐人记录没用了，留着只会让文件越长越大
        val text = File(ReportStore.DIR, "votes.json").readText()
        assertTrue(text.contains(img), "banned 列表里应有这张图")
        assertFalse(text.contains("\"alice\""), "封禁后不该还留着举报人名单：$text")

        // 换个进程（这里用重新读文件模拟）仍然认得出来
        assertTrue(ReportStore.isBanned(img), "封禁必须跨重启保留，否则票数每天归零")
        assertTrue(ReportStore.banned().contains(img))
        assertFalse(ReportStore.isBanned(name("other")), "别的图不受影响")
    }

    @Test
    fun `reporting an already banned image is a no-op`() {
        val img = name("again")
        ReportStore.report(img, "admin", control = true)
        val again = ReportStore.report(img, "alice", control = false)
        assertTrue(again.banned, "已封禁的图再被举报仍回 banned（调用方靠它重放撤回，天然幂等）")
        assertTrue(ReportStore.isBanned(img))
    }

    @Test
    fun `a corrupted store starts from an empty tally instead of throwing`() {
        File(ReportStore.DIR).mkdirs()
        File(ReportStore.DIR, "votes.json").writeText("{ this is not json")
        // 举报只是多一道保护，坏文件不该让聊天整体报错
        assertFalse(ReportStore.isBanned(name("broken")))
        val r = ReportStore.report(name("broken"), "alice", control = false)
        assertEquals(1, r.count)
    }
}
