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
 * 「谁是卧底」引擎测试。
 *
 * 这里刻意不通过 HTTP/WS 打进来，而是直接驱动 [PlayUndercover] + [Play.Room] ——
 * 要验证的是规则本身（谁赢、词怎么分、身份什么时候公开），房间与协议由 PlayRouteTest 覆盖。
 *
 * 流程已经全自动了：讨论阶段人人发一次言 ⇒ 自动进投票；全员表态（投票或弃票）⇒ 5 秒后
 * 自动结算。测试里把 [GameEngine.voteSettleDelayMs] 压成 0 再调 [GameEngine.tick]，
 * 免得单测真的睡 5 秒。
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
        room.engine.voteSettleDelayMs = 0
        return room
    }

    private fun engineOf(room: Play.Room) = room.engine as PlayUndercover

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        this[key]!!.jsonPrimitive.boolean

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

    /**
     * 动作返回的日志行本来由 [Play.doAction] 写进房间日志，测试直接驱动引擎，
     * 得自己补上，否则「身份：平民」这类断言永远看不到（tick 内部的结算会自己写）。
     */
    private fun act(
        room: Play.Room,
        seat: String,
        action: String,
        data: JsonObject = JsonObject(emptyMap())
    ): List<String> = engineOf(room).action(room, seat, action, data)
        .also { lines -> lines.forEach { room.addLog(it) } }

    private fun vote(room: Play.Room, seat: String, target: String) =
        act(room, seat, "vote", buildJsonObject { put("target", target) })

    private fun abstain(room: Play.Room, seat: String) = act(room, seat, "abstain")

    /** 全员发言：发言就是发一条公共聊天，由框架转成 [GameEngine.noteSpoken]。 */
    private fun speakAll(room: Play.Room) {
        room.engine.alive.forEach { room.engine.noteSpoken(room, it).forEach { l -> room.addLog(l) } }
    }

    /** 除了 [except] 之外所有存活的人都投给 [target]。 */
    private fun voteAllExcept(room: Play.Room, target: String, except: Set<String> = emptySet()) {
        room.engine.alive.filter { it != target && it !in except }.forEach { vote(room, it, target) }
    }

    /** 全员表态后让心跳把票结算掉。 */
    private fun settle(room: Play.Room): Boolean = room.engine.tick(room)

    /**
     * 一轮完整投票：全员发言 → 除 target 外都投他 → target 自己弃票 → 结算。
     *
     * [target] 必须表态：投票阶段等的是「人人表态」，被投的那个人既不投也不弃，
     * 这一步就永远收不齐，结算截止时刻也不会被压到 5 秒后。
     */
    private fun round(room: Play.Room, target: String, voters: List<String>? = null) {
        speakAll(room)
        val v = voters ?: room.engine.alive.filter { it != target }
        v.forEach { vote(room, it, target) }
        abstain(room, target)
        settle(room)
    }

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
    fun `start enters the discussion phase`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        assertNull(engineOf(room).start(room))
        // 不给房主「发起投票」的按钮了：开局直接进讨论，发言齐了才投票
        assertEquals("playing", room.engine.phase)
    }

    @Test
    fun `civilians share one word and undercover gets another`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        assertNull(engineOf(room).start(room))

        val w = words(room)
        assertEquals(5, w.size)
        assertEquals(2, w.values.toSet().size, "只该有两种词：平民词与卧底词")

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
                assertTrue(p.jsonObject["role"] is JsonNull, "进行中任何人的身份都不能下发：$p")
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
        // 必须挑一个平民放逐：放逐卧底会直接结束对局，身份就全部公开了，测不到「只有出局者知道」。
        val u = undercoverSeat(room)
        val target = room.seats.keys.first { it != u }
        val onlooker = room.seats.keys.first { it != target }

        round(room, target)

        val engine = engineOf(room)
        assertNotEquals("finished", room.engine.phase)
        assertEquals(
            "civilian",
            engine.gameState(room, target)["myRole"]?.jsonPrimitive?.content,
            "出局后应该知道自己是什么身份"
        )
        assertNull(
            engine.gameState(room, onlooker)["myRole"]?.takeIf { it !is JsonNull },
            "还活着的玩家不应该知道自己的身份"
        )
    }

    @Test
    fun `finished game reveals all roles`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)
        val engine = engineOf(room)
        val u = undercoverSeat(room)

        round(room, u)

        assertEquals("finished", room.engine.phase)
        room.seats.keys.forEach { viewer ->
            engine.view(room, viewer)["players"]!!.jsonArray.forEach { p ->
                assertTrue(p.jsonObject["role"] !is JsonNull, "结束后身份应全部公开")
            }
        }
    }

    // ===== 出局只公布身份 =====

    @Test
    fun `restarting a room rerolls the undercover seat`() {
        // 同一个房间反复重开，卧底座位不能和上一局完全一样 ——
        // 5 人局只有 5 种分法，纯随机时连撞两局有 20%，玩家会以为卧底是固定的。
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        val engine = engineOf(room)
        var prev: Set<String>? = null
        repeat(20) { round ->
            assertNull(engine.start(room), "第 $round 局没开起来")
            val w = words(room)
            val majority = w.values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            val u = w.filterValues { it != majority }.keys
            assertEquals(1, u.size)
            if (prev != null) {
                assertNotEquals(prev, u, "第 $round 局卧底座位和上一局完全一样")
            }
            prev = u
            engine.endGame(room)
        }
    }

    @Test
    fun `elimination publishes identity but never the word`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.customGroup = listOf("柠檬水", "橘子水")
        engineOf(room).start(room)
        val u = undercoverSeat(room)
        val civilian = room.seats.keys.first { it != u }

        speakAll(room)
        voteAllExcept(room, civilian)
        abstain(room, civilian)
        settle(room)

        val text = room.log.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertTrue(text.contains("身份：平民"), "应公布身份：$text")
        assertTrue(!text.contains("柠檬水") && !text.contains("橘子水"), "不该公布词：$text")
        assertEquals("playing", room.engine.phase, "5 人局放逐 1 个平民后还要继续")
    }

    // ===== 讨论 → 投票（全自动） =====

    @Test
    fun `voting starts only after everyone has spoken`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)

        // 只让 4 个人说话，还差 1 个
        room.engine.alive.take(4).forEach { room.engine.noteSpoken(room, it) }
        assertEquals("playing", room.engine.phase, "没发完言不能开投票")

        // 发起投票这类动作已经不存在了
        assertFailsWith<PlayError> { act(room, hostOf(room), "startVote") }

        room.engine.noteSpoken(room, room.engine.alive.last())
        assertEquals("voting", room.engine.phase, "全员发言后自动进投票")
    }

    @Test
    fun `speaking twice does not double count`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)
        val s0 = room.engine.alive.first()

        room.engine.noteSpoken(room, s0)
        room.engine.noteSpoken(room, s0)
        assertEquals(1, room.engine.spoken.count { it == s0 })
        assertEquals("playing", room.engine.phase)
    }

    @Test
    fun `vote settles automatically a few seconds after everyone has voted`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val target = room.engine.alive.first()

        speakAll(room)
        room.engine.voteSettleDelayMs = 0
        room.engine.alive.filter { it != target }.forEach { vote(room, it, target) }

        // 还有人在表态，不能立刻结算
        assertEquals("voting", room.engine.phase)
        // 最后一个人弃票 ⇒ 全员表态，延迟 0 ⇒ 一次心跳就结算
        abstain(room, target)
        settle(room)
        assertTrue(target !in room.engine.alive)
    }

    @Test
    fun `abstaining counts as taking a stance`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        speakAll(room)

        val alive = room.engine.alive.toList()
        // 前 4 个人弃票，最后一个人照常弃票 —— 全员表态完就该结算
        alive.forEach { abstain(room, it) }
        settle(room)

        val text = room.log.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertTrue(text.contains("无人出局"), "全员弃票应判无人出局：$text")
        assertEquals(alive.size, room.engine.alive.size, "没有人该被放逐")
    }

    @Test
    fun `a tie means nobody is eliminated`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        speakAll(room)

        val alive = room.engine.alive.toList()
        // 4 个人分两派打成 2:2，第 5 个人弃票
        vote(room, alive[0], alive[2])
        vote(room, alive[1], alive[2])
        vote(room, alive[2], alive[0])
        vote(room, alive[3], alive[0])
        abstain(room, alive[4])
        settle(room)

        assertEquals(alive.size, room.engine.alive.size, "平票时本轮无人出局")
        assertEquals("playing", room.engine.phase, "平票后回到讨论，开下一轮")

        // 房主裁决那套已经下线
        assertFailsWith<PlayError> {
            act(room, hostOf(room), "breakTie", buildJsonObject { put("target", alive[0]) })
        }
    }

    @Test
    fun `cannot vote for self or for a nonexistent seat`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        speakAll(room)

        assertFailsWith<PlayError> { vote(room, "s0", "s0") }
        assertFailsWith<PlayError> { vote(room, "s0", "nobody") }
    }

    @Test
    fun `dead players cannot vote`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        round(room, "s4")
        assertTrue("s4" !in room.engine.alive)

        // 新的一轮讨论里出局者不能再投票
        speakAll(room)
        assertFailsWith<PlayError> { vote(room, "s4", "s0") }
    }

    @Test
    fun `discussion times out into voting`() {
        val room = newRoom("甲", "乙", "丙")
        engineOf(room).start(room)

        // 一直没人说话：讨论超时后必须自己进投票，否则整局吊死在讨论
        room.engine.forceDiscussDeadline()
        settle(room)
        assertEquals("voting", room.engine.phase)
    }

    @Test
    fun `voting times out and treats silent players as abstaining`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        speakAll(room)

        // 只有一个人投了票，其余人挂机
        vote(room, "s0", "s1")
        room.engine.forceVoteDeadline()
        settle(room)

        assertTrue("s1" !in room.engine.alive, "唯一的票仍应生效")
    }

    // ===== 胜负 =====

    @Test
    fun `civilian wins when all undercovers are out`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val u = undercoverSeat(room)

        round(room, u)

        assertEquals("finished", room.engine.phase)
        assertEquals("civilian", engineOf(room).gameState(room, "s0").str("result"))
    }

    @Test
    fun `undercover wins when survivors reach parity`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        engineOf(room).start(room)
        val u = undercoverSeat(room)
        val civilians = room.seats.keys.filter { it != u }

        var guard = 0
        while (room.engine.phase != "finished" && guard++ < 6) {
            val victim = civilians.firstOrNull { it in room.engine.alive } ?: break
            round(room, victim)
        }

        assertEquals("finished", room.engine.phase)
        assertEquals("undercover", engineOf(room).gameState(room, u).str("result"))
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

    // ===== canStart / canVote =====

    @Test
    fun `canStart reflects host and player count`() {
        val room = newRoom("甲", "乙")
        val engine = engineOf(room)
        assertEquals(false, engine.gameState(room, "s0").bool("canStart"))

        room.seats["s2"] = Play.Seat("s2").also { it.name = "丙" }
        assertEquals(true, engine.gameState(room, "s0").bool("canStart"))
        assertEquals(false, engine.gameState(room, "s1").bool("canStart"))
    }

    @Test
    fun `custom group is echoed back`() {
        val room = newRoom("甲", "乙", "丙")
        room.customGroup = listOf("红", "蓝")
        val engine = engineOf(room)

        val g = engine.gameState(room, "s0")["customGroup"]
        assertNotNull(g)
        assertEquals("红", g.jsonArray[0].jsonPrimitive.content)
    }
}
