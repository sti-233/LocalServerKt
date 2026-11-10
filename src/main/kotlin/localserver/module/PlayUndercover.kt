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
 * 四个刻意的设计：
 * 1. **进行中不告诉玩家自己是卧底**。玩家只知道自己拿到一个词，是不是卧底要靠听别人描述来猜 ——
 *    这是这个游戏的核心乐趣。所以 [visibleRole] 在自己存活且未结束时一律返回 null。
 * 2. **出局也不公布词**。只公布身份（卧底/平民/空白），否则死掉的人一说话就把答案泄了。
 * 3. **讨论必须人人发言**。基类的 [GameEngine.beginDiscuss] 记发言、全员发完自动进投票 ——
 *    没有这个约束，沉默的人会被别人的节奏直接带进投票。
 * 4. **房主设了自定义词组就下场观战**。词组是「平民词 / 卧底词」成对给的，房主握着答案，
 *    留着玩等于开了天眼；干脆让他看戏，也省得大家怀疑他偷看。
 */
class PlayUndercover : GameEngine(Play.UNDERCOVER) {

    override val discussPhase: String = "playing"
    override val votePhase: String = "voting"

    /** 座位 -> 词。这个词**只**会出现在本人的 `myWord` 里。空白玩家不在这个表里。 */
    private val words = LinkedHashMap<String, String>()

    /** 座位 -> `civilian` / `undercover` / `blank`。 */
    private val roles = LinkedHashMap<String, String>()

    /** 本次实际使用的两个词（平民词、卧底词），用于结束后公布。 */
    private var pairUsed: List<String> = emptyList()

    /**
     * 本局是否用了房主的自定义词组。
     * 用了就不在结束时公布词组 —— 房主自己给的词，摊给全场等于把「谁是卧底」的
     * 判断依据直接送出去，玩家自己手里的词已经够了。
     */
    private var usedCustom: Boolean = false

    /**
     * 上一局的卧底座位。重开时避开它 —— 5 人局只有 5 种分法，
     * 连撞两局（20%）足够让人以为卧底是固定的。
     */
    private var lastUndercoverSeats: Set<String> = emptySet()

    private val random = SecureRandom()

    override fun start(room: Play.Room): String? {
        room.spectators.clear()

        val custom = room.customGroup
        // 自定义词组就是「平民词,卧底词」，多一个字都不收 —— 早先允许 3~6 个词再随机挑两个，
        // 房主设了「苹果,梨,草莓」到底会出哪一对根本不可控
        if (custom != null && custom.size != 2) {
            return "自定义词组必须恰好是「平民词,卧底词」两个词"
        }

        // 房主自设词组时他握着答案，直接下场观战
        val hostWatches = custom != null
        if (hostWatches && room.hostSeat.isNotEmpty()) room.spectators.add(room.hostSeat)

        val seats = room.seats.keys.filter { it !in room.spectators }
        val need = if (hostWatches) "房主观战时还需要 3 名玩家" else "至少需要 3 名玩家"
        if (seats.size < 3) {
            return if (hostWatches) "$need，现在只有 ${seats.size} 名" else "至少需要 3 名玩家"
        }
        // 开局必须全员在线：离线的座位收不到自己的词，也发不了言，留着只会卡住流程
        val offline = seats.filter { room.seats[it]?.online != true }
        if (offline.isNotEmpty()) {
            return "还有 ${offline.size} 名玩家不在线（" +
                offline.joinToString("、") { room.nameOf(it) } + "），请等他们回来再开始"
        }

        val k = room.undercoverCount.coerceIn(1, 2)
        // 卧底必须严格少于平民，否则开局就满足「卧底胜」的条件，一局都玩不下去
        if (k >= seats.size - k) return "玩家太少，${k} 个卧底需要至少 ${k * 2 + 1} 人"

        // 空白算平民阵营，但也得留至少一个拿到词的平民，否则全场没人知道该描述什么
        val blank = room.blankCount.coerceIn(0, seats.size - k - 1)
        room.blankCount = blank

        val group = custom ?: BUILTIN_GROUPS[random.nextInt(BUILTIN_GROUPS.size)]

        // 从词组里挑两个不同的词：一个给平民，一个给卧底。
        // 全部卧底共用一个卧底词、全部平民共用一个平民词 —— 这是原版玩法；
        // 给每个人发不同的词会让「猜谁和大家不一样」彻底失去判据。
        val pair = group.toMutableList().also { Collections.shuffle(it, random) }
        val civilianWord = pair[0]
        val undercoverWord = pair[1]

        // 卧底座位每局重抽，但避开上一局：连撞两局会让人以为卧底是固定的。
        // 重试有上限，凑不出新分配就接受结果 —— 不能因为撞车开不了局。
        var picked = pickUndercover(seats, k)
        var attempt = 1
        while (attempt < DEAL_ATTEMPTS && picked == lastUndercoverSeats) {
            picked = pickUndercover(seats, k)
            attempt++
        }
        lastUndercoverSeats = picked
        val undercoverSeats = picked
        // 空白从剩下的好人里抽，不跟卧底重叠
        val blankSeats = seats.filter { it !in undercoverSeats }
            .toMutableList()
            .also { Collections.shuffle(it, random) }
            .take(blank)
            .toSet()

        words.clear()
        roles.clear()
        seats.forEach { s ->
            roles[s] = when {
                s in undercoverSeats -> "undercover"
                s in blankSeats -> "blank"
                else -> "civilian"
            }
            // 空白玩家根本没有词 —— 不往表里放，[gameState] 会把 myWord 发成 null
            if (s !in blankSeats) {
                words[s] = if (s in undercoverSeats) undercoverWord else civilianWord
            }
        }

        pairUsed = listOf(civilianWord, undercoverWord)
        usedCustom = custom != null
        alive.clear()
        alive.addAll(seats)
        round = 1
        result = null
        // 开局直接进讨论：人人发言之后才自动开投票
        beginDiscuss(room)
        return null
    }

    override fun action(room: Play.Room, seatId: String, action: String, data: JsonObject): List<String> {
        if (phase == "waiting") throw PlayError("游戏还没开始")
        if (seatId in room.spectators) throw PlayError("你在观战，不参与这一局")
        if (seatId !in alive && phase != "finished") throw PlayError("你已出局，只能旁观")

        return when (action) {
            "vote" -> {
                val target = data.string("target") ?: throw PlayError("缺少投票目标")
                castVote(room, seatId, target)
                listOf("${room.nameOf(seatId)} 已投票")
            }

            // 弃票也是态度：不是每个人都能在每个局里都看出谁是卧底
            "abstain" -> {
                castVote(room, seatId, null)
                listOf("${room.nameOf(seatId)} 弃票")
            }

            else -> throw PlayError("未知动作：$action")
        }
    }

    /** 放逐某人、公布身份、判胜负。票型由基类的 [settleVote] 结算后调进来。 */
    override fun eliminate(room: Play.Room, target: String): List<String> {
        val lines = ArrayList<String>()
        alive.remove(target)
        votes.clear()
        abstained.clear()

        val role = roles[target] ?: "civilian"
        // 只公布身份，不公布词 —— 死了的人再说出自己的词就等于泄底
        lines.add("${room.nameOf(target)} 被放逐，身份：${roleLabel(role)}")

        val undercoverAlive = alive.count { roles[it] == "undercover" }
        val civilianAlive = alive.size - undercoverAlive

        when {
            undercoverAlive == 0 -> {
                phase = "finished"
                result = "civilian"
                lines.add("卧底全部出局，平民胜利！")
                lines.add(revealWords())
            }
            undercoverAlive >= civilianAlive -> {
                phase = "finished"
                result = "undercover"
                lines.add("卧底人数已达平民数，卧底胜利！")
                lines.add(revealWords())
            }
            else -> {
                round += 1
                ensureHost(room)
                lines.add("进入第 $round 轮讨论")
                beginDiscuss(room)
            }
        }
        return lines
    }

    /** 平票或全员弃票：本轮不淘汰，直接开下一轮讨论。 */
    override fun skipVote(room: Play.Room, reason: String): List<String> {
        round += 1
        val lines = ArrayList<String>()
        lines.add(reason)
        lines.add("进入第 $round 轮讨论")
        beginDiscuss(room)
        return lines
    }

    /**
     * 结束后公布谜底。空白玩家单独列出来，否则平民会以为自己那轮拿的是空白。
     *
     * 自定义词组不公布：那对词是房主自己设的，其他人在局中手里已经拿到了属于自己的
     * 那一个，把两个词摊开只会让人回头去对「谁描述了另一半」。
     */
    private fun revealWords(): String {
        if (usedCustom) return "本局使用的是房主自定义词组，不公布谜底"
        val civilian = pairUsed.getOrNull(0) ?: "?"
        val undercover = pairUsed.getOrNull(1) ?: "?"
        val blanks = roles.filterValues { it == "blank" }.keys.size
        val tail = if (blanks > 0) "（本局有 $blanks 名空白玩家）" else ""
        return "本局平民词：$civilian　卧底词：$undercover$tail"
    }

    override fun visibleRole(room: Play.Room, viewer: String, target: String): String? {
        val role = roles[target] ?: return null
        return when {
            // 结束后全公开，便于复盘
            phase == "finished" -> role
            // 自己出局了：知道自己是谁（空白也一样，否则出局了还蒙着）
            target == viewer && viewer !in alive -> role
            // 自己还活着：不知道（这是游戏的核心）。空白玩家靠 myWord 为空自己就能推出来，
            // 但那是「拿到空白」这个事实，不是身份牌。
            else -> null
        }
    }

    override fun gameState(room: Play.Room, seatId: String): JsonObject = buildJsonObject {
        val started = phase != "waiting"
        val watching = seatId in room.spectators
        val myRoleName = roles[seatId]
        val isBlank = myRoleName == "blank"
        // 空白玩家没有词：给 null，同时用 myBlank 说清楚这是「你不是没发到词，是本局没有词」
        put("myWord", if (started && !isBlank && !watching) JsonPrimitive(words[seatId] ?: "") else JsonNull)
        put("myBlank", JsonPrimitive(started && isBlank))

        val revealOwn = started && (phase == "finished" || seatId !in alive)
        put("myRole", if (revealOwn && myRoleName != null) JsonPrimitive(myRoleName) else JsonNull)

        put("spectating", JsonPrimitive(watching))
        put("undercoverCount", JsonPrimitive(room.undercoverCount))
        put("blankCount", JsonPrimitive(room.blankCount))
        // 只回给房主：词组就是这一局的答案，广播给别人等于开局就摊牌
        put(
            "customGroup",
            if (room.hostSeat != seatId || room.customGroup == null) JsonNull
            else Json.encodeToJsonElement(room.customGroup)
        )

        val host = room.hostSeat == seatId
        val allOnline = room.seats.values.all { it.online }
        val players = room.seats.keys.count { it !in room.spectators }
        val canStart = host && phase == "waiting" && allOnline && players >= 3 &&
            room.undercoverCount < players - room.undercoverCount &&
            room.blankCount < players - room.undercoverCount
        put("canStart", JsonPrimitive(canStart))
        put("canVote", JsonPrimitive(phase == votePhase && seatId in alive))
        put("result", if (result == null) JsonNull else JsonPrimitive(result!!))
    }

    /** 随机抽 [k] 个座位当卧底。抽出来是为了「重开避开上一局」能重试。 */
    private fun pickUndercover(seats: List<String>, k: Int): Set<String> =
        seats.toMutableList()
            .also { Collections.shuffle(it, random) }
            .take(k)
            .toSet()

    override fun onEndGame(room: Play.Room) {
        words.clear()
        roles.clear()
        pairUsed = emptyList()
        usedCustom = false
        room.spectators.clear()
    }

    private fun roleLabel(role: String): String = when (role) {
        "undercover" -> "卧底"
        "blank" -> "空白"
        else -> "平民"
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {

        /** 重开时避开上一局的尝试次数。5 人局只有 5 种分法，几次就能撞开。 */
        private const val DEAL_ATTEMPTS = 24

        /**
         * 内置词库，每个元素是「一对词」之外的候选池；开局随机挑两个。
         * **只放在服务端**：放进 `resources/` 的话会被 `/resources` 直接下载，
         * 等于把答案贴出来。
         *
         * 每组要有 3 个以上词，否则挑不出「两个不同的词」的机会就只剩一种组合。
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
