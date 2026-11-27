package com.hididi.gaslog

import android.content.Context
import com.hididi.gaslog.data.GasRepository
import com.hididi.gaslog.data.SettingsRepository
import com.hididi.gaslog.service.DailyTempCache
import com.hididi.gaslog.service.SunTimesCache
import com.hididi.gaslog.service.WeatherService

/**
 * 极简依赖容器。整个 App 只有一份数据库与设置，手动注入，避免引入 DI 框架。
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val repository = GasRepository(appContext)
    val settings = SettingsRepository(appContext)
    val weather = WeatherService(appContext)
    val sunCache = SunTimesCache(appContext)
    val dailyTempCache = DailyTempCache(appContext)
}