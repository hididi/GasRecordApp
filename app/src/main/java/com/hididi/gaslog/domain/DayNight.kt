package com.hididi.gaslog.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 某地某天的日出/日落（当地时间） */
data class SunTimes(
    val date: LocalDate,
    val sunrise: LocalTime?,
    val sunset: LocalTime?,
) {
    val dayLengthHours: Double
        get() {
            val rise = sunrise ?: return 12.0
            val set = sunset ?: return 12.0
            val minutes = java.time.Duration.between(rise, set).toMinutes()
            return (minutes / 60.0).coerceIn(0.0, 24.0)
        }

    /** 判断某个时刻是否属于"白天" */
    fun isDaylight(time: LocalTime): Boolean {
        val rise = sunrise ?: LocalTime.of(7, 0)
        val set = sunset ?: LocalTime.of(19, 0)
        return !time.isBefore(rise) && time.isBefore(set)
    }
}

data class DayNightUsage(
    val dayM3: Double,
    val nightM3: Double,
    val sunDataDays: Int,
    val totalDays: Int,
) {
    val totalM3: Double get() = dayM3 + nightM3
    val nightShare: Double get() = if (totalM3 > 0) nightM3 / totalM3 else 0.0
}

/**
 * 昼夜用气拆分。
 *
 * 只有"两次读数之间的总量"，没有逐时用气，所以这里用最保守也最可解释的模型：
 * 把两次读数之间的用量按小时均分，再按当地日出/日落把每个小时归到"白天"或"夜间"。
 * 这样得到的比例只反映"该时段跨过了多少白天/黑夜"，不会把 24 小时当作同一回事；
 * 如果同一天既有白天读数又有夜间读数，拆分就退化为精确值。
 * 缺少某天日出日落数据时，按 07:00-19:00 为白天兜底，并在界面注明数据来源天数。
 */
object DayNightCalculator {

    fun compute(
        points: List<ReadingPoint>,
        from: LocalDate,
        to: LocalDate,
        sunProvider: (LocalDate) -> SunTimes?,
        zone: ZoneId = Dates.zone,
    ): DayNightUsage {
        val sorted = points.sortedBy { it.epochMillis }
        var dayM3 = 0.0
        var nightM3 = 0.0
        var sunDays = 0
        val days = ((to.toEpochDay() - from.toEpochDay()).toInt() + 1).coerceAtLeast(1)
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 同日多次读数：直接按时刻拆分
        val byDay = sorted.groupBy { Dates.dateOf(it.epochMillis) }
        byDay.forEach { (date, list) ->
            if (date < from || date > to) return@forEach
            if (list.size < 2) return@forEach
            val times = sunProvider(date)
            if (times != null) sunDays++
            for (i in 1 until list.size) {
                val prev = list[i - 1]
                val cur = list[i]
                val volume = (cur.readingMilli - prev.readingMilli)
                if (volume <= 0) continue
                val share = daylightShare(
                    prev.epochMillis, cur.epochMillis, sunProvider, zone,
                )
                dayM3 += Units.milliToM3(volume) * share
                nightM3 += Units.milliToM3(volume) * (1 - share)
            }
        }

        // 跨天间隔：按小时均分到昼夜
        val intervals = UsageCalculator.buildIntervals(sorted)
        intervals.filter { it.spanDays > 0 }.filter { !it.resetDetected }.forEach { iv ->
            val start = maxOf(iv.startEpochMillis, rangeStart)
            val end = minOf(iv.endEpochMillis, rangeEnd)
            if (end <= start) return@forEach
            var date = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val lastDate = java.time.Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
            while (!date.isAfter(lastDate)) {
                if (sunProvider(date) != null) sunDays++
                date = date.plusDays(1)
            }
            val volume = iv.volumeM3 * (end - start).toDouble() /
                (iv.endEpochMillis - iv.startEpochMillis)
            val share = daylightShare(start, end, sunProvider, zone)
            dayM3 += volume * share
            nightM3 += volume * (1 - share)
        }

        return DayNightUsage(dayM3, nightM3, sunDays, days)
    }

    /** 估算 [start, end] 落在白天的用量比例（按小时均分） */
    fun daylightShare(
        start: Long,
        end: Long,
        sunProvider: (LocalDate) -> SunTimes?,
        zone: ZoneId = Dates.zone,
    ): Double {
        if (end <= start) return 0.0
        val step = 15 * 60 * 1000L // 15 分钟粒度
        var totalSlots = 0
        var daySlots = 0
        var t = start
        val sunCache = HashMap<LocalDate, SunTimes?>()
        while (t < end) {
            val slotEnd = minOf(t + step, end)
            val mid = t + (slotEnd - t) / 2
            val dateTime = java.time.Instant.ofEpochMilli(mid).atZone(zone)
            val date = dateTime.toLocalDate()
            val sun = sunCache.getOrPut(date) { sunProvider(date) }
            val isDay = sun?.isDaylight(dateTime.toLocalTime())
                ?: (dateTime.toLocalTime() >= LocalTime.of(7, 0) && dateTime.toLocalTime() < LocalTime.of(19, 0))
            totalSlots++
            if (isDay) daySlots++
            t = slotEnd
        }
        return if (totalSlots == 0) 0.0 else daySlots.toDouble() / totalSlots
    }
}
