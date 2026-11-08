package localserver.module

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「狼人杀」引擎测试。
 *
 * 重点覆盖三处最容易写错的地方：
 * 1. 角色配置表（4-12 人的狼/民数量）；
 * 2. 夜间结算顺序（守护 → 狼刀 → 解药 → 毒药）；
 * 3. 身份可见性（狼人互认、预言家只知道自己查验的、出局公开）。
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
        return room
    }

    private fun engineOf(room: Play.Room) = room.engine as PlayWerewolf

    /** 某座位的角色。测试直接读 `myRole`（自己的角色对自己永远可见）。 */
    private fun roleOf(room: Play.Room, seat: String): String =
        engineOf(room).gameState(room, seat)["myRole"]!!.jsonPrimitive.content

    private fun seatsWithRole(room: Play.Room, role: String): List<String> =
        room.seats.keys.filter { roleOf(room, it) == role }

    private fun act(room: Play.Room, seat: String, action: String, data: JsonObject = JsonObject(emptyMap())) =
        engineOf(room).action(room, seat, action, data)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** 当前房主（房主可能因出局而转移）。 */
    private fun hostOf(room: Play.Room): String = room.hostSeat

    /** 一个存活的好人座位（非狼人）。 */
    private fun aGood(room: Play.Room): String =
        room.engine.alive.first { roleOf(room, it) != "wolf" }

    /**
     * 走完一个夜晚：狼刀 + 其余角色各自按需提交。
     *
     * `guardTarget` 默认为 null，表示让守卫守一个**与被刀目标不同**的人 ——
     * 否则测试会被自己安排的守护挡掉，看起来像「狼刀没生效」。
     */
    private fun runNight(room: Play.Room, wolfTarget: String, guardTarget: String? = null) {
        val wolves = seatsWithRole(room, "wolf").filter { it in room.engine.alive }
        wolves.forEach { act(room, it, "wolfVote", buildJsonObject { put("target", wolfTarget) }) }

        val seer = seatsWithRole(room, "seer").firstOrNull { it in room.engine.alive }
        if (seer != null) {
            val t = room.engine.alive.first { it != seer }
            act(room, seer, "seerCheck", buildJsonObject { put("target", t) })
        }

        val witch = seatsWithRole(room, "witch").firstOrNull { it in room.engine.alive }
        if (witch != null) act(room, witch, "witchAct", buildJsonObject { put("use", "none") })

        val guard = seatsWithRole(room, "guard").firstOrNull { it in room.engine.alive }
        if (guard != null) {
            // 守卫不能连守同一个人：从候选中挑一个引擎接受的（顺序尝试，失败就换下一个）
            val candidates = if (guardTarget != null) {
                listOf(guardTarget)
            } else {
                room.engine.alive.filter { it != guard && it != wolfTarget }
            }
            candidates.firstOrNull { t ->
                runCatching {
                    act(room, guard, "guardProtect", buildJsonObject { put("target", t) })
                }.isSuccess
            }
        }

        act(room, hostOf(room), "resolveNight")
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
            val p = wolfView["players"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == w }
            assertEquals("wolf", p.jsonObject["role"]!!.jsonPrimitive.content, "狼人应该认识队友")
        }

        val villagerView = engine.view(room, villager)
        val rolesSeen = villagerView["players"]!!.jsonArray
            .filter { it.jsonObject["id"]!!.jsonPrimitive.content != villager }
            .map { it.jsonObject["role"] }
        assertTrue(rolesSeen.all { it is JsonNull }, "平民不该看到任何人的身份")
    }

    @Test
    fun `seer checks are private to the seer`() {
        val room = newRoom(6)
        engineOf(room).start(room)
        val seer = seatsWithRole(room, "seer").first()
        val target = room.seats.keys.first { it != seer }

        act(room, seer, "seerCheck", buildJsonObject { put("target", target) })

        val engine = engineOf(room)
        val mine = engine.gameState(room, seer)["seerChecks"]!!.jsonObject
        assertEquals(1, mine.size)
        assertNotNull(mine[target])

        // 别人拿到的是空对象
        room.seats.keys.filter { it != seer }.forEach { other ->
            assertEquals(
                0,
                engine.gameState(room, other)["seerChecks"]!!.jsonObject.size,
                "查验结果只给预言家"
            )
        }
    }

    @Test
    fun `wolf votes are private to wolves`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val wolves = seatsWithRole(room, "wolf")
        val villager = seatsWithRole(room, "villager").first()
        val target = room.seats.keys.first { roleOf(room, it) != "wolf" }

        wolves.forEach { act(room, it, "wolfVote", buildJsonObject { put("target", target) }) }

        val engine = engineOf(room)
        assertEquals(
            wolves.size,
            engine.gameState(room, wolves[0])["wolfVotes"]!!.jsonObject.size,
            "狼人之间应看到彼此的刀口"
        )
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
    fun `dead players have their role published`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        runNight(room, victim)

        val engine = engineOf(room)
        val observer = room.seats.keys.first { it != victim && roleOf(room, it) == "seer" }
        val p = engine.view(room, observer)["players"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == victim }
        assertEquals("villager", p.jsonObject["role"]!!.jsonPrimitive.content)
    }

    // ===== 夜间结算 =====

    @Test
    fun `wolf kill removes the target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        runNight(room, victim)
        assertTrue(victim !in room.engine.alive)
        assertEquals("day", room.engine.phase)
    }

    @Test
    fun `guard blocks the wolf kill`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val guard = seatsWithRole(room, "guard").first()
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        val wolves = seatsWithRole(room, "wolf")
        wolves.forEach { act(room, it, "wolfVote", buildJsonObject { put("target", victim) }) }
        val seer = seatsWithRole(room, "seer").first()
        act(room, seer, "seerCheck", buildJsonObject { put("target", guard) })
        val witch = seatsWithRole(room, "witch").first()
        act(room, witch, "witchAct", buildJsonObject { put("use", "none") })
        act(room, guard, "guardProtect", buildJsonObject { put("target", victim) })

        act(room, "s0", "resolveNight")
        assertTrue(victim in room.engine.alive, "被守护的人不该死")
        assertTrue(room.engine.alive.size == 5)
    }

    @Test
    fun `witch save revives the wolf target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        seatsWithRole(room, "wolf").forEach {
            act(room, it, "wolfVote", buildJsonObject { put("target", victim) })
        }
        val seer = seatsWithRole(room, "seer").first()
        act(room, seer, "seerCheck", buildJsonObject { put("target", victim) })
        val guard = seatsWithRole(room, "guard").first()
        // 守卫守别人，别把这一刀挡掉，否则测不到解药
        val other = room.seats.keys.first { it != guard && it != victim }
        act(room, guard, "guardProtect", buildJsonObject { put("target", other) })
        act(room, witch, "witchAct", buildJsonObject { put("use", "save") })

        act(room, "s0", "resolveNight")
        assertTrue(victim in room.engine.alive, "解药应把人救回来")
    }

    @Test
    fun `witch cannot save herself`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()

        seatsWithRole(room, "wolf").forEach {
            act(room, it, "wolfVote", buildJsonObject { put("target", witch) })
        }
        // 女巫看到自己是被刀的人，但不能自救
        assertFailsWith<PlayError> {
            act(room, witch, "witchAct", buildJsonObject { put("use", "save") })
        }
    }

    @Test
    fun `poison kills even through guard`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val witch = seatsWithRole(room, "witch").first()
        val guard = seatsWithRole(room, "guard").first()
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        // 狼刀守卫守的那个人（被挡住），女巫毒另一个人
        seatsWithRole(room, "wolf").forEach {
            act(room, it, "wolfVote", buildJsonObject { put("target", victim) })
        }
        act(room, guard, "guardProtect", buildJsonObject { put("target", victim) })
        // 5 人局只有 1 个平民，所以毒预言家 —— 只要不是被守护的 victim 就行
        val poisonTarget = seatsWithRole(room, "seer").first()
        act(room, witch, "witchAct", buildJsonObject {
            put("use", "poison")
            put("target", poisonTarget)
        })

        act(room, "s0", "resolveNight")
        assertTrue(victim in room.engine.alive, "守护挡住了狼刀")
        assertTrue(poisonTarget !in room.engine.alive, "毒药无视守护")
    }

    @Test
    fun `guard cannot protect self or repeat last night target`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val guard = seatsWithRole(room, "guard").first()

        assertFailsWith<PlayError> {
            act(room, guard, "guardProtect", buildJsonObject { put("target", guard) })
        }

        val first = room.seats.keys.first { it != guard && roleOf(room, it) != "wolf" }
        act(room, guard, "guardProtect", buildJsonObject { put("target", first) })
        val witch = seatsWithRole(room, "witch").first()
        act(room, witch, "witchAct", buildJsonObject { put("use", "none") })
        seatsWithRole(room, "wolf").forEach {
            act(room, it, "wolfVote", buildJsonObject { put("target", first) })
        }
        act(room, hostOf(room), "resolveNight")

        // 进入第二夜，不能连守同一个人
        assertFailsWith<PlayError> {
            act(room, guard, "guardProtect", buildJsonObject { put("target", first) })
        }
    }

    @Test
    fun `night deaths are published but identities are not`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val victim = room.seats.keys.first { roleOf(room, it) == "villager" }

        runNight(room, victim)

        val engine = engineOf(room)
        val deaths = engine.gameState(room, "s0")["deaths"]!!.jsonArray
        assertEquals(1, deaths.size)
        assertEquals(victim, deaths[0].jsonPrimitive.content)

        // 白天时不该有人因为「死」而暴露未公开的身份 —— 但狼人杀里死者身份是公开的，
        // 所以这里只验证「public 日志里没有身份字样」
        val logText = room.log.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertTrue(!logText.contains("狼人") || logText.contains("存活狼人"), "夜间公告不该透露死者的具体身份：$logText")
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

        // 平民没有 wolf 权限
        val result = runCatching {
            act(room, villager, "wolfVote", buildJsonObject { put("target", target) })
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `wolf cannot kill a fellow wolf`() {
        val room = newRoom(8)
        engineOf(room).start(room)
        val wolves = seatsWithRole(room, "wolf")
        assertFailsWith<PlayError> {
            act(room, wolves[0], "wolfVote", buildJsonObject { put("target", wolves[1]) })
        }
    }

    @Test
    fun `only host may resolve night`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val nonHost = room.seats.keys.first { it != "s0" }
        assertFailsWith<PlayError> { act(room, nonHost, "resolveNight") }
    }

    // ===== 胜负 =====

    @Test
    fun `good wins when all wolves are eliminated`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val wolf = seatsWithRole(room, "wolf").first()

        // 白天直接放逐狼人（5 人局：1 狼 4 好人，狼全灭即好人胜）
        runNight(room, seatsWithRole(room, "villager").first())
        if (room.engine.phase == "day") {
            act(room, "s0", "startVote")
            room.seats.keys.filter { it in room.engine.alive && it != wolf }.forEach {
                act(room, it, "vote", buildJsonObject { put("target", wolf) })
            }
            act(room, "s0", "resolveVote")
        }

        assertEquals("finished", room.engine.phase)
        assertEquals("good", room.engine.gameState(room, "s0")["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wolves win at parity`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        val wolf = seatsWithRole(room, "wolf").first()

        // 一夜杀掉一个平民 → 4 人，其中 1 狼 3 好人，还没到 parity。
        // 再放逐一个平民 → 1 狼 2 好人；再一夜杀一个 → 1 狼 1 好人 = parity
        var guardCount = 0
        while (room.engine.phase != "finished" && guardCount < 8) {
            val host = hostOf(room)
            if (room.engine.phase == "night") {
                val target = aGood(room)
                runNight(room, target)
            } else if (room.engine.phase == "day") {
                act(room, host, "startVote")
                val victim = aGood(room)
                room.seats.keys.filter { it in room.engine.alive && it != victim }.forEach {
                    act(room, it, "vote", buildJsonObject { put("target", victim) })
                }
                act(room, host, "resolveVote")
            }
            guardCount++
        }

        assertEquals("finished", room.engine.phase, "对局应在若干轮内结束")
        assertEquals("wolves", room.engine.gameState(room, wolf)["result"]!!.jsonPrimitive.content)
    }

    // ===== 投票 =====

    @Test
    fun `vote phase is entered from day and elimination moves to night`() {
        val room = newRoom(5)
        engineOf(room).start(room)
        runNight(room, seatsWithRole(room, "villager").first())
        assertEquals("day", room.engine.phase)

        // 白天不能直接投票，要先发起
        assertFailsWith<PlayError> {
            act(room, hostOf(room), "vote", buildJsonObject { put("target", "s1") })
        }

        val host = hostOf(room)
        act(room, host, "startVote")
        assertEquals("vote", room.engine.phase)
        val target = room.engine.alive.first { it != host }
        act(room, host, "vote", buildJsonObject { put("target", target) })
        act(room, hostOf(room), "resolveVote")

        if (room.engine.phase != "finished") {
            assertEquals("night", room.engine.phase, "放逐后应进入下一夜")
        }
    }

    @Test
    fun `endGame clears all private state`() {
        val room = newRoom(6)
        engineOf(room).start(room)
        val seer = seatsWithRole(room, "seer").first()
        act(room, seer, "seerCheck", buildJsonObject { put("target", "s1") })

        engineOf(room).endGame(room)

        assertEquals("waiting", room.engine.phase)
        val gs = engineOf(room).gameState(room, seer)
        assertTrue(gs["myRole"] is JsonNull)
        assertEquals(0, gs["seerChecks"]!!.jsonObject.size)
        assertEquals(0, gs["deaths"]!!.jsonArray.size)
    }
}
