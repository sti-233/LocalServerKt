package localserver.utils

import java.io.File

object Logger {
    private val debug = true

    private val logFile by lazy {
        File("logs.txt").apply {
            if (!exists()) createNewFile()
            deleteOnExit()
        }
    }

    fun export(): String {
        return logFile.readText().trimStart('\uFEFF')
    }

    fun debug(message: String) {
        if (!debug) return
        logFile.appendText("[Debug] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }

    fun error(message: String) {
        logFile.appendText("[Error] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }
}