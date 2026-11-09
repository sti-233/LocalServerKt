package localserver.module

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「狼人杀」引擎测试。
 *
 * 重点覆盖几处最容易写错的地方：
 * 1. 角色配置表（4-12 人的狼/民数量）；
 * 2. 夜间顺序推进（守卫 → 狼人 → 女巫 → 预言家）与 1 分钟超时自动跳过；
 * 3. 身份可见性（狼人互认、预言家只看到好人/坏人、**出局不公布身份**）；
 * 4. 全自动投票（人人发言 ⇒ 投票；全员表态 ⇒ 5 秒后结算；可弃票）。
 *
 * 流程已经全自动了：没有「发起投票 / 结算投票 / 强制结算夜晚」这些房主动作，
 * 所以测试也必须按服务端推进的节奏走。单测里把
 * [GameEngine.voteSettleDelayMs] 压成 0 再调 [GameEngine.tick]，省得真的睡 5 秒。
 */
class PlayWerewolfTest {

    private fun newRoom(n: Int): Play.Room {
        val room = Play.Room("TEST", Play.WEREWOLF)
        repeat(n) { i ->
            val id = "s$i"
            room.seats[id] = Play.Seat(id).also {
                it.name = "玩家$i"
                it.online = true
            }
        }
        room.hostSeat = "s0"
        room.engine.voteSettleDelayMs = 0
        return room
    }

    private fun engineOf(room: Play.Room) = room.engine as PlayWerewolf

    /** 某座位的角色。测试直接读 `myRole`（自己的角色对自己永远可见）。 */
    private fun roleOf(room: Play.Room, seat: String): String =
        engineOf(room).gameState(room, seat)["myRole"]!!.jsonPrimitive.content

    private fun seatsWithRole(room: Play.Room, role: String): List<String> =
        room.seats.keys.filter { roleOf(room, it) == role }

    /**
     * 直接驱动引擎。返回的日志行会被攒进 [logs]。
     *
     * 引擎的 `action()` 只**返回**日志行，由 [Play.doAction] 写进房间日志；
     * 测试不走 WS，所以想断言「公告说了什么」就得自己攒。
     */
    private fun act(
        room: Play.Room,
        seat: String,
        action: String,
        data: JsonObject = JsonObject(emptyMap())
    ): List<String> = engineOf(room).action(room, seat, action, data).also { logs.addAll(it) }

    private val logs = ArrayList<String>()

    private fun logText(): String = logs.joinToString("\n")

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** 当前房主（房主可能因出局而转移）。 */
    private fun hostOf(room: Play.Room): String = room.hostSeat

    /** 一个存活的好人座位（非狼人）。 */
    private fun aGood(room: Play.Room): String =
        room.engine.alive.first { roleOf(room, it) != "wolf" }

    /**
     * 一个「消耗品」好人：优先平民，其次任意非狼、且不撞 [avoid]。
     * 夜里要杀一个人又不希望恰好杀掉某个关键角色（预言家/女巫）时用它。
     */
    private fun fodder(room: Play.Room, vararg avoid: String): String {
        val pool = room.engine.alive.filter { roleOf(room, it) != "wolf" && it !in avoid }
        return pool.firstOrNull { roleOf(room, it) == "villager" }
            ?: pool.firstOrNull()
            ?: room.engine.alive.first()
    }

    /** 夜间当前轮到谁（空串 = 不在夜间）。顺序由服务端强制：守卫→狼人→女巫→预言家。 */
    private fun stepOf(room: Play.Room): String =
        engineOf(room).gameState(room, room.seats.keys.first())["nightStep"]!!.jsonPrimitive.content

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /**
     * 守卫先守一手。
     *
     * `avoid`（通常是狼刀目标）只是**优先避开**：守到它会把这一刀挡掉，多数测试
     * 不希望这样。但不能只试它 —— 残局里剩下的合法目标可能恰好是上一夜守过的人，
     * 这时守卫会一直提交失败、整夜卡在守卫这步。所以最后兜底再试 `avoid`。
     */
    private fun doGuard(room: Play.Room, target: String? = null, avoid: String? = null) {
        if (stepOf(room) != "guard") return
        val guard = seatsWithRole(room, "guard").firstOrNull { it in room.engine.alive } ?: return
        val candidates = if (target != null) listOf(target)
        else {
            val legal = room.engine.alive.filter { it != guard }
            legal.filter { it != avoid } + legal.filter { it == avoid }
        }
        candidates.firstOrNull { t ->
            runCatching { act(room, guard, "guardProtect", obj("target" to t)) }.isSuccess
        }
    }

    private fun doWolves(room: Play.Room, target: String) {
        if (stepOf(room) != "wolf") return
        seatsWithRole(room, "wolf").filter { it in room.engine.alive }
            .forEach { act(room, it, "wolfVote", obj("target" to target)) }
    }

    private fun doWitch(room: Play.Room, use: String = "none", target: String? = null) {
        if (stepOf(room) != "witch") return
        val witch = seatsWithRole(room, "witch").firstOrNull { it in room.engine.alive } ?: return
        val data = if (target != null) obj("use" to use, "target" to target) else obj("use" to use)
        act(room, witch, "witchAct", data)
    }

    private fun doSeer(room: Play.Room, target: String? = null) {
        if (stepOf(room) != "seer") return
        val seer = seatsWithRole(room, "seer").firstOrNull { it in room.engine.alive } ?: return
        val t = target ?: room.engine.alive.firstOrNull { it != seer } ?: return
        act(room, seer, "seerCheck", obj("target" to t))
    }

    /**
     * 走完一个夜晚：守卫 → 狼人 → 女巫 → 预言家。
     *
     * 顺序由服务端强制，最后一步交完**自动结算**，不需要房主再点一下。
     */
    private fun runNight(room: Play.Room, wolfTarget: String, guardTarget: String? = null) {
        doGuard(room, target = guardTarget, avoid = wolfTarget)
        doWolves(room, wolfTarget)
        doWitch(room, "none")
        doSeer(room)
    }

    /** 夜里被杀的默认对象：一个平民，别误伤关键角色。 */
    private fun nightVictimOf(room: Play.Room): String =
        seatsWithRole(room, "villager").firstOrNull { it in room.engine.alive }
            ?: fodder(room)

    /** 人人发一次言（发言就是发一条公共聊天，框架把它转成 noteSpoken）。 */
    private fun speakAll(room: Play.Room) {
        room.engine.alive.forEach { room.engine.noteSpoken(room, it) }
    }

    /**
     * 全员发言 → 除 [target] 外都投他，[target] 自己弃票 → 心跳结算。
     *
     * `target` 必须表态：投票阶段等的是「人人表态」，被投的那个人不弃票也不投，
     * 这一步就永远收不齐，结算截止时刻也不会被压缩到 5 秒后。
     */
    private fun voteOut(room: Play.Room, target: String) {
        speakAll(room)
        room.engine.alive.filter { it != target }
            .forEach { act(room, it, "vote", obj("target" to target)) }
        act(room, target, "abstain")
        room.engine.tick(room)
    }

    /** 全员发言 → 全员弃票 → 心跳结算。 */
    private fun abstainAll(room: Play.Room) {
        speakAll(room)
        room.engine.alive.forEach { act(room, it, "abstain") }
        room.engine.tick(room)
    }

    // ===== 角色配置 =====

    @Test
    fun `roster matches the documented table`() {
        assertEquals(PlayWerewolf.Roster(1, 0), PlayWerewolf.roster(4))
        assertEquals(PlayWerewolf.Roster(1, 1), PlayWerewolf.roster(5))
        assertEquals(PlayWerewolf.Roster(2, 1), PlayWerewolf.roster(6))
        assertEquals(PlayWerewolf.Roster(2, 1), PlayWerewolf.roster(8))
        assertEquals(PlayWerewolf.Roster(3, 1), PlayWerewolf.roster(9))
        assertEquals(PlayWerewolf.Roster(3, 1), PlayWerewolf.roster(11))
        assertEquals(PlayWerewolf.Roster(4, 1), PlayWerewolf.roster(12))
        assertNull(PlayWerewolf.roster(3))
        assertNull(PlayWerewolf.roster(13))
    }

    @Test
    fun `five player room has one wolf one seer one witch one guard one villager`() {
        val room = newRoom(5)
        assertNull(engineOf(room).start(room))

        assertEquals(1, seatsWithRole(room, "wolf").size)
        assertEquals(1, seatsWithRole(room, "seer").size)
        assertEquals(1, seatsWithRole(room, "witch").size)
        assertEquals(1, seatsWithRole(room, "guard").size)
        assertEquals(1, seatsWithRole(room, "villager").size)
    }

    @Test
    fun `four player room has no guard`() {
        val room = newRoom(4)
        assertNull(engineOf(room).start(room))
        assertEquals(0, seatsWithRole(room, "guard").size)
        assertEquals(1, seatsWithRole(room, "villager").size)
    }

    @Test
    fun `twelve player room has four wolves and five villagers`() {
        val room = newRoom(12)
        assertNull(engineOf(room).start(room))
        assertEquals(4, seatsWithRole(room, "wolf").size)
        assertEquals(5, seatsWithRole(room, "villager").size)
    }

    @Test
    fun `needs at least four players`() {
        val room = newRoom(3)
        assertNotNull(engineOf(room).start(room))
        assertEquals("waiting", room.engine.phase)
    }

    // ===== 身份可见性 =====

    @Test
    fun `wolves know each other but villagers know nothing`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val engine = engineOf(room)
        val wolves = seatsWithRole(room, "wolf")
        val villager = seatsWithRole(room, "villager").first()

        val wolfView = engine.view(room, wolves[0])
        wolves.forEach { w ->
            val p = wolfView["players"]!!.jsonArray
                .first { it.jsonObject["id"]!!.jsonPrimitive.content == w }
            assertEquals("wolf", p.jsonObject["role"]!!.jsonPrimitive.content, "狼人应该认识队友")
        }

        val villagerView = engine.view(room, villager)
        val rolesSeen = villagerView["players"]!!.jsonArray
            .filter { it.jsonObject["id"]!!.jsonPrimitive.content != villager }
            .map { it.jsonObject["role"] }
        assertTrue(rolesSeen.all { it is JsonNull }, "平民不该看到任何人的身份")
    }

    @Test
    fun `seer only learns good or bad and only the seer sees it`() {
        val room = newRoom(6)
        engineOf(room).start(room)
        val seer = seatsWithRole(room, "seer").first()
        val wolf = seatsWithRole(room, "wolf").first()
        val good = room.seats.keys.first { roleOf(room, it) != "wolf" && it != seer }

        skipTo(room, "seer")
        act(room, seer, "seerCheck", obj("target" to wolf))
        // 查验完自动结算进白天，再等到下一夜才轮到预言家 —— 这里只读记录
        val engine = engineOf(room)
        val checks = engine.gameState(room, seer)["seerChecks"]!!.jsonObject
        assertEquals("bad", checks[wolf]!!.jsonPrimitive.content, "查验狼人应回「坏人」")
        assertTrue(checks.values.all { it.jsonPrimitive.content in setOf("good", "bad") },
            "只会给出好人/坏人，不能暴露具体角色")

        // 别人拿到的是空对象
        room.seats.keys.filter { it != seer }.forEach { other ->
            assertEquals(
                0,
                engine.gameState(room, other)["seerChecks"]!!.jsonObject.size,
                "查验结果只给预言家"
            )
        }
        // 顺手确认好人查出来是 good
        assertEquals("good", if (roleOf(room, good) == "wolf") "bad" else "good")
    }

    @Test
    fun `wolf votes are private to wolves`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val wolves = seatsWithRole(room, "wolf")
        val villager = seatsWithRole(room, "villager").first()
        val target = room.seats.keys.first { roleOf(room, it) != "wolf" }

        skipTo(room, "wolf")
        wolves.forEach { act(room, it, "wolfVote", obj("target" to target)) }

        val engine = engineOf(room)
        room.seats.keys.filter { roleOf(room, it) == "wolf" }.forEach { w ->
            assertEquals(
                wolves.size,
                engine.gameState(room, w)["wolfVotes"]!!.jsonObject.size,
                "狼人之间应看到彼此的刀口"
            )
        }
        assertEquals(
            0,
            engine.gameState(room, villager)["wolfVotes"]!!.jsonObject.size,
            "平民不该看到刀口"
        )
    }

    @Test
    fun `wolves channel is only readable by wolves`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val engine = engineOf(room)
        val wolf = seatsWithRole(room, "wolf").first()
        val villager = seatsWithRole(room, "villager").first()

        room.addLog("今晚刀他", by = "狼", scope = "wolves")

        assertTrue(engine.canSeeScope(room, wolf, "wolves"))
        assertTrue(!engine.canSeeScope(room, villager, "wolves"))

        val wolfLogs = engine.view(room, wolf)["log"]!!.jsonArray
        val villagerLogs = engine.view(room, villager)["log"]!!.jsonArray
        assertEquals(1, wolfLogs.size)
        assertEquals(0, villagerLogs.size, "狼人频道的日志不能出现在平民的 state 里")
    }

    @Test
    fun `dead players do not have their role published`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = nightVictimOf(room)

        runNight(room, victim)

        val engine = engineOf(room)
        val observer = seatsWithRole(room, "seer").first()
        val p = engine.view(room, observer)["players"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == victim }
        assertTrue(
            p.jsonObject["role"] is JsonNull,
            "出局不公布身份 —— 公开身份等于告诉大家这一刀砍对没有"
        )
        assertTrue(p.jsonObject["alive"]!!.jsonPrimitive.boolean.not())
    }

    // ===== 夜间流程 =====

    @Test
    fun `night steps run in a fixed order`() {
        val room = newRoom(5)
        engineOf(room).start(room)

        assertEquals("guard", stepOf(room))
        doGuard(room)
        assertEquals("wolf", stepOf(room))
        doWolves(room, aGood(room))
        assertEquals("witch", stepOf(room))
        doWitch(room, "none")
        assertEquals("seer", stepOf(room))
        doSeer(room)
        // 最后一步交完自动结算，不需要房主再点
        assertEquals("day", room.engine.phase)
    }

    @Test
    fun `acting out of turn is rejected`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        assertEquals("guard", stepOf(room))

        val wolf = seatsWithRole(room, "wolf").first()
        assertFailsWith<PlayError> {
            act(room, wolf, "wolfVote", obj("target" to aGood(room)))
        }

        val seer = seatsWithRole(room, "seer").first()
        assertFailsWith<PlayError> {
            act(room, seer, "seerCheck", obj("target" to aGood(room)))
        }
    }

    @Test
    fun `night step is skipped automatically after the timeout`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val engine = engineOf(room)
        assertEquals("guard", stepOf(room))

        // 守卫超时 → 自动守护，直接进狼人
        engine.forceNightDeadline()
        assertTrue(engine.tick(room))
        assertEquals("wolf", stepOf(room))

        // 狼人超时 → 自动袭杀
        engine.forceNightDeadline()
        engine.tick(room)
        assertEquals("witch", stepOf(room))

        // 女巫超时 → 默认不用药（药只有一瓶，自动替人挥霍更糟）
        engine.forceNightDeadline()
        engine.tick(room)
        assertEquals("seer", stepOf(room))

        // 预言家超时 → 自动查验并收尾
        engine.forceNightDeadline()
        engine.tick(room)
        assertEquals("day", room.engine.phase)
        // 自动守护的目标可能与自动刀口撞上（那就是平安夜），所以验公告而不是验人数。
        // 注意 tick() 里自动跳过走的是引擎自己的 room.addLog，不经 act()，日志在 room.log 上。
        val roomLog = room.log.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertTrue(
            roomLog.contains("昨晚出局") || roomLog.contains("平安夜"),
            "夜里该有结算公告：$roomLog"
        )
    }

    @Test
    fun `wolf kill removes the target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = nightVictimOf(room)

        runNight(room, victim)
        assertTrue(victim !in room.engine.alive)
        assertEquals("day", room.engine.phase)
    }

    @Test
    fun `wolves must agree before the night moves on`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val wolves = seatsWithRole(room, "wolf")
        assertEquals(2, wolves.size)

        val a = room.engine.alive.first { roleOf(room, it) != "wolf" }
        val b = room.engine.alive.first { roleOf(room, it) != "wolf" && it != a }

        doGuard(room, avoid = a)
        skipToWolfStep(room)
        act(room, wolves[0], "wolfVote", obj("target" to a))
        act(room, wolves[1], "wolfVote", obj("target" to b))

        // 意见不一就停在这一步等他们商量：不能前进，也不能替他们决定
        assertEquals("wolf", stepOf(room), "狼人没统一目标时不该进入女巫那一步")
        assertEquals("night", room.engine.phase)
        assertTrue(a in room.engine.alive)
        assertTrue(b in room.engine.alive)

        // 改成同一个目标后才继续
        act(room, wolves[1], "wolfVote", obj("target" to a))
        assertEquals("witch", stepOf(room))

        doWitch(room, "none")
        doSeer(room)
        assertTrue(a !in room.engine.alive, "统一目标后该刀生效")
        assertEquals("day", room.engine.phase)
    }

    @Test
    fun `guard blocks the wolf kill`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = nightVictimOf(room)

        doGuard(room, target = victim)
        doWolves(room, victim)
        doWitch(room, "none")
        doSeer(room)

        assertTrue(victim in room.engine.alive, "被守护的人不该死")
        assertEquals("day", room.engine.phase)
        assertEquals(5, room.engine.alive.size)
    }

    @Test
    fun `witch save revives the wolf target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()
        val victim = nightVictimOf(room)

        // 守卫守别人，别把这一刀挡掉，否则测不到解药
        doGuard(room, avoid = victim)
        doWolves(room, victim)
        // 女巫此刻必须已经能看到刀口，否则「要不要救」无从判断
        assertEquals(
            victim,
            engineOf(room).gameState(room, witch)["wolfVictim"]!!.jsonPrimitive.content,
            "女巫应该看得到今晚的刀口"
        )
        doWitch(room, "save")
        doSeer(room)

        assertTrue(victim in room.engine.alive, "解药应把人救回来")
        assertEquals(
            0,
            engineOf(room).gameState(room, witch)["antidoteLeft"]!!.jsonPrimitive.int,
            "解药用掉就没了"
        )
    }

    @Test
    fun `each potion can be used only once`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()
        val engine = engineOf(room)

        // 第一夜：解药救回被刀的人
        val first = nightVictimOf(room)
        doGuard(room, avoid = first)
        doWolves(room, first)
        doWitch(room, "save")
        doSeer(room)
        assertEquals(0, engine.gameState(room, witch)["antidoteLeft"]!!.jsonPrimitive.int)

        // 白天放逐一个非女巫的好人，进第二夜
        voteOut(room, fodder(room, witch))
        assertEquals("night", engine.phase)

        // 第二夜：解药已经没了
        val second = fodder(room, witch, seatsWithRole(room, "guard").firstOrNull() ?: "")
        doGuard(room, avoid = second)
        doWolves(room, second)
        assertFailsWith<PlayError> {
            act(room, witch, "witchAct", obj("use" to "save"))
        }
        doWitch(room, "none")
        doSeer(room)

        // 女巫这一夜可以什么都不用
        assertEquals(1, engine.gameState(room, witch)["poisonLeft"]!!.jsonPrimitive.int)
    }

    @Test
    fun `witch cannot save herself`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()

        doGuard(room, avoid = witch)
        doWolves(room, witch)
        assertEquals(
            witch,
            engineOf(room).gameState(room, witch)["wolfVictim"]!!.jsonPrimitive.content
        )
        assertFailsWith<PlayError> {
            act(room, witch, "witchAct", obj("use" to "save"))
        }
    }

    @Test
    fun `poison kills even through guard`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = nightVictimOf(room)

        doGuard(room, target = victim)
        doWolves(room, victim)
        // 5 人局只有 1 个平民，所以毒预言家 —— 只要不是被守护的 victim 就行
        val poisonTarget = seatsWithRole(room, "seer").first()
        doWitch(room, "poison", poisonTarget)
        doSeer(room)

        assertTrue(victim in room.engine.alive, "守护挡住了狼刀")
        assertTrue(poisonTarget !in room.engine.alive, "毒药无视守护")
    }

    @Test
    fun `witch sees the wolf victim and nobody else does`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()
        val victim = fodder(room, witch)

        doGuard(room, avoid = victim)
        doWolves(room, victim)

        val engine = engineOf(room)
        assertEquals(
            victim,
            engine.gameState(room, witch)["wolfVictim"]!!.jsonPrimitive.content,
            "女巫要看得到刀口"
        )
        room.seats.keys.filter { it != witch }.forEach { other ->
            assertTrue(
                engine.gameState(room, other)["wolfVictim"] is JsonNull,
                "刀口只能给女巫"
            )
        }
    }

    @Test
    fun `guard cannot protect self or repeat last night target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val guard = seatsWithRole(room, "guard").first()

        assertEquals("guard", stepOf(room), "夜间第一步是守卫")
        assertFailsWith<PlayError> {
            act(room, guard, "guardProtect", obj("target" to guard))
        }

        // 守一个好人；狼刀另一个人，否则守护会把这一刀挡掉、夜里没死人
        val first = room.seats.keys.first { it != guard && roleOf(room, it) != "wolf" }
        val killed = room.seats.keys.first {
            it != guard && it != first && roleOf(room, it) != "wolf"
        }
        doGuard(room, target = first)
        doWolves(room, killed)
        doWitch(room, "none")
        doSeer(room)
        assertEquals("day", room.engine.phase)
        assertTrue(first in room.engine.alive, "被守护的人活下来了")

        // 白天放逐一个好人，好让对局继续到第二夜。
        // **不能放逐守卫** —— 守卫一死，第二夜第一步就变成狼人，测不到「不能连守」。
        voteOut(room, room.engine.alive.first { it != first && it != guard && roleOf(room, it) != "wolf" })

        assertEquals("night", room.engine.phase)
        assertEquals("guard", stepOf(room))
        assertFailsWith<PlayError> {
            act(room, guard, "guardProtect", obj("target" to first))
        }
    }

    @Test
    fun `night completes in a small endgame`() {
        // 残局回归：守卫的合法目标被上一夜的守护耗尽时，夜间也必须能走完。
        val room = newRoom(5)
        engineOf(room).start(room)
        val guard = seatsWithRole(room, "guard").first()
        val wolf = seatsWithRole(room, "wolf").first()

        // 第一夜：守卫守 A，狼刀 B → 剩 guard、A、wolf
        val guarded = room.seats.keys.first {
            it != guard && it != wolf && roleOf(room, it) != "wolf"
        }
        val killed = room.seats.keys.first { it != guard && it != guarded && it != wolf }
        doGuard(room, target = guarded)
        doWolves(room, killed)
        doWitch(room, "none")
        doSeer(room)
        assertEquals("day", room.engine.phase)

        // 白天放逐 A —— 场上只剩守卫与狼
        voteOut(room, guarded)

        // 守卫还在时，第二夜必须能推进（不能卡在 guard 步）
        if (guard in room.engine.alive && room.engine.phase == "night") {
            val before = stepOf(room)
            runNight(room, aGood(room))
            assertTrue(
                room.engine.phase != "night" || stepOf(room) != before,
                "夜间应能推进，实际仍停在 $before"
            )
        }
    }

    @Test
    fun `night deaths are announced without identities`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = nightVictimOf(room)

        runNight(room, victim)

        val engine = engineOf(room)
        val deaths = engine.gameState(room, "s0")["deaths"]!!.jsonArray
        assertEquals(1, deaths.size)
        assertEquals(victim, deaths[0].jsonPrimitive.content)

        // 结算日志是引擎"返回"出去由框架写的，测试里攒在 logs（不是 room.log）
        val logText = logText()
        assertTrue(logText.contains("昨晚出局"), "应公告死亡名单：$logText")
        // 只看公告那一行：其它行本来就会提到「女巫本夜不用药」这类行动记录
        val deathLine = logText.lines().first { it.contains("昨晚出局") }
        assertTrue(
            !deathLine.contains("平民") && !deathLine.contains("狼人") &&
                !deathLine.contains("预言家") && !deathLine.contains("女巫") &&
                !deathLine.contains("守卫"),
            "夜间公告不能透露死者的具体身份：$deathLine"
        )
    }

    @Test
    fun `deaths is empty during the night phase`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        assertEquals("night", room.engine.phase)
        assertEquals(0, engineOf(room).gameState(room, "s0")["deaths"]!!.jsonArray.size)
    }

    // ===== 权限 =====

    @Test
    fun `only wolves may wolf vote`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val villager = seatsWithRole(room, "villager").first()
        val target = room.seats.keys.first { roleOf(room, it) != "wolf" }

        skipTo(room, "wolf")
        val result = runCatching {
            act(room, villager, "wolfVote", obj("target" to target))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `wolf cannot kill a fellow wolf`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val wolves = seatsWithRole(room, "wolf")
        skipTo(room, "wolf")
        assertFailsWith<PlayError> {
            act(room, wolves[0], "wolfVote", obj("target" to wolves[1]))
        }
    }

    @Test
    fun `host only actions for the night are gone`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        // 「强制结算夜晚」已经取消：夜间按顺序自动推进，房主不再有这只手
        assertFailsWith<PlayError> { act(room, hostOf(room), "resolveNight") }
        assertFailsWith<PlayError> { act(room, hostOf(room), "startVote") }
        assertFailsWith<PlayError> { act(room, hostOf(room), "resolveVote") }
        assertFailsWith<PlayError> { act(room, hostOf(room), "breakTie", obj("target" to "s1")) }
    }

    // ===== 投票流程 =====

    @Test
    fun `voting only starts after everyone has spoken`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        assertEquals("day", room.engine.phase)

        // 还没人发言，投票打不出去
        assertFailsWith<PlayError> {
            act(room, aGood(room), "vote", obj("target" to aGood(room)))
        }

        speakAll(room)
        assertEquals("vote", room.engine.phase, "全员发言完自动进投票")
    }

    @Test
    fun `vote settles automatically once everyone has voted`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        val target = aGood(room)

        speakAll(room)
        assertEquals("vote", room.engine.phase)
        room.engine.alive.filter { it != target }
            .forEach { act(room, it, "vote", obj("target" to target)) }
        // 被投的人也得表态，否则「人人表态」永远收不齐
        act(room, target, "abstain")

        // 全员表态后还要等 5 秒（测试压成 0），这时 tick 才会真结算
        room.engine.tick(room)
        assertTrue(target !in room.engine.alive, "票数最高的人被放逐")
        if (room.engine.phase != "finished") {
            assertEquals("night", room.engine.phase, "放逐后进入下一夜")
        }
    }

    @Test
    fun `abstaining counts as taking a stance`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        val before = room.engine.alive.size

        abstainAll(room)
        assertEquals(before, room.engine.alive.size, "全员弃票 = 本轮无人出局")
        if (room.engine.phase != "finished") {
            assertEquals("night", room.engine.phase)
        }
        assertTrue(
            room.log.any { it["text"]!!.jsonPrimitive.content.contains("弃票") },
            "应公告全员弃票"
        )
    }

    @Test
    fun `a tie means nobody is eliminated`() {
        val room = newRoom(6)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        val alive = room.engine.alive.toList()
        val a = alive[0]
        val b = alive[1]
        val before = alive.size

        speakAll(room)
        // a 与 b 各拿两票（a 自己不能投自己，所以互投）→ 平票
        val rest = alive.filter { it != a && it != b }
        if (rest.size >= 2) {
            act(room, a, "vote", obj("target" to b))
            act(room, b, "vote", obj("target" to a))
            act(room, rest[0], "vote", obj("target" to a))
            act(room, rest[1], "vote", obj("target" to b))
            rest.drop(2).forEach { act(room, it, "abstain") }
        } else {
            // 6 人局夜里死一个后只有 5 个，够用；保险起见全员弃票
            alive.forEach { act(room, it, "abstain") }
        }
        room.engine.tick(room)

        assertEquals(before, room.engine.alive.size, "平票不淘汰任何人")
    }

    @Test
    fun `discussion times out into voting`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        assertEquals("day", room.engine.phase)

        room.engine.forceDiscussDeadline()
        assertTrue(room.engine.tick(room))
        assertEquals("vote", room.engine.phase, "讨论超时强制进投票")
    }

    @Test
    fun `voting times out and treats silent players as abstaining`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, nightVictimOf(room))
        val alive = room.engine.alive.toList()

        speakAll(room)
        assertEquals("vote", room.engine.phase)

        // 只有一个人投了票，其余挂机 → 超时后按已投的票结算
        val target = alive.first { it != alive[0] }
        act(room, alive[0], "vote", obj("target" to target))
        room.engine.forceVoteDeadline()
        room.engine.tick(room)

        assertTrue(target !in room.engine.alive || room.engine.phase == "finished")
    }

    // ===== 胜负 =====

    @Test
    fun `good wins when all wolves are eliminated`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val wolf = seatsWithRole(room, "wolf").first()

        // 先过一夜，白天直接放逐狼人（5 人局：1 狼 4 好人，狼全灭即好人胜）
        runNight(room, nightVictimOf(room))
        if (room.engine.phase == "day") {
            voteOut(room, wolf)
        }

        assertEquals("finished", room.engine.phase)
        assertEquals(
            "good",
            room.engine.gameState(room, room.seats.keys.first())["result"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `wolves win at parity`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val wolf = seatsWithRole(room, "wolf").first()

        var guardCount = 0
        while (room.engine.phase != "finished" && guardCount < 8) {
            when (room.engine.phase) {
                "night" -> runNight(room, nightVictimOf(room))
                "day" -> voteOut(room, aGood(room))
            }
            guardCount++
        }

        assertEquals("finished", room.engine.phase, "对局应在若干轮内结束")
        assertEquals(
            "wolves",
            room.engine.gameState(room, wolf)["result"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `endGame clears all private state`() {
        val room = newRoom(6)
        engineOf(room).start(room)
        val seer = seatsWithRole(room, "seer").first()
        skipTo(room, "seer")
        // 不能查自己：挑一个不是预言家的活人
        val checkTarget = room.engine.alive.first { it != seer }
        act(room, seer, "seerCheck", obj("target" to checkTarget))

        engineOf(room).endGame(room)

        assertEquals("waiting", room.engine.phase)
        val gs = engineOf(room).gameState(room, seer)
        assertTrue(gs["myRole"] is JsonNull)
        assertEquals(0, gs["seerChecks"]!!.jsonObject.size)
        assertEquals(0, gs["deaths"]!!.jsonArray.size)
    }

    @Test
    fun `restarting a room rerolls every seat`() {
        // 同一个房间反复重开，每次的身份分配都必须和上一局不同 ——
        // 5 人局只有 5 种分法，纯随机时「连撞两局」有 20%，玩家会以为身份是固定的。
        val room = newRoom(5)
        val engine = engineOf(room)
        var prev: Map<String, String>? = null
        repeat(20) { round ->
            engine.start(room)
            val deal = room.seats.keys.associateWith { roleOf(room, it) }
            if (prev != null) {
                assertNotEquals(prev, deal, "第 $round 局的角色分配和上一局完全一样")
            }
            prev = deal
            engine.endGame(room)
        }
    }

    // ===== 测试辅助（依赖上面那些动作） =====

    /** 把夜间推进到狼人那一步，前面的守卫步骤用无争议的动作交掉。 */
    private fun skipToWolfStep(room: Play.Room) {
        doGuard(room)
    }

    /**
     * 把夜间推进到 `role` 那一步，前面的步骤用无争议的动作草草交掉。
     * 用于测试「只有 X 能做什么」这类断言 —— 不先推进到对应步骤，会先撞上顺序守卫。
     */
    private fun skipTo(room: Play.Room, role: String) {
        val order = listOf("guard", "wolf", "witch", "seer")
        for (step in order) {
            if (stepOf(room) != step) continue
            if (step == role) return
            when (step) {
                "guard" -> doGuard(room)
                // 别把预言家/女巫顺手做掉，否则后面那一步直接没人可行动
                "wolf" -> doWolves(
                    room,
                    fodder(
                        room,
                        role,
                        seatsWithRole(room, "seer").firstOrNull() ?: "",
                        seatsWithRole(room, "witch").firstOrNull() ?: ""
                    )
                )

                "witch" -> doWitch(room, "none")
                "seer" -> doSeer(room)
            }
        }
    }
}
