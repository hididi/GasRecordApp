package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DayNightCalculatorTest {

    private val zone = ZoneId.systemDefault()

    private fun ts(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun point(id: Long, date: String, time: String, milli: Long) =
        ReadingPoint(id = id, epochMillis = ts(date, time), readingMilli = milli)

    @Test
    fun `daylight share follows sunrise and sunset`() {
        val sun = { date: LocalDate ->
            SunTimes(date, LocalTime.of(5, 0), LocalTime.of(19, 0))
        }
        // 06:00 → 22:00 共 16 小时，其中 06:00-19:00 为白天 13 小时
        val share = DayNightCalculator.daylightShare(
            ts("2026-06-01", "06:00"),
            ts("2026-06-01", "22:00"),
            sun,
            zone,
        )
        assertEquals(13.0 / 16.0, share, 0.02)
    }

    @Test
    fun `same day day and night readings split exactly`() {
        val points = listOf(
            point(1, "2026-06-01", "06:00", 1_000_000),
            point(2, "2026-06-01", "22:00", 1_010_000), // 10 m³
        )
        val sun = { date: LocalDate -> SunTimes(date, LocalTime.of(5, 0), LocalTime.of(19, 0)) }
        val result = DayNightCalculator.compute(
            points, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), sun, zone,
        )
        assertEquals(10.0, result.totalM3, 0.05)
        assertTrue(result.dayM3 > result.nightM3)
        assertTrue(result.nightShare in 0.1..0.3)
    }

    @Test
    fun `date range without readings has no day or night usage`() {
        val points = listOf(
            point(1, "2026-06-01", "06:00", 1_000_000),
            point(2, "2026-06-01", "22:00", 1_010_000),
        )
        val selectedDate = LocalDate.parse("2026-06-02")

        val result = DayNightCalculator.compute(
            points,
            selectedDate,
            selectedDate,
            { date -> SunTimes(date, LocalTime.of(5, 0), LocalTime.of(19, 0)) },
            zone,
        )

        assertEquals(0.0, result.dayM3, 0.0)
        assertEquals(0.0, result.nightM3, 0.0)
        assertEquals(0.0, result.totalM3, 0.0)
    }

    @Test
    fun `cross day usage is limited to the selected date range`() {
        val points = listOf(
            point(1, "2026-06-01", "00:00", 1_000_000),
            point(2, "2026-06-11", "00:00", 1_010_000), // 10 m³ over 10 days
        )

        val result = DayNightCalculator.compute(
            points,
            LocalDate.parse("2026-06-05"),
            LocalDate.parse("2026-06-05"),
            { date -> SunTimes(date, LocalTime.of(5, 0), LocalTime.of(19, 0)) },
            zone,
        )

        assertEquals(1.0, result.totalM3, 0.05)
    }

    @Test
    fun `falls back to 7 to 19 without sun data`() {
        val share = DayNightCalculator.daylightShare(
            ts("2026-06-01", "00:00"),
            ts("2026-06-02", "00:00"),
            { null },
            zone,
        )
        assertEquals(12.0 / 24.0, share, 0.02)
    }
}
