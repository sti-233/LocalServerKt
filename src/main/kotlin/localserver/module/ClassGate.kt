package localserver.module

import localserver.utils.Logger

/**
 * 「阶段变化才动手」的开关门。
 *
 * 轮询式自动控制最容易踩的坑是覆盖用户的手动操作：每 30 秒刷一次 `state = false`，
 * 用户手动 `/start` 会在半分钟内被打回去。这里用 [Timetable.phaseKey] 的思路解决：
 *
 * - 课表阶段（上课那一节 / 下课）没变 → 什么都不做，手动设置的 `state` 保持不变；
 * - 阶段变了（上节课→下节课、上课→下课、下课→上课）→ 按课表设一次。
 *
 * 结果：手动 `/start` 能一直撑到下一次上下课切换，而不是被下一轮轮询抹掉。
 */
internal class ClassGate(private val setState: (inClass: Boolean) -> Unit) {

    private var lastPhase: String? = null

    /** 传当前阶段标识；返回这次是否真的动了手（便于测试与日志）。 */
    fun apply(phase: String): Boolean {
        if (phase == lastPhase) return false
        val first = lastPhase == null
        lastPhase = phase
        val inClass = phase != "free"
        setState(inClass)
        // 首次同步只打一行，避免启动日志里莫名多出一条"自动关闭"
        Logger.debug(
            if (first) "课表：启动时同步状态，" + (if (inClass) "正课中，服务关闭" else "非正课，服务开启")
            else if (inClass) "课表：正课开始（$phase），服务已自动关闭"
            else "课表：正课结束，服务已自动恢复"
        )
        return true
    }
}
