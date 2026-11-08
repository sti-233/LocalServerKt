package localserver.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 撤回窗口（`Time.withinTwoMin`）的边界。
 *
 * 早先名字叫"2 分钟"、实现是 `toMinutes() <= 1`（实际不到 2 分钟），而且没有下界：
 * 时间戳落在未来时 Duration 为负、条件恒真 —— 一条伪造的"未来"消息永远撤得掉。
 */
class TimeWithinTwoMinTest {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 相对服务端当前时间偏移若干秒，格式化成服务端那种时间戳 */
    private fun stamp(secondsAgo: Long): String =
        LocalDateTime.parse(Time.getCurrentTimeWithDate(), fmt).minusSeconds(secondsAgo).format(fmt)

    @Test
    fun `a just sent message can be revoked`() {
        assertTrue(Time.withinTwoMin(stamp(0)))
        assertTrue(Time.withinTwoMin(stamp(30)))
    }

    @Test
    fun `the window really is two minutes`() {
        assertTrue(Time.withinTwoMin(stamp(110)), "110s must still be inside the 2 minute window")
        assertFalse(Time.withinTwoMin(stamp(125)), "125s must be rejected")
    }

    @Test
    fun `a timestamp in the future is rejected`() {
        // 负偏移 = 未来的时间戳（客户端时钟快，或有人伪造）
        assertFalse(Time.withinTwoMin(stamp(-60)), "future timestamps must not pass the check")
    }

    @Test
    fun `a malformed timestamp is rejected instead of throwing`() {
        assertFalse(Time.withinTwoMin("not a time"))
        assertFalse(Time.withinTwoMin(""))
        assertFalse(Time.withinTwoMin("2026-13-45 99:99:99"))
    }
}
