package localserver.module

import localserver.utils.Logger
import localserver.utils.Util

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 玩家操作被拒绝时抛这个，框架统一转成一条 `err` 帧。 */
class PlayError(message: String) : Exception(message)

/**
 * 两个游戏共用的状态机骨架。
 *
 * 为什么把「存活名单 / 投票 / 平票 / 胜负」放在基类：谁是卧底与狼人杀都有一模一样的
 * 「白天投票放逐」流程，写两遍必然走样。差异只有两点 —— 怎么判身份可见、夜里谁能动手 ——
 * 都交给子类的 [visibleRole] 与 [action]。
 *
 * **防泄漏铁律**：[view] 是按**单个座位**生成的，子类只允许通过 [visibleRole] 表态
 * 「这个人对那个座位看不看得见」。绝不允许先拼一个全房间的大对象再广播 —— 那样一旦漏裁剪，
 * 卧底的词或狼人身份就会直接送到平民的手机上。
 */
abstract class GameEngine(val game: String) {

    var round: Int = 1

    /** waiting | playing | voting | night | day | vote | finished */
    var phase: String = "waiting"

    /** 存活座位号。`waiting` 期为空，[view] 里按「全员存活」处理。 */
    val alive = LinkedHashSet<String>()

    /** 本次投票的实时票型：投票人 -> 目标。 */
    val votes = LinkedHashMap<String, String>()

    /** 上一次结算的票型与票数快照，用于结算后回显。 */
    private var lastVotesMap: Map<String, String> = emptyMap()
    private var lastTallyMap: Map<String, Int> = emptyMap()

    var awaitingTie: Boolean = false
    var tieCandidates: List<String> = emptyList()
    var result: String? = null

    /** 开局。返回 null 表示成功，否则是要回给房主的错误文案。 */
    abstract fun start(room: Play.Room): String?

    /** 游戏内动作。返回要写进日志的公共文本（失败抛 [PlayError]）。 */
    abstract fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String>

    /** 目标玩家对观察者的可见身份；null 表示不公开。 */
    abstract fun visibleRole(room: Play.Room, viewer: String, target: String): String?

    /** 该座位能否看到某个 scope 的日志（`public` 恒可见，`wolves` 只给狼人）。 */
    open fun canSeeScope(room: Play.Room, seatId: String, scope: String): Boolean = scope == "public"

    /** 游戏专属字段，直接合并进 [view] 的顶层。 */
    abstract fun gameState(room: Play.Room, seatId: String): JsonObject

    /** 当前票数统计：目标 -> 票数。 */
    protected fun tally(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        votes.values.forEach { m[it] = (m[it] ?: 0) + 1 }
        return m
    }

    protected fun resetVote() {
        votes.clear()
        awaitingTie = false
        tieCandidates = emptyList()
    }

    /**
     * 房主出局后把房主转给下一个存活玩家。
     *
     * 不做这件事的话，房主一旦被放逐或被杀，`startVote`/`resolveNight` 就再也没人能调，
     * 整局卡死在白天或夜里 —— 而房主恰好是最容易被针对的位置。
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
    }

    /** 收局回到 `waiting`，子类清理自己的私有状态。 */
    open fun endGame(room: Play.Room) {
        phase = "waiting"
        round = 1
        alive.clear()
        resetVote()
        lastVotesMap = emptyMap()
        lastTallyMap = emptyMap()
        result = null
        onEndGame(room)
    }

    open fun onEndGame(room: Play.Room) {}

    /**
     * 生成某个座位能看到的状态。
     *
     * 公共字段（房间/玩家/日志/票型）+ 子类的 [gameState] 平铺合并，前端只需
     * 「收到 state 就整页重绘」，不必理解两份结构。
     */
    fun view(room: Play.Room, seatId: String): JsonObject {
        ensureHost(room)
        val votingNow = phase == "voting" || phase == "vote"

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
            put("awaitingTie", JsonPrimitive(awaitingTie))
            put("tieCandidates", Json.encodeToJsonElement(tieCandidates))
        }
        return JsonObject(base + gameState(room, seatId))
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
                                synchronized(room) {
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
                        val words = raw.split(Regex("[,，、\\s]+"))
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
        val t = (raw ?: "").replace(Regex("[\\p{Cntrl}]"), "").trim()
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
