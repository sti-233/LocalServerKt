package localserver.module

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 禁言存储：时长、覆盖（改时长）、取消、到期自动失效。
 *
 * 这些都直接测 [MuteStore]，不走 HTTP —— 「3 分钟到了就该自动解除」这类判断，
 * 走一遍路由也验证不了时钟，不如把 `until` 直接摆到过去来断言。
 */
class MuteStoreTest {

    private fun file(): File = File(MuteStore.DIR, "mutes.json")

    @BeforeTest
    fun setUp() {
        File(MuteStore.DIR).deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        File(MuteStore.DIR).deleteRecursively()
    }

    @Test
    fun `muting someone makes them active until the deadline`() {
        val entry = MuteStore.mute("alice", 10, "admin", "刷屏")
        val active = MuteStore.active("alice")
        assertNotNull(active, "刚禁言的人应该是生效状态")
        assertEquals(entry.until, active.until)
        assertEquals("刷屏", active.reason)
        assertEquals("admin", active.by)
        // 十分钟后（留点余量）
        assertTrue(active.until > System.currentTimeMillis() + 9 * 60_000, "解禁时刻应该在十分钟左右之后")
    }

    @Test
    fun `unmute lifts it immediately`() {
        MuteStore.mute("alice", 10, "admin")
        assertTrue(MuteStore.unmute("alice"), "解除一个存在的禁言应返回 true")
        assertNull(MuteStore.active("alice"), "解除后不该再生效")
        assertFalse(MuteStore.unmute("alice"), "重复解除应返回 false，别谎报成功")
    }

    @Test
    fun `muting again replaces the deadline instead of stacking`() {
        MuteStore.mute("alice", 60, "admin")
        val second = MuteStore.mute("alice", 5, "admin")
        val active = MuteStore.active("alice")
        assertNotNull(active)
        // 改短了就该真的变短：累加的话这里会是一个多小时
        assertEquals(second.until, active.until, "重复禁言应覆盖成新的解禁时刻")
        assertEquals(1, MuteStore.mutes().size, "同一个人只该有一条记录")
    }

    @Test
    fun `an expired mute stops working on its own`() {
        // 直接把一条已过期的记录写进去（-1 分钟）
        File(MuteStore.DIR).mkdirs()
        file().writeText(
            """{"mutes":{"alice":{"until":${System.currentTimeMillis() - 60_000},"by":"admin","reason":"","createdAt":0}}}"""
        )
        assertNull(MuteStore.active("alice"), "到点之后不依赖任何清理动作就该失效")
        assertTrue(MuteStore.mutes().isEmpty(), "过期记录不该出现在列表里")
    }

    @Test
    fun `expired entries are dropped when the file is rewritten`() {
        File(MuteStore.DIR).mkdirs()
        file().writeText(
            """{"mutes":{"alice":{"until":${System.currentTimeMillis() - 60_000},"by":"admin","reason":"","createdAt":0}}}"""
        )
        // 任意一次写入都会顺手清理
        MuteStore.mute("bob", 10, "admin")
        val text = file().readText()
        assertFalse(text.contains("alice"), "写盘时该把过期的那条丢掉，否则文件只会越攒越多")
        assertTrue(text.contains("bob"))
    }

    @Test
    fun `minutes are clamped to a sane range`() {
        val entry = MuteStore.mute("alice", 100_000, "admin")
        val span = entry.until - entry.createdAt
        assertEquals(MuteStore.MAX_MINUTES * 60_000L, span, "超过上限应夹到一天")
        // 0 或负数夹到 1 分钟：一次「禁言 0 分钟」不该变成永久（或负数导致的永久）
        val tiny = MuteStore.mute("bob", 0, "admin")
        assertEquals(60_000L, tiny.until - tiny.createdAt)
    }

    @Test
    fun `remaining text reads sensibly`() {
        val now = System.currentTimeMillis()
        assertEquals("已结束", MuteStore.remainingText(now - 1000, now))
        // 一分钟以内报秒：说「1 分钟」会让人以为还有一整分钟
        assertEquals("30 秒", MuteStore.remainingText(now + 30_000, now))
        assertEquals("10 分钟", MuteStore.remainingText(now + 10 * 60_000, now))
        assertEquals("1 小时 30 分钟", MuteStore.remainingText(now + 90 * 60_000, now))
    }

    @Test
    fun `a corrupt file does not blow up and counts as nobody muted`() {
        File(MuteStore.DIR).mkdirs()
        file().writeText("{not json at all")
        assertNull(MuteStore.active("alice"), "坏文件应退化成「没人被禁」，而不是抛异常")
        assertTrue(MuteStore.mutes().isEmpty())
    }
}
