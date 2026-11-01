package localserver.utils

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.form
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/timetable/now` 给课表页提供"现在几点、星期几"，让高亮不依赖客户端时钟。
 *
 * 重点验三件事：周序换算（周一=0，与 timetable.json 的格子键同一套编号）、
 * 时间字段彼此自洽（`minutes` 必须等于 `hour * 60 + minute`）、
 * 以及 `epochMs` 是个能反算回同一时刻的毫秒时间戳。
 */
class TimetableNowRouteTest {

    private fun withApp(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(Authentication) {
                form("auth") { skipWhen { true } }
                basic("control") { skipWhen { true } }
            }
            routing {
                with(Network) { network() }
            }
        }
        block(client)
    }

    @Test
    fun `now returns a weekday and a self consistent clock`() = withApp { client ->
        val resp = client.get("/timetable/now")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(
            resp.headers["Content-Type"].orEmpty().contains("json"),
            "unexpected content type: ${resp.headers["Content-Type"]}"
        )

        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val day = obj["day"]!!.jsonPrimitive.content.toInt()
        val hour = obj["hour"]!!.jsonPrimitive.content.toInt()
        val minute = obj["minute"]!!.jsonPrimitive.content.toInt()
        val minutes = obj["minutes"]!!.jsonPrimitive.content.toInt()
        val epochMs = obj["epochMs"]!!.jsonPrimitive.content.toLong()
        val dayName = obj["dayName"]!!.jsonPrimitive.content
        val time = obj["time"]!!.jsonPrimitive.content

        // 周序 0..6，且 dayName 与之一一对应（页面顶栏直接用 dayName）
        assertTrue(day in 0..6, "bad day: $day")
        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day], dayName)
        assertTrue(hour in 0..23, "bad hour: $hour")
        assertTrue(minute in 0..59, "bad minute: $minute")
        // minutes 是当天已过分钟数，必须和 hour/minute 对得上（页面靠它定位到哪一节）
        assertEquals(hour * 60 + minute, minutes, "minutes disagrees with hour/minute")
        assertEquals(
            (if (hour < 10) "0$hour" else "$hour") + ":" + (if (minute < 10) "0$minute" else "$minute"),
            time
        )

        // epochMs 与同一份响应里的时分相差不超过几秒（都在服务端同一次取样内算出来）
        val derived = Time.now().toInstant().toEpochMilli()
        assertTrue(kotlin.math.abs(derived - epochMs) < 5_000, "epochMs drifted: $epochMs vs $derived")
    }

    @Test
    fun `now is behind auth like the timetable page itself`() {
        // 这里不装 skipWhen，验证它确实挂在 authenticate("auth") 里：未认证应被挡下
        testApplication {
            application {
                install(Authentication) {
                    form("auth") { }
                    basic("control") { skipWhen { true } }
                }
                routing {
                    with(Network) { network() }
                }
            }
            val resp = client.get("/timetable/now")
            assertTrue(
                resp.status.value >= 300,
                "expected auth challenge, got ${resp.status}"
            )
        }
    }
}
