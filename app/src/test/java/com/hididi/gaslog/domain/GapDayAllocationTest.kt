package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 故意跳过一天不抄表时，缺口日不能被"没气温"这条分支挤成几乎 0 用量。 */
class GapDayAllocationTest {

    private val zone = ZoneId.systemDefault()

    private fun ts(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun point(id: Long, date: String, time: String, milli: Long) =
        ReadingPoint(id = id, epochMillis = ts(date, time), readingMilli = milli)

    /** 带气温的读数（真实数据里两条读数本身就有当天最低/最高温） */
    private fun pointWithTemp(
        id: Long, date: String, time: String, milli: Long, min: Double, max: Double,
    ) = ReadingPoint(
        id = id, epochMillis = ts(date, time), readingMilli = milli, tempMinC = min, tempMaxC = max,
    )

    private val from = LocalDate.parse("2026-11-25")
    private val to = LocalDate.parse("2026-11-27")
    private val points = listOf(
        point(1, "2026-11-25", "20:49", 1_162_495),
        point(2, "2026-11-27", "22:14", 1_174_759),
    )
    private val sun = { date: LocalDate -> SunTimes(date, LocalTime.of(6, 30), LocalTime.of(17, 0)) }

    private fun perDay(usage: HeatingDegreeEstimate) =
        usage.hourlyEstimates.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.volumeM3 } }

    @Test
    fun `without temperatures the gap is split by elapsed time`() {
        val usage = HeatingDegreeUsageEstimator.estimate(
            points, from, to, sun, zone, dailyTemp = { null },
        )
        val days = perDay(usage)
        val total = usage.totalM3
        assertEquals(12.264, total, 0.001)
        val d26 = days[LocalDate.parse("2026-11-26")] ?: 0.0
        val d27 = days[LocalDate.parse("2026-11-27")] ?: 0.0
        // 11-26 全天 + 11-27 到 22:14，约各占一半
        assertTrue("缺口日 11-26 不应被挤成极小值: $d26 / $d27", d26 > total * 0.35)
        assertTrue("11-27 不应独占: $d26 / $d27", d27 < total * 0.65)
    }

    @Test
    fun `temperature provider fills the gap day and model is used`() {
        val withTemps = listOf(
            pointWithTemp(1, "2026-11-25", "20:49", 1_162_495, 3.6, 12.2),
            pointWithTemp(2, "2026-11-27", "22:14", 1_174_759, 6.7, 20.9),
        )
        val usage = HeatingDegreeUsageEstimator.estimate(
            withTemps, from, to, sun, zone,
            dailyTemp = { date ->
                if (date == LocalDate.parse("2026-11-26")) 8.0 to 19.0 else null
            },
        )
        assertTrue("整段都有气温时应该走温度模型", usage.usedTemperatureModel)
        val days = perDay(usage)
        val total = usage.totalM3
        val d26 = days[LocalDate.parse("2026-11-26")] ?: 0.0
        val d27 = days[LocalDate.parse("2026-11-27")] ?: 0.0
        assertTrue("缺口日分配应合理: $d26 / $d27", d26 > total * 0.2)
        assertTrue("缺口日分配应合理: $d26 / $d27", d27 > total * 0.2)
    }

    @Test
    fun `dailyUsage fills temperature for days without readings`() {
        val days = UsageCalculator.dailyUsage(
            points, from, to,
            tempProvider = { date ->
                if (date == LocalDate.parse("2026-11-26")) 8.0 to 19.0 else null
            },
        )
        val d26 = days.firstOrNull { it.date == LocalDate.parse("2026-11-26") }
        assertEquals(8.0, d26!!.tempMinC!!, 1e-9)
        assertEquals(19.0, d26.tempMaxC!!, 1e-9)
        assertTrue("缺口日应有分摊用量", d26.volumeM3 > 0.0)
    }
}
