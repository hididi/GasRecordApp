package com.hididi.gaslog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** 家庭位置：照片没有 GPS 时用它取当日气温 */
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val homeLabel: String = "",
    val autoWeather: Boolean = true,
    val reminderEnabled: Boolean = false,
    /** 1=周一 … 7=周日 */
    val reminderDayOfWeek: Int = 7,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    /** 计费年度起始（月-日），用于年度累计用气统计 */
    val billingYearStartMonth: Int = 1,
    val billingYearStartDay: Int = 1,
    /** 表盘位数：整数位（黑字）与小数位（红字），用于抄表输入器与识别切分 */
    val integerDigits: Int = 5,
    val decimalDigits: Int = 3,
    /**
     * 气温校正（℃）。Open-Meteo 是全球模式网格值，与本地气象站/城市预报常有 1~3℃ 差异，
     * 这里允许按自家口径整体平移，保证长期记录的一致性。
     */
    /** 气温数据源：openmeteo（默认，免 Key）/ caiyun（需 token，接近国产天气 App 的数值） */
    val weatherProvider: String = "openmeteo",
    val caiyunToken: String = "",
    val tempMinOffsetC: Double = 0.0,
    val tempMaxOffsetC: Double = 0.0,
    /** 兜底识别用的视觉大模型（OpenAI 兼容接口） */
    val aiApiKey: String = "",
    val aiBaseUrl: String = "https://api.deepseek.com/chat/completions",
    val aiModel: String = "deepseek-chat",
    val hasSeenWelcome: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("gaslog_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        homeLat = prefs.getString("homeLat", null)?.toDoubleOrNull(),
        homeLon = prefs.getString("homeLon", null)?.toDoubleOrNull(),
        homeLabel = prefs.getString("homeLabel", "") ?: "",
        autoWeather = prefs.getBoolean("autoWeather", true),
        reminderEnabled = prefs.getBoolean("reminderEnabled", false),
        reminderDayOfWeek = prefs.getInt("reminderDayOfWeek", 7),
        reminderHour = prefs.getInt("reminderHour", 20),
        reminderMinute = prefs.getInt("reminderMinute", 0),
        billingYearStartMonth = prefs.getInt("billingYearStartMonth", 1),
        billingYearStartDay = prefs.getInt("billingYearStartDay", 1),
        weatherProvider = prefs.getString("weatherProvider", "openmeteo") ?: "openmeteo",
        caiyunToken = prefs.getString("caiyunToken", "") ?: "",
        tempMinOffsetC = prefs.getFloat("tempMinOffsetC", 0f).toDouble(),
        tempMaxOffsetC = prefs.getFloat("tempMaxOffsetC", 0f).toDouble(),
        integerDigits = prefs.getInt("integerDigits", 5),
        decimalDigits = prefs.getInt("decimalDigits", 3),
        aiApiKey = prefs.getString("aiApiKey", "") ?: "",
        aiBaseUrl = prefs.getString("aiBaseUrl", "https://api.deepseek.com/chat/completions") ?: "",
        aiModel = prefs.getString("aiModel", "deepseek-chat") ?: "",
        hasSeenWelcome = prefs.getBoolean("hasSeenWelcome", false),
    )

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("homeLat", next.homeLat?.toString())
            .putString("homeLon", next.homeLon?.toString())
            .putString("homeLabel", next.homeLabel)
            .putBoolean("autoWeather", next.autoWeather)
            .putBoolean("reminderEnabled", next.reminderEnabled)
            .putInt("reminderDayOfWeek", next.reminderDayOfWeek)
            .putInt("reminderHour", next.reminderHour)
            .putInt("reminderMinute", next.reminderMinute)
            .putInt("billingYearStartMonth", next.billingYearStartMonth)
            .putInt("billingYearStartDay", next.billingYearStartDay)
            .putString("weatherProvider", next.weatherProvider)
            .putString("caiyunToken", next.caiyunToken)
            .putFloat("tempMinOffsetC", next.tempMinOffsetC.toFloat())
            .putFloat("tempMaxOffsetC", next.tempMaxOffsetC.toFloat())
            .putInt("integerDigits", next.integerDigits)
            .putInt("decimalDigits", next.decimalDigits)
            .putString("aiApiKey", next.aiApiKey)
            .putString("aiBaseUrl", next.aiBaseUrl)
            .putString("aiModel", next.aiModel)
            .putBoolean("hasSeenWelcome", next.hasSeenWelcome)
            .apply()
        _settings.value = next
        return next
    }

    fun markWelcomeSeen() = update { it.copy(hasSeenWelcome = true) }
}