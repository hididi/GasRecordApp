package com.hididi.gaslog.domain

import java.time.LocalDate
import kotlin.math.abs

/** 一条抄表记录（与数据库实体解耦的领域模型，便于单元测试） */
data class ReadingPoint(
    val id: Long,
    val epochMillis: Long,
    val readingMilli: Long,
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    val photoPath: String? = null,
    val source: String = "MANUAL",
    val note: String? = null,
)

/** 某一天的用气情况（可能来自均摊估算） */
data class DayUsage(
    val date: LocalDate,
    val volumeM3: Double,
    val exact: Boolean,
    val readingCount: Int,
    val firstReadingMilli: Long?,
    val lastReadingMilli: Long?,
    val tempMinC: Double?,
    val tempMaxC: Double?,
)

/** 一段时间的用气汇总 */
data class UsageSummary(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val days: Int,
    val daysWithData: Int,
    val volumeM3: Double,
    val avgPerDayM3: Double,
    val estimatedDays: Int,
    val dayList: List<DayUsage>,
    val firstReadingMilli: Long?,
    val lastReadingMilli: Long?,
)

data class IntervalDetail(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val startReadingMilli: Long,
    val endReadingMilli: Long,
    val volumeM3: Double,
    val spanDays: Int,
    val resetDetected: Boolean,
    val estimated: Boolean,
)

/**
 * 用气量计算。
 *
 * 现实约束：燃气表只在拍照时才有读数，某天的真实用量只能通过"两次读数之差 ÷ 间隔天数"推断。
 * 本类统一按下面的规则生成逐日用量：
 *  1. 同一天有两次及以上读数 —— 当天两次读数相减即为当天精确用量（exact=true）。
 *  2. 跨天的两次读数 —— 总差值在 (上次读数日期, 本次读数日期] 的每一天上均摊（exact=false）。
 *  3. 读数比上次小 —— 视为换表/清零，本次读数整体计入，resetDetected=true。
 * 所有"估算"性质的数字都会带 exact=false，UI 必须明确标注，避免把估算当成计量数据。
 */
object UsageCalculator {

    fun buildIntervals(points: List<ReadingPoint>): List<IntervalDetail> {
        val sorted = points.sortedBy { it.epochMillis }
        if (sorted.size < 2) return emptyList()
        val out = ArrayList<IntervalDetail>(sorted.size - 1)
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val raw = cur.readingMilli - prev.readingMilli
            val reset = raw < 0
            val volumeMilli = if (reset) cur.readingMilli else raw
            val startDate = Dates.dateOf(prev.epochMillis)
            val endDate = Dates.dateOf(cur.epochMillis)
            val span = (endDate.toEpochDay() - startDate.toEpochDay()).toInt()
            out += IntervalDetail(
                startEpochMillis = prev.epochMillis,
                endEpochMillis = cur.epochMillis,
                startReadingMilli = prev.readingMilli,
                endReadingMilli = cur.readingMilli,
                volumeM3 = Units.milliToM3(volumeMilli),
                spanDays = span,
                resetDetected = reset,
                estimated = span > 0,
            )
        }
        return out
    }

    /**
     * 生成 [from, to] 区间内每天的用量（区间之外的数据只用来做基准）。
     * tempProvider 用来补"当天没有抄表、但气温可以从天气缓存拿到"的日子。
     */
    fun dailyUsage(
        points: List<ReadingPoint>,
        from: LocalDate,
        to: LocalDate,
        tempProvider: (LocalDate) -> Pair<Double?, Double?>? = { null },
    ): List<DayUsage> {
        val sorted = points.sortedBy { it.epochMillis }
        if (sorted.isEmpty() || from > to) return emptyList()

        val byDay = sorted.groupBy { Dates.dateOf(it.epochMillis) }
        val result = LinkedHashMap<LocalDate, DayUsage>()

        // 1) 同一天多次读数：精确差值
        byDay.forEach { (date, list) ->
            if (list.size >= 2) {
                val volumeMilli = list.last().readingMilli - list.first().readingMilli
                if (volumeMilli >= 0) {
                    merge(
                        result, date, Units.milliToM3(volumeMilli), exact = true,
                        readingCount = list.size,
                        first = list.first().readingMilli,
                        last = list.last().readingMilli,
                        tempMin = list.minOfOrNull { it.tempMinC ?: Double.MAX_VALUE }?.takeIf {
                            it != Double.MAX_VALUE
                        },
                        tempMax = list.maxOfOrNull { it.tempMaxC ?: -Double.MAX_VALUE }?.takeIf {
                            it != -Double.MAX_VALUE
                        },
                    )
                }
            }
        }

        // 2) 跨天均摊
        buildIntervals(sorted).forEach { iv ->
            val startDate = Dates.dateOf(iv.startEpochMillis)
            val endDate = Dates.dateOf(iv.endEpochMillis)
            val daySpan = (endDate.toEpochDay() - startDate.toEpochDay()).toInt()
            if (daySpan <= 0) return@forEach
            val perDay = iv.volumeM3 / daySpan
            for (k in 1..daySpan) {
                val d = startDate.plusDays(k.toLong())
                merge(
                    result, d, perDay, exact = false,
                    readingCount = if (d == endDate) 1 else 0,
                    first = if (d == endDate) iv.endReadingMilli else null,
                    last = if (d == endDate) iv.endReadingMilli else null,
                    tempMin = null,
                    tempMax = null,
                )
            }
        }

        // 合并每天的读数条数/读数/温度（用当天实际记录覆盖）
        byDay.forEach { (date, list) ->
            val existing = result[date] ?: return@forEach
            if (date < from || date > to) return@forEach
            val tMin = list.mapNotNull { it.tempMinC }.minOrNull()
            val tMax = list.mapNotNull { it.tempMaxC }.maxOrNull()
            result[date] = existing.copy(
                readingCount = list.size,
                firstReadingMilli = list.first().readingMilli,
                lastReadingMilli = list.last().readingMilli,
                tempMinC = existing.tempMinC ?: tMin,
                tempMaxC = existing.tempMaxC ?: tMax,
            )
        }

        // 3) 当天有读数但当天没有用量来源（例如只有一次读数、或有断层）：补 0 值条目
        byDay.keys.filter { it in from..to && it !in result }.forEach { d ->
            result[d] = DayUsage(
                date = d,
                volumeM3 = 0.0,
                exact = true,
                readingCount = byDay[d]?.size ?: 0,
                firstReadingMilli = byDay[d]?.firstOrNull()?.readingMilli,
                lastReadingMilli = byDay[d]?.lastOrNull()?.readingMilli,
                tempMinC = byDay[d]?.mapNotNull { it.tempMinC }?.minOrNull(),
                tempMaxC = byDay[d]?.mapNotNull { it.tempMaxC }?.maxOrNull(),
            )
        }

        val out = ArrayList<DayUsage>()
        var cur = from
        while (!cur.isAfter(to)) {
            result[cur]?.let { day ->
                val provided = tempProvider(day.date)
                out += if (provided == null) day else day.copy(
                    tempMinC = provided.first ?: day.tempMinC,
                    tempMaxC = provided.second ?: day.tempMaxC,
                )
            }
            cur = cur.plusDays(1)
        }
        return out
    }

    private fun merge(
        target: MutableMap<LocalDate, DayUsage>,
        date: LocalDate,
        volume: Double,
        exact: Boolean,
        readingCount: Int,
        first: Long?,
        last: Long?,
        tempMin: Double?,
        tempMax: Double?,
    ) {
        val old = target[date]
        target[date] = if (old == null) {
            DayUsage(date, volume, exact, readingCount, first, last, tempMin, tempMax)
        } else {
            old.copy(
                volumeM3 = old.volumeM3 + volume,
                exact = old.exact && exact,
                readingCount = maxOf(old.readingCount, readingCount),
                firstReadingMilli = old.firstReadingMilli ?: first,
                lastReadingMilli = old.lastReadingMilli ?: last,
                tempMinC = old.tempMinC ?: tempMin,
                tempMaxC = old.tempMaxC ?: tempMax,
            )
        }
    }

    fun summarize(
        points: List<ReadingPoint>,
        from: LocalDate,
        to: LocalDate,
        tempProvider: (LocalDate) -> Pair<Double?, Double?>? = { null },
    ): UsageSummary {
        val days = dailyUsage(points, from, to, tempProvider)
        val total = days.sumOf { it.volumeM3 }
        val daysWithData = days.count { it.readingCount > 0 }
        val dayCount = (to.toEpochDay() - from.toEpochDay()).toInt() + 1
        // 有效天数：有读数的天数 + 均摊覆盖的天数
        val coveredDays = days.count { it.volumeM3 > 0.0 || it.readingCount > 0 }
        val inRange = points.filter {
            val d = Dates.dateOf(it.epochMillis)
            d in from..to
        }.sortedBy { it.epochMillis }
        return UsageSummary(
            fromDate = from,
            toDate = to,
            days = dayCount,
            daysWithData = daysWithData,
            volumeM3 = total,
            avgPerDayM3 = if (coveredDays > 0) total / coveredDays else 0.0,
            estimatedDays = days.count { !it.exact && it.volumeM3 > 0.0 },
            dayList = days,
            firstReadingMilli = inRange.firstOrNull()?.readingMilli,
            lastReadingMilli = inRange.lastOrNull()?.readingMilli,
        )
    }

    /** 两点读数之间的用量（含跨表清零的提示） */
    fun volumeBetween(points: List<ReadingPoint>, startMs: Long, endMs: Long): IntervalDetail? {
        val sorted = points.sortedBy { it.epochMillis }
        val start = sorted.filter { it.epochMillis <= startMs }.maxByOrNull { it.epochMillis }
            ?: return null
        val end = sorted.filter { it.epochMillis <= endMs }.maxByOrNull { it.epochMillis }
            ?: return null
        if (end.id == start.id) return null
        val raw = end.readingMilli - start.readingMilli
        val reset = raw < 0
        val volume = if (reset) end.readingMilli else raw
        return IntervalDetail(
            startEpochMillis = start.epochMillis,
            endEpochMillis = end.epochMillis,
            startReadingMilli = start.readingMilli,
            endReadingMilli = end.readingMilli,
            volumeM3 = Units.milliToM3(volume),
            spanDays = (Dates.dateOf(end.epochMillis).toEpochDay() -
                Dates.dateOf(start.epochMillis).toEpochDay()).toInt(),
            resetDetected = reset,
            estimated = true,
        )
    }

    /** 同比/环比：与上一个等长区间比较 */
    fun previousPeriod(from: LocalDate, to: LocalDate): Pair<LocalDate, LocalDate> {
        val len = (to.toEpochDay() - from.toEpochDay()).toLong()
        val prevTo = from.minusDays(1)
        val prevFrom = prevTo.minusDays(len)
        return prevFrom to prevTo
    }

    /** 线性相关（用于"气温 vs 用气"的粗略相关性提示） */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size < 3 || xs.size != ys.size) return null
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        if (dx <= 1e-9 || dy <= 1e-9) return null
        val r = num / (kotlin.math.sqrt(dx) * kotlin.math.sqrt(dy))
        return if (r.isNaN()) null else r
    }

    fun nearlyEqual(a: Double, b: Double, eps: Double = 1e-6): Boolean = abs(a - b) < eps
}