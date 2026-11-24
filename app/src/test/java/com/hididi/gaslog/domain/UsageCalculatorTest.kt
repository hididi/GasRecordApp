package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class UsageCalculatorTest {

    private fun ts(date: String, time: String = "12:00"): Long {
        val d = LocalDate.parse(date)
        val t = LocalTime.parse(time)
        return d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun point(id: Long, date: String, time: String, readingMilli: Long, tempMax: Double? = null) =
        ReadingPoint(
            id = id,
            epochMillis = ts(date, time),
            readingMilli = readingMilli,
            tempMaxC = tempMax,
        )

    @Test
    fun `two readings in one day give exact usage`() {
        val points = listOf(
            point(1, "2026-11-20", "08:00", 123_000),
            point(2, "2026-11-20", "20:00", 123_400),
        )
        val daily = UsageCalculator.dailyUsage(points, LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-20"))
        assertEquals(1, daily.size)
        assertTrue(daily[0].exact)
        assertEquals(0.4, daily[0].volumeM3, 1e-9)
        assertEquals(2, daily[0].readingCount)
    }

    @Test
    fun `cross day readings are spread over the gap`() {
        val points = listOf(
            point(1, "2026-11-18", "19:00", 100_000),
            point(2, "2026-11-21", "19:00", 100_900),
        )
        val from = LocalDate.parse("2026-11-18")
        val to = LocalDate.parse("2026-11-22")
        val daily = UsageCalculator.dailyUsage(points, from, to)
        val byDate = daily.associateBy { it.date.toString() }
        // 0.9 m³ 分摊到 19/20/21 三天
        assertEquals(0.3, byDate.getValue("2026-11-19").volumeM3, 1e-9)
        assertEquals(0.3, byDate.getValue("2026-11-20").volumeM3, 1e-9)
        assertEquals(0.3, byDate.getValue("2026-11-21").volumeM3, 1e-9)
        assertFalse(byDate.getValue("2026-11-20").exact)
        // 18 号只有一次读数，用量为 0（当天无法与自身相减）
        assertEquals(0.0, byDate.getValue("2026-11-18").volumeM3, 1e-9)
    }

    @Test
    fun `meter reset is treated as new full reading`() {
        val points = listOf(
            point(1, "2026-11-18", "19:00", 900_000),
            point(2, "2026-11-19", "19:00", 12_000),
        )
        val intervals = UsageCalculator.buildIntervals(points)
        assertEquals(1, intervals.size)
        assertTrue(intervals[0].resetDetected)
        assertEquals(12.0, intervals[0].volumeM3, 1e-9)
    }

    @Test
    fun `summary aggregates volume and marks estimated days`() {
        val points = listOf(
            point(1, "2026-11-01", "19:00", 100_000),
            point(2, "2026-11-03", "19:00", 102_000),
            point(3, "2026-11-03", "22:00", 102_500),
        )
        val summary = UsageCalculator.summarize(
            points,
            LocalDate.parse("2026-11-01"),
            LocalDate.parse("2026-11-05"),
        )
        assertEquals(5, summary.days)
        assertTrue(summary.volumeM3 > 2.0)
        assertTrue(summary.estimatedDays >= 1)
    }

    @Test
    fun `previous period has same length`() {
        val (pf, pt) = UsageCalculator.previousPeriod(
            LocalDate.parse("2026-11-10"),
            LocalDate.parse("2026-11-19"),
        )
        assertEquals(LocalDate.parse("2026-10-31"), pf)
        assertEquals(LocalDate.parse("2026-11-09"), pt)
    }

    @Test
    fun `pearson correlates temperature and volume`() {
        val temps = listOf(20.0, 15.0, 10.0, 5.0, 0.0)
        val volume = listOf(0.5, 0.7, 0.9, 1.1, 1.3)
        val r = UsageCalculator.pearson(temps, volume)!!
        assertTrue(r < -0.9)
    }

    @Test
    fun `reading parsing keeps three decimals`() {
        assertEquals(1_234_567L, Units.parseReading("1234.567"))
        assertEquals(1_234_000L, Units.parseReading("1234"))
        assertEquals(500L, Units.parseReading("0.5"))
        assertNull(Units.parseReading("abc"))
        assertEquals("1234.567", Units.formatReading(1_234_567L))
        assertEquals("¥987.65", Units.formatMoney(98_765))
    }
}