package com.hididi.gaslog.service

import android.content.Context
import com.hididi.gaslog.domain.SunTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 日出日落缓存。按「经纬度（两位小数） + 日期」缓存，避免每次统计都联网；
 * 拿不到数据的日期返回 null，调用方按 07:00-19:00 兜底。
 */
class SunTimesCache(context: Context) {

    private val file = File(context.filesDir, "sun_cache.json")
    private val mutex = Mutex()
    private val cache = HashMap<String, String>()
    private var loaded = false

    private fun key(lat: Double, lon: Double, date: LocalDate): String =
        "%.2f,%.2f,%s".format(Locale.US, lat, lon, date.toString())

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                root.keys().forEach { k -> cache[k] = root.optString(k) }
            }
        }
    }

    fun get(lat: Double, lon: Double, date: LocalDate): SunTimes? {
        loadIfNeeded()
        val value = cache[key(lat, lon, date)] ?: return null
        val parts = value.split('|')
        if (parts.size != 2) return null
        return runCatching {
            SunTimes(date, LocalTime.parse(parts[0]), LocalTime.parse(parts[1]))
        }.getOrNull()
    }

    /** 补齐缺失日期的日出日落（有网络的日期才会补上） */
    suspend fun ensure(
        weather: WeatherService,
        lat: Double,
        lon: Double,
        dates: List<LocalDate>,
    ) {
        loadIfNeeded()
        val missing = dates.distinct().filter { get(lat, lon, it) == null }
        if (missing.isEmpty()) return
        val fetched = HashMap<String, String>()
        for (date in missing) {
            val times = weather.fetchSunTimes(lat, lon, date) ?: continue
            val rise = times.sunrise ?: continue
            val set = times.sunset ?: continue
            fetched[key(lat, lon, date)] = "${rise.withSecond(0).withNano(0)}|${set.withSecond(0).withNano(0)}"
        }
        if (fetched.isEmpty()) return
        mutex.withLock {
            cache.putAll(fetched)
            persist()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            cache.forEach { (k, v) -> root.put(k, v) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}