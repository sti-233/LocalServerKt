package localserver.utils

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Time {
    fun getCurrentTime(): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        return currentTime().format(formatter)
    }

    fun getCurrentTimeWithDate(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return currentTime().format(formatter)
    }

    fun getCurrentDate(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return currentTime().format(formatter)
    }

    /** 当前时刻（Asia/Shanghai）。需要星期、时分等多个字段时用这个，别在别处另取 ZoneId。 */
    fun now(): ZonedDateTime = currentTime()

    /**
     * 消息是否还在可撤回窗口内（发送时间到现在的时长不超过 2 分钟）。
     *
     * 早先实现是 `Duration.between(...).toMinutes() <= 1`：名字说 2 分钟、实际是"不足 2 分钟"，
     * 且没有下界 —— 时间戳落在未来（客户端时钟快）时 Duration 为负、条件恒真，
     * 任何一条伪造的"未来"消息都能被撤回。
     */
    fun withinTwoMin(givenTimeStr: String): Boolean {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return try {
            val given = LocalDateTime.parse(givenTimeStr, formatter)
            val now = LocalDateTime.parse(getCurrentTimeWithDate(), formatter)
            val elapsed = Duration.between(given, now)
            !elapsed.isNegative && elapsed <= RETRACT_WINDOW
        } catch (e: Exception) {
            false // 时间格式不对 = 不是本服务端发的时间戳，不允许撤回
        }
    }

    /** 可撤回窗口 */
    private val RETRACT_WINDOW: Duration = Duration.ofMinutes(2)

    private fun currentTime(): ZonedDateTime {
        val now = LocalDateTime.now()
        return now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
    }
}