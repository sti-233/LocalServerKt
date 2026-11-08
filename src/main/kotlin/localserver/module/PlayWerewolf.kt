package localserver.module

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Collections

/**
 * 「狼人杀」（5 种角色：狼人 / 预言家 / 女巫 / 守卫 / 平民）。
 *
 * 角色配置按人数确定（见 [roster]）。夜间流程是一轮「收集全部夜间行动 → 一次性结算」，
 * 而不是边收边算：守卫的守护必须先于狼刀生效，女巫的解药要在知道死者之后才能用，
 * 结算顺序一旦打乱就会出现「先毒死女巫、女巫却已经用了解药」这类矛盾。
 *
 * 结算顺序（[resolveNight]）：
 * 守卫守护 → 狼人袭击（被守护则幸免）→ 女巫解药（可救活）→ 女巫毒药（必死）→
 * 天亮只公布死亡名单不公布身份 → 清空守卫目标（下一夜不能连守）→ 判胜负 → 进白天。
 */
class PlayWerewolf : GameEngine(Play.WEREWOLF) {

    /** 座位 -> `wolf` / `seer` / `witch` / `guard` / `villager`。 */
    private val roles = LinkedHashMap<String, String>()

    /** 预言家 -> 已查验结果。只有预言家本人能拿到。 */
    private val seerChecks = LinkedHashMap<String, String>()

    /** 狼人本夜的刀口：狼座位 -> 目标。狼人之间可见，用于商量/统一。 */
    private val wolfVotes = LinkedHashMap<String, String>()

    /** 守卫本夜守护的人。 */
    private var guardTarget: String? = null

    /** 守卫**上一夜**守护的人 —— 不能连守两夜同一个人。 */
    private var lastGuardTarget: String? = null

    /** 女巫本夜的行动：`save` / `poison` / `none` / null（未提交）。 */
    private var witchAction: String? = null
    private var witchTarget: String? = null

    /** 本夜已提交行动的角色集合（wolf 单独看 [wolfVotes]，其余看各自的字段）。 */
    private val submitted = HashSet<String>()

    /** 昨夜死亡名单，天亮时公布。 */
    private var lastDeaths: List<String> = emptyList()

    private val random = SecureRandom()

    // ===== 开局 =====

    override fun start(room: Play.Room): String? {
        val seats = room.seats.keys.toList()
        val n = seats.size
        if (n < 4) return "至少需要 4 名玩家"

        val config = roster(n) ?: return "人数超出支持范围（4-12 人）"

        val shuffled = seats.toMutableList().also { Collections.shuffle(it, random) }
        var idx = 0
        roles.clear()
        repeat(config.wolves) { roles[shuffled[idx++]] = "wolf" }
        roles[shuffled[idx++]] = "seer"
        roles[shuffled[idx++]] = "witch"
        if (config.guard > 0) roles[shuffled[idx++]] = "guard"
        while (idx < n) roles[shuffled[idx++]] = "villager"

        seerChecks.clear()
        wolfVotes.clear()
        guardTarget = null
        lastGuardTarget = null
        witchAction = null
        witchTarget = null
        submitted.clear()
        lastDeaths = emptyList()

        alive.clear()
        alive.addAll(seats)
        phase = "night"
        round = 1
        resetVote()
        result = null
        return null
    }

    // ===== 动作 =====

    override fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String> {
        if (phase == "waiting") throw PlayError("游戏还没开始")

        // 夜间动作
        when (action) {
            "wolfVote" -> return wolfVoteAction(room, seatId, data)
            "seerCheck" -> return seerCheckAction(room, seatId, data)
            "witchAct" -> return witchActAction(room, seatId, data)
            "guardProtect" -> return guardProtectAction(room, seatId, data)
        }

        if (seatId !in alive && phase != "finished") throw PlayError("你已出局，只能旁观")

        return when (action) {
            "startVote" -> {
                requireHost(room, seatId)
                if (phase != "day") throw PlayError("现在不能发起投票")
                resetVote()
                phase = "vote"
                listOf("白天投票开始")
            }

            "vote" -> {
                if (phase != "vote") throw PlayError("现在不是投票时间")
                if (seatId !in alive) throw PlayError("出局的人不能投票")
                val target = data.string("target") ?: throw PlayError("缺少投票目标")
                if (target !in alive) throw PlayError("目标不在场上")
                if (target == seatId) throw PlayError("不能投给自己")
                val prev = votes.put(seatId, target)
                if (prev == null) listOf("${room.nameOf(seatId)} 已投票") else emptyList()
            }

            "resolveVote" -> {
                requireHost(room, seatId)
                if (phase != "vote") throw PlayError("当前没有进行中的投票")
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

            "resolveNight" -> {
                requireHost(room, seatId)
                if (phase != "night") throw PlayError("现在不是夜晚")
                val lines = ArrayList<String>()
                lines.add("房主强制结算了夜晚（未提交的行动视为放弃）")
                lines.addAll(resolveNight(room))
                lines
            }

            else -> throw PlayError("未知动作：$action")
        }
    }

    private fun wolfVoteAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        if (phase != "night") throw PlayError("现在不是夜晚")
        if (roles[seatId] != "wolf") throw PlayError("只有狼人可以在夜里行动")
        if (seatId !in alive) throw PlayError("你已出局")
        val target = data.string("target") ?: throw PlayError("缺少袭击目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (roles[target] == "wolf") throw PlayError("不能袭击狼人队友")
        wolfVotes[seatId] = target
        checkNightReady(room)
        // 狼人频道里能看到彼此的刀口，公共日志不写
        return listOf()
    }

    private fun seerCheckAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        if (phase != "night") throw PlayError("现在不是夜晚")
        if (roles[seatId] != "seer") throw PlayError("只有预言家可以查验")
        if (seatId !in alive) throw PlayError("你已出局")
        val target = data.string("target") ?: throw PlayError("缺少查验目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (target == seatId) throw PlayError("不用查验自己")
        seerChecks[target] = roles[target] ?: "villager"
        submitted.add("seer")
        checkNightReady(room)
        return listOf("预言家完成查验")
    }

    private fun witchActAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        if (phase != "night") throw PlayError("现在不是夜晚")
        if (roles[seatId] != "witch") throw PlayError("只有女巫可以用药")
        if (seatId !in alive) throw PlayError("你已出局")
        val use = data.string("use") ?: throw PlayError("缺少用药方式")

        when (use) {
            "save" -> {
                // 女巫不能自救
                val victim = wolfVictim()
                if (victim == seatId) throw PlayError("不能对自己使用解药")
                witchAction = "save"
                witchTarget = null
            }

            "poison" -> {
                val target = data.string("target") ?: throw PlayError("缺少毒杀目标")
                if (target !in alive) throw PlayError("目标不在场上")
                if (target == seatId) throw PlayError("不能毒自己")
                // 同一夜里既救又毒同一个人是矛盾的，直接拒掉
                if (witchAction == "save" && witchTarget == target) {
                    throw PlayError("同一夜不能既救又毒同一个人")
                }
                witchAction = "poison"
                witchTarget = target
            }

            "none" -> {
                witchAction = "none"
                witchTarget = null
            }

            else -> throw PlayError("未知的用药方式：$use")
        }
        submitted.add("witch")
        checkNightReady(room)
        return listOf("女巫完成用药")
    }

    private fun guardProtectAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        if (phase != "night") throw PlayError("现在不是夜晚")
        if (roles[seatId] != "guard") throw PlayError("只有守卫可以守护")
        if (seatId !in alive) throw PlayError("你已出局")
        val target = data.string("target") ?: throw PlayError("缺少守护目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (target == seatId) throw PlayError("守卫不能守护自己")
        if (target == lastGuardTarget) throw PlayError("不能连续两晚守护同一个人")
        guardTarget = target
        submitted.add("guard")
        checkNightReady(room)
        return listOf("守卫完成守护")
    }

    /** 今晚狼人实际要刀的人：票最多的那个；平票随机取一个。 */
    private fun wolfVictim(): String? {
        if (wolfVotes.isEmpty()) return null
        val counts = wolfVotes.values.groupingBy { it }.eachCount()
        val max = counts.values.max()
        val top = counts.filter { it.value == max }.keys.toList()
        return if (top.size == 1) top[0] else top[random.nextInt(top.size)]
    }

    /** 所有需要行动的人都提交了就把 `nightReady` 打开（只影响前端提示，不自动结算）。 */
    private fun checkNightReady(room: Play.Room) {
        // 房主点「结算夜晚」才真正推进，这里什么都不做
    }

    // ===== 夜间结算 =====

    private fun resolveNight(room: Play.Room): List<String> {
        val lines = ArrayList<String>()

        // 1. 狼人袭击；2. 守卫守护
        val victim = wolfVictim()
        val guarded = guardTarget
        var killed: String? = null
        if (victim != null && victim != guarded) killed = victim

        // 3. 女巫解药：女巫还活着、手里有解药、且被刀的不是女巫自己（不能自救）
        if (killed != null && witchAction == "save") {
            val witch = witchSeat()
            if (witch != null && witch in alive && killed != witch) killed = null
        }

        // 4. 女巫毒药
        val poisoned: String? = if (witchAction == "poison") witchTarget else null

        val deaths = LinkedHashSet<String>()
        if (killed != null) deaths.add(killed)
        if (poisoned != null) deaths.add(poisoned)
        // 被毒的人即使被守护也会死（毒药无视守护）
        alive.removeAll(deaths)

        lastDeaths = deaths.toList()
        if (deaths.isEmpty()) {
            lines.add("天亮了，昨晚是平安夜")
        } else {
            // 只公布死亡名单，不公布身份
            lines.add("天亮了，昨晚出局：" + deaths.joinToString("、") { room.nameOf(it) })
        }

        // 清空本夜行动，守卫目标滚到「上一夜」
        lastGuardTarget = guardTarget
        guardTarget = null
        wolfVotes.clear()
        witchAction = null
        witchTarget = null
        submitted.clear()
        votes.clear()

        val end = checkWinner(room)
        if (end != null) {
            lines.add(end)
        } else {
            phase = "day"
            round += 1
            ensureHost(room)
            lines.add("进入白天讨论，发言后由房主发起投票")
        }
        return lines
    }

    private fun witchSeat(): String? = roles.entries.firstOrNull { it.value == "witch" }?.key

    // ===== 投票放逐 =====

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
            return lines
        }

        lines.addAll(eliminate(room, top[0]))
        return lines
    }

    private fun eliminate(room: Play.Room, target: String): List<String> {
        val lines = ArrayList<String>()
        alive.remove(target)
        votes.clear()

        val role = roles[target] ?: "villager"
        // 狼人杀里放逐公布身份是标准玩法（否则好人无从推理）
        lines.add("${room.nameOf(target)} 被放逐，身份：${roleName(role)}")

        val end = checkWinner(room)
        if (end != null) {
            lines.add(end)
        } else {
            phase = "night"
            round += 1
            ensureHost(room)
            lines.add("天黑请闭眼，进入第 $round 夜")
        }
        return lines
    }

    private fun checkWinner(room: Play.Room): String? {
        val wolves = alive.count { roles[it] == "wolf" }
        val goods = alive.size - wolves
        return when {
            wolves == 0 -> {
                phase = "finished"
                result = "good"
                "狼人全部出局，好人胜利！"
            }
            wolves >= goods -> {
                phase = "finished"
                result = "wolves"
                "存活狼人已达好人数量，狼人胜利！"
            }
            else -> null
        }
    }

    private fun roleName(role: String): String = when (role) {
        "wolf" -> "狼人"
        "seer" -> "预言家"
        "witch" -> "女巫"
        "guard" -> "守卫"
        else -> "平民"
    }

    private fun requireHost(room: Play.Room, seatId: String) {
        if (room.hostSeat != seatId) throw PlayError("只有房主可以执行这个操作")
    }

    // ===== 视图 =====

    override fun visibleRole(room: Play.Room, viewer: String, target: String): String? {
        val role = roles[target] ?: return null
        return when {
            phase == "finished" -> role
            viewer == target -> role
            // 狼人互相认识
            roles[viewer] == "wolf" && role == "wolf" -> role
            // 出局的人身份公开
            target !in alive && phase != "waiting" -> role
            else -> null
        }
    }

    override fun canSeeScope(room: Play.Room, seatId: String, scope: String): Boolean =
        scope == "public" || (scope == "wolves" && roles[seatId] == "wolf")

    override fun gameState(room: Play.Room, seatId: String): JsonObject = buildJsonObject {
        val started = phase != "waiting"
        val myRole = roles[seatId]
        put("myRole", if (started && myRole != null) JsonPrimitive(myRole) else JsonNull)

        // 查验结果只给预言家
        val checks = if (started && myRole == "seer") seerChecks else emptyMap()
        put("seerChecks", JsonObject(checks.mapValues { JsonPrimitive(it.value) }))

        // 刀口只给狼人
        val wv = if (started && myRole == "wolf") wolfVotes else emptyMap()
        put("wolfVotes", JsonObject(wv.mapValues { JsonPrimitive(it.value) }))

        // 我还需要提交哪些夜间行动
        val pending = buildJsonArray {
            if (started && phase == "night" && seatId in alive) {
                when (myRole) {
                    "wolf" -> if (seatId !in wolfVotes) add(JsonPrimitive("wolf"))
                    "seer" -> if ("seer" !in submitted) add(JsonPrimitive("seer"))
                    "witch" -> if ("witch" !in submitted) add(JsonPrimitive("witch"))
                    "guard" -> if ("guard" !in submitted) add(JsonPrimitive("guard"))
                }
            }
        }
        put("pendingFor", pending)

        // 该角色只要还有人活着就必须提交；全死了（或本局没这个角色）自然算就绪
        val ready = phase == "night" &&
            alive.filter { roles[it] == "wolf" }.all { it in wolfVotes } &&
            (alive.none { roles[it] == "seer" } || "seer" in submitted) &&
            (alive.none { roles[it] == "witch" } || "witch" in submitted) &&
            (alive.none { roles[it] == "guard" } || "guard" in submitted)
        put("nightReady", JsonPrimitive(ready))
        put("canAct", JsonPrimitive(phase == "night" && seatId in alive && myRole in setOf("wolf", "seer", "witch", "guard")))
        // 前端投票面板统一读 canVote（与卧底局同名），别让玩家照着「我没死」的界面点空
        put("canVote", JsonPrimitive(phase == "vote" && seatId in alive))
        put("canStart", JsonPrimitive(room.hostSeat == seatId && phase == "waiting" && room.seats.size >= 4))
        put("deaths", Json.encodeToJsonElement(if (phase == "night") emptyList() else lastDeaths))
        put("result", if (result == null) JsonNull else JsonPrimitive(result!!))
    }

    override fun onEndGame(room: Play.Room) {
        roles.clear()
        seerChecks.clear()
        wolfVotes.clear()
        submitted.clear()
        guardTarget = null
        lastGuardTarget = null
        witchAction = null
        witchTarget = null
        lastDeaths = emptyList()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    internal data class Roster(val wolves: Int, val guard: Int)

    companion object {
        /**
         * 角色配置表（4≤n≤12）：
         * - n=4：狼1 预1 女1 守0 民1
         * - n=5：狼1 预1 女1 守1 民1
         * - n=6-8：狼2 预1 女1 守1 民 n-5
         * - n=9-11：狼3 预1 女1 守1 民 n-6
         * - n=12：狼4 预1 女1 守1 民5
         */
        internal fun roster(n: Int): Roster? = when (n) {
            4 -> Roster(1, 0)
            5 -> Roster(1, 1)
            in 6..8 -> Roster(2, 1)
            in 9..11 -> Roster(3, 1)
            12 -> Roster(4, 1)
            else -> null
        }
    }
}
