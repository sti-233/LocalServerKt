package localserver.module

import localserver.utils.Logger
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 玩家操作被拒绝时抛这个，框架统一转成一条 `err` 帧。 */
class PlayError(message: String) : Exception(message)

/**
 * 两个游戏共用的状态机骨架。
 *
 * 为什么把「存活名单 / 投票 / 胜负」放在基类：谁是卧底与狼人杀都有一模一样的
 * 「讨论 → 投票放逐」流程，写两遍必然走样。差异只有三点 —— 怎么判身份可见、
 * 夜里谁能动手、出局之后往哪走 —— 都交给子类。
 *
 * **流程是全自动的，不由房主推动**：
 * 讨论阶段每个人都必须发一次言，全员发完立刻进投票；投票阶段可以弃票，
 * 所有人表态完再等 5 秒自动结算。两个阶段各有一个兜底超时，防止有人挂机把
 * 整局吊死。推进靠 [tick]，由 [Play] 的秒级心跳协程驱动。
 *
 * **防泄漏铁律**：[view] 是按**单个座位**生成的，子类只允许通过 [visibleRole] 表态
 * 「这个人对那个座位看不看得见」。绝不允许先拼一个全房间的大对象再广播 —— 那样一旦漏裁剪，
 * 卧底的词或狼人身份就会直接送到平民的手机上。
 */
abstract class GameEngine(val game: String) {

    var round: Int = 1

    /** waiting | playing(讨论) | voting | night | day(讨论) | vote | finished */
    var phase: String = "waiting"

    /** 存活座位号。`waiting` 期为空，[view] 里按「全员存活」处理。 */
    val alive = LinkedHashSet<String>()

    /** 本次投票的真实票：投票人 -> 目标。**弃票不在这里**，见 [abstained]。 */
    val votes = LinkedHashMap<String, String>()

    /** 本次投票弃票的人。弃票同样是表态，照样让流程往前走。 */
    val abstained = HashSet<String>()

    /** 本轮讨论里已经发过言的人。没发言的人不该被别人的节奏带着投票。 */
    val spoken = HashSet<String>()

    /** 上一次结算的票型与票数快照，用于结算后回显。 */
    private var lastVotesMap: Map<String, String> = emptyMap()
    private var lastTallyMap: Map<String, Int> = emptyMap()

    /** 投票截止时刻（epoch ms），0 表示当前没有倒计时。 */
    private var voteDeadlineAt: Long = 0L

    /** 讨论截止时刻（epoch ms），0 表示当前不在讨论。 */
    private var discussDeadlineAt: Long = 0L

    var result: String? = null

    /** 两个游戏对「讨论」「投票」阶段的叫法不同（卧底是 playing/voting，狼人杀是 day/vote）。 */
    protected abstract val discussPhase: String
    protected abstract val votePhase: String

    /** 开局。返回 null 表示成功，否则是要回给房主的错误文案。 */
    abstract fun start(room: Play.Room): String?

    /** 游戏内动作。返回要写进日志的公共文本（失败抛 [PlayError]）。 */
    abstract fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String>

    /** 目标玩家对观察者的可见身份；null 表示不公开。 */
    abstract fun visibleRole(room: Play.Room, viewer: String, target: String): String?

    /** 投票结算后淘汰一个人（含判胜负、进入下一阶段）。 */
    protected abstract fun eliminate(room: Play.Room, target: String): List<String>

    /** 平票 / 全员弃票：本轮不淘汰，直接进下一阶段。 */
    protected abstract fun skipVote(room: Play.Room, reason: String): List<String>

    /** 该座位能否看到某个 scope 的日志（`public` 恒可见，`wolves` 只给狼人）。 */
    open fun canSeeScope(room: Play.Room, seatId: String, scope: String): Boolean = scope == "public"

    /** 游戏专属字段，直接合并进 [view] 的顶层。 */
    abstract fun gameState(room: Play.Room, seatId: String): JsonObject

    // ===== 讨论 / 投票（全自动） =====

    private fun now(): Long = System.currentTimeMillis()

    /**
     * 本轮的「应该表态的人」：存活且在线。
     *
     * 退化成全部存活是刻意的：全员掉线时若返回空集合，`all{}` 会恒为真，
     * 任何一次发言/投票都会立刻把自己推进下一阶段。宁可让回来的那个人等，
     * 也不要让流程被一个空集合骗过去。
     */
    protected fun voters(room: Play.Room): List<String> {
        val online = alive.filter { room.seats[it]?.online == true }
        return if (online.isEmpty()) alive.toList() else online
    }

    /** 还有谁没发言（用于 state.speakNeeded 与前端提示）。 */
    fun speakNeeded(room: Play.Room): List<String> =
        if (phase == discussPhase) voters(room).filter { it !in spoken } else emptyList()

    /** 还有谁没投票也没弃票。 */
    private fun voteNeeded(room: Play.Room): List<String> =
        if (phase == votePhase) voters(room).filter { it !in votes && it !in abstained } else emptyList()

    /** 进入讨论：清空发言记录，起讨论超时。 */
    protected fun beginDiscuss(room: Play.Room) {
        phase = discussPhase
        spoken.clear()
        resetVote()
        discussDeadlineAt = now() + DISCUSS_MAX_MS
    }

    /** 进入投票：清空票型，起投票超时。 */
    protected fun beginVote(room: Play.Room) {
        phase = votePhase
        spoken.clear()
        resetVote()
        discussDeadlineAt = 0L
        voteDeadlineAt = now() + VOTE_MAX_MS
    }

    /**
     * 记一次发言。全员发完就自动进投票，返回要写进日志的行。
     *
     * 由 [Play] 的 chat 分支调用 —— 发言就是发一条公共频道的聊天，
     * 不额外做一个「我要发言」的按钮：多一个按钮就多一次「点了但没说话」的空转。
     */
    fun noteSpoken(room: Play.Room, seatId: String): List<String> {
        if (phase != discussPhase) return emptyList()
        if (seatId !in alive) return emptyList()
        if (!spoken.add(seatId)) return emptyList()
        if (speakNeeded(room).isEmpty()) {
            beginVote(room)
            return listOf("全员发言完毕，开始投票")
        }
        return emptyList()
    }

    /** 投票（[target] 为 null 表示弃票）。 */
    protected fun castVote(room: Play.Room, seatId: String, target: String?) {
        if (phase != votePhase) throw PlayError("现在不是投票时间")
        if (seatId !in alive) throw PlayError("出局的人不能投票")
        if (target == null) {
            abstained.add(seatId)
            votes.remove(seatId)
        } else {
            if (target !in alive) throw PlayError("目标不在场上")
            if (target == seatId) throw PlayError("不能投给自己")
            votes[seatId] = target
            abstained.remove(seatId)
        }
        // 全员表态完就不再干等了，但留 5 秒给人反悔/看票型
        if (voteNeeded(room).isEmpty()) {
            val soon = now() + voteSettleDelayMs
            if (voteDeadlineAt == 0L || soon < voteDeadlineAt) voteDeadlineAt = soon
        }
    }

    /** 测试用：把「全员表态后等 5 秒」压成 0，省得单测真的睡 5 秒。 */
    internal var voteSettleDelayMs: Long = VOTE_SETTLE_DELAY_MS

    /** 测试用：把讨论/投票的截止时刻拉到过去，逼心跳走超时分支。 */
    internal fun forceDiscussDeadline() {
        discussDeadlineAt = System.currentTimeMillis() - 1
    }

    internal fun forceVoteDeadline() {
        voteDeadlineAt = System.currentTimeMillis() - 1
    }

    /**
     * 秒级心跳：讨论超时强制进投票，投票到点自动结算。
     *
     * 返回值只表示「阶段真的变了」，倒计时本身由 [Play] 靠 [countdownActive] 每秒推一次。
     */
    fun tick(room: Play.Room): Boolean {
        // 子类的自有计时（狼人杀每个夜间角色 1 分钟）
        if (tickOwn(room)) return true
        if (phase == discussPhase && discussDeadlineAt > 0 && now() >= discussDeadlineAt) {
            room.addLog("讨论时间到，开始投票")
            beginVote(room)
            return true
        }
        if (phase == votePhase && voteDeadlineAt > 0 && now() >= voteDeadlineAt) {
            val missing = voteNeeded(room)
            if (missing.isNotEmpty()) {
                room.addLog("投票时间到，未表态的 ${missing.size} 人视为弃票")
                missing.forEach { abstained.add(it) }
            }
            settleVote(room).forEach { room.addLog(it) }
            return true
        }
        return false
    }

    /** 当前有没有正在跑的倒计时（有的话前端要每秒看到新数字）。 */
    val countdownActive: Boolean
        get() = ownCountdownActive || (phase == discussPhase && discussDeadlineAt > 0) ||
            (phase == votePhase && voteDeadlineAt > 0)

    /** 子类自己的计时（狼人杀的夜间每步 1 分钟）。返回 true 表示它推进了阶段。 */
    protected open fun tickOwn(room: Play.Room): Boolean = false

    protected open val ownCountdownActive: Boolean get() = false

    /**
     * 结算投票。平票或全员弃票都算「本轮无人出局」—— 不再让房主裁决，
     * 房主裁决意味着「谁当房主」直接决定谁死，而房主本身已经是最容易被针对的位置。
     */
    protected fun settleVote(room: Play.Room): List<String> {
        val t = tally()
        snapshotVote(t)

        val max = t.values.maxOrNull() ?: 0
        val top = if (max == 0) emptyList() else t.filter { it.value == max }.keys.toList()
        return when {
            top.isEmpty() -> skipVote(room, "全员弃票，本轮无人出局")
            top.size > 1 -> skipVote(
                room,
                "票数相同（" + top.joinToString("、") { room.nameOf(it) } + "），本轮无人出局"
            )
            else -> eliminate(room, top[0])
        }
    }

    // ===== 通用小工具 =====

    /** 当前票数统计：目标 -> 票数。 */
    protected fun tally(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        votes.values.forEach { m[it] = (m[it] ?: 0) + 1 }
        return m
    }

    protected fun resetVote() {
        votes.clear()
        abstained.clear()
        voteDeadlineAt = 0L
    }

    /**
     * 房主出局后把房主转给下一个存活玩家。
     *
     * 房主现在只负责开局/改配置/收局，但房主位置上没人时这些操作就没人能做，
     * 直接导致「想再开一局」也点不了。转移本身要写日志，否则玩家会突然发现
     * 管理面板换了人却不知道为什么。
     */
    fun ensureHost(room: Play.Room) {
        if (phase == "waiting" || phase == "finished") return
        if (room.hostSeat in alive) return
        val next = alive.firstOrNull() ?: return
        val oldName = room.nameOf(room.hostSeat)
        room.hostSeat = next
        room.addLog("房主 $oldName 已出局，${room.nameOf(next)} 接任房主")
    }

    /** 结算后把本次票型留作回显，再清空实时票。 */
    protected fun snapshotVote(t: Map<String, Int>) {
        lastVotesMap = votes.toMap()
        lastTallyMap = t
        votes.clear()
        abstained.clear()
        voteDeadlineAt = 0L
    }

    /** 收局回到 `waiting`，子类清理自己的私有状态。 */
    open fun endGame(room: Play.Room) {
        phase = "waiting"
        round = 1
        alive.clear()
        spoken.clear()
        resetVote()
        discussDeadlineAt = 0L
        lastVotesMap = emptyMap()
        lastTallyMap = emptyMap()
        result = null
        onEndGame(room)
    }

    open fun onEndGame(room: Play.Room) {}

    /**
     * 生成某个座位能看到的状态。
     *
     * 公共字段（房间/玩家/日志/票型/倒计时）+ 子类的 [gameState] 平铺合并，
     * 前端只需「收到 state 就整页重绘」，不必理解两份结构。
     */
    fun view(room: Play.Room, seatId: String): JsonObject {
        ensureHost(room)
        val votingNow = phase == votePhase
        val discussing = phase == discussPhase

        val players = buildJsonArray {
            room.seats.values.forEach { s ->
                // waiting 期还没开局，alive 是空的，此时一律显示为存活
                val isAlive = phase == "waiting" || s.id in alive
                add(buildJsonObject {
                    put("id", JsonPrimitive(s.id))
                    put("name", JsonPrimitive(s.name))
                    put("alive", JsonPrimitive(isAlive))
                    put("me", JsonPrimitive(s.id == seatId))
                    put("online", JsonPrimitive(s.online))
                    put("spoken", JsonPrimitive(s.id in spoken))
                    val r = visibleRole(room, seatId, s.id)
                    put("role", if (r == null) JsonNull else JsonPrimitive(r))
                })
            }
        }

        // 日志按 scope 裁剪：狼人频道的内容对非狼人根本不存在
        val logs = buildJsonArray {
            room.log.forEach { item ->
                val scope = item["scope"]!!.jsonPrimitive.content
                if (scope == "public" || canSeeScope(room, seatId, scope)) add(item)
            }
        }

        val needSpeak = speakNeeded(room)
        val needVote = voteNeeded(room)
        val base = buildJsonObject {
            put("room", JsonPrimitive(room.id))
            put("game", JsonPrimitive(game))
            put("seat", JsonPrimitive(seatId))
            put("phase", JsonPrimitive(phase))
            put("round", JsonPrimitive(round))
            put("isHost", JsonPrimitive(room.hostSeat == seatId))
            put("players", players)
            put("log", logs)

            val mine = votes[seatId] ?: lastVotesMap[seatId]
            put("myVote", if (mine == null) JsonNull else JsonPrimitive(mine))
            // 投票进行中只公开自己那一票，票型要等结算后才亮出来
            if (votingNow) {
                put("votes", JsonObject(emptyMap()))
                put("tally", JsonObject(emptyMap()))
            } else {
                put("votes", JsonObject(lastVotesMap.mapValues { JsonPrimitive(it.value) }))
                put("tally", JsonObject(lastTallyMap.mapValues { JsonPrimitive(it.value) }))
            }

            // 流程推进所需的公共进度：前端据此画「还差几个人」与倒计时
            put("spoken", Json.encodeToJsonElement(spoken.toList()))
            put("speakNeeded", Json.encodeToJsonElement(needSpeak))
            put("canSpeak", JsonPrimitive(discussing && seatId in alive && seatId in needSpeak))
            put("myVoted", JsonPrimitive(seatId in votes || seatId in abstained))
            put("votedCount", JsonPrimitive(votes.size + abstained.size))
            put("voteTotal", JsonPrimitive(if (votingNow) voters(room).size else 0))
            put("voteIn", JsonPrimitive(secondsLeft(voteDeadlineAt)))
            put("discussIn", JsonPrimitive(secondsLeft(discussDeadlineAt)))
        }
        return JsonObject(base + gameState(room, seatId))
    }

    private fun secondsLeft(deadline: Long): Int {
        if (deadline <= 0L) return 0
        val ms = deadline - System.currentTimeMillis()
        return if (ms <= 0L) 0 else ((ms + 999) / 1000).toInt()
    }

    companion object {
        /** 全员表态后等多久自动结算 —— 用户明确要求 5 秒。 */
        private const val VOTE_SETTLE_DELAY_MS = 5_000L

        /** 投票兜底：有人挂机时最多等这么久，到点按已投的票结算。 */
        private const val VOTE_MAX_MS = 90_000L

        /** 讨论兜底：有人一直不说话，到点强制进投票。 */
        private const val DISCUSS_MAX_MS = 180_000L
    }
}

/**
 * 「Play」派对游戏模块：谁是卧底 + 狼人杀。
 *
 * 房间状态全在内存里（重启即丢，这是刻意的：没有值得持久化的东西，落盘反而要处理
 * 版本演进的兼容问题）。HTTP 只负责把三个页面 URL 跳到静态资源，房间列表走 WS 的
 * `lobby` 消息 —— 一种机制打天下，不必再维护一套 REST。
 */
object Play {

    const val UNDERCOVER = "undercover"
    const val WEREWOLF = "werewolf"

    private const val LOG_LIMIT = 100
    private const val IDLE_ROOM_MS = 10L * 60 * 1000
    private const val MAX_SEATS = 16

    /** 房号字母表：去掉 0/O/1/I，念出来不会混。 */
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val random = SecureRandom()

    class Seat(val id: String) {
        @Volatile var name: String = ""
        @Volatile var online: Boolean = false
    }

    class Room(val id: String, val game: String) {
        val seats = LinkedHashMap<String, Seat>()

        var hostSeat: String = ""

        val engine: GameEngine = when (game) {
            UNDERCOVER -> PlayUndercover()
            else -> PlayWerewolf()
        }

        /** 卧底的房主配置，跨局保留（房主改一次就不必每局重设）。 */
        var undercoverCount: Int = 1
        var customGroup: List<String>? = null

        val log = ArrayList<JsonObject>()

        @Volatile var updatedAt: Long = System.currentTimeMillis()

        fun nameOf(seatId: String): String = seats[seatId]?.name ?: "?"

        /** 同名座位（用于「同一个人掉线后用同一昵称回来」的座位恢复）。 */
        fun seatByName(name: String): Seat? =
            seats.values.firstOrNull { it.name == name }

        fun addLog(text: String, by: String = "", scope: String = "public") {
            log.add(buildJsonObject {
                put("text", JsonPrimitive(text))
                put("by", JsonPrimitive(by))
                put("ts", JsonPrimitive(System.currentTimeMillis()))
                put("scope", JsonPrimitive(scope))
            })
            // 只保留最近若干条：派对游戏没人会往上翻几十屏，无上限增长只会吃内存
            while (log.size > LOG_LIMIT) log.removeAt(0)
        }
    }

    /** 房间表。`internal` 是为了测试能直接清空/检查。 */
    internal val rooms = ConcurrentHashMap<String, Room>()

    /** 座位 -> 出站队列。键是 `房号|座位号`，一个座位同时只有一条连接。 */
    private val conns = ConcurrentHashMap<String, Channel<String>>()

    // ===== 秒级心跳：讨论/投票的自动推进 =====

    private val tickerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tickerStarted = AtomicBoolean(false)

    /**
     * 起一次心跳就够了。
     *
     * 为什么需要心跳：投票/讨论的推进条件里有「时间」（全员表态后 5 秒、兜底超时），
     * 而这两个都不由任何一条客户端消息触发 —— 没有定时器的话，最后一个玩家投完票
     * 就再也没人叫醒服务端，整局停在投票阶段。
     */
    private fun ensureTicker() {
        if (!tickerStarted.compareAndSet(false, true)) return
        tickerScope.launch {
            while (true) {
                delay(1_000)
                try {
                    tickAll()
                } catch (e: Exception) {
                    Logger.error("Play tick failed: ${e.message}")
                }
            }
        }
    }

    private fun tickAll() {
        rooms.values.forEach { room ->
            synchronized(room) {
                val changed = room.engine.tick(room)
                // 有倒计时就每秒推一次，否则前端的数字会停在拿到 state 的那一刻
                if (changed || room.engine.countdownActive) broadcast(room)
            }
        }
    }

    internal fun resetForTest() {
        rooms.clear()
        conns.clear()
    }

    // ===== 路由 =====

    fun Route.playRoute() {
        authenticate("auth") {
            get("/play") { call.respondRedirect("/resources/play/index.html") }
            get("/play/undercover") { call.respondRedirect("/resources/play/undercover.html") }
            get("/play/werewolf") { call.respondRedirect("/resources/play/werewolf.html") }
        }
        playWs()
    }

    private fun Route.playWs() = authenticate("auth") {
        webSocket("/play/ws") {
            val ip = call.request.local.remoteAddress
            val defaultName = runCatching { Util.getUserName(ip) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "玩家"

            val session = this
            val outbox = Channel<String>(capacity = 64)
            val writer = launch {
                try {
                    for (f in outbox) session.send(Frame.Text(f))
                } catch (e: Exception) {
                    Logger.debug("Play writer stopped: ${e.message}")
                }
            }

            var boundRoom: String? = null
            var boundSeat: String? = null

            fun frame(o: JsonObject) {
                outbox.trySend(o.toString())
            }

            fun err(text: String) {
                frame(buildJsonObject {
                    put("type", JsonPrimitive("err"))
                    put("text", JsonPrimitive(text))
                })
            }

            fun bind(roomId: String, seatId: String) {
                val key = "$roomId|$seatId"
                // 同一座位在别处登录：先告诉旧页面一声，再掐掉它的出站队列
                conns[key]?.let { old ->
                    old.trySend(buildJsonObject {
                        put("type", JsonPrimitive("err"))
                        put("text", JsonPrimitive("该座位已在别的页面登录"))
                    }.toString())
                    old.close()
                }
                conns[key] = outbox
                boundRoom = roomId
                boundSeat = seatId
            }

            fun sendJoined(room: Room, seatId: String) {
                frame(buildJsonObject {
                    put("type", JsonPrimitive("joined"))
                    put("room", JsonPrimitive(room.id))
                    put("seat", JsonPrimitive(seatId))
                    put("name", JsonPrimitive(room.nameOf(seatId)))
                    put("game", JsonPrimitive(room.game))
                    put("isHost", JsonPrimitive(room.hostSeat == seatId))
                })
            }

            try {
                for (f in incoming) {
                    if (f !is Frame.Text) continue
                    // 单帧坏掉只丢这一帧：整块 try 会把一个坏帧升级成断连接（照 Chat 的做法）
                    val obj = try {
                        Json.parseToJsonElement(f.readText()).jsonObject
                    } catch (e: Exception) {
                        Logger.debug("Play bad frame: ${e.message}")
                        continue
                    }
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                    try {
                        when (type) {
                            "create" -> {
                                val game = obj["game"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (game != UNDERCOVER && game != WEREWOLF) throw PlayError("未知的游戏")
                                val name = cleanName(obj["name"]?.jsonPrimitive?.contentOrNull, defaultName)
                                sweep()
                                ensureTicker()
                                val roomId = newRoomId()
                                val seatId = newSeatId()
                                val room = Room(roomId, game)
                                synchronized(room) {
                                    room.seats[seatId] = Seat(seatId).also {
                                        it.name = name
                                        it.online = true
                                    }
                                    room.hostSeat = seatId
                                    room.addLog("$name 创建了房间")
                                }
                                rooms[roomId] = room
                                bind(roomId, seatId)
                                sendJoined(room, seatId)
                                synchronized(room) { broadcast(room) }
                            }

                            "join" -> {
                                val roomId = (obj["room"]?.jsonPrimitive?.contentOrNull ?: "").uppercase()
                                val room = rooms[roomId] ?: throw PlayError("房间不存在")
                                val name = cleanName(obj["name"]?.jsonPrimitive?.contentOrNull, defaultName)
                                ensureTicker()
                                synchronized(room) {
                                    // 同名且已掉线的座位直接接管：刷新页面/换标签页/手机熄屏回来，
                                    // 都该回到自己原来那个座位，而不是在旁边多站一个人
                                    val stale = room.seatByName(name)?.takeIf { !it.online }
                                    if (stale != null) {
                                        stale.online = true
                                        bind(roomId, stale.id)
                                        sendJoined(room, stale.id)
                                        room.addLog("$name 回到了房间")
                                        broadcast(room)
                                        return@synchronized
                                    }
                                    if (room.engine.phase != "waiting") throw PlayError("游戏已经开始，无法加入")
                                    if (room.seats.size >= MAX_SEATS) throw PlayError("房间已满")
                                    val seatId = newSeatId()
                                    room.seats[seatId] = Seat(seatId).also {
                                        it.name = name
                                        it.online = true
                                    }
                                    room.addLog("$name 加入了房间")
                                    bind(roomId, seatId)
                                    sendJoined(room, seatId)
                                    broadcast(room)
                                }
                            }

                            "resume" -> {
                                val roomId = (obj["room"]?.jsonPrimitive?.contentOrNull ?: "").uppercase()
                                val seatId = obj["seat"]?.jsonPrimitive?.contentOrNull ?: ""
                                val room = rooms[roomId] ?: throw PlayError("房间不存在")
                                ensureTicker()
                                synchronized(room) {
                                    val seat = room.seats[seatId] ?: throw PlayError("座位已失效，请重新加入")
                                    seat.online = true
                                    bind(roomId, seatId)
                                    sendJoined(room, seatId)
                                    broadcast(room)
                                }
                            }

                            "lobby" -> {
                                val filter = obj["game"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it == UNDERCOVER || it == WEREWOLF }
                                sweep()
                                frame(buildJsonObject {
                                    put("type", JsonPrimitive("roomList"))
                                    put("rooms", roomListJson(filter))
                                })
                            }

                            "action" -> {
                                val room = boundRoom?.let { rooms[it] } ?: throw PlayError("尚未加入房间")
                                val seatId = boundSeat ?: throw PlayError("尚未加入房间")
                                val action = obj["action"]?.jsonPrimitive?.contentOrNull
                                    ?: throw PlayError("缺少 action")
                                val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
                                synchronized(room) {
                                    doAction(room, seatId, action, data)
                                    broadcast(room)
                                }
                            }

                            "chat" -> {
                                val room = boundRoom?.let { rooms[it] } ?: throw PlayError("尚未加入房间")
                                val seatId = boundSeat ?: throw PlayError("尚未加入房间")
                                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                                    ?.trim()?.take(500).orEmpty()
                                if (text.isEmpty()) continue
                                val scope = obj["scope"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it == "wolves" } ?: "public"
                                synchronized(room) {
                                    if (!room.engine.canSeeScope(room, seatId, scope)) {
                                        throw PlayError("你不能发送该频道")
                                    }
                                    room.addLog(text, room.nameOf(seatId), scope)
                                    // 只有公共发言才算「发过言」：狼人频道里嘀咕两句不能顶替场上发言
                                    if (scope == "public") {
                                        room.engine.noteSpoken(room, seatId).forEach { room.addLog(it) }
                                    }
                                    broadcast(room)
                                }
                            }

                            else -> throw PlayError("未知消息类型：$type")
                        }
                    } catch (e: PlayError) {
                        err(e.message ?: "操作失败")
                    } catch (e: Exception) {
                        Logger.error("Play handle '$type' failed: ${e.message}")
                        err("服务端处理失败")
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                Logger.debug("Play client disconnected")
            } catch (e: Exception) {
                Logger.error("Play connection error: ${e.message}")
            } finally {
                val roomId = boundRoom
                val seatId = boundSeat
                if (roomId != null && seatId != null) {
                    val key = "$roomId|$seatId"
                    // 只摘自己这一条：被新连接顶掉的旧连接不能把新连接从表里删掉
                    if (conns[key] === outbox) conns.remove(key)
                    rooms[roomId]?.let { room ->
                        synchronized(room) {
                            room.seats[seatId]?.online = false
                            broadcast(room)
                        }
                    }
                }
                outbox.close()
                writer.cancel()
            }
        }
    }

    // ===== 动作分发 =====

    private fun doAction(room: Room, seatId: String, action: String, data: JsonObject) {
        val engine = room.engine
        engine.ensureHost(room)
        val isHost = room.hostSeat == seatId

        when (action) {
            "start" -> {
                if (!isHost) throw PlayError("只有房主可以开始游戏")
                if (engine.phase != "waiting") throw PlayError("游戏已经开始了")
                val problem = engine.start(room)
                if (problem != null) throw PlayError(problem)
                room.addLog("游戏开始，共 ${room.seats.size} 名玩家")
            }

            "config" -> {
                if (!isHost) throw PlayError("只有房主可以修改设置")
                if (room.game != UNDERCOVER) throw PlayError("这个游戏没有可配置项")
                if (engine.phase != "waiting") throw PlayError("游戏进行中不能修改设置")
                data["undercover"]?.jsonPrimitive?.intOrNull?.let { k ->
                    if (k !in 1..2) throw PlayError("卧底人数只能是 1 或 2")
                    room.undercoverCount = k
                    room.addLog("卧底人数设为 $k")
                }
                data["group"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    if (raw.isBlank()) {
                        room.customGroup = null
                        room.addLog("已恢复使用内置随机词组")
                    } else {
                        val words = raw.split(Regex("[,，、\\\\s]+"))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        if (words.size < 2) throw PlayError("词组至少需要 2 个不同的词")
                        if (words.size > 6) throw PlayError("词组最多 6 个词")
                        if (words.any { it.length > 16 }) throw PlayError("每个词最长 16 个字符")
                        room.customGroup = words
                        room.addLog("房主设置了自定义词组：" + words.joinToString("、"))
                    }
                }
            }

            "endGame" -> {
                if (!isHost) throw PlayError("只有房主可以结束本局")
                engine.endGame(room)
                room.addLog("本局结束，回到准备状态")
            }

            else -> engine.action(room, seatId, action, data).forEach { room.addLog(it) }
        }
    }

    // ===== 广播 =====

    /**
     * 给房间里**每个座位分别**生成状态再发。这是本模块唯一的防泄漏出口，
     * 不要改成「生成一份全量对象，循环发出去」。
     */
    private fun broadcast(room: Room) {
        room.updatedAt = System.currentTimeMillis()
        val engine = room.engine
        room.seats.values.forEach { seat ->
            val state = engine.view(room, seat.id)
            val json = buildJsonObject {
                put("type", JsonPrimitive("state"))
                put("room", JsonPrimitive(room.id))
                put("game", JsonPrimitive(room.game))
                put("seat", JsonPrimitive(seat.id))
                put("state", state)
            }.toString()
            conns["${room.id}|${seat.id}"]?.trySend(json)
        }
    }

    private fun roomListJson(gameFilter: String?): JsonArray = buildJsonArray {
        rooms.values
            .filter { gameFilter == null || it.game == gameFilter }
            .filter { it.seats.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .forEach { r ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(r.id))
                    put("game", JsonPrimitive(r.game))
                    put("players", JsonPrimitive(r.seats.size))
                    put("phase", JsonPrimitive(r.engine.phase))
                })
            }
    }

    // ===== 工具 =====

    private fun newRoomId(): String {
        repeat(20) {
            val sb = StringBuilder(4)
            repeat(4) { sb.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]) }
            val id = sb.toString()
            if (!rooms.containsKey(id)) return id
        }
        // 20 次都撞上（几乎不可能），退化成时间戳后缀：宁可不好念也别死循环
        return "R" + (System.nanoTime() % 1000).toString().padStart(3, '0')
    }

    private fun newSeatId(): String = UUID.randomUUID().toString().take(8)

    /** 昵称：去掉控制字符、截断到 12 字，空则用默认名。 */
    private fun cleanName(raw: String?, fallback: String): String {
        val t = (raw ?: "").replace(Regex("[\\\\p{Cntrl}]"), "").trim()
        return if (t.isEmpty()) fallback else t.take(12)
    }

    /** 懒清理：空房间放置 10 分钟就回收，避免多一个常驻协程。 */
    private fun sweep() {
        val now = System.currentTimeMillis()
        rooms.entries.removeIf { (_, r) ->
            r.seats.isEmpty() && now - r.updatedAt > IDLE_ROOM_MS
        }
    }
}
