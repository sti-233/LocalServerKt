package localserver.utils

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 正课判定：上午+下午 9 节里科目不是体育/自习的才算正课。
 *
 * 作息来自 `src/main/resources/timetable.json`，与课表页共用同一份数据。
 */
class TimetableTest {

    // 固定用上海时区构造时刻，避免测试机时区影响结果
    private fun at(dayOfWeek: Int, hour: Int, minute: Int, saturday: Boolean = false): ZonedDateTime {
        // 2026-11-02 是周一，往后推 dayOfWeek 天（周一=0 … 周日=6）
        val base = LocalDateTime.of(2026, 11, 2, hour, minute)
        return base.plusDays(dayOfWeek.toLong()).atZone(ZoneId.of("Asia/Shanghai"))
    }

    @Test
    fun `monday first period is class`() {
        // 周一第 1 节是班会，算正课
        assertEquals(1, Timetable.currentPeriod(at(0, 8, 10)))
        assertTrue(Timetable.inClass(at(0, 8, 10)))
    }

    @Test
    fun `break between periods is free`() {
        assertNull(Timetable.currentPeriod(at(0, 8, 45)))
        assertFalse(Timetable.inClass(at(0, 8, 45)))
    }

    @Test
    fun `early study and night study are not class`() {
        // 早自习 07:30-08:00、晚自习 18:30-20:00 都不算正课
        assertNull(Timetable.currentPeriod(at(0, 7, 45)))
        assertNull(Timetable.currentPeriod(at(0, 19, 0)))
        assertFalse(Timetable.inClass(at(0, 7, 45)))
        assertFalse(Timetable.inClass(at(0, 19, 0)))
    }

    @Test
    fun `pe and self study periods are skipped`() {
        // 周三：第 4 节体育、第 5 节自习
        assertNull(Timetable.currentPeriod(at(2, 10, 55)))
        assertNull(Timetable.currentPeriod(at(2, 11, 45)))
        // 同一份课表里第 3 节是语文，仍应算正课
        assertEquals(3, Timetable.currentPeriod(at(2, 10, 10)))
    }

    @Test
    fun `fifth period ends at twelve thirty`() {
        // 第 5 节 11:40-12:30，边界上要落在第 5 节（to 是开区间）
        assertEquals(5, Timetable.currentPeriod(at(0, 11, 40)))
        assertEquals(5, Timetable.currentPeriod(at(0, 12, 29)))
        assertNull(Timetable.currentPeriod(at(0, 12, 30)))
    }

    @Test
    fun `afternoon periods use the same numbering`() {
        // 下午第 6 节 14:30-15:10，周一第 6 节是生物
        assertEquals(6, Timetable.currentPeriod(at(0, 14, 30)))
        assertEquals(9, Timetable.currentPeriod(at(0, 17, 0)))
        assertNull(Timetable.currentPeriod(at(0, 17, 40)))
    }

    @Test
    fun `sunday daytime has no class`() {
        // 周日的格子是空的 → 一整天都不算正课（只有晚自习，而晚自习本来就不算）
        for (h in listOf(8, 10, 11, 14, 15, 16, 17)) {
            assertNull(Timetable.currentPeriod(at(6, h, 5)), "周日 $h 点不该有正课")
        }
    }

    @Test
    fun `saturday uses its own bell schedule`() {
        // 周六第 1 节 08:00-08:40（第 1 节 A/B 两套课表都是正课：数学 / 语文）
        assertEquals(1, Timetable.currentPeriod(at(5, 8, 10)))
        // 平时 09:30-09:50 是大课间，周六 09:30-09:40 是课间
        assertNull(Timetable.currentPeriod(at(5, 9, 35)))
        // 周六 12:00 放学，12:05 已经没有正课
        assertNull(Timetable.currentPeriod(at(5, 12, 5)))
    }

    @Test
    fun `week one starts on the last monday of august`() {
        // 第 1 周 = 2026-08-31（周一）那一周；同一个周内任何一天都算第 1 周
        val w1Mon = LocalDateTime.of(2026, 8, 31, 12, 0).atZone(ZoneId.of("Asia/Shanghai"))
        val w1Sun = LocalDateTime.of(2026, 9, 6, 12, 0).atZone(ZoneId.of("Asia/Shanghai"))
        val w2Mon = LocalDateTime.of(2026, 9, 7, 12, 0).atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(1, Timetable.weekOf(w1Mon))
        assertEquals(1, Timetable.weekOf(w1Sun))
        assertEquals(2, Timetable.weekOf(w2Mon))
        assertFalse(Timetable.isEvenWeek(w1Mon))
        assertTrue(Timetable.isEvenWeek(w2Mon))
        // 测试基准日 2026-11-02（周一）正好是第 10 周（双周）
        assertEquals(10, Timetable.weekOf(at(0, 12, 0)))
        assertTrue(Timetable.isEvenWeek(at(0, 12, 0)))
    }

    @Test
    fun `saturday picks a or b by week parity`() {
        // 2026-10-31 是第 9 周（单周 → A 课表），第 3 节 A=物理，算正课
        val oddSat = LocalDateTime.of(2026, 10, 31, 10, 0).atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(9, Timetable.weekOf(oddSat))
        assertEquals(3, Timetable.currentPeriod(oddSat))

        // 2026-11-07 是第 10 周（双周 → B 课表），第 3 节 B=自习，不算正课
        val evenSat = LocalDateTime.of(2026, 11, 7, 10, 0).atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(10, Timetable.weekOf(evenSat))
        assertNull(Timetable.currentPeriod(evenSat))

        // 第 4 节 A=英语、B=化学，单双周都算正课
        assertEquals(4, Timetable.currentPeriod(
            LocalDateTime.of(2026, 10, 31, 11, 0).atZone(ZoneId.of("Asia/Shanghai"))))
        assertEquals(4, Timetable.currentPeriod(
            LocalDateTime.of(2026, 11, 7, 11, 0).atZone(ZoneId.of("Asia/Shanghai"))))
    }

    @Test
    fun `phase key is stable inside one period and changes across periods`() {
        assertEquals("free", Timetable.phaseKey(at(0, 8, 45)))
        assertEquals(Timetable.phaseKey(at(0, 8, 1)), Timetable.phaseKey(at(0, 8, 39)))
        assertTrue(Timetable.phaseKey(at(0, 8, 1)) != Timetable.phaseKey(at(0, 8, 55)))
        // 上课时 key 带上日期，跨天同节次不会误判成"没变化"
        assertTrue(Timetable.phaseKey(at(0, 8, 1)) != Timetable.phaseKey(at(1, 8, 1)))
    }
}
