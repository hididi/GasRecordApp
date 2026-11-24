package com.hididi.gaslog.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

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

/** 一个整点开始的估算用气片段。最后一个片段可能不足一小时。 */
data class HourlyUsageEstimate(
    val date: LocalDate,
    val hour: Int,
    val temperatureC: Double?,
    val volumeM3: Double,
)

/** 读数间隔按逐小时温度权重分摊后的结果。 */
data class HeatingDegreeEstimate(
    val hourlyEstimates: List<HourlyUsageEstimate>,
    val usedTemperatureModel: Boolean,
    val sunDataDays: Int,
    val totalDays: Int,
    private val sunTimesByDate: Map<LocalDate, SunTimes?> = emptyMap(),
) {
    val totalM3: Double get() = hourlyEstimates.sumOf { it.volumeM3 }

    fun hourly(date: LocalDate): List<HourlyUsageEstimate> =
        hourlyEstimates.filter { it.date == date }

    fun dailyM3(date: LocalDate): Double = hourly(date).sumOf { it.volumeM3 }

    fun dayNight(): DayNightUsage {
        var day = 0.0
        var night = 0.0
        hourlyEstimates.forEach { item ->
            // 整点片段按中点归类，和原有 15 分钟计算器保持同一昼夜边界。
            val time = LocalTime.of(item.hour, 30)
            if (sunTimesByDate[item.date]?.isDaylight(time) == true ||
                (sunTimesByDate[item.date] == null && time >= LocalTime.of(7, 0) && time < LocalTime.of(19, 0))
            ) day += item.volumeM3 else night += item.volumeM3
        }
        return DayNightUsage(day, night, sunDataDays, totalDays)
    }
}

/**
 * 在只有两次表读数时，用每日最低/最高温估出平滑日内温度，再按采暖度分配用气。
 * 这不是计量值；没有气温数据的间隔仍退回按时长均分。
 */
object HeatingDegreeUsageEstimator {

    private const val MAX_MODEL_SPAN_DAYS = 31

    private data class Slice(
        val date: LocalDate,
        val hour: Int,
        val daylight: Boolean,
        val temperatureC: Double?,
        val durationMillis: Long,
    )

    /** 只有会参与温度模型的日期才需要补齐日出日落缓存。 */
    fun modelDates(
        points: List<ReadingPoint>,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        if (from > to) return emptyList()
        val dates = LinkedHashSet<LocalDate>()
        UsageCalculator.buildIntervals(points).forEach { interval ->
            if (interval.volumeM3 <= 0.0 || interval.spanDays > MAX_MODEL_SPAN_DAYS) return@forEach
            var date = maxOf(Dates.dateOf(interval.startEpochMillis), from)
            val lastDate = minOf(Dates.dateOf(interval.endEpochMillis), to)
            while (!date.isAfter(lastDate)) {
                dates += date
                date = date.plusDays(1)
            }
        }
        return dates.toList()
    }

    fun estimate(
        points: List<ReadingPoint>,
        from: LocalDate,
        to: LocalDate,
        sunProvider: (LocalDate) -> SunTimes?,
        zone: ZoneId = Dates.zone,
    ): HeatingDegreeEstimate {
        if (from > to) return HeatingDegreeEstimate(emptyList(), false, 0, 0)
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val temperatures = points.groupBy { Dates.dateOf(it.epochMillis) }.mapValues { (_, readings) ->
            readings.mapNotNull { it.tempMinC }.minOrNull() to readings.mapNotNull { it.tempMaxC }.maxOrNull()
        }
        val sunCache = HashMap<LocalDate, SunTimes?>()
        fun sun(date: LocalDate): SunTimes? = sunCache.getOrPut(date) { sunProvider(date) }

        val out = ArrayList<HourlyUsageEstimate>()
        var usedTemperatureModel = false
        UsageCalculator.buildIntervals(points).forEach { interval ->
            if (interval.volumeM3 <= 0.0) return@forEach
            val start = maxOf(interval.startEpochMillis, rangeStart)
            val end = minOf(interval.endEpochMillis, rangeEnd)
            if (end <= start) return@forEach
            val useModel = interval.spanDays <= MAX_MODEL_SPAN_DAYS
            val slices = if (useModel) {
                slices(start, end, temperatures, ::sun, zone)
            } else {
                // shortcut: 超过 31 天不跨采暖季推断；需要跨季模型时再引入采暖季设置。
                slices(start, end, emptyMap(), { null }, zone)
            }
            val hasTemperature = useModel && slices.any { it.temperatureC != null }
            if (hasTemperature) usedTemperatureModel = true
            val weights = if (useModel) slices.map { slice ->
                val degree = slice.temperatureC?.let { max(0.0, 18.0 - it) } ?: 1.0
                // 同温条件下夜间用气略高，避免把昼夜长度本身当作用气差异。
                val nightFactor = if (slice.daylight) 0.65 else 1.0
                degree * nightFactor * slice.durationMillis
            } else {
                slices.map { it.durationMillis.toDouble() }
            }
            val weightTotal = weights.sum()
            val visibleVolume = interval.volumeM3 * (end - start).toDouble() /
                (interval.endEpochMillis - interval.startEpochMillis)
            slices.indices.forEach { index ->
                val volume = if (weightTotal > 0.0) visibleVolume * weights[index] / weightTotal else 0.0
                val slice = slices[index]
                out += HourlyUsageEstimate(slice.date, slice.hour, slice.temperatureC, volume)
            }
        }
        val days = (to.toEpochDay() - from.toEpochDay()).toInt() + 1
        return HeatingDegreeEstimate(
            hourlyEstimates = out,
            usedTemperatureModel = usedTemperatureModel,
            sunDataDays = sunCache.values.count { it != null },
            totalDays = days,
            sunTimesByDate = sunCache,
        )
    }

    private fun slices(
        start: Long,
        end: Long,
        temperatures: Map<LocalDate, Pair<Double?, Double?>>,
        sun: (LocalDate) -> SunTimes?,
        zone: ZoneId,
    ): List<Slice> {
        val out = ArrayList<Slice>()
        var cursor = start
        while (cursor < end) {
            val dateTime = java.time.Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = dateTime.toLocalDateTime().withMinute(0).withSecond(0).withNano(0)
                .plusHours(1).atZone(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(nextHour, end)
            val date = dateTime.toLocalDate()
            val (minTemp, maxTemp) = temperatures[date] ?: (null to null)
            out += Slice(
                date = date,
                hour = dateTime.hour,
                daylight = sun(date)?.isDaylight(dateTime.toLocalTime())
                    ?: (dateTime.toLocalTime() >= LocalTime.of(7, 0) && dateTime.toLocalTime() < LocalTime.of(19, 0)),
                temperatureC = estimateTemperature(dateTime.hour, minTemp, maxTemp),
                durationMillis = sliceEnd - cursor,
            )
            cursor = sliceEnd
        }
        return out
    }

    private fun estimateTemperature(hour: Int, min: Double?, max: Double?): Double? {
        if (min == null && max == null) return null
        if (min == null) return max
        if (max == null) return min
        // 最低温约在清晨 03:00，最高温约在下午 15:00 的平滑日内曲线。
        val mean = (min + max) / 2.0
        val amplitude = (max - min) / 2.0
        return mean - amplitude * cos((hour - 3) * 2.0 * PI / 24.0)
    }
}
