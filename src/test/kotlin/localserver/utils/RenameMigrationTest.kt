package localserver.utils

import localserver.module.PictureStore
import localserver.module.Prefs
import localserver.types.Message
import localserver.types.User

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 改名迁移：把「用户名」从各种落盘位置里换掉。
 *
 * 用户名同时被当成四样东西用（归档文件名的一半、消息体里的发送者、图片/AI/配置的目录名），
 * 光改 `userList.json` 会留下一堆对不上的旧数据 —— 私聊会话从侧边栏消失、自己发的旧消息
 * 被当成别人发的、图片库/配置全空。这组测试盯的就是「改名之后旧数据还在、还认得出来」。
 */
class RenameMigrationTest {

    private val msgDir = File("message")
    private val pictureDir = File(PictureStore.DIR)
    private val aiDir = File(AiHistory.DEFAULT_ROOT)
    private val prefsDir = File(Prefs.ROOT)

    @BeforeTest
    fun setUp() {
        listOf(msgDir, pictureDir, aiDir, prefsDir).forEach { it.deleteRecursively() }
        listOf(msgDir, pictureDir, aiDir, prefsDir).forEach { it.mkdirs() }
        Util.setUserList(
            mutableListOf(
                User("10.0.0.1", "alice"),
                User("10.0.0.2", "bob"),
                User("10.0.0.3", "carol")
            )
        )
    }

    @AfterTest
    fun tearDown() {
        listOf(msgDir, pictureDir, aiDir, prefsDir, File("userList.json")).forEach { it.deleteRecursively() }
    }

    private fun archive(base: String, vararg messages: Message): File {
        val f = File(msgDir, "history_$base.json")
        f.writeText(Util.prettyJson.encodeToString(messages.toList()))
        return f
    }

    private fun read(base: String): List<Message> =
        Util.historyJson.decodeFromString(File(msgDir, "history_$base.json").readText())

    // ===== partnerIn：归档名 → 另一个人 =====

    @Test
    fun `partner is found from a private archive name`() {
        assertEquals("bob", Util.partnerIn("alice-bob", "alice"))
        assertEquals("alice", Util.partnerIn("alice-bob", "bob"))
    }

    @Test
    fun `a name containing a dash is not split in the wrong place`() {
        // 不能 split('-')：alice-b 与 bob 的归档叫 alice-b-bob，简单的切分会解出 "alice"
        assertEquals("bob", Util.partnerIn("alice-b-bob", "alice-b"))
        assertEquals("alice-b", Util.partnerIn("alice-b-bob", "bob"))
    }

    @Test
    fun `a name that is not part of the archive is rejected`() {
        assertNull(Util.partnerIn("alice-bob", "carol"))
    }

    // ===== 私聊归档改名 =====

    @Test
    fun `private archives follow the new name`() {
        val src = archive(
            "alice-bob",
            Message("alice", "2026-01-01 10:00:00", "mine"),
            Message("bob", "2026-01-01 10:00:05", "his")
        )
        val stats = Util.migrateRename("alice", "alice2")

        assertTrue(!src.exists(), "旧归档应已删除")
        val moved = read("alice2-bob")
        assertEquals(listOf("alice2", "bob"), moved.map { it.name })
        assertEquals(listOf("mine", "his"), moved.map { it.text })
        assertEquals(1, stats["historyRenamed"])
        // 旧名字的消息在私聊归档里也要改掉，否则前端 msg.name === me 不成立
        assertEquals(1, stats["messagesRewritten"])
    }

    @Test
    fun `an existing archive for the new name is merged instead of overwritten`() {
        archive("alice-bob", Message("bob", "2026-01-01 10:00:00", "old"))
        archive("alice2-bob", Message("bob", "2026-01-02 10:00:00", "new"))

        val stats = Util.migrateRename("alice", "alice2")

        val merged = read("alice2-bob")
        assertEquals(2, merged.size, "两边消息都要在，不能覆盖")
        assertEquals(listOf("old", "new"), merged.map { it.text }, "按时间排序")
        assertEquals(1, stats["historyMerged"])
        assertTrue(!File(msgDir, "history_alice-bob.json").exists())
    }

    @Test
    fun `merging does not duplicate the same message`() {
        val same = Message("bob", "2026-01-01 10:00:00", "same")
        archive("alice-bob", same)
        archive("alice2-bob", same)

        Util.migrateRename("alice", "alice2")

        assertEquals(1, read("alice2-bob").size)
    }

    @Test
    fun `group history keeps its file name but the sender is rewritten`() {
        val day = archive(
            "2026-01-01",
            Message("alice", "2026-01-01 10:00:00", "hello"),
            Message("bob", "2026-01-01 10:00:05", "yo")
        )

        val stats = Util.migrateRename("alice", "alice2")

        assertTrue(day.exists(), "群聊归档按日期命名，不该改名")
        val list = read("2026-01-01")
        assertEquals(listOf("alice2", "bob"), list.map { it.name })
        assertEquals(1, stats["messagesRewritten"])
    }

    @Test
    fun `a self chat archive becomes newname-new name`() {
        // 自己发给自己时归档名是 `我-我`
        val src = archive("alice-alice", Message("alice", "2026-01-01 10:00:00", "note to self"))

        Util.migrateRename("alice", "alice2")

        assertTrue(!src.exists())
        val moved = read("alice2-alice2")
        assertEquals(listOf("alice2"), moved.map { it.name })
        assertEquals("note to self", moved[0].text)
    }

    @Test
    fun `migrating twice is harmless`() {
        archive("alice-bob", Message("alice", "2026-01-01 10:00:00", "hi"))

        Util.migrateRename("alice", "alice2")
        val second = Util.migrateRename("alice", "alice2")

        assertEquals(0, second["historyFiles"])
        assertEquals(0, second["messagesRewritten"])
        assertEquals(1, read("alice2-bob").size)
    }

    @Test
    fun `a corrupt target archive is skipped and the source is kept`() {
        val src = archive("alice-bob", Message("bob", "2026-01-01 10:00:00", "hi"))
        File(msgDir, "history_alice2-bob.json").writeText("[{\"name\": \"A\", ")

        val stats = Util.migrateRename("alice", "alice2")

        assertTrue(src.exists(), "源文件不能删 —— 合并失败时删了就真丢了")
        assertEquals(1, stats["failed"])
    }

    // ===== 图片 / AI / 配置的目录改名 =====

    @Test
    fun `pictures move to the new user directory`() {
        val oldDir = File(pictureDir, "alice").apply { mkdirs() }
        File(oldDir, "a".repeat(64) + ".png").writeBytes(byteArrayOf(1, 2, 3))

        val stats = PictureStore.renameUser("alice", "alice2")

        assertEquals(1, stats["pictures"])
        assertTrue(File(pictureDir, "alice2/" + "a".repeat(64) + ".png").isFile)
        assertTrue(!oldDir.exists(), "空掉的旧目录要删掉，否则还会出现在扫描里")
    }

    @Test
    fun `the same picture already in the new directory is deduped`() {
        val name = "b".repeat(64) + ".jpg"
        File(File(pictureDir, "alice").apply { mkdirs() }, name).writeBytes(byteArrayOf(1))
        File(File(pictureDir, "alice2").apply { mkdirs() }, name).writeBytes(byteArrayOf(1))

        val stats = PictureStore.renameUser("alice", "alice2")

        assertEquals(1, stats["pictures"])
        assertTrue(File(pictureDir, "alice2/$name").isFile)
    }

    @Test
    fun `ai conversations move and shares follow the new owner`() {
        val oldDir = File(aiDir, "alice").apply { mkdirs() }
        val id = "20260101_100000_abcdef"
        File(oldDir, "$id.json").writeText("{}")
        AiHistory.saveShares(
            listOf(localserver.types.AiShare("f".repeat(24), "alice", id, "2026-01-01 10:00:00"))
        )

        val stats = AiHistory.renameUser("alice", "alice2")

        assertEquals(1, stats["conversations"])
        assertEquals(1, stats["shares"])
        assertTrue(File(aiDir, "alice2/$id.json").isFile)
        assertEquals("alice2", AiHistory.loadShares().first().owner)
    }

    @Test
    fun `prefs move and lastRead keys are renamed`() {
        Prefs.merge(
            "alice",
            "chat",
            mapOf("lastRead_alice" to """{"p:bob":"2026-01-01 10:00:00"}""", "other" to "x"),
            emptyList()
        )

        val stats = Prefs.renameUser("alice", "alice2")

        assertEquals(2, stats["keys"])
        assertTrue(!File(prefsDir, "alice.json").exists())
        val page = Prefs.load("alice2")["chat"]!!.let { it as kotlinx.serialization.json.JsonObject }
        assertTrue(page.containsKey("lastRead_alice2"), "已读表的键要跟着改名，否则会话全变未读")
        assertTrue(!page.containsKey("lastRead_alice"))
        assertEquals("x", page["other"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }
}
