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
 * 分享链接存储（AiHistory 的分享部分）单测：令牌生成、复用、查找、撤销、持久化。
 * 全部在临时目录里跑，不碰真实的 cache/ai。
 */
class AiShareTest {

    private fun tempBase(): File {
        val dir = Files.createTempDirectory("ai-share-test").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun conv(id: String = "20260101_100000_aaaaaa") = AiConversation(
        id = id,
        name = "测试会话",
        createdAt = "2026-01-01 10:00:00",
        updatedAt = "2026-01-01 10:00:00",
        messages = listOf(AiMessage(role = "user", content = "你好", time = "2026-01-01 10:00:00"))
    )

    @Test
    fun tokenIsHexAndLookupable() {
        val base = tempBase()
        val share = AiHistory.createShare("tester", conv().id, base = base)
        assertNotNull(share)
        assertTrue(AiHistory.isValidToken(share.token), "令牌应是合法 hex: " + share.token)

        val found = AiHistory.findShare(share.token, base)
        assertNotNull(found)
        assertEquals("tester", found.owner)
        assertEquals(conv().id, found.convId)
    }

    @Test
    fun creatingTwiceReusesSameToken() {
        val base = tempBase()
        val first = AiHistory.createShare("tester", conv().id, base = base)!!
        val second = AiHistory.createShare("tester", conv().id, base = base)!!
        assertEquals(first.token, second.token, "同一会话重复分享应复用同一令牌")
        assertEquals(1, AiHistory.loadShares(base).size)
    }

    @Test
    fun differentConversationsGetDifferentTokens() {
        val base = tempBase()
        val a = AiHistory.createShare("tester", "20260101_100000_aaaaaa", base = base)!!
        val b = AiHistory.createShare("tester", "20260102_100000_bbbbbb", base = base)!!
        assertFalse(a.token == b.token)
        assertEquals(2, AiHistory.loadShares(base).size)
    }

    @Test
    fun revokeRemovesOnlyOwnShare() {
        val base = tempBase()
        val mine = AiHistory.createShare("tester", conv().id, base = base)!!
        val other = AiHistory.createShare("other", conv().id, base = base)!!

        assertTrue(AiHistory.revokeShare("tester", conv().id, base))
        assertNull(AiHistory.findShare(mine.token, base), "撤销后自己那条应查不到")
        assertNotNull(AiHistory.findShare(other.token, base), "别人的分享不受影响")
        // 再撤一次：已经没有记录，返回 false
        assertFalse(AiHistory.revokeShare("tester", conv().id, base))
    }

    @Test
    fun invalidTokenNeverMatches() {
        val base = tempBase()
        AiHistory.createShare("tester", conv().id, base = base)
        assertFalse(AiHistory.isValidToken(""))
        assertFalse(AiHistory.isValidToken("../../etc/passwd"))
        assertFalse(AiHistory.isValidToken("ABCDEF0123456789ABCDEF01"))
        assertNull(AiHistory.findShare("", base))
        assertNull(AiHistory.findShare("../evil", base))
    }

    @Test
    fun invalidConversationIdIsRefused() {
        val base = tempBase()
        assertNull(AiHistory.createShare("tester", "../evil", base = base))
        assertEquals(0, AiHistory.loadShares(base).size)
    }

    @Test
    fun sharesSurviveReloadFromDisk() {
        val base = tempBase()
        val share = AiHistory.createShare("tester", conv().id, now = "2026-11-01 03:00:00", base = base)!!
        // 重新从磁盘读一遍（同一个目录，新的一次 load）
        val again = AiHistory.findShare(share.token, base)
        assertNotNull(again)
        assertEquals("2026-11-01 03:00:00", again.createdAt)
        assertTrue(File(base, "_shares.json").exists(), "分享记录应落盘到 _shares.json")
    }

    @Test
    fun corruptStoreDoesNotThrow() {
        val base = tempBase()
        File(base, "_shares.json").writeText("{ 这不是 JSON")
        assertEquals(0, AiHistory.loadShares(base).size)
        // 损坏时新建仍应成功（覆盖写入）
        assertNotNull(AiHistory.createShare("tester", conv().id, base = base))
    }
}
