package com.hididi.gaslog.domain

/** 交费记录（领域模型） */
data class PaymentInfo(
    val id: Long,
    val paidAtMillis: Long,
    val readingMilli: Long,
    val amountCents: Long?,
    val tierNo: Int,
    val remainingM3: Double?,
    val note: String?,
)

data class ReplayResult(
    val state: TierState?,
    val volumeSincePaymentM3: Double,
    val costSincePaymentCents: Long,
    val costLines: List<CostLine>,
    val anchorMissingReason: String? = null,
)

/**
 * 从交费记录出发「重放」到某个时刻：
 * 交费时的表读数 + 阶梯状态 → 之后每次抄表的读数差 → 推进阶梯位置。
 * 这是把用户手工填写的信息变成"现在还剩多少低价气"的唯一可靠办法。
 */
object BillingReplay {

    fun anchorState(payment: PaymentInfo, tiers: List<TierDef>): TierState? =
        payment.remainingM3?.let { CostEngine.stateFromManual(payment.tierNo, it, tiers) }
            ?: TierState(payment.tierNo, 0.0, unlimited = false)

    fun replay(
        payment: PaymentInfo?,
        tiers: List<TierDef>,
        points: List<ReadingPoint>,
        untilEpochMillis: Long,
    ): ReplayResult {
        if (payment == null) {
            return ReplayResult(
                state = CostEngine.initialState(tiers),
                volumeSincePaymentM3 = 0.0,
                costSincePaymentCents = 0,
                costLines = emptyList(),
                anchorMissingReason = "还没有填写交费记录，档位与费用按第一档初始状态估算",
            )
        }
        val state = anchorState(payment, tiers)
        val sorted = points.filter { it.epochMillis > payment.paidAtMillis && it.epochMillis <= untilEpochMillis }
            .sortedBy { it.epochMillis }
        // 用交费读数之后、目标时刻之前最大的读数推进
        val lastReading = (sorted.lastOrNull()?.readingMilli)
            ?: points.filter { it.epochMillis <= untilEpochMillis }
                .maxByOrNull { it.epochMillis }?.readingMilli
            ?: payment.readingMilli
        val deltaMilli = (lastReading - payment.readingMilli).coerceAtLeast(0L)
        val volume = Units.milliToM3(deltaMilli)
        val quote = CostEngine.quote(state, tiers, volume)
        return ReplayResult(
            state = quote.endState,
            volumeSincePaymentM3 = volume,
            costSincePaymentCents = quote.totalCents,
            costLines = quote.lines,
        )
    }
}