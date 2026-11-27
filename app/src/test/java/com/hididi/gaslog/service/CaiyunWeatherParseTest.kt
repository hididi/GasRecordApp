package com.hididi.gaslog.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CaiyunWeatherParseTest {

    private val sample = """
    {
      "status": "ok",
      "api_version": "v2.6",
      "result": {
        "daily": {
          "temperature": [
            {"date": "2026-11-25T00:00:00+08:00", "min": 7, "max": 14, "avg": 10},
            {"date": "2026-11-26T00:00:00+08:00", "min": 9, "max": 19, "avg": 13}
          ]
        }
      }
    }
    """.trimIndent()

    @Test
    fun `parses caiyun daily min and max`() {
        val t = parseCaiyunDailyJson(sample, LocalDate.parse("2026-11-25"))
        assertEquals(7.0, t.tempMinC!!, 1e-9)
        assertEquals(14.0, t.tempMaxC!!, 1e-9)
    }

    @Test
    fun `missing date raises error message`() {
        val e = runCatching { parseCaiyunDailyJson(sample, LocalDate.parse("2026-12-31")) }
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull()!!.message!!.contains("没有"))
    }

    @Test
    fun `error status is surfaced`() {
        val bad = """{"status":"failed","error":"invalid token"}"""
        val e = runCatching { parseCaiyunDailyJson(bad, LocalDate.parse("2026-11-25")) }
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull()!!.message!!.contains("invalid token"))
    }
}
