package LocalServer.Utils

import LocalServer.Types.User
import LocalServer.Types.Message

import kotlinx.serialization.json.*
import java.io.File

object Util {
    private val prettyJson = Json { prettyPrint = true }

    public fun getUserList(): MutableList<User> {
        val userFile = File("userList.json")
        if (!userFile.exists()) {
            userFile.createNewFile()
            return mutableListOf<User>()
        }
        return Json.decodeFromString<MutableList<User>>(userFile.readText())
    }

    public fun setUserList(userList: List<User>) {
        val userFile = File("userList.json")
        if (!userFile.exists()) {
            userFile.createNewFile()
        }
        userFile.writeText(prettyJson.encodeToString(userList))
    }

    public fun getHistory(target: String? = null): String {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        if (!historyFile.exists()) {
            if (!historyFile.parentFile.exists()) historyFile.parentFile.mkdirs()
            historyFile.createNewFile()
            historyFile.writeText(prettyJson.encodeToString(listOf(Message("System", Time.getCurrentTime(), "New file created."))))
        }
        return historyFile.readText()
    }

    public fun getHistoryList(target: String? = null): MutableList<Message> {
        return Json.decodeFromString<MutableList<Message>>(getHistory(target))
    }

    public fun addHistory(history: Message, target: String? = null) {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        val historyList = getHistoryList(target)
        historyList.add(history)
        historyFile.writeText(prettyJson.encodeToString(historyList))
    }

    public fun delHistory(name: String, time: String, target: String? = null) {
        val historyFile = File("message/history_${target ?: Time.getCurrentDate()}.json")
        val historyList = getHistoryList(target)
        historyList.removeIf { (it.name == name) && (it.time == time) && Time.withinTwoMin(it.time)}
        historyFile.writeText(prettyJson.encodeToString(historyList))
    }

    public fun getTarget(current: String, targetUser: String): String {
        val list = listOf(current, targetUser).sorted()
        return list[0] + "-" + list[1]
    }

    public fun getUserName(ip: String): String {
        return Util.getUserList().firstOrNull { it.ip.equals(ip).and(!it.name.isNullOrEmpty()) }?.name ?: throw Exception("User name is null!")
    }

    public fun getUserIp(name: String): String {
        return Util.getUserList().firstOrNull { it.name.equals(name) }?.ip ?: throw Exception("User is null!")
    }

    public fun userExists(key: String): Boolean {
        return Util.getUserList().firstOrNull { it.ip.equals(key).or(it.name.equals(key)) } != null
    }
}