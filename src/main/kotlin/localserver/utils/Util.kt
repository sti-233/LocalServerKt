package localserver.utils

import localserver.types.User
import localserver.types.Message

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object Util {
    val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 读存档专用：宽松解析，多出来的字段一律忽略。
     *
     * 存档是**长期存在**的数据，而 [Message] 是**会变**的代码。哪天给它加个 `id`/`edited`/
     * `revoked`，用严格解析去读旧客户端写下的、或者手工编辑过的文件就会抛
     * `Encountered an unknown key`。这个异常不是「那一条显示不出来」这么轻 ——
     * 读取发生在 `addHistory` 的「读全文 → 改 → 覆写」第一步，会一路冒到 WS 读循环的
     * `catch (e: Exception)`，把整条连接拆掉：表现是「发一条消息就断线，重连再发又断」。
     *
     * 写仍走 [prettyJson]（干净、不带陌生字段），读用它兜住历史包袱。
     */
    val historyJson = Json { ignoreUnknownKeys = true }

    // userList.json 的解析结果缓存，键是 (文件长度, mtime)。
    // 这份文件在热路径上被反复整份读盘 + 反序列化：WS 每帧都要 getUserName，
    // 每次图片操作都要 pictureUserOf，而它通常只有几行。
    @Volatile
    private var userCache: Pair<Pair<Long, Long>, List<User>>? = null

    fun getUserList(): MutableList<User> {
        val userFile = File("userList.json")
        if (!userFile.exists()) {
            writeAtomic(userFile, prettyJson.encodeToString(mutableListOf<User>()))
            return mutableListOf<User>()
        }
        val stamp = userFile.length() to userFile.lastModified()
        userCache?.let { if (it.first == stamp) return it.second.toMutableList() }
        val text = userFile.readText().trimStart('\uFEFF').trim()
        if (text.isEmpty()) return mutableListOf<User>()
        val list = try {
            historyJson.decodeFromString<MutableList<User>>(text)
        } catch (e: Exception) {
            Logger.error("Failed to parse userList.json: ${e.message}. Returning empty list.")
            mutableListOf<User>()
        }
        userCache = stamp to list
        // 回一份拷贝：调用方会 add/removeIf 之后再 setUserList，
        // 直接交出缓存对象会让「还没落盘的改动」被别的线程读到
        return list.toMutableList()
    }

    fun setUserList(userList: List<User>) {
        writeAtomic(File("userList.json"), prettyJson.encodeToString(userList))
        userCache = null
    }

    // ===== 聊天记录 =====
    // add/del 都是「读整份 → 改 → 整份覆写」：并发时后写的那次会覆盖掉先写的消息（丢消息）。
    // 按文件分锁，不同会话之间不互斥。
    private val historyLocks = ConcurrentHashMap<String, Any>()

    private fun lockOf(file: File): Any = historyLocks.computeIfAbsent(file.path) { Any() }

    /**
     * 原子落盘：先写 `<名字>.tmp` 再改名。
     *
     * 直接 writeText 到目标文件时，进程若在写一半退出会留下半截 JSON，
     * 之后每次读都解析失败 —— 表现为会话白屏、最后一条消息判不出来。
     */
    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text)
            // 必须用 Files.move + REPLACE_EXISTING：File.renameTo 在 Windows 上不能覆盖
            // 已存在的目标，只能「先删再改名」，而删掉到改名之间有个窗口 —— 这期间文件
            // 根本不存在。并发读（/history、/conversations 扫目录、另一个线程的
            // getHistory）会看到"归档少了"，于是把它当成新会话重新建一份空档，
            // 测试里就撞见过 message/ 下只剩 .tmp 的中间态。
            // REPLACE_EXISTING 直接把新 inode 换上去，没有缺席时刻。
            moveReplacing(tmp, file)
        } catch (e: Exception) {
            Logger.error("writeAtomic failed on ${file.path}: ${e.message}")
            tmp.delete()
        }
    }

    private fun moveReplacing(tmp: File, file: File) {
        val target = file.toPath()
        val source = tmp.toPath()
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: Exception) {
            // 跨卷/特殊文件系统不支持原子移动时退一步：不要求原子，但也不留缺席窗口
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (e: Exception) {
            Logger.error("moveReplacing failed on ${file.path}: ${e.message}")
        }
        // 最后兜底：直接覆盖写（内容正确，但写一半时进程被杀会留半截，只能算保底）
        file.writeText(tmp.readText())
        tmp.delete()
    }

    private fun historyFileOf(target: String?): File =
        File("message/history_${target ?: Time.getCurrentDate()}.json")

    fun getHistory(target: String? = null): String {
        val historyFile = historyFileOf(target)
        if (!historyFile.exists()) {
            // 两个请求同时发现文件不存在会各建一份，后写的覆盖先写的，锁住
            synchronized(lockOf(historyFile)) {
                if (!historyFile.exists()) {
                    historyFile.parentFile?.mkdirs()
                    deleteEmptyHistory()
                    writeAtomic(
                        historyFile,
                        prettyJson.encodeToString(
                            listOf(Message("System", Time.getCurrentTimeWithDate(), "New file created."))
                        )
                    )
                }
            }
        }
        return historyFile.readText().trimStart('\uFEFF')
    }

    // 清理空归档：只含一条 System 占位消息（"New file created."）的 history 文件直接删除。
    // 在创建新文件之前调用，新文件尚不存在，无需排除；解析失败的文件跳过，不误删
    private fun deleteEmptyHistory() {
        File("message").listFiles { f -> f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json") }?.forEach { f ->
            val list = try {
                historyJson.decodeFromString<MutableList<Message>>(f.readText().trimStart('\uFEFF'))
            } catch (e: Exception) {
                return@forEach
            }
            if (list.size == 1 && list[0].name == "System") f.delete()
        }
    }

    fun getHistoryList(target: String? = null): MutableList<Message> {
        return historyJson.decodeFromString<MutableList<Message>>(getHistory(target))
    }

    fun addHistory(history: Message, target: String? = null) {
        val historyFile = historyFileOf(target)
        synchronized(lockOf(historyFile)) {
            val historyList = getHistoryList(target)
            historyList.add(history)
            writeAtomic(historyFile, prettyJson.encodeToString(historyList))
        }
    }

    fun delHistory(name: String, time: String, target: String? = null) {
        val historyFile = historyFileOf(target)
        synchronized(lockOf(historyFile)) {
            val historyList = getHistoryList(target)
            historyList.removeIf { (it.name == name) && (it.time == time) }
            writeAtomic(historyFile, prettyJson.encodeToString(historyList))
        }
    }

    fun getTarget(current: String, targetUser: String): String {
        val list = listOf(current, targetUser).sorted()
        return list[0] + "-" + list[1]
    }

    fun getUserName(ip: String): String {
        return Util.getUserList().firstOrNull { (it.ip == ip).and(!it.name.isNullOrEmpty()) }?.name ?: throw Exception("User name is null!")
    }

    fun getUserIp(name: String): String {
        return Util.getUserList().firstOrNull { it.name.equals(name) }?.ip ?: throw Exception("User is null!")
    }

    fun userExists(key: String): Boolean {
        return Util.getUserList().firstOrNull { (it.ip == key).or(it.name.equals(key)) } != null
    }
}
