package com.hididi.gaslog.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hididi.gaslog.GasLogApp
import com.hididi.gaslog.data.store.MeterReadingEntity
import com.hididi.gaslog.data.store.PaymentEntity
import com.hididi.gaslog.domain.BillingReplay
import com.hididi.gaslog.domain.CostEngine
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.HeatingDegreeUsageEstimator
import com.hididi.gaslog.domain.PaymentInfo
import com.hididi.gaslog.domain.ReadingPoint
import com.hididi.gaslog.domain.SunTimes
import com.hididi.gaslog.domain.TierDef
import com.hididi.gaslog.domain.TierState
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.service.AiVisionService
import com.hididi.gaslog.service.MeterOcr
import com.hididi.gaslog.service.OcrCandidate
import com.hididi.gaslog.service.PhotoStore
import com.hididi.gaslog.service.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** 正在核对/编辑的一条抄表草稿 */
data class ReadingDraft(
    val key: String = UUID.randomUUID().toString(),
    val photoPath: String? = null,
    /** 审核期间的唯一临时图片；确认时按 EXIF 拍摄秒数归档。 */
    val photoIsTemporary: Boolean = false,
    val epochMillis: Long = System.currentTimeMillis(),
    /** 从左到右已输入的数字（不含小数点）；显示时按位数补位 */
    val cells: String = "",
    val integerCount: Int = 5,
    val decimalCount: Int = 3,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    val weatherStatus: String = "NOT_FETCHED",
    val source: String = "GALLERY",
    val ocrRaw: String? = null,
    val ocrCandidates: List<OcrCandidate> = emptyList(),
    val note: String = "",
    val editingId: Long? = null,
    /** 手工录入：不显示拍摄信息，日期时间手动选，位置用默认位置 */
    val manual: Boolean = false,
    val ocrBusy: Boolean = false,
    val weatherBusy: Boolean = false,
    val aiBusy: Boolean = false,
    val error: String? = null,
) {
    val totalDigits: Int get() = integerCount + decimalCount

    /** "1142.545"；没填任何数字时返回 null */
    fun composedText(): String? {
        if (cells.isEmpty()) return null
        val padded: String = if (cells.length <= integerCount) {
            cells.padStart(integerCount, '0') + "0".repeat(decimalCount)
        } else {
            cells.take(integerCount) + cells.drop(integerCount).padEnd(decimalCount, '0')
        }
        return padded.substring(0, integerCount) + "." + padded.substring(integerCount)
    }

    val readingMilli: Long? get() = composedText()?.let { Units.parseReading(it) }
    val hasReading: Boolean get() = (readingMilli ?: 0L) > 0L

    fun intDigits(): String = cells.take(integerCount)
    fun decDigits(): String = cells.drop(integerCount)

    companion object {
        fun cellsFrom(text: String?, integerCount: Int, decimalCount: Int): String {
            if (text.isNullOrBlank()) return ""
            val intPart = text.substringBefore('.').filter { it.isDigit() }
            val decPart = text.substringAfter('.', "").filter { it.isDigit() }
            val dec = decPart.padEnd(decimalCount, '0').take(decimalCount)
            // 整数位不足时左侧补 0：表盘显示的是 01143.150，识别常把前导 0 丢掉
            val int = intPart.takeLast(integerCount).padStart(integerCount, '0')
            val trimmedDec = dec.trimEnd('0')
            return int + trimmedDec
        }
    }
}

data class ReviewSession(val items: List<ReadingDraft>, val index: Int) {
    val current: ReadingDraft? get() = items.getOrNull(index)
    val size: Int get() = items.size
}

data class Snapshot(
    val readings: List<ReadingPoint> = emptyList(),
    val tiers: List<TierDef> = emptyList(),
    val payments: List<PaymentInfo> = emptyList(),
)

class GasLogViewModel(app: Application) : AndroidViewModel(app) {

    private val container = GasLogApp.container
    val repo = container.repository
    val settingsRepo = container.settings
    private val weather = container.weather
    private val sunCache = container.sunCache
    private val dailyTempCache = container.dailyTempCache
    private val aiVision = AiVisionService(app)

    val settings = settingsRepo.settings

    val snapshot: StateFlow<Snapshot> = combine(
        repo.readings, repo.tiers, repo.payments,
    ) { readings, tiers, payments -> Snapshot(readings, tiers, payments) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Snapshot())

    private val _session = MutableStateFlow<ReviewSession?>(null)
    val session: StateFlow<ReviewSession?> = _session.asStateFlow()

    /** 日出日落等异步数据就绪后自增，界面据此重算 */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    init {
        viewModelScope.launch {
            repo.ensureLoaded()
            repo.seedDefaultsIfEmpty()
        }
        viewModelScope.launch {
            val s = settingsRepo.settings.value
            ReminderScheduler.apply(app, s.reminderEnabled, s.reminderDayOfWeek, s.reminderHour, s.reminderMinute)
        }
    }

    private fun newDraft(
        photoPath: String? = null,
        photoIsTemporary: Boolean = false,
        epochMillis: Long = System.currentTimeMillis(),
        source: String = "GALLERY",
        manual: Boolean = false,
        editingId: Long? = null,
        takenLat: Double? = null,
        takenLon: Double? = null,
    ): ReadingDraft {
        val s = settingsRepo.settings.value
        // 位置优先用 App 里设置好的家庭位置
        val lat = s.homeLat ?: takenLat
        val lon = s.homeLon ?: takenLon
        return ReadingDraft(
            photoPath = photoPath,
            photoIsTemporary = photoIsTemporary,
            epochMillis = epochMillis,
            integerCount = s.integerDigits,
            decimalCount = s.decimalDigits,
            latitude = lat,
            longitude = lon,
            locationName = if (s.homeLabel.isNotBlank()) s.homeLabel else null,
            source = source,
            manual = manual,
            editingId = editingId,
        )
    }

    // ---------------- 导入 ----------------

    /** 多选导入：所有照片进同一个核对会话，可上一张/下一张，最后一起确认 */
    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val drafts = ArrayList<ReadingDraft>()
            for (uri in uris) {
                val imported = withContext(Dispatchers.IO) { PhotoStore.importFromUri(app, uri) }
                if (imported == null) continue
                val draft = newDraft(
                    photoPath = imported.file.absolutePath,
                    photoIsTemporary = true,
                    epochMillis = imported.exif.takenAtMillis ?: System.currentTimeMillis(),
                    source = "GALLERY",
                    takenLat = imported.exif.latitude,
                    takenLon = imported.exif.longitude,
                )
                drafts += draft
            }
            if (drafts.isEmpty()) {
                _session.value = ReviewSession(
                    listOf(ReadingDraft(error = "无法读取这些照片")), 0,
                )
                return@launch
            }
            _session.value = ReviewSession(drafts, 0)
            drafts.forEachIndexed { i, draft -> processDraft(i, draft.key) }
        }
    }

    /** 相机拍完 */
    fun startCapture(file: File, takenAtMillis: Long) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { PhotoStore.compressCaptured(app, file) }
            val draft = newDraft(
                photoPath = file.absolutePath,
                photoIsTemporary = true,
                epochMillis = imported?.exif?.takenAtMillis ?: takenAtMillis,
                source = "CAMERA",
                takenLat = imported?.exif?.latitude,
                takenLon = imported?.exif?.longitude,
            )
            _session.value = ReviewSession(listOf(draft), 0)
            processDraft(0, draft.key)
        }
    }

    /** 手工录入：不拍照，日期时间可改，位置固定用家庭位置 */
    fun startManual() {
        val last = snapshot.value.readings.maxByOrNull { it.epochMillis }
        val s = settingsRepo.settings.value
        val draft = newDraft(source = "MANUAL", manual = true).let { d ->
            if (last != null) {
                d.copy(
                    cells = ReadingDraft.cellsFrom(
                        Units.formatReading(last.readingMilli), d.integerCount, d.decimalCount,
                    ),
                )
            } else d
        }.copy(
            locationName = s.homeLabel.ifBlank { null },
        )
        _session.value = ReviewSession(listOf(draft), 0)
        viewModelScope.launch { fetchWeatherFor(0, draft.key) }
    }

    fun startEdit(id: Long) {
        viewModelScope.launch {
            val r = repo.readingById(id) ?: return@launch
            val s = settingsRepo.settings.value
            val draft = ReadingDraft(
                photoPath = r.photoPath,
                epochMillis = r.epochMillis,
                cells = ReadingDraft.cellsFrom(Units.formatReading(r.readingMilli), s.integerDigits, s.decimalDigits),
                integerCount = s.integerDigits,
                decimalCount = s.decimalDigits,
                latitude = s.homeLat,
                longitude = s.homeLon,
                locationName = if (s.homeLabel.isNotBlank()) s.homeLabel else null,
                tempMinC = r.tempMinC,
                tempMaxC = r.tempMaxC,
                weatherStatus = if (r.tempMinC != null || r.tempMaxC != null) "OK" else "NOT_FETCHED",
                source = r.source,
                note = r.note ?: "",
                editingId = id,
            )
            _session.value = ReviewSession(listOf(draft), 0)
        }
    }

    private fun processDraft(index: Int, key: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val draft = item(index, key) ?: return@launch
            updateItem(index, key) { it.copy(ocrBusy = true) }
            val path = draft.photoPath
            if (path != null) {
                val outcome = withContext(Dispatchers.IO) {
                    val bitmap = PhotoStore.loadUprightBitmap(File(path), 1600)
                    if (bitmap == null) null else MeterOcr.recognizeRobust(bitmap, draft.decimalCount)
                }
                val best = outcome?.candidates?.firstOrNull()
                updateItem(index, key) { cur ->
                    if (cur.ocrCandidates.isNotEmpty() && cur.cells.isNotEmpty()) {
                        cur.copy(ocrBusy = false, ocrRaw = outcome?.rawText ?: cur.ocrRaw)
                    } else {
                        cur.copy(
                            ocrBusy = false,
                            cells = cur.cells.ifEmpty {
                                best?.let { b -> ReadingDraft.cellsFrom(b.readingText, cur.integerCount, cur.decimalCount) } ?: ""
                            },
                            ocrRaw = outcome?.rawText,
                            ocrCandidates = outcome?.candidates ?: emptyList(),
                            error = if (outcome == null) "照片读取失败" else null,
                        )
                    }
                }
            } else {
                updateItem(index, key) { it.copy(ocrBusy = false) }
            }
            fetchWeatherFor(index, key)
        }
    }

    fun updateCurrent(transform: (ReadingDraft) -> ReadingDraft) {
        val s = _session.value ?: return
        val cur = s.current ?: return
        _session.value = s.copy(items = s.items.map { if (it.key == cur.key) transform(it) else it })
    }

    private fun item(index: Int, key: String): ReadingDraft? =
        _session.value?.items?.firstOrNull { it.key == key }

    private fun updateItem(index: Int, key: String, transform: (ReadingDraft) -> ReadingDraft) {
        val s = _session.value ?: return
        _session.value = s.copy(items = s.items.map { if (it.key == key) transform(it) else it })
    }

    fun goTo(index: Int) {
        val s = _session.value ?: return
        if (index in s.items.indices) _session.value = s.copy(index = index)
    }

    // ---------------- 气温 / 位置 ----------------

    fun refreshWeatherCurrent() {
        val s = _session.value ?: return
        val cur = s.current ?: return
        fetchWeatherFor(s.index, cur.key)
    }

    private fun fetchWeatherFor(index: Int, key: String) {
        val app = getApplication<Application>()
        val draft = item(index, key) ?: return
        val s = settingsRepo.settings.value
        val lat = s.homeLat ?: draft.latitude
        val lon = s.homeLon ?: draft.longitude
        if (!s.autoWeather) {
            updateItem(index, key) { it.copy(weatherStatus = "MANUAL", weatherBusy = false) }
            return
        }
        if (lat == null || lon == null) {
            updateItem(index, key) { it.copy(weatherStatus = "NO_LOCATION", weatherBusy = false) }
            return
        }
        updateItem(index, key) { it.copy(weatherBusy = true, latitude = lat, longitude = lon) }
        viewModelScope.launch {
            val date = Dates.dateOf(draft.epochMillis)
            val result = fetchDailyTemp(lat, lon, date)
            val name = s.homeLabel.ifBlank {
                withContext(Dispatchers.IO) { weather.describeLocation(lat, lon) } ?: ""
            }
            if (result.isSuccess) {
                val okTemp = result.getOrNull()
                if (okTemp != null) {
                    dailyTempCache.put(
                        lat, lon, date,
                        okTemp.tempMinC?.let { v -> v + settingsRepo.settings.value.tempMinOffsetC },
                        okTemp.tempMaxC?.let { v -> v + settingsRepo.settings.value.tempMaxOffsetC },
                    )
                }
            }
            updateItem(index, key) { cur ->
                if (result.isSuccess) {
                    val t = result.getOrThrow()
                    cur.copy(
                        weatherBusy = false,
                        tempMinC = t.tempMinC?.let { v -> v + settingsRepo.settings.value.tempMinOffsetC },
                        tempMaxC = t.tempMaxC?.let { v -> v + settingsRepo.settings.value.tempMaxOffsetC },
                        weatherStatus = "OK",
                        locationName = cur.locationName ?: name.ifBlank { null },
                    )
                } else {
                    cur.copy(
                        weatherBusy = false,
                        weatherStatus = "FAILED",
                        locationName = cur.locationName ?: name.ifBlank { null },
                        error = "气温获取失败：${result.exceptionOrNull()?.message ?: "网络不可用"}",
                    )
                }
            }
            // 顺手把日出日落缓存下来，统计页要用
            sunCache.ensure(weather, lat, lon, listOf(date))
        }
    }

    /** 记录详情：把某一天所有记录的气温一起刷新 */
    fun refreshWeatherForDate(date: LocalDate) {
        viewModelScope.launch {
            val s = settingsRepo.settings.value
            val lat = s.homeLat
            val lon = s.homeLon
            if (lat == null || lon == null) return@launch
            val result = fetchDailyTemp(lat, lon, date)
            val readings = repo.readingsOnce().filter { Dates.dateOf(it.epochMillis) == date }
            if (result.isSuccess) {
                val t = result.getOrThrow()
                val minOffset = s.tempMinOffsetC
                val maxOffset = s.tempMaxOffsetC
                readings.forEach {
                    repo.updateWeather(
                        it.id,
                        t.tempMinC?.let { v -> v + minOffset },
                        t.tempMaxC?.let { v -> v + maxOffset },
                        "OK",
                    )
                }
                sunCache.ensure(weather, lat, lon, listOf(date))
            } else {
                readings.forEach { repo.updateWeather(it.id, it.tempMinC, it.tempMaxC, "FAILED") }
            }
        }
    }

    /** 某天的气温（与有没有抄表无关），来自天气缓存 */
    fun dailyTempFor(date: LocalDate): Pair<Double?, Double?>? {
        val s = settingsRepo.settings.value
        val lat = s.homeLat ?: return null
        val lon = s.homeLon ?: return null
        return dailyTempCache.get(lat, lon, date)
    }

    /**
     * 补拉区间内缺失日期的气温（例如故意跳过一天没抄表，那天的气温也要补上），
     * 补到缓存后逐日表、采暖度模型都能用上。
     */
    fun ensureDailyTemps(from: LocalDate, to: LocalDate) {
        val s = settingsRepo.settings.value
        if (!s.autoWeather) return
        val lat = s.homeLat ?: return
        val lon = s.homeLon ?: return
        viewModelScope.launch {
            var date = from
            var fetched = 0
            var changed = false
            var attempts = 0
            while (!date.isAfter(to) && attempts < 40) {
                if (dailyTempCache.get(lat, lon, date) == null) {
                    attempts++
                    val r = fetchDailyTemp(lat, lon, date)
                    if (r.isSuccess) {
                        val t = r.getOrThrow()
                        dailyTempCache.put(
                            lat, lon, date,
                            t.tempMinC?.let { v -> v + s.tempMinOffsetC },
                            t.tempMaxC?.let { v -> v + s.tempMaxOffsetC },
                        )
                        fetched++
                        changed = true
                    }
                }
                date = date.plusDays(1)
            }
            if (changed) _dataVersion.value = _dataVersion.value + 1
            if (fetched > 0) {
                // 已存记录里若有当天缺气温的，顺便回填
                backfillReadingTemps(from, to)
            }
        }
    }

    /** 把缓存里的每日气温回填到当天的历史记录上（只补空缺） */
    private suspend fun backfillReadingTemps(from: LocalDate, to: LocalDate) {
        val s = settingsRepo.settings.value
        val lat = s.homeLat ?: return
        val lon = s.homeLon ?: return
        val readings = repo.readingsOnce()
        readings.forEach { r ->
            val date = Dates.dateOf(r.epochMillis)
            if (date < from || date > to) return@forEach
            if (r.tempMinC != null && r.tempMaxC != null) return@forEach
            val cached = dailyTempCache.get(lat, lon, date) ?: return@forEach
            repo.updateWeather(r.id, cached.first, cached.second, "OK")
        }
    }

    fun sunTimesFor(date: LocalDate): SunTimes? {
        val s = settingsRepo.settings.value
        val lat = s.homeLat ?: return null
        val lon = s.homeLon ?: return null
        return sunCache.get(lat, lon, date)
    }

    /** 统计页需要区间的日出日落，按需补齐（联网失败则用 07:00-19:00 兜底） */
    fun ensureSunTimes(from: LocalDate, to: LocalDate) {
        val s = settingsRepo.settings.value
        val lat = s.homeLat ?: return
        val lon = s.homeLon ?: return
        viewModelScope.launch {
            val dates = HeatingDegreeUsageEstimator.modelDates(snapshot.value.readings, from, to)
            if (dates.isEmpty()) return@launch
            sunCache.ensure(weather, lat, lon, dates)
            _dataVersion.value = _dataVersion.value + 1
        }
    }

    // ---------------- AI 兜底识别 ----------------

    fun aiRecognizeCurrent() {
        val app = getApplication<Application>()
        val s = _session.value ?: return
        val cur = s.current ?: return
        val path = cur.photoPath
        if (path == null) {
            updateCurrent { it.copy(error = "这条记录没有照片，AI 无从识别") }
            return
        }
        val cfg = AiVisionService.Config(
            apiKey = settingsRepo.settings.value.aiApiKey,
            baseUrl = settingsRepo.settings.value.aiBaseUrl,
            model = settingsRepo.settings.value.aiModel,
        )
        if (!cfg.enabled) {
            updateCurrent { it.copy(error = "还没配置 AI：请到「设置 → AI 兜底识别」填接口地址、模型和 Key") }
            return
        }
        updateItem(s.index, cur.key) { it.copy(aiBusy = true, error = null) }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                PhotoStore.loadUprightBitmap(File(path), 1400)
            }
            if (bitmap == null) {
                updateItem(s.index, cur.key) { it.copy(aiBusy = false, error = "照片读取失败") }
                return@launch
            }
            val result = aiVision.recognizeReading(bitmap, cfg, cur.integerCount, cur.decimalCount)
            updateItem(s.index, cur.key) { item ->
                if (result.isSuccess) {
                    val text = result.getOrThrow()
                    item.copy(
                        aiBusy = false,
                        cells = ReadingDraft.cellsFrom(text, item.integerCount, item.decimalCount),
                        error = null,
                    )
                } else {
                    item.copy(aiBusy = false, error = "AI 识别失败：${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    // ---------------- 保存 ----------------

    /** Deletes only images that are no longer referenced by either persisted data or the review session. */
    private suspend fun deletePhotoIfUnreferenced(
        path: String?,
        excludingRecordIds: Set<Long> = emptySet(),
        excludingDraftKeys: Set<String> = emptySet(),
    ) {
        if (path.isNullOrBlank()) return
        val usedByReading = repo.readingsOnce().any { it.id !in excludingRecordIds && it.photoPath == path }
        val usedByDraft = _session.value?.items?.any {
            it.key !in excludingDraftKeys && it.photoPath == path
        } == true
        if (!usedByReading && !usedByDraft) {
            withContext(Dispatchers.IO) { PhotoStore.delete(path) }
        }
    }

    fun saveAll(onDone: (saved: Int, skipped: Int) -> Unit) {
        val s = _session.value ?: return
        viewModelScope.launch {
            var saved = 0
            var skipped = 0
            for (item in s.items) {
                val milli = item.readingMilli
                if (milli == null || milli <= 0L || item.error?.startsWith("照片读取失败") == true) {
                    skipped++
                    if (item.photoIsTemporary) {
                        deletePhotoIfUnreferenced(item.photoPath, excludingDraftKeys = setOf(item.key))
                    }
                    continue
                }
                val photoPath = if (item.photoIsTemporary && item.photoPath != null) {
                    withContext(Dispatchers.IO) {
                        PhotoStore.archiveTemporaryPhoto(getApplication(), item.photoPath, item.epochMillis)
                    } ?: item.photoPath
                } else {
                    item.photoPath
                }
                val entity = MeterReadingEntity(
                    id = item.editingId ?: 0,
                    epochMillis = item.epochMillis,
                    readingMilli = milli,
                    photoPath = photoPath,
                    latitude = item.latitude,
                    longitude = item.longitude,
                    locationName = item.locationName,
                    tempMinC = item.tempMinC,
                    tempMaxC = item.tempMaxC,
                    weatherStatus = item.weatherStatus,
                    source = item.source,
                    ocrRaw = item.ocrRaw,
                    note = item.note.ifBlank { null },
                )
                val result = repo.saveReading(entity)
                result.removedReadings.forEach { removed ->
                    deletePhotoIfUnreferenced(removed.photoPath)
                }
                saved++
            }
            _session.value = null
            onDone(saved, skipped)
        }
    }

    fun discardSession() {
        val s = _session.value ?: return
        _session.value = null
        viewModelScope.launch {
            s.items.filter { it.photoIsTemporary }.forEach { draft ->
                deletePhotoIfUnreferenced(draft.photoPath)
            }
        }
    }

    fun deleteReading(id: Long) {
        viewModelScope.launch {
            val r = repo.readingById(id)
            repo.deleteReading(id)
            deletePhotoIfUnreferenced(r?.photoPath, excludingRecordIds = setOf(id))
        }
    }

    // ---------------- 阶梯价 & 交费 ----------------

    fun saveTiers(list: List<TierDef>) {
        viewModelScope.launch { repo.saveTiers(list) }
    }

    fun savePayment(
        paidAtMillis: Long,
        readingMilli: Long,
        amountCents: Long?,
        tierNo: Int,
        remainingM3: Double?,
        note: String?,
        existingId: Long? = null,
    ) {
        viewModelScope.launch {
            val entity = PaymentEntity(
                id = existingId ?: 0,
                paidAtMillis = paidAtMillis,
                readingMilli = readingMilli,
                amountCents = amountCents,
                tierNo = tierNo,
                remainingM3 = remainingM3,
                note = note,
            )
            if (existingId != null) repo.updatePayment(entity) else repo.insertPayment(entity)
        }
    }

    fun deletePayment(id: Long) {
        viewModelScope.launch { repo.deletePayment(id) }
    }

    // ---------------- 设置 ----------------

    fun saveHomeLocation(label: String, lat: Double?, lon: Double?) {
        settingsRepo.update { it.copy(homeLabel = label, homeLat = lat, homeLon = lon) }
    }

    fun setReminder(enabled: Boolean, dayOfWeek: Int, hour: Int, minute: Int) {
        settingsRepo.update {
            it.copy(
                reminderEnabled = enabled,
                reminderDayOfWeek = dayOfWeek,
                reminderHour = hour,
                reminderMinute = minute,
            )
        }
        ReminderScheduler.apply(getApplication(), enabled, dayOfWeek, hour, minute)
    }

    fun setAutoWeather(enabled: Boolean) {
        settingsRepo.update { it.copy(autoWeather = enabled) }
    }

    fun setWeatherProvider(provider: String, caiyunToken: String) {
        settingsRepo.update {
            it.copy(
                weatherProvider = provider,
                caiyunToken = caiyunToken.trim(),
                autoWeather = if (provider == "caiyun") true else it.autoWeather,
            )
        }
    }

    /** 取某天某地的气温：按设置选择数据源，彩云失败时回退到 Open-Meteo */
    private suspend fun fetchDailyTemp(
        lat: Double,
        lon: Double,
        date: LocalDate,
    ): Result<com.hididi.gaslog.domain.DailyTemp> {
        val s = settingsRepo.settings.value
        if (s.weatherProvider == "caiyun" && s.caiyunToken.isNotBlank()) {
            val caiyun = weather.fetchDailyCaiyun(lat, lon, date, s.caiyunToken)
            if (caiyun.isSuccess) return caiyun
        }
        return weather.fetchDaily(lat, lon, date)
    }

    fun setTempOffset(minOffset: Double, maxOffset: Double) {
        settingsRepo.update {
            it.copy(
                tempMinOffsetC = minOffset.coerceIn(-10.0, 10.0),
                tempMaxOffsetC = maxOffset.coerceIn(-10.0, 10.0),
            )
        }
    }

    fun setDigitDefaults(integerCount: Int, decimalCount: Int) {
        settingsRepo.update { it.copy(integerDigits = integerCount, decimalDigits = decimalCount) }
    }

    fun setAiConfig(apiKey: String, baseUrl: String, model: String) {
        settingsRepo.update { it.copy(aiApiKey = apiKey.trim(), aiBaseUrl = baseUrl.trim(), aiModel = model.trim()) }
    }

    // ---------------- 位置 / 清空 / 导入导出 ----------------

    suspend fun geocodePlace(query: String): Pair<Double, Double>? = weather.geocode(query)

    fun clearAll(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val photos = repo.readingsOnce().mapNotNull { it.photoPath }
            repo.clearAll()
            repo.seedDefaultsIfEmpty()
            photos.forEach { deletePhotoIfUnreferenced(it) }
            onDone("已清空全部数据")
        }
    }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repo.exportCsv()) }
    }

    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repo.exportBackupJson()) }
    }

    fun importBackup(json: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { repo.importBackupJson(json) }
            onDone(
                result.fold(
                    onSuccess = { "已导入 ${it.readingsAdded} 条记录、${it.paymentsAdded} 条交费记录" },
                    onFailure = { "导入失败：${it.message}" },
                ),
            )
        }
    }

    // ---------------- 派生计算 ----------------

    fun currentTierState(
        snapshot: Snapshot = this.snapshot.value,
        until: Long = System.currentTimeMillis(),
    ): TierState? {
        val payment = snapshot.payments.maxByOrNull { it.paidAtMillis }
        if (payment == null) return CostEngine.initialState(snapshot.tiers)
        return BillingReplay.replay(payment, snapshot.tiers, snapshot.readings, until).state
    }

    fun replayResult(snapshot: Snapshot = this.snapshot.value, until: Long = System.currentTimeMillis()) =
        BillingReplay.replay(snapshot.payments.maxByOrNull { it.paidAtMillis }, snapshot.tiers, snapshot.readings, until)

    fun usageFor(from: LocalDate, to: LocalDate, snapshot: Snapshot = this.snapshot.value) =
        UsageCalculator.summarize(snapshot.readings, from, to)
}
