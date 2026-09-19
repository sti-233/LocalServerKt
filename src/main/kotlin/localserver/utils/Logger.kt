package localserver.utils

import java.io.File

object Logger {
    private val debug = true

    private val logFile by lazy {
        File("logs.txt").apply {
            if (exists()) delete()
            createNewFile()
        }
    }

    fun export(): String {
        return logFile.readText().trimStart('\uFEFF')
    }

    fun debug(message: String) {
        if (!debug) return
        println("[Debug] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
        logFile.appendText("[Debug] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }

    fun error(message: String) {
        println("[Error] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
        logFile.appendText("[Error] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }
}