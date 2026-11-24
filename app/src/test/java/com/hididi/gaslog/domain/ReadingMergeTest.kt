package com.hididi.gaslog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMergeTest {

    private val existing = listOf(
        MergeReading(id = 1, epochMillis = 1_000, readingMilli = 100_000),
        MergeReading(id = 2, epochMillis = 2_000, readingMilli = 200_000),
    )

    @Test
    fun `same timestamp replaces the timestamp record`() {
        val result = ReadingMerge.resolve(
            existing = existing,
            incomingEpochMillis = 1_000,
            incomingReadingMilli = 150_000,
        )

        assertEquals(1L, result.keepId)
        assertTrue(result.deleteIds.isEmpty())
    }

    @Test
    fun `same reading replaces the reading record`() {
        val result = ReadingMerge.resolve(
            existing = existing,
            incomingEpochMillis = 3_000,
            incomingReadingMilli = 200_000,
        )

        assertEquals(2L, result.keepId)
        assertTrue(result.deleteIds.isEmpty())
    }

    @Test
    fun `timestamp collision wins and removes a separate same reading record`() {
        val result = ReadingMerge.resolve(
            existing = existing,
            incomingEpochMillis = 1_000,
            incomingReadingMilli = 200_000,
        )

        assertEquals(1L, result.keepId)
        assertEquals(setOf(2L), result.deleteIds)
    }
}
