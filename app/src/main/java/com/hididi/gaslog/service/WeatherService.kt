package com.hididi.gaslog.service

import android.content.Context
import android.location.Geocoder
import com.hididi.gaslog.domain.DailyTemp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 当日气温区间。
 *
 * 数据来源 Open-Meteo（无需 API Key）：
 *  - 最近 92 天：预报接口的 past_days 历史回算
 *  - 更早：历史存档接口
 * 只在用户手机联网时请求，不上传任何表数/照片信息，只发送经纬度与日期。
 */
class WeatherService(private val context: Context) {

    suspend fun fetchDaily(lat: Double, lon: Double, date: LocalDate): Result<DailyTemp> =
        withContext(Dispatchers.IO) {
            runCatching {
                val today = LocalDate.now()
                val daysAgo = ChronoUnit.DAYS.between(date, today)
                val url = if (daysAgo in 0..92) {
                    val pastDays = daysAgo.coerceIn(1, 92)
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(
                        Locale.US, lat, lon,
                    ) + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto" +
                        "&past_days=$pastDays&forecast_days=1"
                } else {
                    val d = date.toString()
                    "https://archive-api.open-meteo.com/v1/archive?latitude=%.4f&longitude=%.4f".format(
                        Locale.US, lat, lon,
                    ) + "&start_date=$d&end_date=$d&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
                }
                val json = httpGet(url) ?: error("网络请求失败")
                parseDaily(json, date)
            }
        }

    /** 指定日期该地点的日出/日落（当地时间） */
    suspend fun fetchSunTimes(lat: Double, lon: Double, date: LocalDate): com.hididi.gaslog.domain.SunTimes? =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = date.toString()
                val url =
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.US, lat, lon) +
                        "&daily=sunrise,sunset&timezone=auto&start_date=$d&end_date=$d"
                val json = httpGet(url) ?: return@runCatching null
                val root = JSONObject(json)
                val daily = root.optJSONObject("daily") ?: return@runCatching null
                val rise = daily.optJSONArray("sunrise")?.optString(0)?.takeIf { it.length >= 16 }
                val set = daily.optJSONArray("sunset")?.optString(0)?.takeIf { it.length >= 16 }
                if (rise == null || set == null) return@runCatching null
                com.hididi.gaslog.domain.SunTimes(
                    date = date,
                    sunrise = LocalTime.parse(rise.substring(11, 16)),
                    sunset = LocalTime.parse(set.substring(11, 16)),
                )
            }.getOrNull()
        }

    /**
     * 彩云天气（Caiyun）每日温度。小米/部分国产天气 App 的数据与之相近，需要用户自己的 token。
     * 注意彩云接口的坐标顺序是「经度,纬度」。
     */
    suspend fun fetchDailyCaiyun(
        lat: Double,
        lon: Double,
        date: LocalDate,
        token: String,
    ): Result<DailyTemp> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.caiyunapp.com/v2.6/%s/%.4f,%.4f/daily?dailysteps=7&alert=false"
                .format(token, lon, lat)
            val json = httpGet(url) ?: error("网络请求失败")
            parseCaiyunDaily(json, date)
        }
    }

    private fun parseDaily(json: String, date: LocalDate): DailyTemp {
        val root = JSONObject(json)
        val daily = root.optJSONObject("daily") ?: error("返回数据缺少 daily 字段")
        val times = daily.optJSONArray("time") ?: error("返回数据缺少 time")
        val tMax = daily.optJSONArray("temperature_2m_max")
        val tMin = daily.optJSONArray("temperature_2m_min")
        val target = date.toString()
        for (i in 0 until times.length()) {
            if (times.optString(i) == target) {
                val max = if (tMax != null && !tMax.isNull(i)) tMax.optDouble(i) else null
                val min = if (tMin != null && !tMin.isNull(i)) tMin.optDouble(i) else null
                if (max == null && min == null) error("该日期没有气温数据")
                return DailyTemp(date, min, max)
            }
        }
        error("该日期没有气温数据（历史数据可能有几天延迟）")
    }

    /** 经纬度 → 地名（失败时返回 null，UI 退回显示坐标） */
    suspend fun describeLocation(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)
            list?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.locality ?: addr.adminArea, addr.subLocality, addr.thoroughfare)
                    .joinToString("").ifBlank { addr.getAddressLine(0) }
            }
        }.getOrNull()
    }

    /** 地名 → 经纬度（用户手动输入"城市/地址"时用） */
    suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.CHINA).getFromLocationName(query, 1)
            list?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    private fun parseCaiyunDaily(json: String, date: LocalDate): DailyTemp =
        com.hididi.gaslog.service.parseCaiyunDailyJson(json, date)

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GasLog-Android/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/** 解析彩云天气 /v2.6 daily 返回，取出指定日期的最低/最高温（℃）。 */
internal fun parseCaiyunDailyJson(json: String, date: java.time.LocalDate): DailyTemp {
    val root = JSONObject(json)
    if (root.optString("status", "") != "ok") {
        error(root.optString("error", "彩云接口返回异常"))
    }
    val arr = root.getJSONObject("result").getJSONObject("daily").getJSONArray("temperature")
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val day = o.optString("date", "").take(10)
        if (day == date.toString()) {
            val max = o.optDouble("max", Double.NaN).takeIf { !it.isNaN() }
            val min = o.optDouble("min", Double.NaN).takeIf { !it.isNaN() }
            if (max == null && min == null) error("彩云天气返回该日气温为空")
            return DailyTemp(date, min, max)
        }
    }
    error("彩云天气没有 ${date} 的数据（可能超出 7 天范围）")
}
