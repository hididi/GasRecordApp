package com.hididi.gaslog.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MeterDigitInputTest {

    @Test
    fun `initial focus starts at first nonzero digit`() {
        assertEquals(2, firstNonZeroDigitIndex("001234567", 9))
    }

    @Test
    fun `initial focus starts at first cell when all digits are zero or blank`() {
        assertEquals(0, firstNonZeroDigitIndex("000000000", 9))
        assertEquals(0, firstNonZeroDigitIndex("", 9))
    }

    @Test
    fun `initial focus uses the first nonzero digit of the largest historical reading`() {
        assertEquals(3, initialFocusDigitIndex("", 8, "00042000"))
    }

    @Test
    fun `initial focus falls back to the entered digits without historical readings`() {
        assertEquals(2, initialFocusDigitIndex("001234567", 9, null))
    }
}
