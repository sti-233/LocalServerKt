package localserver.module

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Collections

/**
 * 「谁是卧底」。
 *
 * 规则：每人拿到一个词，多数人拿到同一个（平民词），少数人（卧底）拿到另一个相近的词。
 * 轮流描述后投票放逐，直到卧底全出局（平民胜）或存活卧底数 ≥ 存活平民数（卧底胜）。
 *
 * 两个刻意的设计：
 * 1. **进行中不告诉玩家自己是卧底**。玩家只知道自己拿到一个词，是不是卧底要靠听别人描述来猜 ——
 *    这是这个游戏的核心乐趣。所以 [visibleRole] 在自己存活且未结束时一律返回 null。
 * 2. **出局也不公布词**。只公布身份（卧底/平民），否则死掉的人一说话就把答案泄了。
 */
class PlayUndercover : GameEngine(Play.UNDERCOVER) {

    /** 座位 -> 词。这个词**只**会出现在本人的 `myWord` 里。 */
    private val words = LinkedHashMap<String, String>()

    /** 座位 -> `civilian` / `undercover`。 */
    private val roles = LinkedHashMap<String, String>()

    /** 本次实际使用的词组快照，用于「出局公布身份」时区分两个词（见 [resolveVote] 的日志）。 */
    private var groupSnapshot: List<String> = emptyList()

    private val random = SecureRandom()

    override fun start(room: Play.Room): String? {
        val seats = room.seats.keys.toList()
        if (seats.size < 3) return "至少需要 3 名玩家"

        val k = room.undercoverCount.coerceIn(1, 2)
        // 卧底必须严格少于平民，否则开局就满足「卧底胜」的条件，一局都玩不下去
        if (k >= seats.size - k) return "玩家太少，${k} 个卧底需要至少 ${k * 2 + 1} 人"

        // 房主设了词组就必须用它 —— 悄悄退回内置词库会让「我明明设了词」变成灵异事件
        val custom = room.customGroup
        if (custom != null && custom.size < 2) {
            return "自定义词组至少需要 2 个词"
        }
        val group = custom ?: BUILTIN_GROUPS[random.nextInt(BUILTIN_GROUPS.size)]

        if (group.size < 2) return "这个词组只有 ${group.size} 个词，至少需要 2 个"

        // 从词组里挑两个不同的词：一个给平民，一个给卧底。
        // 全部卧底共用一个卧底词、全部平民共用一个平民词 —— 这是原版玩法；
        // 给每个人发不同的词会让「猜谁和大家不一样」彻底失去判据。
        val pair = group.toMutableList().also { Collections.shuffle(it, random) }
        val civilianWord = pair[0]
        val undercoverWord = pair[1]
        val undercoverSeats = seats.toMutableList()
            .also { Collections.shuffle(it, random) }
            .take(k)
            .toSet()

        words.clear()
        roles.clear()
        seats.forEach { s ->
            val isU = s in undercoverSeats
            roles[s] = if (isU) "undercover" else "civilian"
            words[s] = if (isU) undercoverWord else civilianWord
        }

        groupSnapshot = group
        alive.clear()
        alive.addAll(seats)
        phase = "playing"
        round = 1
        resetVote()
        result = null
        return null
    }

    override fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String> {
        if (phase == "waiting") throw PlayError("游戏还没开始")
        if (seatId !in alive && phase != "finished") throw PlayError("你已出局，只能旁观")

        return when (action) {
            "startVote" -> {
                requireHost(room, seatId)
                if (phase != "playing") throw PlayError("当前不能发起投票")
                resetVote()
                phase = "voting"
                listOf("第 $round 轮投票开始")
            }

            "vote" -> {
                if (phase != "voting") throw PlayError("现在不是投票时间")
                if (seatId !in alive) throw PlayError("出局的人不能投票")
                val target = data.string("target") ?: throw PlayError("缺少投票目标")
                if (target !in alive) throw PlayError("目标不在场上")
                if (target == seatId) throw PlayError("不能投给自己")
                val prev = votes.put(seatId, target)
                // 投票是匿名的，日志里不写投给谁 —— 否则票型等于公开
                if (prev == null) listOf("${room.nameOf(seatId)} 已投票") else emptyList()
            }

            "resolveVote" -> {
                requireHost(room, seatId)
                if (phase != "voting") throw PlayError("当前没有进行中的投票")
                resolveVote(room)
            }

            "breakTie" -> {
                requireHost(room, seatId)
                if (!awaitingTie) throw PlayError("当前没有平票需要裁决")
                val target = data.string("target") ?: throw PlayError("缺少裁决目标")
                if (target !in tieCandidates) throw PlayError("只能从平票候选人中选择")
                awaitingTie = false
                tieCandidates = emptyList()
                eliminate(room, target)
            }

            else -> throw PlayError("未知动作：$action")
        }
    }

    private fun requireHost(room: Play.Room, seatId: String) {
        if (room.hostSeat != seatId) throw PlayError("只有房主可以执行这个操作")
    }

    /** 结算本轮投票。返回要写进日志的文本行。 */
    private fun resolveVote(room: Play.Room): List<String> {
        val t = tally()
        if (t.isEmpty()) throw PlayError("还没有人投票")

        val lines = ArrayList<String>()
        val maxVotes = t.values.max()
        val top = t.entries.filter { it.value == maxVotes }.map { it.key }

        snapshotVote(t)

        if (top.size > 1) {
            awaitingTie = true
            tieCandidates = top
            lines.add("票数相同：" + top.joinToString("、") { room.nameOf(it) } + "，等待房主裁决")
            // 平票时停在 voting 阶段，房主裁决后才继续
            return lines
        }

        lines.addAll(eliminate(room, top[0]))
        return lines
    }

    /** 放逐某人、公布身份、判胜负、进下一轮。 */
    private fun eliminate(room: Play.Room, target: String): List<String> {
        val lines = ArrayList<String>()
        alive.remove(target)
        votes.clear()

        val role = roles[target] ?: "civilian"
        val roleName = if (role == "undercover") "卧底" else "平民"
        // 只公布身份，不公布词 —— 死了的人再说出自己的词就等于泄底
        lines.add("${room.nameOf(target)} 被放逐，身份：$roleName")

        val undercoverAlive = alive.count { roles[it] == "undercover" }
        val civilianAlive = alive.size - undercoverAlive

        when {
            undercoverAlive == 0 -> {
                phase = "finished"
                result = "civilian"
                lines.add("卧底全部出局，平民胜利！")
                lines.add("本局词组：" + groupSnapshot.joinToString("、") + "（卧底词：${undercoverWord()}）")
            }
            undercoverAlive >= civilianAlive -> {
                phase = "finished"
                result = "undercover"
                lines.add("卧底人数已达平民数，卧底胜利！")
                lines.add("本局词组：" + groupSnapshot.joinToString("、") + "（卧底词：${undercoverWord()}）")
            }
            else -> {
                phase = "playing"
                round += 1
                ensureHost(room)
            }
        }
        return lines
    }

    private fun undercoverWord(): String =
        roles.entries.firstOrNull { it.value == "undercover" }?.let { words[it.key] } ?: "?"

    override fun visibleRole(room: Play.Room, viewer: String, target: String): String? {
        val role = roles[target] ?: return null
        return when {
            // 结束后全公开，便于复盘
            phase == "finished" -> role
            // 自己出局了：知道自己是谁
            target == viewer && viewer !in alive -> role
            // 自己还活着：不知道（这是游戏的核心）
            else -> null
        }
    }

    override fun gameState(room: Play.Room, seatId: String): JsonObject = buildJsonObject {
        val started = phase != "waiting"
        // 只有活着的人才看自己的词；出局后词还留着没意义，但也没有泄漏风险
        put("myWord", if (started) JsonPrimitive(words[seatId] ?: "") else JsonNull)

        val role = roles[seatId]
        val revealOwn = started && (phase == "finished" || seatId !in alive)
        put("myRole", if (revealOwn && role != null) JsonPrimitive(role) else JsonNull)

        put("undercoverCount", JsonPrimitive(room.undercoverCount))
        put("customGroup", if (room.customGroup == null) JsonNull else Json.encodeToJsonElement(room.customGroup))

        val host = room.hostSeat == seatId
        val canStart = host && phase == "waiting" &&
            room.seats.size >= 3 && room.undercoverCount < room.seats.size - room.undercoverCount
        put("canStart", JsonPrimitive(canStart))
        put("canVote", JsonPrimitive(phase == "voting" && seatId in alive))
        put("result", if (result == null) JsonNull else JsonPrimitive(result!!))
    }

    override fun onEndGame(room: Play.Room) {
        words.clear()
        roles.clear()
        groupSnapshot = emptyList()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * 内置词库。**只放在服务端**：放进 `resources/` 的话会被 `/resources` 直接下载，
         * 等于把答案贴出来。
         *
         * 每组要有 3 个以上词，否则「2 个卧底」时挑不出足量的平民词。
         */
        private val BUILTIN_GROUPS: List<List<String>> = listOf(
            listOf("苹果", "梨", "桃", "香蕉"),
            listOf("可乐", "雪碧", "芬达", "苏打水"),
            listOf("西瓜", "哈密瓜", "香瓜", "甜瓜"),
            listOf("牛奶", "酸奶", "豆奶", "奶昔"),
            listOf("包子", "饺子", "馄饨", "汤圆"),
            listOf("钢笔", "铅笔", "圆珠笔", "毛笔"),
            listOf("篮球", "足球", "排球", "手球"),
            listOf("钢琴", "吉他", "小提琴", "二胡"),
            listOf("火车", "地铁", "公交车", "出租车"),
            listOf("医生", "护士", "药师", "兽医"),
            listOf("夏天", "秋天", "春天", "冬天"),
            listOf("手机", "平板", "笔记本", "台式机"),
            listOf("微信", "QQ", "钉钉", "飞书"),
            listOf("汉堡", "三明治", "热狗", "卷饼"),
            listOf("咖啡", "茶", "果汁", "奶茶"),
            listOf("狮子", "老虎", "豹子", "狼"),
            listOf("沙发", "椅子", "凳子", "床"),
            listOf("雨伞", "雨衣", "雨靴", "斗篷"),
            listOf("游泳", "跑步", "骑车", "爬山"),
            listOf("期中考试", "期末考试", "月考", "模拟考")
        )
    }
}
