package com.hididi.gaslog.data

import android.content.Context
import com.hididi.gaslog.data.store.GasStore
import com.hididi.gaslog.data.store.MeterReadingEntity
import com.hididi.gaslog.data.store.PaymentEntity
import com.hididi.gaslog.data.store.ReadingSaveResult
import com.hididi.gaslog.data.store.TariffTierEntity
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.PaymentInfo
import com.hididi.gaslog.domain.ReadingPoint
import com.hididi.gaslog.domain.TierDef
import com.hididi.gaslog.domain.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GasRepository(context: Context) {

    private val store = GasStore(context)

    /** 首次访问时把磁盘数据读进内存（幂等） */
    suspend fun ensureLoaded() = store.ensureLoaded()

    val readings: Flow<List<ReadingPoint>> = store.readings.map { list -> list.map { it.toDomain() } }
    val tiers: Flow<List<TierDef>> = store.tiers.map { list -> list.map { it.toDomain() } }
    val payments: Flow<List<PaymentInfo>> = store.payments.map { list -> list.map { it.toDomain() } }

    suspend fun readingsOnce(): List<ReadingPoint> = store.readings.value.map { it.toDomain() }
    suspend fun tiersOnce(): List<TierDef> = store.tiers.value.map { it.toDomain() }
    suspend fun paymentsOnce(): List<PaymentInfo> = store.payments.value.map { it.toDomain() }
    suspend fun latestReading(): ReadingPoint? = store.readings.value.maxByOrNull { it.epochMillis }?.toDomain()
    suspend fun readingById(id: Long): ReadingPoint? =
        store.readings.value.firstOrNull { it.id == id }?.toDomain()

    suspend fun saveReading(entity: MeterReadingEntity): ReadingSaveResult = store.saveReading(entity)
    suspend fun insertReading(entity: MeterReadingEntity): Long = store.insertReading(entity)
    suspend fun updateReading(entity: MeterReadingEntity) = store.updateReading(entity)

    suspend fun updateWeather(id: Long, tempMinC: Double?, tempMaxC: Double?, status: String) =
        store.updateWeather(id, tempMinC, tempMaxC, status)

    suspend fun deleteReading(id: Long) = store.deleteReading(id)

    suspend fun saveTiers(tiers: List<TierDef>) {
        store.replaceTiers(
            tiers.sortedBy { it.tierNo }.mapIndexed { i, t ->
                TariffTierEntity(tierNo = i + 1, capM3 = t.capM3, priceCentsPerM3 = t.priceCentsPerM3)
            },
        )
    }

    suspend fun insertPayment(entity: PaymentEntity): Long = store.insertPayment(entity)
    suspend fun updatePayment(entity: PaymentEntity) = store.updatePayment(entity)
    suspend fun deletePayment(id: Long) = store.deletePayment(id)
    suspend fun latestPayment(): PaymentInfo? =
        store.payments.value.maxByOrNull { it.paidAtMillis }?.toDomain()

    suspend fun clearAll() = store.clearAll()

    /** 首次启动时写入一份示例阶梯价（用户可在"费用"页改成自己城市的） */
    suspend fun seedDefaultsIfEmpty() {
        if (store.tiers.value.isEmpty()) {
            saveTiers(
                listOf(
                    TierDef(1, 300.0, 278),
                    TierDef(2, 540.0, 306),
                    TierDef(3, null, 361),
                ),
            )
        }
    }

    // ---------- 导出 / 导入 ----------

    suspend fun exportBackupJson(): String = store.exportBackupJson()

    suspend fun importBackupJson(json: String): ImportResult {
        val (readings, payments) = store.importBackupJson(json)
        return ImportResult(readings, payments)
    }

    /** 逐条 CSV：日期/时间/读数/来源/温度/地点/备注 */
    suspend fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append("时间,读数(m³),来源,当日最低温(℃),当日最高温(℃),地点,备注\n")
        store.readings.value.sortedBy { it.epochMillis }.forEach { e ->
            sb.append(csv(Dates.formatDateTime(e.epochMillis))).append(',')
            sb.append(Units.formatReading(e.readingMilli)).append(',')
            sb.append(e.source).append(',')
            sb.append(e.tempMinC?.toString() ?: "").append(',')
            sb.append(e.tempMaxC?.toString() ?: "").append(',')
            sb.append(csv(e.locationName ?: "")).append(',')
            sb.append(csv(e.note ?: "")).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v

    data class ImportResult(val readingsAdded: Int, val paymentsAdded: Int)
}

fun MeterReadingEntity.toDomain(): ReadingPoint = ReadingPoint(
    id = id,
    epochMillis = epochMillis,
    readingMilli = readingMilli,
    tempMinC = tempMinC,
    tempMaxC = tempMaxC,
    photoPath = photoPath,
    source = source,
    note = note,
)

fun TariffTierEntity.toDomain(): TierDef = TierDef(tierNo, capM3, priceCentsPerM3)

fun PaymentEntity.toDomain(): PaymentInfo = PaymentInfo(
    id = id,
    paidAtMillis = paidAtMillis,
    readingMilli = readingMilli,
    amountCents = amountCents,
    tierNo = tierNo,
    remainingM3 = remainingM3,
    note = note,
)
