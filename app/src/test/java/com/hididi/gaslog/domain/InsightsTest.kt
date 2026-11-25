package com.hididi.gaslog.domain

import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class InsightsTest {

    @Test
    fun `multiple readings in one day do not create an insight`() {
        val date = LocalDate.parse("2026-11-20")

        val insights = Insights.detect(
            points = emptyList(),
            daily = listOf(
                DayUsage(
                    date = date,
                    volumeM3 = 0.8,
                    exact = true,
                    readingCount = 2,
                    firstReadingMilli = 100_000,
                    lastReadingMilli = 100_800,
                    tempMinC = 8.0,
                    tempMaxC = 14.0,
                ),
            ),
            state = null,
            tiers = emptyList(),
            today = date,
        )

        assertFalse(insights.any { it.title == "当天有两次以上读数" })
    }
}
