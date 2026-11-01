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

    fun withinTwoMin(givenTimeStr: String): Boolean {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val givenDateTime = LocalDateTime.parse(givenTimeStr, formatter)
        val currentZonedDateTime = LocalDateTime.parse(getCurrentTimeWithDate(), formatter)
        val duration = Duration.between(givenDateTime, currentZonedDateTime)
        val minutes = duration.toMinutes()
        return minutes <= 1
    }

    private fun currentTime(): ZonedDateTime {
        val now = LocalDateTime.now()
        return now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
    }
}