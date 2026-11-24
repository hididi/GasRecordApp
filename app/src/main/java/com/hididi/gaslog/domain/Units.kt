package com.hididi.gaslog.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 气量与金额的单位约定（全工程统一）：
 * - 气量读数以「千分之一立方米」的整数保存（readingMilli），1 m³ = 1000。
 *   这样 0.001 m³ 的精度不会因浮点误差漂移，做差、累加都用整数。
 * - 金额以「分」的整数保存（priceCentsPerM3 / amountCents）。
 */
object Units {
    const val MILLI_PER_M3: Long = 1000

    fun milliToM3(milli: Long): Double = milli.toDouble() / MILLI_PER_M3

    fun m3ToMilli(m3: Double): Long = Math.round(m3 * MILLI_PER_M3)

    /** 读数显示：1234.567 */
    fun formatReading(milli: Long): String {
        val neg = milli < 0
        val v = kotlin.math.abs(milli)
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(v / MILLI_PER_M3).append('.')
        sb.append((v % MILLI_PER_M3).toString().padStart(3, '0'))
        return sb.toString()
    }

    /** 气量显示：保留 3 位小数，去掉无意义的尾零后至少 3 位（用量统计希望看到精度） */
    fun formatVolume(m3: Double): String =
        BigDecimal.valueOf(m3).setScale(3, RoundingMode.HALF_UP).toPlainString()

    fun formatVolumeShort(m3: Double): String {
        val bd = BigDecimal.valueOf(m3).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    fun formatMoney(cents: Long): String {
        val bd = BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
        return "¥" + bd.toPlainString()
    }

    fun formatMoneyPlain(cents: Long): String =
        BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun formatPrice(cents: Long): String =
        BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** 解析用户输入的读数（允许 0-3 位小数，逗号分隔的千位忽略），失败返回 null */
    fun parseReading(input: String): Long? {
        val cleaned = input.trim().replace(",", "").replace("，", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!cleaned.matches(Regex("""\d{0,9}(\.\d{0,3})?"""))) return null
        return try {
            BigDecimal(cleaned).movePointRight(3).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseMoneyToCents(input: String): Long? {
        val cleaned = input.trim().replace("¥", "").replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!cleaned.matches(Regex("""\d{0,8}(\.\d{0,2})?"""))) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseDoubleOrNull(input: String): Double? =
        input.trim().replace(",", "").toDoubleOrNull()
}

object Dates {
    val zone: ZoneId = ZoneId.systemDefault()

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun dateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun dateTimeOf(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun epochOf(date: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    fun formatDate(epochMillis: Long): String = dateOf(epochMillis).format(dateFmt)

    fun formatDate(date: LocalDate): String = date.format(dateFmt)

    fun formatDateTime(epochMillis: Long): String = dateTimeOf(epochMillis).format(dateTimeFmt)

    fun formatTime(epochMillis: Long): String = dateTimeOf(epochMillis).format(timeFmt)

    fun today(): LocalDate = LocalDate.now(zone)

    fun monthKey(date: LocalDate): String = "%04d-%02d".format(Locale.US, date.year, date.monthValue)

    fun monthLabel(date: LocalDate): String = "%d年%d月".format(Locale.US, date.year, date.monthValue)

    fun weekdayLabel(date: LocalDate): String {
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return names[date.dayOfWeek.value - 1]
    }
}