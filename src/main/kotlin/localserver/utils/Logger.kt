package localserver.utils

import java.io.File

object Logger {
    // 注意：不要命名为 debug/error，否则会与下面的同名函数冲突（Kotlin 属性优先解析）
    private val debugEnabled = true

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
        if (!debugEnabled) return
        println("[Debug] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
        logFile.appendText("[Debug] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }

    fun error(message: String) {
        println("[Error] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
        logFile.appendText("[Error] ${Time.getCurrentTimeWithDate()}\n$message\n\n")
    }
}