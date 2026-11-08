package localserver.utils

import localserver.types.Message
import localserver.types.User

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用户名写入口的校验：`-` 与控制字符一律拒绝。
 *
 * 为什么必须禁 `-`：私聊归档名是 `history_<两人名排序后以 '-'>.json`。名字本身带 `-`
 * 时，从文件名反推「是哪两个人」就有多个解 —— `alice-b` 与 `bob` 的归档叫
 * `alice-b-bob`，读回来可能是 `alice` + `b-bob`，前端 `partnerOf` 的 `split('-')`
 * 会直接解错人。与其在读取侧做各种猜测，不如在写入口就禁掉这个字符。
 *
 * 但要注意：**只约束新写入的名字**。已经存在的、带 `-` 的老名字必须依然能读出来，
 * 否则这次改动会变成一次静默的数据丢失。下面最后一个用例钉住这一面。
 */
class UserNameValidationTest {

    private val msgDir = File("message")

    @BeforeTest
    fun setUp() {
        msgDir.deleteRecursively()
        msgDir.mkdirs()
        Util.setUserList(
            mutableListOf(
                User("10.0.0.1", "alice"),
                User("10.0.0.2", "bob")
            )
        )
    }

    @Test
    fun `a name with a hyphen is rejected`() {
        assertFalse(Util.isValidUserName("alice-b"))
        assertFalse(Util.isValidUserName("-"))
        assertFalse(Util.isValidUserName("bob-"))
    }

    @Test
    fun `a name with control characters is rejected`() {
        assertFalse(Util.isValidUserName("alice\nbob"))
        assertFalse(Util.isValidUserName("alice\u0000bob"))
        assertFalse(Util.isValidUserName("alice\u007fbob"))
    }

    @Test
    fun `a blank name is rejected`() {
        assertFalse(Util.isValidUserName(""))
        assertFalse(Util.isValidUserName("   "))
    }

    @Test
    fun `ordinary names are accepted`() {
        assertTrue(Util.isValidUserName("alice"))
        assertTrue(Util.isValidUserName("张三"))
        assertTrue(Util.isValidUserName("alice_2"))
        assertTrue(Util.isValidUserName("王五.3"))
    }

    /**
     * 关键回归：校验只挡新名字，老档必须照旧可读。
     *
     * 造一份 `alice-b` 与 `bob` 的私聊归档，确认 [Util.partnerIn] 仍能从文件名反推出
     * `bob`，且 [Util.getHistoryList] 读得出内容 —— 也就是说，禁用 `-` 不会让已有
     * 数据变成孤儿。
     */
    @Test
    fun `an existing archive whose name contains a hyphen is still readable`() {
        val base = Util.getTarget("alice-b", "bob")
        assertEquals("alice-b-bob", base)
        assertFalse(Util.isValidUserName("alice-b"))

        val file = File(msgDir, "history_$base.json")
        file.writeText(
            Util.prettyJson.encodeToString(
                listOf(
                    Message("alice-b", "2026-11-01 10:00:00", "hello"),
                    Message("bob", "2026-11-01 10:01:00", "hi")
                )
            )
        )

        assertEquals("bob", Util.partnerIn(base, "alice-b"))
        assertEquals("alice-b", Util.partnerIn(base, "bob"))
        // 歧义是真的：同一个文件名，"alice" 也能被认成当事人（另一半是 "b-bob"），
        // 因为 getTarget("alice", "b-bob") 恰好也拼回 "alice-b-bob"。
        // partnerIn 只能保证「返回的那个人与 from 拼回去等于原名」，没法排除这种多解 ——
        // 这正是为什么要在写入口禁掉 '-'，让新名字不再产生这种文件名。
        assertEquals("b-bob", Util.partnerIn(base, "alice"))
        val list = Util.getHistoryList(base)
        assertEquals(2, list.size)
        assertEquals("hello", list[0].text)
    }
}
