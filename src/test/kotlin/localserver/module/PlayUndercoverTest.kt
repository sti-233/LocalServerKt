package localserver.module

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * 每个座位看到的自己的词。**只收真正拿到词的人**：观战者与空白玩家的 myWord 是 null，
     * 强行 `.content` 会抛异常（而他们本来就不该有词）。
     */
    private fun words(room: Play.Room): Map<String, String> =
        room.seats.keys.mapNotNull { s ->
            val w = engineOf(room).gameState(room, s)["myWord"]
            if (w == null || w is JsonNull) null else s to w.jsonPrimitive.content
        }.toMap()

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
        // 词组是房主定的，他握着答案，这一局必须下场观战
        assertTrue("s0" in room.spectators, "设了自定义词组的房主应当观战")
        assertEquals(4, w.size, "观战的房主不该拿到词")
    }

    @Test
    fun `custom group must be exactly two words`() {
        // 一个词凑不出「平民词 / 卧底词」，三个词则是房主也说不清这局用哪两个
        val one = newRoom("甲", "乙", "丙", "丁")
        one.customGroup = listOf("只有一个")
        assertNotNull(engineOf(one).start(one))

        val three = newRoom("甲", "乙", "丙", "丁")
        three.customGroup = listOf("红", "蓝", "绿")
        assertNotNull(engineOf(three).start(three))
    }

    @Test
    fun `host spectates while a custom group is set`() {
        val room = newRoom("甲", "乙", "丙", "丁")
        room.customGroup = listOf("苹果派", "香蕉派")
        assertNull(engineOf(room).start(room))

        assertTrue("s0" in room.spectators)
        assertFalse("s0" in room.engine.alive, "观战者不进存活名单")
        assertEquals(true, engineOf(room).gameState(room, "s0").bool("spectating"))
        // 观战者不能投票，也不能假装发言推进流程
        assertFailsWith<PlayError> { act(room, "s0", "abstain") }
        assertEquals(0, room.engine.speakNeeded(room).count { it == "s0" })
    }

    @Test
    fun `clearing the custom group puts the host back in`() {
        val room = newRoom("甲", "乙", "丙", "丁")
        room.customGroup = listOf("苹果派", "香蕉派")
        assertNull(engineOf(room).start(room))
        assertTrue("s0" in room.spectators)

        // 恢复内置词库之后再开的局，房主照常参与
        engineOf(room).endGame(room)
        room.customGroup = null
        assertNull(engineOf(room).start(room))
        assertTrue(room.spectators.isEmpty())
        assertTrue("s0" in room.engine.alive)
    }

    @Test
    fun `blank players get no word and count as civilians`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.blankCount = 1
        assertNull(engineOf(room).start(room))

        val w = words(room)
        assertEquals(4, w.size, "5 人里有 1 个空白，只有 4 个人拿到词")
        // 空白自己知道自己是空白（否则他会一直等发词），但身份牌依旧不公开
        val blank = room.seats.keys.first { it !in w.keys }
        assertEquals(true, engineOf(room).gameState(room, blank).bool("myBlank"))
        assertNull(engineOf(room).gameState(room, blank)["myRole"]?.takeIf { it !is JsonNull })
        // 空白算平民阵营：他不知道自己词（没有词），但也不该被当成卧底
        assertNull(engineOf(room).gameState(room, blank)["myWord"]?.takeIf { it !is JsonNull })
    }

    @Test
    fun `blank count is clamped to leave at least one civilian`() {
        val room = newRoom("甲", "乙", "丙")
        room.blankCount = 5
        // 3 人 1 卧底 → 只剩 2 个好人位，最多 1 个空白；要求 4 个空白必须被夹到合法值
        assertNull(engineOf(room).start(room))
        assertEquals(1, room.blankCount)
        assertEquals(3, engineOf(room).alive.size)
    }

    @Test
    fun `blank player wins by guessing the civilian word`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.blankCount = 1
        assertNull(engineOf(room).start(room))

        val w = words(room)
        val blank = room.seats.keys.first { it !in w.keys }
        // 多数人拿到的是平民词，空白要猜的就是它
        val civilian = w.values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

        val lines = act(room, blank, "guess", buildJsonObject { put("word", civilian) })
        assertEquals("finished", room.engine.phase)
        assertEquals("blank", room.engine.result)
        assertTrue(lines.any { it.contains("猜中了平民词") }, lines.toString())
    }

    @Test
    fun `blank guess is a single attempt`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.blankCount = 1
        assertNull(engineOf(room).start(room))

        val w = words(room)
        val blank = room.seats.keys.first { it !in w.keys }
        val civilian = w.values.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

        // 猜错只是把机会用掉，不结束本局 —— 否则空白可以拿错误次数试出答案
        assertFailsWith<PlayError> {
            act(room, blank, "guess", buildJsonObject { put("word", "一定不是这个词") })
        }
        assertEquals("playing", room.engine.phase)
        assertFalse(engineOf(room).gameState(room, blank).bool("canGuess"), "猜过一次就不能再猜")
        // 猜对了也不给了
        assertFailsWith<PlayError> {
            act(room, blank, "guess", buildJsonObject { put("word", civilian) })
        }
    }

    @Test
    fun `only blank players may guess`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊")
        room.blankCount = 1
        assertNull(engineOf(room).start(room))

        val w = words(room)
        val civilianSeat = w.entries.first {
            it.value == w.values.groupingBy { v -> v }.eachCount().maxByOrNull { c -> c.value }!!.key
        }.key
        assertFailsWith<PlayError> {
            act(room, civilianSeat, "guess", buildJsonObject { put("word", "苹果") })
        }
    }

    @Test
    fun `blanks win once they reach parity with civilians`() {
        // 6 人：1 卧底 + 2 空白 + 3 平民
        val room = newRoom("甲", "乙", "丙", "丁", "戊", "己")
        room.blankCount = 2
        assertNull(engineOf(room).start(room))

        val w = words(room)
        val blankSeats = room.seats.keys.filter { it !in w.keys }
        assertEquals(2, blankSeats.size)

        // 先投掉卧底：此时空白 2 < 平民 3，还得继续
        round(room, undercoverSeat(room))
        assertEquals("playing", room.engine.phase, "没有卧底但空白还没追平，本局必须继续")

        // 再投掉一个平民 → 空白 2 = 平民 2，空白胜利
        val civilianSeat = room.engine.alive.first { it !in blankSeats }
        round(room, civilianSeat)
        assertEquals("finished", room.engine.phase)
        assertEquals("blank", room.engine.result)
    }

    @Test
    fun `blanks lose when they are all voted out`() {
        val room = newRoom("甲", "乙", "丙", "丁", "戊", "己")
        room.blankCount = 2
        assertNull(engineOf(room).start(room))

        val w = words(room)
        val blankSeats = room.seats.keys.filter { it !in w.keys }

        round(room, undercoverSeat(room))
        assertEquals("playing", room.engine.phase)

        round(room, blankSeats[0])
        assertEquals("playing", room.engine.phase, "还剩一个空白，平民不能直接赢")

        round(room, blankSeats[1])
        assertEquals("finished", room.engine.phase)
        assertEquals("civilian", room.engine.result)
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
        // 用 alive 取人：设了自定义词组后房主在观战，不在存活名单里，投他会报「目标不在场」
        val civilian = room.engine.alive.first { it != u }

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

        room.seats["s2"] = Play.Seat("s2").also {
            it.name = "丙"
            it.online = true
        }
        assertEquals(true, engine.gameState(room, "s0").bool("canStart"))
        assertEquals(false, engine.gameState(room, "s1").bool("canStart"))
    }

    @Test
    fun `all players must be online to start`() {
        val room = newRoom("甲", "乙", "丙")
        val engine = engineOf(room)
        assertNull(engine.start(room))

        engine.endGame(room)
        room.seats["s2"]!!.online = false
        assertEquals(false, engine.gameState(room, "s0").bool("canStart"))
        val problem = engine.start(room)
        assertNotNull(problem)
        assertTrue(problem.contains("不在线"))
        assertTrue(problem.contains("丙"))
    }

    @Test
    fun `custom group is echoed back to the host only`() {
        val room = newRoom("甲", "乙", "丙")
        room.customGroup = listOf("红", "蓝")
        val engine = engineOf(room)

        val g = engine.gameState(room, "s0")["customGroup"]
        assertNotNull(g)
        assertEquals("红", g.jsonArray[0].jsonPrimitive.content)

        // 词组就是这一局的答案：除了房主，谁都不该在 state 里看到它
        assertTrue(engine.gameState(room, "s1")["customGroup"] is JsonNull, "非房主不该看到词组")
        assertTrue(engine.gameState(room, "s2")["customGroup"] is JsonNull, "非房主不该看到词组")
    }

    @Test
    fun `custom group is never announced when the game ends`() {
        // 房主设了词组就下场观战，场上四人：1 卧底 3 平民
        val room = newRoom("甲", "乙", "丙", "丁")
        room.customGroup = listOf("苹果派", "香蕉派")
        assertNull(engineOf(room).start(room))
        val u = undercoverSeat(room)

        round(room, u)
        assertEquals("finished", room.engine.phase)

        // 自定义词组是房主给的答案，结束时也不公布
        val text = room.log.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertFalse(text.contains("苹果派"), "结束公告不该带自定义词组：$text")
        assertFalse(text.contains("香蕉派"), "结束公告不该带自定义词组：$text")
        assertTrue(text.contains("不公布谜底"), "应当明确说明本局不公布谜底")
    }
}
