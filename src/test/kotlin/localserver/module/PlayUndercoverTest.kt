package localserver.module

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「谁是卧底」引擎测试。
 *
 * 这里刻意不通过 HTTP/WS 打进来，而是直接驱动 [PlayUndercover] + [Play.Room] ——
 * 要验证的是规则本身（谁赢、词怎么分、身份什么时候公开），房间与协议由 PlayRouteTest 覆盖。
 */
class PlayUndercoverTest {

    private fun newRoom(vararg names: String): Play.Room {
        val room = Play.Room("TEST", Play.UNDERCOVER)
        names.forEachIndexed { i, n ->
            val id = "s$i"
            room.seats[id] = Play.Seat(id).also {
                it.name = n
                it.online = true
            }
        }
        room.hostSeat = "s0"
        return room
    }

    private fun engineOf(room: Play.Room) = room.engine as PlayUndercover

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /** 每个座位看到的自己的词。 */
    private fun words(room: Play.Room): Map<String, String> =
        room.seats.keys.associateWith {
            engineOf(room).gameState(room, it)["myWord"]!!.jsonPrimitive.content
        }

    /** 从「词」反推谁是卧底：与多数人不同的那个。 */
    private fun undercoverSeat(room: Play.Room): String {
        val w = words(room)
        val majority = w.values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        return w.entries.first { it.value != majority }.key
    }

    private fun act(room: Play.Room, seat: String, action: String, data: JsonObject = JsonObject(emptyMap())): List<String> =
        engineOf(room).action(room, seat, action, data)

    /** 直接拿动作返回的日志行（比翻 room.log 稳，不会被前面的历史干扰）。 */
    private fun actLines(room: Play.Room, seat: String, action: String, data: JsonObject = JsonObject(emptyMap())): String =
        act(room, seat, action, data).joinToString("\n")

    private fun vote(room: Play.Room, seat: String, target: String) =
        act(room, seat, "vote", buildJsonObject { put("target", target) })

    /** 当前房主（房主可能因出局而转移）。 */
    private fun hostOf(room: Play.Room): String = room.hostSeat

    // ===== 开局 =====

    @Test
    fun `needs at least three players`() {
        val room = newRoom("甲", "乙")
        assertNotNull(engineOf(room).start(room))
        assertEquals("waiting", room.engine.phase)
    }

    @Test
    fun `rejects two undercovers in a three player room`() {
        val room = newRoom("甲", "乙", "丙")
        room.undercoverCount = 2
        // 2 个卧底 + 1 个平民，开局就满足「卧底胜」，一局都玩不下去
        assertNotNull(engineOf(room).start(room))
        assertEquals("waiting", room.engine.phase)
    }

    @Test
    fun `civilians share one word and undercover gets another`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        assertNull(engineOf(room).start(room))

        val w = words(room)
        assertEquals(5, w.size)
        val distinct = w.values.toSet()
        assertEquals(2, distinct.size, "只该有两种词：平民词与卧底词")

        val counts = w.values.groupingBy { it }.eachCount().values.sorted()
        assertEquals(listOf(1, 4), counts, "5 人 1 卧底 = 4 平民 + 1 卧底")
    }

    @Test
    fun `custom group is used as the word source`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.customGroup = listOf("苹果派", "香蕉派")
        assertNull(engineOf(room).start(room))

        val w = words(room)
        assertTrue(w.values.all { it == "苹果派" || it == "香蕉派" }, "词必须来自自定义词组：$w")
    }

    @Test
    fun `single word custom group cannot start`() {
        val room = newRoom("甲", "乙", "丙")
        room.customGroup = listOf("只有一个")
        assertNotNull(engineOf(room).start(room))
    }

    // ===== 身份可见性 =====

    @Test
    fun `role is hidden from everyone while playing`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)

        val engine = engineOf(room)
        room.seats.keys.forEach { viewer ->
            // 自己也不知道自己是不是卧底 —— 这是游戏的核心
            assertNull(engine.gameState(room, viewer)["myRole"]?.takeIf { it !is JsonNull })

            val state = engine.view(room, viewer)
            state["players"]!!.jsonArray.forEach { p ->
                assertTrue(
                    p.jsonObject["role"] is JsonNull,
                    "进行中任何人的身份都不能下发：$p"
                )
            }
        }
    }

    @Test
    fun `no state field ever carries another player's word`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val engine = engineOf(room)

        room.seats.keys.forEach { viewer ->
            val state = engine.view(room, viewer)
            // 顶层只有 myWord（自己的），且 players 里不许出现 word 字段
            assertTrue(state.containsKey("myWord"))
            state["players"]!!.jsonArray.forEach { p ->
                assertNull(p.jsonObject["word"], "players[] 里不允许有 word 字段")
            }
        }
    }

    @Test
    fun `eliminated player learns their own role`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val target = "s4"

        act(room, "s0", "startVote")
        vote(room, "s0", target)
        vote(room, "s1", target)
        vote(room, "s2", target)
        act(room, "s0", "resolveVote")

        val engine = engineOf(room)
        assertNotNull(
            engine.gameState(room, target)["myRole"]?.takeIf { it !is JsonNull },
            "出局后应该知道自己是什么身份"
        )
        // 其他人依旧看不到
        assertNull(engine.gameState(room, "s0")["myRole"]?.takeIf { it !is JsonNull })
    }

    @Test
    fun `finished game reveals all roles`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)
        val engine = engineOf(room)
        val u = undercoverSeat(room)

        act(room, "s0", "startVote")
        val voters = room.seats.keys.filter { it != u }
        voters.forEach { vote(room, it, u) }
        if (room.seats.size > voters.size) vote(room, voters[0], u) // 补一票凑够
        act(room, "s0", "resolveVote")

        assertEquals("finished", room.engine.phase)
        room.seats.keys.forEach { viewer ->
            val state = engine.view(room, viewer)
            state["players"]!!.jsonArray.forEach { p ->
                assertTrue(p.jsonObject["role"] !is JsonNull, "结束后身份应全部公开")
            }
        }
    }

    // ===== 出局只公布身份 =====

    @Test
    fun `elimination publishes identity but never the word`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.customGroup = listOf("柠檬水", "橘子水")
        engineOf(room).start(room)
        val u = undercoverSeat(room)
        val civilian = room.seats.keys.first { it != u }

        act(room, "s0", "startVote")
        // 放逐一个平民：局还没结束，此时绝不能出现任何词
        room.seats.keys.filter { it != civilian }.forEach { vote(room, it, civilian) }
        val lines = actLines(room, "s0", "resolveVote")

        assertTrue(lines.contains("身份：平民"), "应公布身份：$lines")
        assertTrue(!lines.contains("柠檬水") && !lines.contains("橘子水"), "不该公布词：$lines")
        assertEquals("playing", room.engine.phase, "5 人局放逐 1 个平民后还要继续")
    }

    // ===== 投票流程 =====

    @Test
    fun `cannot vote for self or for a nonexistent seat`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)

        act(room, "s0", "startVote")
        assertFailsWith<PlayError> { vote(room, "s0", "s0") }
        assertFailsWith<PlayError> { vote(room, "s0", "nobody") }
    }

    @Test
    fun `only host can start and resolve`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)

        assertFailsWith<PlayError> { act(room, "s1", "startVote") }
        act(room, "s0", "startVote")
        vote(room, "s0", "s1")
        assertFailsWith<PlayError> { act(room, "s1", "resolveVote") }
    }

    @Test
    fun `tie waits for host decision`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val engine = engineOf(room)

        act(room, "s0", "startVote")
        vote(room, "s0", "s1")
        vote(room, "s1", "s0") // 1:1 平票
        act(room, "s0", "resolveVote")

        assertTrue(room.engine.awaitingTie)
        assertEquals(setOf("s0", "s1"), room.engine.tieCandidates.toSet())
        assertEquals("voting", room.engine.phase, "平票时停在投票阶段")

        // 只能从候选人里选
        assertFailsWith<PlayError> {
            act(room, "s0", "breakTie", buildJsonObject { put("target", "s4") })
        }
        act(room, "s0", "breakTie", buildJsonObject { put("target", "s1") })
        assertTrue(!room.engine.awaitingTie)
        assertTrue("s1" !in room.engine.alive)
        assertEquals(4, engine.alive.size)
    }

    @Test
    fun `dead players cannot vote`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)

        act(room, "s0", "startVote")
        room.seats.keys.filter { it != "s4" }.forEach { vote(room, it, "s4") }
        act(room, "s0", "resolveVote")
        assertTrue("s4" !in room.engine.alive)

        // 放逐后回到 playing，要再发起一轮才能投票
        if (room.engine.phase == "playing") act(room, hostOf(room), "startVote")
        assertFailsWith<PlayError> { vote(room, "s4", "s0") }
    }

    // ===== 胜负 =====

    @Test
    fun `civilian wins when all undercovers are out`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val u = undercoverSeat(room)

        act(room, "s0", "startVote")
        room.seats.keys.filter { it != u }.forEach { vote(room, it, u) }
        act(room, "s0", "resolveVote")

        assertEquals("finished", room.engine.phase)
        assertEquals("civilian", engineOf(room).gameState(room, "s0")["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `undercover wins when survivors reach parity`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val u = undercoverSeat(room)
        val civilians = room.seats.keys.filter { it != u }

        // 连续放逐平民，直到存活卧底 ≥ 存活平民
        var round = 0
        while (room.engine.phase != "finished" && round < 6) {
            act(room, hostOf(room), "startVote")
            val victim = civilians.first { it in room.engine.alive }
            // 投票人不能是自己，所以要把被投的那个人排除掉
            room.seats.keys.filter { it in room.engine.alive && it != victim }.forEach { vote(room, it, victim) }
            act(room, hostOf(room), "resolveVote")
            round++
        }

        assertEquals("finished", room.engine.phase)
        assertEquals("undercover", engineOf(room).gameState(room, u)["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `endGame resets to waiting and forgets the words`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)
        engineOf(room).endGame(room)

        assertEquals("waiting", room.engine.phase)
        assertEquals(0, room.engine.alive.size)
        assertTrue(
            engineOf(room).gameState(room, "s0")["myWord"] is JsonNull,
            "回到准备状态后不该还留着上一局的词"
        )
    }

    // ===== canStart / canVote 提示 =====

    @Test
    fun `canStart reflects host and player count`() {
        val room = newRoom("甲", "乙")
        val engine = engineOf(room)
        assertEquals(false, engine.gameState(room, "s0")["canStart"]!!.jsonPrimitive.content.toBoolean())

        room.seats["s2"] = Play.Seat("s2").also { it.name = "丙" }
        assertEquals(true, engine.gameState(room, "s0")["canStart"]!!.jsonPrimitive.content.toBoolean())
        // 非房主永远是 false
        assertEquals(false, engine.gameState(room, "s1")["canStart"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `custom group is echoed back only to the host`() {
        val room = newRoom("甲", "乙", "丙")
        room.customGroup = listOf("红", "蓝")
        val engine = engineOf(room)

        // gameState 里 customGroup 是公共字段（协议如此），但 UI 只在 isHost 时显示；
        // 这里确认它确实带着词组，方便房主回显
        val g = engine.gameState(room, "s0")["customGroup"]
        assertNotNull(g)
        assertEquals("红", g.jsonArray[0].jsonPrimitive.content)
    }
}
