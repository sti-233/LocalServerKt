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
