package com.hididi.gaslog.data.store

import android.content.Context
import com.hididi.gaslog.domain.MergeReading
import com.hididi.gaslog.domain.ReadingMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReadingSaveResult(
    val savedId: Long,
    /** Records replaced or removed as part of this save, for safe photo cleanup by the caller. */
    val removedReadings: List<MeterReadingEntity>,
)

/** SQLite 是主存储；JSON 仅用于导入、导出和一次性迁移。 */
class GasStore(context: Context) {

    private val legacyFile = File(context.filesDir, "gaslog_data.json")
    private val database = GasDatabase(context)
    private val mutex = Mutex()

    private val _readings = MutableStateFlow<List<MeterReadingEntity>>(emptyList())
    private val _tiers = MutableStateFlow<List<TariffTierEntity>>(emptyList())
    private val _payments = MutableStateFlow<List<PaymentEntity>>(emptyList())

    val readings: StateFlow<List<MeterReadingEntity>> = _readings.asStateFlow()
    val tiers: StateFlow<List<TariffTierEntity>> = _tiers.asStateFlow()
    val payments: StateFlow<List<PaymentEntity>> = _payments.asStateFlow()

    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
            _readings.value = snapshot.readings.sortedBy { it.epochMillis }
            _tiers.value = snapshot.tiers.sortedBy { it.tierNo }
            _payments.value = snapshot.payments.sortedBy { it.paidAtMillis }
            loaded = true
        }
    }

    private fun loadSnapshot(): GasDatabaseSnapshot {
        val existing = database.readSnapshot()
        if (existing.readings.isNotEmpty() || existing.tiers.isNotEmpty() || existing.payments.isNotEmpty() || !legacyFile.exists()) {
            return existing
        }
        val migrated = parseSnapshot(legacyFile.readText())
        database.replaceSnapshot(migrated)
        val backup = File(legacyFile.parentFile, "${legacyFile.name}.migrated.bak")
        if (!backup.exists()) legacyFile.renameTo(backup)
        return migrated
    }

    private fun parseSnapshot(json: String): GasDatabaseSnapshot {
        val root = JSONObject(json)
        val readings = root.optJSONArray("readings")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    MeterReadingEntity(
                        id = o.optLong("id"),
                        epochMillis = o.optLong("epochMillis"),
                        readingMilli = o.optLong("readingMilli"),
                        photoPath = o.stringOrNull("photoPath"),
                        latitude = o.doubleOrNull("latitude"),
                        longitude = o.doubleOrNull("longitude"),
                        locationName = o.stringOrNull("locationName"),
                        tempMinC = o.doubleOrNull("tempMinC"),
                        tempMaxC = o.doubleOrNull("tempMaxC"),
                        weatherStatus = o.optString("weatherStatus", "NOT_FETCHED"),
                        source = o.optString("source", "MANUAL"),
                        ocrRaw = o.stringOrNull("ocrRaw"),
                        note = o.stringOrNull("note"),
                        createdAt = o.optLong("createdAt"),
                    )
                }
            } ?: emptyList()
        val tiers = root.optJSONArray("tiers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    TariffTierEntity(
                        tierNo = o.optInt("tierNo", i + 1),
                        capM3 = o.doubleOrNull("capM3"),
                        priceCentsPerM3 = o.optLong("priceCentsPerM3"),
                    )
                }
            } ?: emptyList()
        val payments = root.optJSONArray("payments")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    PaymentEntity(
                        id = o.optLong("id"),
                        paidAtMillis = o.optLong("paidAtMillis"),
                        readingMilli = o.optLong("readingMilli"),
                        amountCents = o.longOrNull("amountCents"),
                        tierNo = o.optInt("tierNo", 1),
                        remainingM3 = o.doubleOrNull("remainingM3"),
                        note = o.stringOrNull("note"),
                        createdAt = o.optLong("createdAt"),
                    )
                }
            } ?: emptyList()
        return GasDatabaseSnapshot(readings, tiers, payments)
    }

    private fun buildBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        val readingsArr = JSONArray()
        _readings.value.forEach { e ->
            readingsArr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("epochMillis", e.epochMillis)
                    put("readingMilli", e.readingMilli)
                    put("photoPath", e.photoPath ?: JSONObject.NULL)
                    put("latitude", e.latitude ?: JSONObject.NULL)
                    put("longitude", e.longitude ?: JSONObject.NULL)
                    put("locationName", e.locationName ?: JSONObject.NULL)
                    put("tempMinC", e.tempMinC ?: JSONObject.NULL)
                    put("tempMaxC", e.tempMaxC ?: JSONObject.NULL)
                    put("weatherStatus", e.weatherStatus)
                    put("source", e.source)
                    put("ocrRaw", e.ocrRaw ?: JSONObject.NULL)
                    put("note", e.note ?: JSONObject.NULL)
                    put("createdAt", e.createdAt)
                },
            )
        }
        root.put("readings", readingsArr)

        val tiersArr = JSONArray()
        _tiers.value.forEach { t ->
            tiersArr.put(
                JSONObject().apply {
                    put("tierNo", t.tierNo)
                    put("capM3", t.capM3 ?: JSONObject.NULL)
                    put("priceCentsPerM3", t.priceCentsPerM3)
                },
            )
        }
        root.put("tiers", tiersArr)

        val paymentsArr = JSONArray()
        _payments.value.forEach { p ->
            paymentsArr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("paidAtMillis", p.paidAtMillis)
                    put("readingMilli", p.readingMilli)
                    put("amountCents", p.amountCents ?: JSONObject.NULL)
                    put("tierNo", p.tierNo)
                    put("remainingM3", p.remainingM3 ?: JSONObject.NULL)
                    put("note", p.note ?: JSONObject.NULL)
                    put("createdAt", p.createdAt)
                },
            )
        }
        root.put("payments", paymentsArr)

        return root.toString()
    }

    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            database.replaceSnapshot(GasDatabaseSnapshot(_readings.value, _tiers.value, _payments.value))
        }
    }

    // ---------------- 抄表记录 ----------------

    suspend fun saveReading(entity: MeterReadingEntity): ReadingSaveResult = mutex.withLock {
        val result = mergeReading(_readings.value, entity)
        _readings.value = result.readings
        persist()
        ReadingSaveResult(result.saved.id, result.displaced)
    }

    suspend fun insertReading(entity: MeterReadingEntity): Long = saveReading(entity).savedId

    suspend fun updateReading(entity: MeterReadingEntity) {
        saveReading(entity)
    }

    private data class MergedReading(
        val saved: MeterReadingEntity,
        val readings: List<MeterReadingEntity>,
        val displaced: List<MeterReadingEntity>,
    )

    /** Keeps one record for a timestamp or a meter value, favoring the timestamp when they conflict. */
    private fun mergeReading(
        existing: List<MeterReadingEntity>,
        incoming: MeterReadingEntity,
    ): MergedReading {
        val preferredId = incoming.id.takeIf { it > 0 }
        val candidates = existing.filterNot { it.id == preferredId }
        val resolution = ReadingMerge.resolve(
            candidates.map { MergeReading(it.id, it.epochMillis, it.readingMilli) },
            incoming.epochMillis,
            incoming.readingMilli,
        )
        val savedId = resolution.keepId
            ?: preferredId
            ?: (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        val replacedIds = resolution.deleteIds.toMutableSet()
        if (preferredId != null && preferredId != savedId) replacedIds += preferredId
        val displaced = existing.filter { it.id == savedId || it.id in replacedIds }
        val saved = incoming.copy(id = savedId)
        val readings = (existing.filterNot { it.id == savedId || it.id in replacedIds } + saved)
            .sortedBy { it.epochMillis }
        return MergedReading(saved, readings, displaced)
    }

    suspend fun updateWeather(id: Long, tempMinC: Double?, tempMaxC: Double?, status: String) =
        mutex.withLock {
            _readings.value = _readings.value.map {
                if (it.id == id) it.copy(tempMinC = tempMinC, tempMaxC = tempMaxC, weatherStatus = status) else it
            }
            persist()
        }

    suspend fun deleteReading(id: Long) = mutex.withLock {
        _readings.value = _readings.value.filterNot { it.id == id }
        persist()
    }

    suspend fun clearReadings() = mutex.withLock {
        _readings.value = emptyList()
        persist()
    }

    // ---------------- 阶梯气价 ----------------

    suspend fun replaceTiers(items: List<TariffTierEntity>) = mutex.withLock {
        _tiers.value = items.sortedBy { it.tierNo }
        persist()
    }

    suspend fun clearTiers() = mutex.withLock {
        _tiers.value = emptyList()
        persist()
    }

    // ---------------- 交费记录 ----------------

    suspend fun insertPayment(entity: PaymentEntity): Long = mutex.withLock {
        val id = (_payments.value.maxOfOrNull { it.id } ?: 0L) + 1
        _payments.value = (_payments.value + entity.copy(id = id)).sortedBy { it.paidAtMillis }
        persist()
        id
    }

    suspend fun updatePayment(entity: PaymentEntity) = mutex.withLock {
        _payments.value = _payments.value.map { if (it.id == entity.id) entity else it }
        persist()
    }

    suspend fun deletePayment(id: Long) = mutex.withLock {
        _payments.value = _payments.value.filterNot { it.id == id }
        persist()
    }

    suspend fun clearPayments() = mutex.withLock {
        _payments.value = emptyList()
        persist()
    }

    suspend fun clearAll() = mutex.withLock {
        _readings.value = emptyList()
        _tiers.value = emptyList()
        _payments.value = emptyList()
        persist()
    }

    /** 备份导出：兼容旧 JSON 格式，导入后可完整还原。 */
    suspend fun exportBackupJson(): String {
        ensureLoaded()
        return withContext(Dispatchers.IO) { buildBackupJson() }
    }

    /** 导入备份：覆盖现有数据（调用前 UI 已提示） */
    suspend fun importBackupJson(json: String): Pair<Int, Int> {
        ensureLoaded()
        return mutex.withLock {
        val root = JSONObject(json)
        var readingsBefore = _readings.value
        var addedReadings = 0
        root.optJSONArray("readings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val epoch = o.optLong("epochMillis")
                val reading = o.optLong("readingMilli")
                val incoming = MeterReadingEntity(
                    epochMillis = epoch,
                    readingMilli = reading,
                    photoPath = o.stringOrNull("photoPath"),
                    latitude = o.doubleOrNull("latitude"),
                    longitude = o.doubleOrNull("longitude"),
                    locationName = o.stringOrNull("locationName"),
                    tempMinC = o.doubleOrNull("tempMinC"),
                    tempMaxC = o.doubleOrNull("tempMaxC"),
                    weatherStatus = o.optString("weatherStatus", "NOT_FETCHED"),
                    source = o.optString("source", "MANUAL"),
                    ocrRaw = o.stringOrNull("ocrRaw"),
                    note = o.stringOrNull("note"),
                )
                val unchanged = readingsBefore.any {
                    it.epochMillis == incoming.epochMillis && it.readingMilli == incoming.readingMilli &&
                        it.photoPath == incoming.photoPath && it.note == incoming.note
                }
                val merged = mergeReading(readingsBefore, incoming)
                readingsBefore = merged.readings
                if (unchanged) continue
                addedReadings++
            }
        }
        if (addedReadings > 0) {
            _readings.value = readingsBefore.sortedBy { it.epochMillis }
        }

        root.optJSONArray("tiers")?.let { arr ->
            if (arr.length() > 0) {
                val list = ArrayList<TariffTierEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list += TariffTierEntity(
                        tierNo = o.optInt("tierNo", i + 1),
                        capM3 = o.doubleOrNull("capM3"),
                        priceCentsPerM3 = o.optLong("priceCentsPerM3"),
                    )
                }
                _tiers.value = list.sortedBy { it.tierNo }
            }
        }

        var addedPayments = 0
        val payments = _payments.value.toMutableList()
        root.optJSONArray("payments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val paidAt = o.optLong("paidAtMillis")
                val reading = o.optLong("readingMilli")
                if (payments.any { it.paidAtMillis == paidAt && it.readingMilli == reading }) continue
                val id = (payments.maxOfOrNull { it.id } ?: 0L) + 1
                payments += PaymentEntity(
                    id = id,
                    paidAtMillis = paidAt,
                    readingMilli = reading,
                    amountCents = o.longOrNull("amountCents"),
                    tierNo = o.optInt("tierNo", 1),
                    remainingM3 = o.doubleOrNull("remainingM3"),
                    note = o.stringOrNull("note"),
                )
                addedPayments++
            }
        }
        if (addedPayments > 0) {
            _payments.value = payments.sortedBy { it.paidAtMillis }
        }
        persist()
            addedReadings to addedPayments
        }
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
