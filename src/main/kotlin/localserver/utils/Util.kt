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

    /**
     * 用户名是否可用（新增/改名时校验）。
     *
     * 挡住 `-`：它是私聊归档名的分隔符（`history_<排序后两人名>.json`）。名字里带它，
     * `alice-b` 与 `bob` 的归档就叫 `alice-b-bob`，光看文件名分不清切点 —— 前端
     * `partnerOf` 的 `split('-')` 会解出 `alice`，服务端 `getTarget` 也拼不出原名。
     * 写入口禁掉，归档名就永远无歧义，不必再猜。
     *
     * 控制字符一并挡掉：它们混进文件名与 JSON 键，肉眼只看得到空白。
     *
     * 注意只约束**新写入**的名字，不追溯现有的：读取侧 [partnerIn] 按 `getTarget`
     * 回验，带 `-` 的老名字仍然认得出来，不会因为加了这个校验就读不出旧档。
     */
    fun isValidUserName(name: String): Boolean {
        if (name.isBlank()) return false
        return name.none { it == '-' || it.isISOControl() }
    }

    // ===== 改名迁移 =====
    // 用户名同时被当成「归档文件名的一部分」和「消息体里的发送者」，改名之后两头都对不上：
    //   - 私聊归档叫 history_旧名-对方.json，与新名字拼出来的 getTarget(新名, 对方) 不符，
    //     chat.html 的 `p.split('-').includes(me)` 直接跳过整条会话 → 侧边栏里会话消失，
    //     手打旧名也读不出来（/history 会因 userExists(旧名) 为 false 回 400）；
    //   - 消息体的 name 还是旧名，前端 `msg.name === me` 不成立，自己发的旧消息被当成
    //     别人发的（亮未读红点、没有撤回按钮）。
    // 所以改名必须连带迁移数据，且要做到「没改过名也跑不坏」（幂等）。

    /** 迁移期间独占：改名要同时动源与目标两个文件，逐个加锁容易和 addHistory 交叉 */
    private val renameLock = Any()

    private val DATE_ARCHIVE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /**
     * 从私聊归档名里找出「另一个人」，[from] 必须是参与者之一；解析不出返回 null。
     *
     * 名字本身可能含 `-`（[AiHistory.sanitizeUser] 放行它），所以**不能 `split('-')`**：
     * 把每个 `-` 当作切点试一遍，用 [getTarget] 回验（排序后拼回去必须等于原名），
     * 再要求 [from] 出现在两侧之一，才能排除 `alice-bob-cat` 这种多解。
     */
    fun partnerIn(fileBase: String, from: String): String? {
        for (i in fileBase.indices) {
            if (fileBase[i] != '-') continue
            val a = fileBase.substring(0, i)
            val b = fileBase.substring(i + 1)
            if (a.isEmpty() || b.isEmpty()) continue
            if (getTarget(a, b) != fileBase) continue
            if (a == from) return b
            if (b == from) return a
        }
        return null
    }

    /**
     * 把 [from] 相关的聊天记录迁到 [to] 名下：
     * 1. 私聊归档改名（目标已存在就按时间合并，不覆盖、不丢消息）；
     * 2. 所有存档里 `name == from` 的消息改写为 [to]（群聊按天归档也要改）。
     *
     * 幂等：跑第二遍时已经没有 `from` 参与的归档与消息，返回全 0。
     * 内容不删：合并策略下两条会话的消息都留着，只在同一 (name,time,text) 上取一条。
     */
    fun migrateRename(from: String, to: String): Map<String, Int> {
        val stats = mutableMapOf(
            "historyFiles" to 0, "historyRenamed" to 0, "historyMerged" to 0,
            "messagesRewritten" to 0, "failed" to 0
        )
        if (from == to || from.isBlank() || to.isBlank()) return stats
        synchronized(renameLock) {
            val dir = File("message")
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json")
            }.orEmpty()

            // 第 1 步：私聊归档改名/合并
            for (f in files) {
                val base = f.name.removePrefix("history_").removeSuffix(".json")
                if (DATE_ARCHIVE.matches(base)) continue // 群聊按日期，与名字无关
                val other = partnerIn(base, from) ?: continue
                // 自己发给自己的归档叫 `我-我`：partnerIn 会把两侧都解成本人，改完之后应该是
                // `新名-新名`，不能拼成 `旧名-新名`（那样前端 partnerOf 会解出一个不存在的人）
                val destBase = if (other == from) getTarget(to, to) else getTarget(to, other)
                if (destBase == base) continue // 例如改名前后的名字排序后仍拼出同一个串
                stats["historyFiles"] = stats["historyFiles"]!! + 1
                val destFile = historyFileOf(destBase)
                val srcList = readHistoryFile(f)
                if (srcList == null) {
                    stats["failed"] = stats["failed"]!! + 1
                    continue
                }
                val rewritten = srcList.map { m ->
                    if (m.name == from) stats["messagesRewritten"] = stats["messagesRewritten"]!! + 1
                    rewriteName(m, from, to)
                }
                val destList = if (destFile.exists()) readHistoryFile(destFile) else null
                if (destFile.exists() && destList == null) {
                    // 目标文件坏了：不动它，也不删源文件，留给人处理
                    Logger.error("migrateRename: refuse to merge into unreadable ${destFile.path}")
                    stats["failed"] = stats["failed"]!! + 1
                    continue
                }
                if (destList != null) {
                    // 去重键：同一条消息（同人同时刻同内容）只留一条。用不可见字符当分隔符，
                    // 免得 "ab|cd" 和 "a|bcd" 撞成同一个键
                    val seen = mutableSetOf<String>()
                    val sep = '\u0000'
                    val union = (destList + rewritten)
                        .filter { seen.add("" + it.name + sep + it.time + sep + it.text) }
                        .sortedBy { it.time }
                    writeAtomic(destFile, prettyJson.encodeToString(union.toMutableList()))
                    stats["historyMerged"] = stats["historyMerged"]!! + 1
                } else {
                    writeAtomic(destFile, prettyJson.encodeToString(rewritten.toMutableList()))
                    stats["historyRenamed"] = stats["historyRenamed"]!! + 1
                }
                if (!f.delete()) {
                    Logger.error("migrateRename: cannot delete ${f.path} after writing ${destFile.path}")
                    stats["failed"] = stats["failed"]!! + 1
                }
            }

            // 第 2 步：其余存档（尤其是群聊按天归档）里的发送者名字
            val remaining = dir.listFiles { f ->
                f.isFile && f.name.startsWith("history_") && f.name.endsWith(".json")
            }.orEmpty()
            for (f in remaining) {
                val list = readHistoryFile(f) ?: continue
                // 判据不能只看「有没有旧名字发的消息」：一条别人发的消息也可能
                // 通过 reply 快照引用着旧名字，那种文件同样需要改写（否则引用块里
                // 一直挂着旧名，看着像另一个人）。两种引用都算命中。
                val touched = list.any { it.name == from || it.reply?.name == from }
                if (!touched) continue
                val rewritten = list.map { m ->
                    if (m.name == from) stats["messagesRewritten"] = stats["messagesRewritten"]!! + 1
                    rewriteName(m, from, to)
                }
                writeAtomic(f, prettyJson.encodeToString(rewritten.toMutableList()))
            }
        }
        return stats
    }

    /**
     * 把一条消息里所有「指向旧名字」的地方换成新名字。
     *
     * 除了 [Message.name]，还要改 [Message.reply] 里的快照名字 —— 回复块显示的是
     * "回复了某某"的一行摘要，改了名之后那里若还写着旧名，看着像另一个人在说话。
     * 回复的**时间与文本不改**：那是当时那条消息的内容快照。
     */
    private fun rewriteName(m: Message, from: String, to: String): Message {
        val renamed = if (m.name == from) m.copy(name = to) else m
        val reply = renamed.reply ?: return renamed
        return if (reply.name == from) renamed.copy(reply = reply.copy(name = to)) else renamed
    }

    /** 读一份存档；文件不存在或解析失败返回 null（迁移里宁可跳过也不清空别人的记录） */
    private fun readHistoryFile(file: File): MutableList<Message>? = try {
        historyJson.decodeFromString<MutableList<Message>>(file.readText().trimStart('\uFEFF'))
    } catch (e: Exception) {
        Logger.error("migrateRename: cannot read ${file.path}: ${e.message}")
        null
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
