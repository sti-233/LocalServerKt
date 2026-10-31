package localserver.utils

import localserver.types.AiConversation
import localserver.types.AiMessage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AiHistory 的单测：目录净化、会话 id 校验、增删改查、排序。
 * 全部在临时目录里跑，不碰真实的 cache/ai。
 */
class AiHistoryTest {

    // 每个测试用独立的临时根目录
    private fun tempBase(): File {
        val dir = Files.createTempDirectory("ai-history-test").toFile()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun userNameSanitizeRemovesPathSeparators() {
        assertEquals("unknown", AiHistory.sanitizeUser(""))
        assertEquals("_", AiHistory.sanitizeUser(".."))
        // 目录穿越字符被替换，目录名里不会留下路径分隔符
        val evil = AiHistory.sanitizeUser("../../etc")
        assertFalse(evil.contains("/"))
        assertFalse(evil.contains("\\"))
        assertFalse(evil.contains(".."))
    }

    @Test
    fun userNameSanitizeKeepsChineseAndAlnum() {
        assertEquals("张三", AiHistory.sanitizeUser("张三"))
        assertEquals("user_1.2", AiHistory.sanitizeUser("user_1.2"))
    }

    @Test
    fun conversationIdValidationRejectsTraversal() {
        assertTrue(AiHistory.isValidId("20260101_120000_abc123"))
        assertFalse(AiHistory.isValidId("../secrets"))
        assertFalse(AiHistory.isValidId("20260101_120000"))
        assertFalse(AiHistory.isValidId("20260101_120000_ABC123"))
        assertFalse(AiHistory.isValidId(""))
    }

    @Test
    fun createWritesFileUnderUserDirAndCanBeReadBack() {
        val base = tempBase()
        val conv = AiHistory.create("alice", null, "2026-01-02 03:04:05", base)
        assertTrue(AiHistory.isValidId(conv.id))
        assertTrue(conv.name.startsWith("新对话"))

        // 文件落在 cache/ai/<用户名>/<id>.json 下
        val file = File(AiHistory.userDir("alice", base), conv.id + ".json")
        assertTrue(file.exists(), "会话文件应存在: " + file.path)

        val loaded = AiHistory.load("alice", conv.id, base)
        assertNotNull(loaded)
        assertEquals(conv.id, loaded!!.id)
        assertEquals(conv.name, loaded.name)
    }

    @Test
    fun usersCannotSeeEachOthersConversations() {
        val base = tempBase()
        val conv = AiHistory.create("alice", null, "2026-01-02 03:04:05", base)
        assertNull(AiHistory.load("bob", conv.id, base))
        assertTrue(AiHistory.list("bob", base).isEmpty())
        assertEquals(1, AiHistory.list("alice", base).size)
    }

    @Test
    fun renameUpdatesNameAndMarksCustom() {
        val base = tempBase()
        val conv = AiHistory.create("alice", null, "2026-01-02 03:04:05", base)
        val renamed = AiHistory.rename("alice", conv.id, "我的会议记录", base)
        assertNotNull(renamed)
        assertEquals("我的会议记录", renamed!!.name)
        assertTrue(renamed.custom)

        val loaded = AiHistory.load("alice", conv.id, base)
        assertEquals("我的会议记录", loaded!!.name)
        assertTrue(loaded.custom)
    }

    @Test
    fun renameOfMissingConversationReturnsNull() {
        val base = tempBase()
        assertNull(AiHistory.rename("alice", "20260101_120000_abc123", "x", base))
    }

    @Test
    fun deleteRemovesConversation() {
        val base = tempBase()
        val conv = AiHistory.create("alice", null, "2026-01-02 03:04:05", base)
        assertTrue(AiHistory.delete("alice", conv.id, base))
        assertNull(AiHistory.load("alice", conv.id, base))
        // 重复删除返回 false
        assertFalse(AiHistory.delete("alice", conv.id, base))
    }

    @Test
    fun listIsSortedByLastMessageTimeNewestFirst() {
        val base = tempBase()
        val older = AiHistory.create("alice", "旧", "2026-01-01 10:00:00", base)
        val newer = AiHistory.create("alice", "新", "2026-01-01 12:00:00", base)
        val middle = AiHistory.create("alice", "中", "2026-01-01 11:00:00", base)

        // 写入消息，决定 lastTime
        fun withMsg(id: String, time: String) {
            val conv = AiHistory.load("alice", id, base)!!
            conv.messages = listOf(AiMessage(role = "user", content = "hi", time = time))
            AiHistory.save("alice", conv, base)
        }
        withMsg(older.id, "2026-01-01 10:00:00")
        withMsg(newer.id, "2026-01-01 12:00:00")
        withMsg(middle.id, "2026-01-01 11:00:00")

        val list = AiHistory.list("alice", base)
        assertEquals(3, list.size)
        assertEquals(listOf("新", "中", "旧"), list.map { it.name })
        assertEquals(newer.id, list[0].id)
    }

    @Test
    fun summaryContainsPreviewAndCount() {
        val base = tempBase()
        val conv = AiHistory.create("alice", "会话", "2026-01-02 03:04:05", base)
        conv.messages = listOf(
            AiMessage(role = "user", content = "第一行\n第二行", time = "2026-01-02 03:04:05"),
            AiMessage(role = "assistant", content = "回答", time = "2026-01-02 03:04:06")
        )
        AiHistory.save("alice", conv, base)

        val summary = AiHistory.list("alice", base).first()
        assertEquals(2, summary.count)
        assertEquals("2026-01-02 03:04:06", summary.lastTime)
        assertEquals("回答", summary.preview)
    }

    @Test
    fun brokenFileIsSkippedWithoutAffectingOthers() {
        val base = tempBase()
        val good = AiHistory.create("alice", "好", "2026-01-02 03:04:05", base)
        // 写一个坏 JSON 文件
        File(AiHistory.userDir("alice", base), "20260101_120000_broken1.json")
            .writeText("{ 这不是合法 JSON")

        val list = AiHistory.list("alice", base)
        assertEquals(1, list.size)
        assertEquals(good.id, list[0].id)
    }

    @Test
    fun nameSanitizeLimitsLengthAndRemovesNewlines() {
        assertNull(AiHistory.sanitizeName("   "))
        assertNull(AiHistory.sanitizeName(null))
        assertEquals("a b", AiHistory.sanitizeName("a\nb"))
        assertEquals(AiHistory.MAX_NAME_LENGTH, AiHistory.sanitizeName("x".repeat(200))!!.length)
    }

    @Test
    fun autoNameFlattensAndTruncates() {
        assertEquals("你好 世界", AiHistory.autoName("你好\n世界"))
        assertNull(AiHistory.autoName("   "))
        assertEquals(AiHistory.MAX_NAME_LENGTH, AiHistory.autoName("y".repeat(200))!!.length)
    }

    @Test
    fun newIdIsAlwaysValidAndUnique() {
        // 正常时间串
        assertTrue(AiHistory.isValidId(AiHistory.newId()))
        // 异常时间串也要产出合法 id（旧实现会拼出 "abc_def" 这类非法 id）
        val weird = listOf("", "x", "2026", "2026-01-02", "2026-01-02 03:04:05 extra")
        weird.forEach { assertTrue(AiHistory.isValidId(AiHistory.newId(it)), "非法 id: " + it) }
        // 同一秒内连续创建也不重复
        val ids = (1..50).map { AiHistory.newId("2026-01-02 03:04:05") }
        assertEquals(50, ids.toSet().size)
    }

    @Test
    fun sanitizeUserHandlesWindowsReservedAndTrailingDots() {
        // Windows 保留设备名会被加前缀，避免创建失败
        assertEquals("_CON", AiHistory.sanitizeUser("CON"))
        assertEquals("_nul", AiHistory.sanitizeUser("nul"))
        // 尾部的点/空格在 Windows 上非法：连续点先被折叠成下划线，残留的尾部点空格再削掉
        val trailing = AiHistory.sanitizeUser("alice...")
        assertFalse(trailing.endsWith("."), "目录名不能以点结尾: " + trailing)
        assertTrue(trailing.startsWith("alice"))
        assertEquals("alice", AiHistory.sanitizeUser("  alice  "))
        // 净化结果非空
        assertTrue(AiHistory.sanitizeUser("...").isNotEmpty())
    }

    @Test
    fun saveRejectsInvalidId() {
        val base = tempBase()
        val conv = AiConversation(id = "../escape", name = "x", createdAt = "t", updatedAt = "t")
        assertFalse(AiHistory.save("alice", conv, base))
        // 目录里不该出现任何文件
        assertTrue(AiHistory.list("alice", base).isEmpty())
    }

    @Test
    fun savedFileContainsAllDefaults() {
        // encodeDefaults：messages 为空也要落进文件（否则重读时字段缺失、前端拿不到结构）
        val base = tempBase()
        val conv = AiHistory.create("alice", "会话", "2026-01-02 03:04:05", base)
        val text = File(AiHistory.userDir("alice", base), conv.id + ".json").readText()
        assertTrue(text.contains("messages"))
        assertTrue(text.contains("custom"))
    }

    @Test
    fun saveFailureDoesNotThrow() {
        // base 指向一个文件（而不是目录），mkdirs 会失败 → save 返回 false 而不是抛异常
        val blocked = File(tempBase(), "not-a-dir")
        blocked.writeText("x")
        val conv = AiHistory.create("alice", null, "2026-01-02 03:04:05", tempBase())
        assertFalse(AiHistory.save("alice", conv, blocked))
    }
}
