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