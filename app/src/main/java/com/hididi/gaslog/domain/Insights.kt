package com.hididi.gaslog.domain

import java.time.LocalDate

enum class InsightLevel { INFO, WARN, ALERT }

data class Insight(
    val level: InsightLevel,
    val title: String,
    val detail: String,
)

/** 天气信息（当天温度区间） */
data class DailyTemp(
    val date: LocalDate,
    val tempMinC: Double?,
    val tempMaxC: Double?,
) {
    /** 不带单位的温度值，适合表格的独立温度列。 */
    val valueLabel: String
        get() = when {
            tempMinC != null && tempMaxC != null -> "${formatTemp(tempMinC)}~${formatTemp(tempMaxC)}"
            tempMinC != null -> "≥${formatTemp(tempMinC)}"
            tempMaxC != null -> "≤${formatTemp(tempMaxC)}"
            else -> "—"
        }

    val label: String
        get() = valueLabel.takeUnless { it == "—" }?.plus("℃") ?: "—"

    private fun formatTemp(v: Double): String =
        if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}

/** 用掉 volume 后的档位终点 + 费用，用于预测与"还剩多少气/钱"的推算 */
data class Projection(
    val avgDailyM3: Double,
    val basisDays: Int,
    /** 本月已用（截至今天） */
    val monthToDateM3: Double = 0.0,
    /** 本月剩余天数 */
    val remainingDays: Int = 0,
    val projectedMonthM3: Double,
    val projectedMonthCostCents: Long,
    val daysToNextTier: Int?,
    val stateNow: TierState?,
    val annualUsedM3: Double?,
)

object Insights {

    /** 异常与提醒：只在真的可疑时才给出，避免噪音 */
    fun detect(
        points: List<ReadingPoint>,
        daily: List<DayUsage>,
        state: TierState?,
        tiers: List<TierDef>,
        today: LocalDate = Dates.today(),
    ): List<Insight> {
        val out = ArrayList<Insight>()
        val sorted = points.sortedBy { it.epochMillis }

        // 1) 读数回退
        UsageCalculator.buildIntervals(sorted)
            .filter { it.resetDetected }
            .takeLast(2)
            .forEach { iv ->
                out += Insight(
                    InsightLevel.WARN,
                    "读数比上一次小",
                    "${Dates.formatDate(iv.startEpochMillis)} ${Units.formatReading(iv.startReadingMilli)} → " +
                        "${Dates.formatDate(iv.endEpochMillis)} ${Units.formatReading(iv.endReadingMilli)}，" +
                        "可能是换表/清零，也可能是录错了。本段用量按新读数全额计算。",
                )
            }

        // 2) 日用量尖峰
        val volumes = daily.filter { it.volumeM3 > 0.0 }.map { it.volumeM3 }.sorted()
        if (volumes.size >= 5) {
            val median = volumes[volumes.size / 2]
            val spikes = daily.filter { it.volumeM3 > 0.0 && it.volumeM3 > median * 3 && it.volumeM3 > 1.0 }
            spikes.takeLast(2).forEach { d ->
                out += Insight(
                    InsightLevel.WARN,
                    "用气量异常偏高",
                    "${Dates.formatDate(d.date)} 用了 ${Units.formatVolume(d.volumeM3)} m³，" +
                        "约为平时（中位 ${Units.formatVolume(median)} m³）的 ${
                            String.format(java.util.Locale.US, "%.1f", d.volumeM3 / median)
                        } 倍。可检查是否漏气、表具故障或当日长时间用气。",
                )
            }
        }

        // 3) 长时间没有抄表
        val last = sorted.lastOrNull()
        if (last != null) {
            val gap = today.toEpochDay() - Dates.dateOf(last.epochMillis).toEpochDay()
            if (gap >= 4) {
                out += Insight(
                    InsightLevel.INFO,
                    "已经 $gap 天没有抄表",
                    "补拍一张表照可以让区间用量更准确；间隔越长，估算分摊的不确定性越大。",
                )
            }
        } else {
            out += Insight(
                InsightLevel.INFO,
                "还没有抄表记录",
                "拍下燃气表或从相册导入照片即可开始记录。",
            )
        }

        // 4) 阶梯余量
        if (state != null && !state.unlimited && state.remainingM3 >= 0) {
            val total = tiers.maxOfOrNull { it.capM3 ?: 0.0 } ?: 0.0
            if (state.remainingM3 <= 5.0) {
                out += Insight(
                    InsightLevel.WARN,
                    "本档用气量即将用尽",
                    "第 ${state.tierNo} 档仅剩 ${Units.formatVolume(state.remainingM3)} m³，" +
                        "用完后将按更高档位计价。",
                )
            }
            if (total > 0) {
                // 只做提示性说明
            }
        }

        // 5) 同日多读数：精确数据说明
        val multi = daily.filter { it.readingCount >= 2 }.sortedByDescending { it.date }.firstOrNull()
        if (multi != null) {
            out += Insight(
                InsightLevel.INFO,
                "当天有两次以上读数",
                "${Dates.formatDate(multi.date)} 两次读数的差值 " +
                    "${Units.formatVolume(multi.volumeM3)} m³ 就是当天精确用量。",
            )
        }
        return out
    }

    /** 最近 N 天日用量 + 本月预测 */
    fun projectMonth(
        daily: List<DayUsage>,
        today: LocalDate,
        state: TierState?,
        tiers: List<TierDef>,
    ): Projection? {
        val basis = daily.filter { it.date <= today && it.volumeM3 > 0.0 }.takeLast(14)
        if (basis.size < 2) return null
        val avg = basis.sumOf { it.volumeM3 } / basis.size
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val monthDays = daily.filter { it.date in monthStart..today }
        val mtdVolume = monthDays.sumOf { it.volumeM3 }
        val remainingDays = (monthEnd.toEpochDay() - today.toEpochDay()).toInt().coerceAtLeast(0)
        val projectedTotal = mtdVolume + avg * remainingDays
        val costOfRest = if (state != null) CostEngine.quote(state, tiers, avg * remainingDays).totalCents else 0L
        val daysToNext = state?.let { CostEngine.daysToNextTier(it, tiers, avg) }
        return Projection(
            avgDailyM3 = avg,
            basisDays = basis.size,
            monthToDateM3 = mtdVolume,
            remainingDays = remainingDays,
            projectedMonthM3 = projectedTotal,
            projectedMonthCostCents = costOfRest,
            daysToNextTier = daysToNext,
            stateNow = state,
            annualUsedM3 = state?.let { CostEngine.annualUsageFromState(it, tiers) },
        )
    }
}
