package com.hididi.gaslog.domain

import kotlin.math.min

/** 一档阶梯气价：capM3 为该档的「年度累计用气量上限」，null 表示最后一档不限量 */
data class TierDef(
    val tierNo: Int,
    val capM3: Double?,
    val priceCentsPerM3: Long,
)

/** 阶梯计费所处的位置：第几档、本档还剩多少气量可用 */
data class TierState(
    val tierNo: Int,
    val remainingM3: Double,
    val unlimited: Boolean = false,
)

data class CostLine(
    val tierNo: Int,
    val volumeM3: Double,
    val priceCentsPerM3: Long,
    val costCents: Long,
)

data class CostQuote(
    val volumeM3: Double,
    val lines: List<CostLine>,
    val totalCents: Long,
    val endState: TierState?,
    val avgPricePerM3Cents: Double,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 阶梯气费核算。
 *
 * 只做「从某个起点状态开始，用掉 volume 立方米要花多少钱」这一件事，
 * 因为这才是账单的本质；起点状态由用户手工填写的交费记录
 * （交费读数 / 当前档位 / 本档剩余气量）或由前期用量推进得到。
 */
object CostEngine {

    fun sortTiers(tiers: List<TierDef>): List<TierDef> = tiers.sortedBy { it.tierNo }

    /** 用户手工填写的「目前所处区间 + 剩余区间气量」→ 起点状态 */
    fun stateFromManual(tierNo: Int, remainingM3: Double, tiers: List<TierDef>): TierState? {
        val sorted = sortTiers(tiers)
        val def = sorted.firstOrNull { it.tierNo == tierNo } ?: return null
        return TierState(
            tierNo = def.tierNo,
            remainingM3 = remainingM3.coerceAtLeast(0.0),
            unlimited = def.capM3 == null,
        )
    }

    /** 默认起点：第一档满额 */
    fun initialState(tiers: List<TierDef>): TierState? {
        val first = sortTiers(tiers).firstOrNull() ?: return null
        return if (first.capM3 == null) {
            TierState(first.tierNo, Double.MAX_VALUE, unlimited = true)
        } else {
            TierState(first.tierNo, first.capM3, unlimited = false)
        }
    }

    /** 某档位的可用气量（相对上一档的上限差） */
    fun tierCapacity(tier: TierDef, previousCap: Double?): Double? {
        val cap = tier.capM3 ?: return null
        val prev = previousCap ?: 0.0
        return (cap - prev).coerceAtLeast(0.0)
    }

    /**
     * 从 start 状态开始用掉 volumeM3 的费用明细。
     * @return 明细；无法计价时（起点档位不在阶梯表里）返回带 warning 的空报价。
     */
    fun quote(start: TierState?, tiers: List<TierDef>, volumeM3: Double): CostQuote {
        val sorted = sortTiers(tiers)
        if (sorted.isEmpty()) {
            return CostQuote(volumeM3, emptyList(), 0, null, 0.0, listOf("尚未设置阶梯气价"))
        }
        if (volumeM3 <= 0.0) {
            return CostQuote(0.0, emptyList(), 0, start, 0.0)
        }
        val state = start ?: initialState(sorted)
        if (state == null || sorted.none { it.tierNo == state.tierNo }) {
            return CostQuote(
                volumeM3, emptyList(), 0, null, 0.0,
                listOf("起点档位不在阶梯表中，请检查阶梯气价设置"),
            )
        }

        val warnings = ArrayList<String>()
        val lines = ArrayList<CostLine>()
        var tierNo = state.tierNo
        var remaining = if (state.unlimited) Double.MAX_VALUE else state.remainingM3
        var left = volumeM3
        var totalCents = 0L
        var guard = 0

        while (left > 0.0000001 && guard++ < 64) {
            val def = sorted.firstOrNull { it.tierNo == tierNo }
            if (def == null) {
                // 已经越过最高档
                val last = sorted.last()
                warnings += "用气量已超出最高档（${last.tierNo}档）上限，超出部分按 ${last.tierNo} 档单价估算"
                val take = left
                val cost = Math.round(take * last.priceCentsPerM3)
                lines += CostLine(last.tierNo, take, last.priceCentsPerM3, cost)
                totalCents += cost
                left = 0.0
                tierNo = last.tierNo
                remaining = 0.0
                break
            }
            val capacity = when {
                tierNo == state.tierNo && state.unlimited -> Double.MAX_VALUE
                tierNo == state.tierNo -> state.remainingM3
                else -> {
                    val prevDef = sorted.lastOrNull { it.tierNo < def.tierNo }
                    tierCapacity(def, prevDef?.capM3) ?: Double.MAX_VALUE
                }
            }
            if (capacity <= 1e-9) {
                // 本档已用尽，进入下一档
                tierNo = nextTierNo(sorted, tierNo) ?: (tierNo + 1)
                remaining = 0.0
                continue
            }
            val take = min(left, capacity)
            val cost = Math.round(take * def.priceCentsPerM3)
            lines += CostLine(def.tierNo, take, def.priceCentsPerM3, cost)
            totalCents += cost
            left -= take
            remaining = capacity - take
            if (left <= 0.0000001) break
            val next = nextTierNo(sorted, def.tierNo)
            if (next == null) {
                // 最后一档且已超量，交给下一轮循环的 def == null 分支处理
                tierNo = def.tierNo + 1
                remaining = 0.0
            } else {
                tierNo = next
                remaining = 0.0
            }
        }

        val endState = sorted.firstOrNull { it.tierNo == tierNo }?.let { def ->
            val isLast = nextTierNo(sorted, def.tierNo) == null
            if (isLast && def.capM3 == null) {
                TierState(def.tierNo, Double.MAX_VALUE, unlimited = true)
            } else {
                val cap = if (tierNo == state.tierNo) state.remainingM3 else {
                    val prevDef = sorted.lastOrNull { it.tierNo < def.tierNo }
                    tierCapacity(def, prevDef?.capM3) ?: 0.0
                }
                TierState(def.tierNo, (cap - lines.filter { it.tierNo == def.tierNo }.sumOf { it.volumeM3 })
                    .coerceAtLeast(0.0), unlimited = false)
            }
        }

        val avg = if (volumeM3 > 0) totalCents.toDouble() / volumeM3 else 0.0
        return CostQuote(
            volumeM3 = volumeM3,
            lines = lines,
            totalCents = totalCents,
            endState = endState,
            avgPricePerM3Cents = avg,
            warnings = warnings,
        )
    }

    private fun nextTierNo(sorted: List<TierDef>, current: Int): Int? =
        sorted.map { it.tierNo }.filter { it > current }.minOrNull()

    /** 由「年累计用气量」反推所处档位 */
    fun stateFromAnnualUsage(annualUsedM3: Double, tiers: List<TierDef>): TierState? {
        val sorted = sortTiers(tiers)
        if (sorted.isEmpty()) return null
        var used = annualUsedM3.coerceAtLeast(0.0)
        for ((i, tier) in sorted.withIndex()) {
            val cap = tier.capM3
            if (cap == null) {
                return TierState(tier.tierNo, Double.MAX_VALUE, unlimited = true)
            }
            val prevCap = if (i == 0) 0.0 else sorted[i - 1].capM3
            val capacity = (cap - (prevCap ?: 0.0)).coerceAtLeast(0.0)
            if (used < capacity - 1e-9) {
                return TierState(tier.tierNo, capacity - used, unlimited = false)
            }
            used -= capacity
        }
        val last = sorted.last()
        return TierState(last.tierNo, 0.0, unlimited = last.capM3 == null)
    }

    /**
     * 由档位状态反推「本计费年度已累计用气量」（用于账单/预测展示）。
     * 第一档剩余 r1 时，年累计 = cap1 - r1。
     */
    fun annualUsageFromState(state: TierState, tiers: List<TierDef>): Double? {
        val sorted = sortTiers(tiers)
        val def = sorted.firstOrNull { it.tierNo == state.tierNo } ?: return null
        if (state.unlimited) return def.capM3
        val cap = def.capM3 ?: return null
        return (cap - state.remainingM3).coerceAtLeast(0.0)
    }

    /** 按日均用量预测：还需要多少天进入下一档 */
    fun daysToNextTier(state: TierState, tiers: List<TierDef>, avgDailyM3: Double): Int? {
        if (avgDailyM3 <= 0.0 || state.unlimited) return null
        val sorted = sortTiers(tiers)
        if (sorted.none { it.tierNo > state.tierNo }) return null
        return kotlin.math.ceil(state.remainingM3 / avgDailyM3).toInt().coerceAtLeast(0)
    }

    fun totalPriceRange(tiers: List<TierDef>): String {
        val sorted = sortTiers(tiers)
        if (sorted.isEmpty()) return "-"
        val prices = sorted.map { it.priceCentsPerM3 }
        return if (prices.min() == prices.max()) {
            "${Units.formatPrice(prices.min())} 元/m³"
        } else {
            "${Units.formatPrice(prices.min())}~${Units.formatPrice(prices.max())} 元/m³"
        }
    }
}