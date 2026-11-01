package localserver.utils

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 课表作息（`src/main/resources/timetable.json`），供「正课时段自动关闭服务」使用。
 *
 * 为什么服务端要读这个 JSON：课表页本身是纯静态的，作息与科目都写在这个文件里；
 * 服务端直接读同一份，就不必在 Kotlin 里再抄一遍作息表，改 JSON 两边同时生效。
 *
 * 「正课」= 上午 + 下午的 9 节（`kind: 'period'`），且当节科目不是 [NOT_CLASS]。
 * 早自习（`kind: 'pre'`）、晚自习（`kind: 'night'`）与课间都不算正课。
 */
object Timetable {
    /** 这些科目不算正课，上课期间不锁服务器。要扩展直接往这里加。 */
    private val NOT_CLASS = setOf("体育", "自习")

    private const val RESOURCE = "/timetable.json"
    private const val KIND_PERIOD = "period"

    /**
     * 作息表里的一行。
     * @param from 距 0 点的分钟数（"08:00" → 480），比较时不必反复解析字符串
     */
    internal data class Bell(
        val part: String,
        val kind: String,   // pre / period / break / night
        val n: Int,         // 节次，只有 period / night 有
        val label: String,
        val from: Int,
        val to: Int
    )

    private data class Model(
        val week: List<Bell>,
        val sat: List<Bell>,
        val cells: Map<String, String>,
        val satB: Map<String, String>
    )

    private fun toMin(s: String): Int =
        s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun bellOf(e: JsonObject) = Bell(
        part = e.str("part"),
        kind = e.str("kind"),
        n = e["n"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0,
        label = e.str("label"),
        from = toMin(e.str("from")),
        to = toMin(e.str("to"))
    )

    private fun cellsOf(e: JsonObject?): Map<String, String> =
        e?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" } ?: emptyMap()

    /**
     * 读并解析课表。每次调用都重新读文件，不缓存 —— 文件只有几 KB，
     * 而缓存会让「改完 JSON 得等一会儿才生效」这种困惑更麻烦。
     * 解析失败返回 null（调用方按「不上课」处理，宁可不锁也别把人挡在外面）。
     */
    private fun load(): Model? = runCatching {
        val text = Timetable::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
        val root = Json.parseToJsonElement(text).jsonObject
        val bells = root["bells"] as? JsonObject
        val week = (bells?.get("week") as? kotlinx.serialization.json.JsonArray)
            ?.map { bellOf(it.jsonObject) } ?: emptyList()
        val sat = (bells?.get("sat") as? kotlinx.serialization.json.JsonArray)
            ?.map { bellOf(it.jsonObject) } ?: emptyList()
        if (week.isEmpty()) return null
        Model(
            week = week,
            sat = sat,
            cells = cellsOf(root["cells"] as? JsonObject),
            satB = cellsOf(root["satB"] as? JsonObject)
        )
    }.getOrNull()

    /**
     * 第 1 周的周一。2026-08-31 正好是周一，往后每 7 天一换周。
     * 单周（1、3、5…）周六上午用 A 课表，双周（2、4、6…）用 B 课表。
     */
    private val WEEK1_MONDAY: LocalDate = LocalDate.of(2026, 8, 31)

    /**
     * 教学周次，第 1 周从 [WEEK1_MONDAY] 起算。开学前的日期返回 0 或负数，
     * 此时按单双周取模仍能得到确定的 A/B（不会抛错）。
     */
    fun weekOf(t: ZonedDateTime = Time.now()): Int {
        val days = ChronoUnit.DAYS.between(WEEK1_MONDAY, t.toLocalDate())
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 双周用 B 课表（周六上午）。 */
    fun isEvenWeek(t: ZonedDateTime = Time.now()): Boolean = weekOf(t) % 2 == 0

    /**
     * 现在是不是正在上正课。
     */
    fun inClass(): Boolean = inClass(Time.now())

    fun inClass(t: ZonedDateTime): Boolean = currentPeriod(t) != null

    /**
     * 当前正在上的正课节次（1..9）；不在正课时段、或当节是体育/自习时返回 null。
     */
    fun currentPeriod(t: ZonedDateTime): Int? {
        val m = load() ?: return null
        val day = t.dayOfWeek.value - 1          // 周一=0 … 周日=6，与页面格子键一致
        val sat = day == 5
        val bell = if (sat) m.sat else m.week
        if (bell.isEmpty()) return null
        val mins = t.hour * 60 + t.minute
        val b = bell.firstOrNull { it.kind == KIND_PERIOD && mins >= it.from && mins < it.to }
            ?: return null
        // 周六上午有两套课表：单周 A（cells）、双周 B（satB），下午两套共用 cells。
        val key = "$day-${b.n}"
        val subject = if (sat && b.n <= 5 && isEvenWeek(t)) m.satB[key] else m.cells[key]
        // 该节没有科目（例如周日的格子是空的）也算没课
        return if (subject != null && subject.isNotBlank() && subject !in NOT_CLASS) b.n else null
    }

    /**
     * 「课表阶段」标识，用来判断状态该不该跟着变。
     *
     * 只有 上课 ↔ 下课 的切换才会换 key（上课时带上日期与节次，下课时固定 `free`），
     * 所以手动 `/start`、`/exit` 的效果能一直保持到下一次上下课切换，不会被每轮轮询覆盖。
     */
    fun phaseKey(t: ZonedDateTime = Time.now()): String {
        val n = currentPeriod(t) ?: return "free"
        return "class:${t.toLocalDate()}:$n"
    }
}
