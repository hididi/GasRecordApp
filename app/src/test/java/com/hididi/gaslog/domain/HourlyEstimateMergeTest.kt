package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HourlyEstimateMergeTest {

    private val zone = ZoneId.systemDefault()

    private fun ts(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun point(id: Long, date: String, time: String, milli: Long) = ReadingPoint(
        id = id,
        epochMillis = ts(date, time),
        readingMilli = milli,
        tempMinC = 5.0,
        tempMaxC = 15.0,
    )

    @Test
    fun `same hour from two intervals is merged into one row`() {
        // 17:50→18:10 与 18:10→18:40 两个区间都会切出 18 点的片段
        val points = listOf(
            point(1, "2026-11-24", "17:50", 1_000_000),
            point(2, "2026-11-24", "18:10", 1_000_200),
            point(3, "2026-11-24", "18:40", 1_000_500),
        )
        val sun = { date: LocalDate -> SunTimes(date, LocalTime.of(5, 0), LocalTime.of(19, 0)) }
        val estimate = HeatingDegreeUsageEstimator.estimate(
            points,
            LocalDate.parse("2026-11-24"),
            LocalDate.parse("2026-11-24"),
            sun,
            zone,
        )
        val hours = estimate.hourly(LocalDate.parse("2026-11-24")).map { it.hour }
        assertEquals(hours.distinct(), hours)
        assertEquals(hours.sorted(), hours)
        assertTrue("应该至少包含 17、18 两个整点: $hours", hours.containsAll(listOf(17, 18)))
        // 合并后总量仍等于读数差 0.5 m³
        assertEquals(0.5, estimate.totalM3, 0.001)
    }
}
