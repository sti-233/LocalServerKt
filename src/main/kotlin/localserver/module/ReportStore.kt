package localserver.module

import localserver.utils.Logger
import localserver.utils.Util

import kotlinx.serialization.json.*
import java.io.File

/**
 * 聊天图片的举报与封禁。
 *
 * 规则：同一张图（按内容哈希命名，同名必然同字节）被 **3 个不同用户**举报，
 * 或被 **1 个 control 用户**举报，就把它封禁 —— 撤回消息、删掉磁盘上所有副本，
 * 并把文件名记进黑名单，之后谁都无法再保存/发送这张图（重传同一张图哈希相同，
 * 绕不过去）。
 *
 * 为什么要落盘：举报是低频但需要跨重启累积的事。只放内存的话，昨天 2 个人举报过的图
 * 今天重启就从 0 开始，等于永远凑不满 3 票。
 *
 * 记录落在 `cache/report/votes.json`：
 * `{"banned":["<sha256>.<ext>"],"votes":{"<sha256>.<ext>":{"reporters":["alice","bob"],"updatedAt":<毫秒>}}}`
 *
 * 两张表的分工：`votes` 是还在累积中的举报，`banned` 是已封禁的图。封禁时把对应那条
 * 从 `votes` 里删掉 —— 黑名单本身足以判定「已处理」，留着逐人记录只会让文件白长。
 */
object ReportStore {

    const val DIR = "cache/report"

    /** 需要多少个**不同用户**举报才封禁；control 用户一票即封 */
    const val NEEDED = 3

    /** 一次举报的结果。[count] 是当前累计人数（control 直接记作已达阈值） */
    class Result(val count: Int, val needed: Int, val banned: Boolean, val duplicate: Boolean)

    /** 一条还在累计中的举报，供 Control 页面展示（谁报了、什么时候） */
    class Pending(val name: String, val reporters: List<String>, val updatedAt: Long)

    private fun file(): File = File(DIR, "votes.json")

    /**
     * 这张图是否已被封禁。
     *
     * 每次调用都读一次文件（几十字节）：这个判断落在 `/img` 上，量不大，
     * 而缓存一份就要处理「文件被手改/进程内多处写」的失效，不值得。
     */
    @Synchronized
    fun isBanned(name: String): Boolean = bannedOf(load()).contains(name)

    /** 已封禁的全部文件名（排查用） */
    @Synchronized
    fun banned(): List<String> = bannedOf(load()).sorted()

    /**
     * 还在累计中的举报（按更新时间倒序），供 Control 页面看「差几票」。
     *
     * 已封禁的不会出现在这里 —— 封禁时那条就从 votes 里删掉了。
     */
    @Synchronized
    fun pending(): List<Pending> {
        val votes = load()["votes"]?.jsonObject ?: return emptyList()
        return votes.mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val reporters = obj["reporters"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val at = obj["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            Pending(name, reporters, at)
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * 撤销封禁，返回是否真的解封了一张。
     *
     * 误封是必然会发生的事：report 那张图可能是被恶意举报的，也可能举报者记错了图。
     * 没有这个出口的话，解封只能去手改 `cache/report/votes.json`，而那份文件在服务端运行目录里，
     * 手改还要重启才生效（本对象的读取虽然每次都读盘，但不建议让人去碰它）。
     */
    @Synchronized
    fun unban(name: String): Boolean {
        val root = load()
        val banned = bannedOf(root).toMutableSet()
        if (!banned.remove(name)) return false
        // 解封只动黑名单，不还原已删的图片与消息 —— 那些在封禁时就没了，这里做不到
        write(banned, root["votes"]?.jsonObject ?: emptyMap())
        return true
    }

    /**
     * 记一次举报，返回累计结果。
     *
     * [reporter] 是举报者用户名：同一人重复点举报只算一票（否则一个人点三下就能封图）。
     * [control] 为 true 表示举报者在 control 白名单里，一票即封。
     *
     * 已封禁的图再被举报：不再累计，直接回 banned=true（调用方据此重放撤回动作，天然幂等）。
     */
    @Synchronized
    fun report(name: String, reporter: String, control: Boolean): Result {
        val root = load()
        val banned = bannedOf(root).toMutableSet()
        if (name in banned) return Result(NEEDED, NEEDED, banned = true, duplicate = false)

        val votes = root["votes"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        val reporters = votes[name]?.jsonObject?.get("reporters")
            ?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toMutableSet()
            ?: mutableSetOf()
        val duplicate = !reporters.add(reporter)
        val trigger = control || reporters.size >= NEEDED

        if (trigger) {
            banned.add(name)
            // 封禁后名单本身就够用了，逐人记录删掉，别让文件越攒越大
            votes.remove(name)
        } else {
            votes[name] = buildJsonObject {
                put("reporters", stringsToJson(reporters.sorted()))
                put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            }
        }
        write(banned, votes)
        return Result(
            count = if (control) NEEDED else reporters.size,
            needed = NEEDED,
            banned = trigger,
            duplicate = duplicate
        )
    }

    // ===== 落盘 =====

    private fun load(): JsonObject = try {
        val f = file()
        if (!f.isFile) JsonObject(emptyMap())
        else Util.historyJson.decodeFromString<JsonObject>(f.readText().trimStart('\uFEFF'))
    } catch (e: Exception) {
        // 坏文件当成空表：举报是多一道保护，计数从头开始也比让聊天整体报错好
        Logger.error("Failed to read report store: ${e.message}")
        JsonObject(emptyMap())
    }

    private fun write(banned: Set<String>, votes: Map<String, JsonElement>) {
        try {
            File(DIR).mkdirs()
            file().writeText(
                buildJsonObject {
                    put("banned", stringsToJson(banned.sorted()))
                    put("votes", JsonObject(votes))
                }.toString()
            )
        } catch (e: Exception) {
            // 写不进去（磁盘满/被占用）时这一次举报只在本次进程内生效，下次重启丢失 —— 记日志即可
            Logger.error("Failed to write report store: ${e.message}")
        }
    }

    private fun bannedOf(root: JsonObject): Set<String> =
        root["banned"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()

    private fun stringsToJson(items: Collection<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })
}
