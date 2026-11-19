package localserver.module

import localserver.utils.Logger
import localserver.utils.Util

import kotlinx.serialization.json.*
import java.io.File

/**
 * 聊天禁言。
 *
 * control 用户可以临时把某个人禁言：他仍然能看历史、能收消息，但发不出新的（文字与图片都不行）。
 * 时长到点自动失效，也可以随时取消或改成一个新时长 —— 这条记录本身就带 `until` 时间戳，
 * 「修改时长」就是把这一条整个覆盖掉，不需要另记状态。
 *
 * 为什么要落盘：禁言是跨连接生效的（换个标签页、重连、甚至重启服务都该还算数）；
 * 只放内存的话重启一次禁言就悄悄失效了，被禁的人只需要等一次重启。
 *
 * 记录落在 `cache/mute/mutes.json`：
 * `{"mutes":{"<用户名>":{"until":<毫秒时间戳>,"by":"<操作者>","reason":"","createdAt":<毫秒>}}}`
 *
 * 时间用 `System.currentTimeMillis()`（绝对时刻），不是本地格式化字符串：
 * 禁言是「到某个瞬间为止」，与服务端时区、部署机器时钟格式都无关，比较起来也不会踩解析失败的坑。
 */
object MuteStore {

    const val DIR = "cache/mute"

    /** 不带时长参数时用多久 */
    const val DEFAULT_MINUTES = 10

    /** 上限一天：再长就不是「临时静音」了，管理员也没有回头改的机会 */
    const val MAX_MINUTES = 24 * 60

    /** 一次禁言。[until] 是解除时刻的毫秒时间戳 */
    class Muted(
        val name: String,
        val until: Long,
        val by: String,
        val reason: String,
        val createdAt: Long
    )

    private fun file(): File = File(DIR, "mutes.json")

    /**
     * 这个人现在是否被禁言；没被禁（或已经到点）返回 null。
     *
     * 到点的记录读的时候就被过滤掉，不依赖有人在别的时机清理 —— 禁言过期必须是
     * 「时间到了立刻能说话」，而不是「等下一次有人打开控制面板才生效」。
     */
    @Synchronized
    fun active(name: String): Muted? {
        if (name.isEmpty()) return null
        val now = System.currentTimeMillis()
        return parseAll(now).firstOrNull { it.name == name }
    }

    /** 当前所有生效中的禁言（按解除时间从早到晚），供 Control 面板展示 */
    @Synchronized
    fun mutes(): List<Muted> = parseAll(System.currentTimeMillis()).sortedBy { it.until }

    /**
     * 禁言一个人（已存在则覆盖成新的时长，也就是「修改时长」）。
     *
     * 返回写入后的记录，调用方拿 [until] 回给前端/推给被禁的人。
     */
    @Synchronized
    fun mute(name: String, minutes: Int, by: String, reason: String = ""): Muted {
        val now = System.currentTimeMillis()
        val span = minutes.coerceIn(1, MAX_MINUTES)
        val entry = Muted(name, now + span * 60_000L, by, reason, now)
        val all = loadMutes()
        all[name] = buildJsonObject {
            put("until", JsonPrimitive(entry.until))
            put("by", JsonPrimitive(by))
            put("reason", JsonPrimitive(reason))
            put("createdAt", JsonPrimitive(now))
        }
        write(all)
        return entry
    }

    /** 取消禁言，返回是否真的有这么一条（没有就别谎报已解除） */
    @Synchronized
    fun unmute(name: String): Boolean {
        val all = loadMutes()
        if (all.remove(name) == null) return false
        write(all)
        return true
    }

    /**
     * 剩余时长的中文说法，给消息提示与面板共用。
     *
     * 一分钟以内报秒（「剩 30 秒」比「剩 1 分钟」准，也让人知道马上就能说话）；
     * 再往上按分钟向上取整，最后一段不足一分钟仍算一分钟，别让人以为已经解除了。
     */
    fun remainingText(until: Long, now: Long = System.currentTimeMillis()): String {
        val left = until - now
        if (left <= 0) return "已结束"
        val minutes = (left + 59_999) / 60_000
        return when {
            minutes > 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
            minutes > 1 -> "$minutes 分钟"
            else -> "${(left + 999) / 1000} 秒"
        }
    }

    // ===== 落盘 =====

    /** 文件里的 `mutes` 表（可写副本）。整份结构是 `{"mutes":{...}}`，别误当成平表 */
    private fun loadMutes(): MutableMap<String, JsonElement> =
        (load()["mutes"] as? JsonObject).orEmpty().toMutableMap()

    private fun load(): JsonObject = try {
        val f = file()
        if (!f.isFile) JsonObject(emptyMap())
        else Util.historyJson.decodeFromString<JsonObject>(f.readText().trimStart('\uFEFF'))
    } catch (e: Exception) {
        // 坏文件当空表：禁言只是管理手段，读不出来时宁可放人进来说话（并记账），
        // 也不要让整个聊天模块连带报错
        Logger.error("Failed to read mute store: ${e.message}")
        JsonObject(emptyMap())
    }

    /** 解析出还没过期的条目。坏字段整条跳过，别让一个手改坏的名字拖垮全部 */
    private fun parseAll(now: Long): List<Muted> =
        (load()["mutes"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val until = obj["until"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            if (until <= now) return@mapNotNull null
            Muted(
                name = name,
                until = until,
                by = obj["by"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            )
        }

    /**
     * 写盘时顺手丢掉已过期的条目。
     *
     * 不清理的话这份文件只会越攒越多 —— 每禁言一个人留一条，一年后全是早就解除的名字，
     * 而真正生效的那几条淹没在里面，排查时根本看不出谁还在被禁。
     */
    private fun write(all: Map<String, JsonElement>) {
        val now = System.currentTimeMillis()
        val alive = all.filterValues { value ->
            val until = (value as? JsonObject)?.get("until")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            until != null && until > now
        }
        try {
            File(DIR).mkdirs()
            file().writeText(
                buildJsonObject { put("mutes", JsonObject(alive)) }.toString()
            )
        } catch (e: Exception) {
            // 写不进去时这次禁言只在本次 load 的空表上生效，等于没生效 —— 记日志，别静默
            Logger.error("Failed to write mute store: ${e.message}")
        }
    }
}
