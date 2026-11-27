package com.hididi.gaslog.service

import android.content.Context
import com.hididi.gaslog.data.store.GasDatabase
import java.time.LocalDate
import java.util.Locale

/**
 * 每天的气温缓存（与读数无关）。
 *
 * 有意跳过某天不抄表时，那几天没有读数、也就没有气温，
 * 逐日表和采暖度模型就会缺数据 —— 这里单独按「日期 + 位置」缓存当天最低/最高温，
 * 让没有读数的那几天也能补上气温、按同一套模型分摊。
 */
class DailyTempCache(context: Context) {

    private val database = GasDatabase(context)

    private fun key(lat: Double, lon: Double, date: LocalDate): String =
        "t|%.2f,%.2f,%s".format(Locale.US, lat, lon, date.toString())

    fun get(lat: Double, lon: Double, date: LocalDate): Pair<Double?, Double?>? {
        val raw = database.cacheValue(key(lat, lon, date)) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val min = parts[0].toDoubleOrNull()
        val max = parts[1].toDoubleOrNull()
        return if (min == null && max == null) null else min to max
    }

    fun put(lat: Double, lon: Double, date: LocalDate, min: Double?, max: Double?) {
        if (min == null && max == null) return
        val value = "${min ?: ""}|${max ?: ""}"
        database.putCacheValue(key(lat, lon, date), value)
    }
}
