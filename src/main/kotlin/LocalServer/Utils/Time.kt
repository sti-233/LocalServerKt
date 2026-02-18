package LocalServer.Utils

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Time {
    public fun getCurrentTime(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return currentTime().format(formatter)
    }

    public fun getCurrentDate(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return currentTime().format(formatter)
    }

    public fun withinTwoMin(givenTimeStr: String): Boolean {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val givenDateTime = LocalDateTime.parse(givenTimeStr, formatter)
        val currentZonedDateTime = LocalDateTime.parse(getCurrentTime(), formatter)
        val duration = Duration.between(givenDateTime, currentZonedDateTime)
        val minutes = duration.toMinutes()
        return minutes <= 1
    }

    private fun currentTime(): ZonedDateTime {
        val now = LocalDateTime.now()
        return now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
    }
}