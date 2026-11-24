package com.hididi.gaslog.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingDetailSwipeTest {

    @Test
    fun `swipe settles on the available neighboring record after crossing threshold`() {
        assertEquals(
            DetailSwipeTarget.Previous,
            detailSwipeTarget(dragOffsetX = 180f, contentWidth = 1_000, hasPrevious = true, hasNext = true),
        )
        assertEquals(
            DetailSwipeTarget.Next,
            detailSwipeTarget(dragOffsetX = -180f, contentWidth = 1_000, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun `swipe returns to current record below threshold or at list boundary`() {
        assertEquals(
            DetailSwipeTarget.Current,
            detailSwipeTarget(dragOffsetX = 149f, contentWidth = 1_000, hasPrevious = true, hasNext = true),
        )
        assertEquals(
            DetailSwipeTarget.Current,
            detailSwipeTarget(dragOffsetX = 300f, contentWidth = 1_000, hasPrevious = false, hasNext = true),
        )
    }
}
