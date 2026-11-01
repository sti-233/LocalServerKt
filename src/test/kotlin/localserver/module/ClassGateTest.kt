package localserver.module

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ClassGate] 的核心约定：阶段不变就不碰状态，所以手动 `/start`、`/exit` 不会被轮询覆盖。
 */
class ClassGateTest {

    @Test
    fun `first apply sets state from the timetable`() {
        var state = true
        val gate = ClassGate { inClass -> state = !inClass }

        assertTrue(gate.apply("class:2026-11-02:1"))
        assertFalse(state)
    }

    @Test
    fun `repeated apply within one period keeps manual override`() {
        var state = true
        val gate = ClassGate { inClass -> state = !inClass }

        gate.apply("class:2026-11-02:1")   // 上课 → state = false
        state = true                       // 用户手动 /start
        // 同一节内反复轮询：阶段没变，不能再把 state 打回 false
        assertFalse(gate.apply("class:2026-11-02:1"))
        assertTrue(state)
    }

    @Test
    fun `next period overrides again`() {
        var state = true
        val gate = ClassGate { inClass -> state = !inClass }

        gate.apply("class:2026-11-02:1")
        state = true
        assertTrue(gate.apply("class:2026-11-02:2"))   // 换到第 2 节 → 重新关闭
        assertFalse(state)
    }

    @Test
    fun `break restores the service`() {
        var state = true
        val gate = ClassGate { inClass -> state = !inClass }

        gate.apply("class:2026-11-02:1")
        assertFalse(state)
        assertTrue(gate.apply("free"))
        assertTrue(state)
    }

    @Test
    fun `manual exit during break survives until next class`() {
        var state = true
        val gate = ClassGate { inClass -> state = !inClass }

        gate.apply("free")
        state = false                      // 课间手动 /exit
        assertFalse(gate.apply("free"))    // 轮询不该把它改回 true
        assertFalse(state)
        // 下一节上课会按课表设一次（仍然是 false，但这是课表要求的，不是被手动值带偏）
        assertTrue(gate.apply("class:2026-11-02:3"))
        assertFalse(state)
        // 再下课后自动恢复
        gate.apply("free")
        assertTrue(state)
    }
}
