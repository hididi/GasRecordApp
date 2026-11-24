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