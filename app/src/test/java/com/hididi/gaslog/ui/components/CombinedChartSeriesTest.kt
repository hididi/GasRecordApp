package com.hididi.gaslog.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CombinedChartSeriesTest {

    @Test
    fun `keeps usage temperature and date in one aligned series`() {
        val series = CombinedChartSeries(
            usage = listOf(1.2, 0.8, 1.5),
            temperatures = listOf(8.5, null, 10.0),
            labels = listOf("11/23", "11/24", "11/25"),
        )

        assertEquals(3, series.size)
        assertEquals("11/24", series.labels[1])
        assertEquals(0.8, series.usage[1], 0.0)
        assertNull(series.temperatures[1])
    }
}
