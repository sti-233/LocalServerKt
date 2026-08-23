package localserver.utils

import localserver.types.User
import localserver.types.Message

import kotlinx.serialization.json.*
import java.io.File

object Util {
    val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun getUserList(): MutableList<User> {
        val userFile = File("userList.json")
        if (!userFile.exists()) {
            userFile.createNewFile()
            return mutableListOf<User>()
        }
        return Json.decodeFromString<MutableList<User>>(userFile.readText().trimStart('\uFEFF'))
    }

    fun setUserList(userList: List<User>) {
        val userFile = File("userList.json")
        if (!userFile.exists()) {
            userFile.createNewFile()
        }
        userFile.writeText(prettyJson.encodeToString(userList))
    }

    fun getHistory(target: String? = null): String {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        if (!historyFile.exists()) {
            if (!historyFile.parentFile.exists()) historyFile.parentFile.mkdirs()
            historyFile.createNewFile()
            historyFile.writeText(prettyJson.encodeToString(listOf(Message("System", Time.getCurrentTimeWithDate(), "New file created."))))
        }
        return historyFile.readText().trimStart('\uFEFF')
    }

    fun getHistoryList(target: String? = null): MutableList<Message> {
        return Json.decodeFromString<MutableList<Message>>(getHistory(target))
    }

    fun addHistory(history: Message, target: String? = null) {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        val historyList = getHistoryList(target)
        historyList.add(history)
        historyFile.writeText(prettyJson.encodeToString(historyList))
    }

    fun delHistory(name: String, time: String, target: String? = null) {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        val historyList = getHistoryList(target)
        historyList.removeIf { (it.name == name) && (it.time == time)}
        historyFile.writeText(prettyJson.encodeToString(historyList))
    }

    fun getTarget(current: String, targetUser: String): String {
        val list = listOf(current, targetUser).sorted()
        return list[0] + "-" + list[1]
    }

    fun getUserName(ip: String): String {
        return Util.getUserList().firstOrNull { it.ip.equals(ip).and(!it.name.isNullOrEmpty()) }?.name ?: throw Exception("User name is null!")
    }

    fun getUserIp(name: String): String {
        return Util.getUserList().firstOrNull { it.name.equals(name) }?.ip ?: throw Exception("User is null!")
    }

    fun userExists(key: String): Boolean {
        return Util.getUserList().firstOrNull { it.ip.equals(key).or(it.name.equals(key)) } != null
    }
}