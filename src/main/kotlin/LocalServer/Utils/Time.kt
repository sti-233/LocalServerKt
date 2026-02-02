package LocalServer.Utils

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Time {
    public fun getCurrentTime(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return currentTime(formatter)
    }

    public fun getCurrentDate(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return currentTime(formatter)
    }

    private fun currentTime(formatter: DateTimeFormatter): String {
        val now = LocalDateTime.now()
        val zonedDateTime = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Asia/Shanghai"))
        return zonedDateTime.format(formatter)
    }
}