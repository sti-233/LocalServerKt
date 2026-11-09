package localserver.module

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Collections

/**
 * 「狼人杀」：狼人 / 预言家 / 女巫 / 守卫 / 平民。
 *
 * 夜间**按角色顺序轮流行动**：守卫 → 狼人 → 女巫 → 预言家，每一步所有人交完
 * 立刻进下一步，最后一步交完自动结算。顺序是硬要求：
 * - 女巫必须在狼人之后，否则她不知道刀口，"要不要用解药"根本无从判断；
 * - 守卫必须最前，守护才先于狼刀生效。
 *
 * 三条反直觉但必要的规则：
 * 1. **多个狼人只能刀同一个人** —— 全体存活狼人必须指向同一目标才算数，
 *    意见不一时当夜无人被袭击。各投各的再按票数取最多，等于让狼人"分票"也照样杀人。
 * 2. **解药与毒药各一瓶，跨整局不恢复**，且都可以不用。
 * 3. **出局不公布身份** —— 死了的人身份一旦公开，「谁被刀」就直接等价于「谁是好人」。
 */
class PlayWerewolf : GameEngine(Play.WEREWOLF) {

    override val discussPhase: String = "day"
    override val votePhase: String = "vote"

    /** 座位 -> wolf / seer / witch / guard / villager。 */
    private val roles = LinkedHashMap<String, String>()

    /** 预言家的查验记录：座位 -> `good` / `bad`。只给好人坏人，不给具体角色。 */
    private val seerChecks = LinkedHashMap<String, String>()

    /** 本夜狼人的选择：狼座位 -> 目标。全体一致才算数。 */
    private val wolfVotes = LinkedHashMap<String, String>()

    /** 本夜各角色是否已提交（seer / witch / guard）。 */
    private val submitted = HashSet<String>()

    private var guardTarget: String? = null
    private var lastGuardTarget: String? = null

    private var witchAction: String? = null
    private var witchTarget: String? = null

    /** 女巫的药各只有一瓶，跨整局不恢复。 */
    private var antidoteLeft: Int = 1
    private var poisonLeft: Int = 1

    /** 当前夜间轮到谁：guard / wolf / witch / seer，空串 = 已结算。 */
    private var nightStep: String = ""

    /** 狼人那一步锁存的刀口。之后女巫看的、结算判的都必须是它，不能再现算。 */
    private var nightVictim: String? = null

    /** 本夜当前步骤的截止时刻（epoch ms），0 表示没在计时。 */
    private var nightDeadlineAt: Long = 0L

    /**
     * 当前步骤的行动者「是什么时候全部离线的」，0 = 不是全离线状态。
     * 用来区分「刚刷新了一下」和「真的关了页面」。
     */
    private var allOfflineSince: Long = 0L

    private var lastDeaths: List<String> = emptyList()

    /**
     * 上一局的角色分配，用来保证「重开一局」不会原样照搬。
     *
     * 身份每局都重新洗牌，但 5 人 1 狼只有 5 种可能，撞上「狼还是他」的概率有 20% ——
     * 玩家撞见一次就会认定身份是固定的。这里最多试 [DEAL_ATTEMPTS] 次，
     * 只要求结果与上一局不同（不强求「每个人都不一样」，那在 4 人局里做不到）。
     */
    private var lastDeal: Map<String, String> = emptyMap()

    private val random = SecureRandom()

    // ===== 开局 =====

    override fun start(room: Play.Room): String? {
        val seats = room.seats.keys.toList()
        if (seats.size < 4) return "至少需要 4 名玩家"
        // 开局必须全员在线：离线的人看不见自己的身份，也轮不到他行动，
        // 一开局就少一个活人的局还不如不开
        val offline = seats.filter { room.seats[it]?.online != true }
        if (offline.isNotEmpty()) {
            return "还有 ${offline.size} 名玩家不在线（" +
                offline.joinToString("、") { room.nameOf(it) } + "），请等他们回来再开始"
        }
        val config = roster(seats.size) ?: return "人数超出支持范围（4-12 人）"

        // 洗牌重发，但避开上一局的分配：连着两局身份一模一样会让人以为身份是固定的。
        // 重试仍有上限，凑不出新分配（例如所有玩家都退过房）就接受结果，不能因为撞车开不了局。
        var deal = dealRoles(seats, config)
        var attempt = 1
        while (attempt < DEAL_ATTEMPTS && deal == lastDeal) {
            deal = dealRoles(seats, config)
            attempt++
        }
        lastDeal = deal
        roles.clear()
        roles.putAll(deal)

        seerChecks.clear()
        antidoteLeft = 1
        poisonLeft = 1
        lastGuardTarget = null
        lastDeaths = emptyList()
        alive.clear()
        alive.addAll(seats)
        round = 1
        result = null
        beginNight(room)
        return null
    }

    // ===== 夜间流程 =====

    /** 洗一次牌并按座位发角色。抽出来是为了「重开避开上一局」能重试。 */
    private fun dealRoles(seats: List<String>, config: Roster): Map<String, String> {
        val shuffled = seats.toMutableList().also { Collections.shuffle(it, random) }
        val deal = LinkedHashMap<String, String>()
        var i = 0
        repeat(config.wolves) { deal[shuffled[i++]] = "wolf" }
        deal[shuffled[i++]] = "seer"
        deal[shuffled[i++]] = "witch"
        if (config.guard > 0) deal[shuffled[i++]] = "guard"
        while (i < shuffled.size) deal[shuffled[i++]] = "villager"
        return deal
    }

    private fun livingWithRole(role: String): List<String> = alive.filter { roles[it] == role }

    private fun beginNight(room: Play.Room): List<String> {
        wolfVotes.clear()
        submitted.clear()
        guardTarget = null
        witchAction = null
        witchTarget = null
        nightVictim = null
        votes.clear()
        abstained.clear()
        spoken.clear()
        // 阶段必须在这里先置好：tickOwn / advanceNight 都以 phase == "night" 为前提
        phase = "night"
        nightStep = nextStepAfter("")
        // 所有人都有活人可动手时才有下一步；没有就直接结算（残局里守卫/预言家可能全军覆没）
        if (nightStep.isEmpty()) return resolveNight(room)
        nightDeadlineAt = System.currentTimeMillis() + NIGHT_STEP_MS
        allOfflineSince = 0L
        return emptyList()
    }

    /** 从 `step` 之后往下找第一个还有活人的角色。 */
    private fun nextStepAfter(step: String): String {
        val from = if (step.isEmpty()) 0 else NIGHT_ORDER.indexOf(step) + 1
        for (idx in from until NIGHT_ORDER.size) {
            if (livingWithRole(NIGHT_ORDER[idx]).isNotEmpty()) return NIGHT_ORDER[idx]
        }
        return ""
    }

    /**
     * 本步必须表态的座位。
     *
     * 狼人是唯一有多个行动者的步骤，规则也不一样：**只要还有在线的狼人，
     * 离线的狼就不参与这一刀**。否则一个人关了手机，剩下的狼就永远凑不出「一致」，
     * 整夜卡死在狼人这一步。全狼都离线时才退化成「全体狼人」，交给服务端代打。
     */
    private fun stepActors(room: Play.Room, step: String): List<String> {
        if (step != "wolf") return livingWithRole(step)
        val wolves = livingWithRole("wolf")
        val online = wolves.filter { room.seats[it]?.online == true }
        return if (online.isEmpty()) wolves else online
    }

    /** 本步是否已交齐。 */
    private fun stepDone(room: Play.Room, step: String): Boolean = when (step) {
        "guard" -> guardTarget != null
        // 狼人不只是"都投了"，还必须指向同一个人：意见不一时停在这一步等他们商量，
        // 不能替他们决定（也不能当成空刀直接跳过，那样女巫看到的就是"没人被袭击"）
        "wolf" -> wolvesAgreed(room)
        else -> step in submitted
    }

    /** 本步的行动者都已表态，且（对狼人）都指向同一个人。 */
    private fun wolvesAgreed(room: Play.Room): Boolean {
        val wolves = stepActors(room, "wolf")
        if (wolves.isEmpty()) return true
        if (wolves.any { it !in wolfVotes }) return false
        return wolves.map { wolfVotes[it] }.distinct().size == 1
    }

    /** 本步交齐就往下拨；最后一步交完直接结算。返回值要写进日志。 */
    private fun advanceNight(room: Play.Room): List<String> {
        if (nightStep.isEmpty() || !stepDone(room, nightStep)) return emptyList()
        if (nightStep == "wolf") nightVictim = agreedWolfVictim(room)
        nightStep = nextStepAfter(nightStep)
        if (nightStep.isEmpty()) return resolveNight(room)
        nightDeadlineAt = System.currentTimeMillis() + NIGHT_STEP_MS
        allOfflineSince = 0L
        return emptyList()
    }

    /**
     * 狼人当夜的刀口：**本步的行动者（在线狼人）必须指向同一个人**。
     * 意见不一就没人死 —— 否则「分票也照样杀人」会让狼人毫无配合压力。
     */
    private fun agreedWolfVictim(room: Play.Room): String? {
        val wolves = stepActors(room, "wolf")
        if (wolves.isEmpty()) return null
        val targets = wolves.map { wolfVotes[it] }.distinct()
        return if (targets.size == 1) targets[0] else null
    }

    /** 结算本夜。返回要写进日志的行。 */
    private fun resolveNight(room: Play.Room): List<String> {
        val lines = ArrayList<String>()

        val victim = nightVictim
        val guarded = guardTarget
        // 守卫挡刀：守护目标就是狼刀目标时，这一刀无效
        var killed: String? = if (victim != null && victim != guarded) victim else null

        // 女巫解药：救回被刀的人（但不能救自己）
        val witchSaves = witchAction == "save" && antidoteLeft > 0 && witchTarget != null

        // 同守同救 = 奶死：守卫守的人和女巫救的人是同一个，两个保护叠在一起反而把人送走。
        // 狼刀本身已经被守卫挡掉了，但药还是照扣 —— 女巫事先无从知道守卫守了谁。
        if (victim != null && victim == guarded && witchSaves && witchTarget == victim) {
            lines.add("守卫与女巫同时护住了 ${room.nameOf(victim)}，反被救死")
            killed = victim
            antidoteLeft -= 1
        } else {
            if (victim != null && victim == guarded) lines.add("守卫挡下了今晚的袭击")
            if (killed != null && witchSaves) {
                val witch = livingWithRole("witch").firstOrNull()
                if (witch != null && killed != witch) {
                    lines.add("女巫用解药救回了 ${room.nameOf(killed)}")
                    antidoteLeft -= 1
                    killed = null
                }
            }
        }

        // 女巫毒药：无视守护，直接死
        val poisoned = if (witchAction == "poison" && poisonLeft > 0) witchTarget else null
        if (poisoned != null) {
            lines.add("女巫毒死了 ${room.nameOf(poisoned)}")
            poisonLeft -= 1
        }

        val deaths = LinkedHashSet<String>()
        killed?.let { deaths.add(it) }
        poisoned?.let { deaths.add(it) }
        alive.removeAll(deaths)
        lastDeaths = deaths.toList()

        lines.add(
            if (deaths.isEmpty()) "天亮了，昨晚是平安夜"
            // 只公布死亡名单，不公布身份
            else "天亮了，昨晚出局：" + deaths.joinToString("、") { room.nameOf(it) }
        )

        lastGuardTarget = guardTarget
        guardTarget = null
        witchAction = null
        witchTarget = null
        nightVictim = null
        nightStep = ""
        nightDeadlineAt = 0L
        wolfVotes.clear()
        submitted.clear()

        val finish = checkWinner()
        if (finish != null) {
            lines.add(finish)
            return lines
        }

        ensureHost(room)
        lines.add("进入第 $round 天讨论，每人发言后自动开始投票")
        beginDiscuss(room)
        return lines
    }

    /**
     * 夜间推进。两条路：
     * 1. **轮到的人全部离线** → 服务端替他行动，不让全房间干等 60 秒；
     * 2. 有人在线但就是不操作 → 满 60 秒才替他行动。
     *
     * 离线判定留了 [OFFLINE_GRACE_MS] 的缓冲：玩家刷新页面时座位会短暂
     * `online == false`，一断就替他做决定等于刷新一次就丢一次夜间行动。
     */
    override fun tickOwn(room: Play.Room): Boolean {
        if (phase != "night" || nightStep.isEmpty()) {
            allOfflineSince = 0L
            return false
        }

        val waiting = stepActors(room, nightStep)
        val allOffline = waiting.isNotEmpty() && waiting.all { room.seats[it]?.online != true }
        val now = System.currentTimeMillis()
        val timedOut = nightDeadlineAt > 0L && now >= nightDeadlineAt

        if (allOffline) {
            if (allOfflineSince == 0L) allOfflineSince = now
            // 缓冲期内不替人做决定，但也不等到 60 秒
            if (now - allOfflineSince < OFFLINE_GRACE_MS) return false
        } else {
            allOfflineSince = 0L
            if (!timedOut) return false
        }

        autoAct(room, allOffline)
        return true
    }

    /** 服务端替当前步骤做决定。[offline] 只影响日志措辞。 */
    private fun autoAct(room: Play.Room, offline: Boolean) {
        val lines = ArrayList<String>()
        val why = if (offline) "离线" else "超时未行动"

        when (nightStep) {
            "guard" -> {
                val guard = livingWithRole("guard").firstOrNull()
                // 不能守自己，也不能连着两晚守同一个人
                val legal = alive.filter { it != guard && it != lastGuardTarget }
                val pick = legal.randomOrNull(random)
                guardTarget = pick ?: guard
                lines.add("守卫$why，自动守护 ${pick?.let { room.nameOf(it) } ?: "自己"}")
            }

            "wolf" -> {
                val wolves = stepActors(room, "wolf")
                val candidates = alive.filter { roles[it] != "wolf" }
                val pick = candidates.randomOrNull(random)
                if (pick != null) {
                    // 自动时全体狼人统一指向同一个目标，避免「意见不一 = 空刀」
                    wolves.forEach { wolfVotes[it] = pick }
                    lines.add("狼人$why，自动袭击 ${room.nameOf(pick)}")
                }
            }

            "witch" -> {
                // 默认不用药：药只有一瓶，自动替人挥霍比"这夜空过"糟糕得多
                witchAction = "none"
                submitted.add("witch")
                lines.add("女巫$why，本夜不用药")
            }

            "seer" -> {
                val seer = livingWithRole("seer").firstOrNull()
                val pick = alive.filter { it != seer }.randomOrNull(random)
                if (seer != null && pick != null) {
                    seerChecks[pick] = if (roles[pick] == "wolf") "bad" else "good"
                    submitted.add("seer")
                    lines.add("预言家$why，自动完成查验")
                }
            }
        }
        allOfflineSince = 0L
        lines.forEach { room.addLog(it) }
        advanceNight(room).forEach { room.addLog(it) }
    }

    override val ownCountdownActive: Boolean
        get() = phase == "night" && nightStep.isNotEmpty() && nightDeadlineAt > 0L

    /** 测试用：把当前步骤的截止时刻拉到过去，逼心跳走「超时自动跳过」分支。 */
    internal fun forceNightDeadline() {
        nightDeadlineAt = System.currentTimeMillis() - 1
    }

    /** 测试用：把「全员离线」的缓冲期拉过去，免得单测真的等 5 秒。 */
    internal fun forceOfflineGraceElapsed() {
        allOfflineSince = System.currentTimeMillis() - OFFLINE_GRACE_MS - 1
    }

    // ===== 动作分发 =====

    override fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String> {
        // 夜间动作先处理：它们在 phase == "night" 下发生
        when (action) {
            "wolfVote" -> return wolfVoteAction(room, seatId, data)
            "seerCheck" -> return seerCheckAction(room, seatId, data)
            "witchAct" -> return witchActAction(room, seatId, data)
            "guardProtect" -> return guardProtectAction(room, seatId, data)
        }

        if (phase == "waiting") throw PlayError("游戏还没开始")
        if (seatId !in alive && phase != "finished") throw PlayError("你已出局，只能旁观")

        return when (action) {
            "vote" -> {
                val target = data.string("target") ?: throw PlayError("缺少投票目标")
                castVote(room, seatId, target)
                listOf("${room.nameOf(seatId)} 已投票")
            }

            "abstain" -> {
                castVote(room, seatId, null)
                listOf("${room.nameOf(seatId)} 弃票")
            }

            else -> throw PlayError("未知动作：$action")
        }
    }

    private fun requireNightStep(step: String) {
        if (phase != "night") throw PlayError("现在不是夜晚")
        if (nightStep != step) throw PlayError("现在不是${roleLabel(step)}行动的时间")
    }

    private fun wolfVoteAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        requireNightStep("wolf")
        if (roles[seatId] != "wolf") throw PlayError("只有狼人可以袭击")
        if (seatId !in alive) throw PlayError("你已出局，只能旁观")
        val target = data.string("target") ?: throw PlayError("缺少袭击目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (roles[target] == "wolf") throw PlayError("不能袭击狼人队友")
        wolfVotes[seatId] = target
        // 狼人在商量期间改票算「有操作」，60 秒的自动跳过从最后一次操作重新计时，
        // 否则他们正讨论到一半就被系统替他们决定了
        nightDeadlineAt = System.currentTimeMillis() + NIGHT_STEP_MS
        return listOf("${room.nameOf(seatId)} 选择了袭击目标") + advanceNight(room)
    }

    private fun seerCheckAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        requireNightStep("seer")
        if (roles[seatId] != "seer") throw PlayError("只有预言家可以查验")
        val target = data.string("target") ?: throw PlayError("缺少查验目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (target == seatId) throw PlayError("不用查验自己")
        // 只给好人 / 坏人：给具体角色等于一次查验就把整局拆了
        seerChecks[target] = if (roles[target] == "wolf") "bad" else "good"
        submitted.add("seer")
        return listOf("预言家完成查验") + advanceNight(room)
    }

    private fun witchActAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        requireNightStep("witch")
        if (roles[seatId] != "witch") throw PlayError("只有女巫可以使用药水")
        val use = data.string("use") ?: "none"
        when (use) {
            "save" -> {
                if (antidoteLeft <= 0) throw PlayError("解药已经用完了")
                val victim = nightVictim
                if (victim == null) throw PlayError("今晚没有人被袭击")
                if (victim == seatId) throw PlayError("不能对自己使用解药")
                witchAction = "save"
                witchTarget = victim
            }

            "poison" -> {
                if (poisonLeft <= 0) throw PlayError("毒药已经用完了")
                val target = data.string("target") ?: throw PlayError("缺少下毒目标")
                if (target !in alive) throw PlayError("目标不在场上")
                if (target == seatId) throw PlayError("不能毒自己")
                // 同一夜既救又毒同一个人会让结算自相矛盾
                if (witchAction == "save" && target == witchTarget) throw PlayError("不能对同一个人既救又毒")
                witchAction = "poison"
                witchTarget = target
            }

            else -> {
                witchAction = "none"
                witchTarget = null
            }
        }
        submitted.add("witch")
        val text = when (use) {
            "save" -> "女巫使用了解药"
            "poison" -> "女巫使用了毒药"
            else -> "女巫本夜不用药"
        }
        return listOf(text) + advanceNight(room)
    }

    private fun guardProtectAction(room: Play.Room, seatId: String, data: JsonObject): List<String> {
        requireNightStep("guard")
        if (roles[seatId] != "guard") throw PlayError("只有守卫可以守护")
        val target = data.string("target") ?: throw PlayError("缺少守护目标")
        if (target !in alive) throw PlayError("目标不在场上")
        if (target == seatId) throw PlayError("不能守护自己")
        if (target == lastGuardTarget) throw PlayError("不能连续两晚守护同一个人")
        guardTarget = target
        return listOf("守卫守护了 ${room.nameOf(target)}") + advanceNight(room)
    }

    // ===== 出局与胜负 =====

    override fun eliminate(room: Play.Room, target: String): List<String> {
        val lines = ArrayList<String>()
        alive.remove(target)
        votes.clear()
        abstained.clear()
        // 不公布身份：说了就等于告诉大家刚才那一刀/那一票砍对了没有
        lines.add("${room.nameOf(target)} 被放逐")

        val finish = checkWinner()
        if (finish != null) {
            lines.add(finish)
            return lines
        }
        round += 1
        ensureHost(room)
        lines.add("天黑请闭眼，进入第 $round 夜")
        lines.addAll(beginNight(room))
        return lines
    }

    override fun skipVote(room: Play.Room, reason: String): List<String> {
        val lines = ArrayList<String>()
        lines.add(reason)
        val finish = checkWinner()
        if (finish != null) {
            lines.add(finish)
            return lines
        }
        round += 1
        lines.add("天黑请闭眼，进入第 $round 夜")
        lines.addAll(beginNight(room))
        return lines
    }

    private fun checkWinner(): String? {
        val wolves = livingWithRole("wolf").size
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

    // ===== 身份可见性 =====

    override fun canSeeScope(room: Play.Room, seatId: String, scope: String): Boolean {
        if (scope == "public") return true
        return scope == "wolves" && roles[seatId] == "wolf"
    }

    override fun visibleRole(room: Play.Room, viewer: String, target: String): String? {
        val role = roles[target] ?: return null
        return when {
            // 结束后全公开，便于复盘
            phase == "finished" -> role
            // 自己永远知道自己的身份
            target == viewer -> role
            // 狼人互认
            roles[viewer] == "wolf" && role == "wolf" -> role
            // 其余一律不公开 —— 包括出局者：身份一公开，「谁被刀」就等于「谁是好人」
            else -> null
        }
    }

    // ===== 对外状态 =====

    override fun gameState(room: Play.Room, seatId: String): JsonObject = buildJsonObject {
        val myRole = roles[seatId]
        put("myRole", if (myRole == null) JsonNull else JsonPrimitive(myRole))

        // 查验记录只给预言家
        if (myRole == "seer") {
            put("seerChecks", JsonObject(seerChecks.mapValues { JsonPrimitive(it.value) }))
        } else {
            put("seerChecks", JsonObject(emptyMap()))
        }

        // 狼人的选择只给狼人
        if (myRole == "wolf") {
            put("wolfVotes", JsonObject(wolfVotes.mapValues { JsonPrimitive(it.value) }))
        } else {
            put("wolfVotes", JsonObject(emptyMap()))
        }

        // 药瓶剩余只给女巫
        if (myRole == "witch") {
            put("antidoteLeft", JsonPrimitive(antidoteLeft))
            put("poisonLeft", JsonPrimitive(poisonLeft))
            // 刀口只给女巫：她得知道今晚谁被刀才能决定要不要用解药
            put("wolfVictim", nightVictim?.let { JsonPrimitive(it) } ?: JsonNull)
        } else {
            put("antidoteLeft", JsonPrimitive(0))
            put("poisonLeft", JsonPrimitive(0))
            put("wolfVictim", JsonNull)
        }

        // 当前该谁行动 + 我还欠什么没交
        val myTurn = phase == "night" && seatId in alive && nightStep == myRole
        put("nightStep", JsonPrimitive(if (phase == "night") nightStep else ""))
        // 当前步骤的行动者是否全部离线：离线就由服务端代打（缓冲几秒），
        // 前端据此改口「即将自动行动」，否则其他人会以为还要等满一分钟
        val stepOffline = phase == "night" && nightStep.isNotEmpty() &&
            stepActors(room, nightStep).let { it.isNotEmpty() && it.all { s -> room.seats[s]?.online != true } }
        put("nightStepOffline", JsonPrimitive(stepOffline))
        put(
            "pendingFor", if (myTurn) Json.encodeToJsonElement(listOf(myRole!!)) else Json.encodeToJsonElement(emptyList<String>())
        )
        put("canAct", JsonPrimitive(myTurn))
        // 顺序推进之后一交齐就自动结算，不存在"都交完了等房主点"的状态
        put("nightReady", JsonPrimitive(false))

        put("deaths", Json.encodeToJsonElement(if (phase == "night") emptyList<String>() else lastDeaths))

        val host = room.hostSeat == seatId
        val allOnline = room.seats.values.all { it.online }
        put(
            "canStart",
            JsonPrimitive(host && phase == "waiting" && room.seats.size >= 4 && allOnline)
        )
        put("canVote", JsonPrimitive(phase == votePhase && seatId in alive))
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
        nightStep = ""
        nightVictim = null
        nightDeadlineAt = 0L
        lastDeaths = emptyList()
        antidoteLeft = 1
        poisonLeft = 1
    }

    private fun roleLabel(step: String): String = when (step) {
        "guard" -> "守卫"
        "wolf" -> "狼人"
        "witch" -> "女巫"
        else -> "预言家"
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun <T> List<T>.randomOrNull(random: SecureRandom): T? =
        if (isEmpty()) null else this[random.nextInt(size)]

    internal data class Roster(val wolves: Int, val guard: Int)

    companion object {
        /** 夜间行动顺序。改这个顺序会直接改变游戏平衡，见类注释。 */
        private val NIGHT_ORDER = listOf("guard", "wolf", "witch", "seer")

        /** 每个角色 1 分钟不操作就自动替他决定并跳过。 */
        private const val NIGHT_STEP_MS = 60_000L

        /**
         * 行动角色全部离线后，先等这么久再替他行动。
         *
         * 刷新页面时旧连接先断、新连接后连，座位会短暂 `online == false`；
         * 没有这段缓冲，按一下 F5 就等于放弃本夜行动。
         */
        private const val OFFLINE_GRACE_MS = 5_000L

        /** 重开时避开上一局的尝试次数。5 人局只有 5 种分配，几次就能撞开。 */
        private const val DEAL_ATTEMPTS = 24

        internal fun roster(n: Int): Roster? = when (n) {
            4 -> Roster(wolves = 1, guard = 0)
            5 -> Roster(wolves = 1, guard = 1)
            in 6..8 -> Roster(wolves = 2, guard = 1)
            in 9..11 -> Roster(wolves = 3, guard = 1)
            12 -> Roster(wolves = 4, guard = 1)
            else -> null
        }
    }
}
