package com.hididi.gaslog.ui.screens

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hididi.gaslog.BuildConfig
import com.hididi.gaslog.domain.CostEngine
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.ui.components.Exporter
import com.hididi.gaslog.ui.components.KeyValueRow
import com.hididi.gaslog.ui.components.NoticeCard
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.vm.GasLogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GasLogViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var placeQuery by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text == null) {
                    message = "无法读取备份文件"
                } else {
                    vm.importBackup(text) { msg -> message = msg }
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            scope.launch {
                val loc = currentLocation(context)
                if (loc != null) {
                    vm.saveHomeLocation(settings.homeLabel.ifBlank { "家庭位置" }, loc.first, loc.second)
                    message = "已记录家庭位置：%.4f, %.4f".format(loc.first, loc.second)
                } else {
                    message = "暂时取不到定位，可手动输入城市名"
                }
            }
        } else {
            message = "未授予定位权限，可用手填城市名"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { msg ->
                item { NoticeCard("INFO", "提示", msg) }
            }

            item {
                SectionCard(
                    title = "家庭位置",
                    subtitle = "照片没有 GPS 信息时，用它来取当日气温",
                ) {
                    if (settings.homeLat != null && settings.homeLon != null) {
                        KeyValueRow(
                            "当前",
                            "${settings.homeLabel.ifBlank { "未命名" }} (%.3f, %.3f)".format(
                                settings.homeLat, settings.homeLon,
                            ),
                        )
                    } else {
                        Text(
                            "还没有设置家庭位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = placeQuery,
                            onValueChange = { placeQuery = it },
                            label = { Text("按城市/地址查找") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            scope.launch {
                                val result = vm.geocodePlace(placeQuery)
                                if (result == null) {
                                    message = "没有找到「$placeQuery」，可以试试「城市名+区名」"
                                } else {
                                    vm.saveHomeLocation(placeQuery, result.first, result.second)
                                    message = "已设置为 $placeQuery"
                                }
                            }
                        }) { Text("查找") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    }) { Text("使用当前定位") }
                }
            }

            item {
                SectionCard(title = "气温自动获取", subtitle = "只发送经纬度与日期") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("导入照片时自动查询当日气温", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "数据源可在下面选择；不上传照片和表数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.autoWeather,
                            onCheckedChange = { vm.setAutoWeather(it) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("数据源", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = { vm.setWeatherProvider("openmeteo", settings.caiyunToken) },
                            label = {
                                Text(
                                    if (settings.weatherProvider == "openmeteo") "Open-Meteo（当前，免 Key）" else "Open-Meteo（免 Key）",
                                    fontSize = 11.sp,
                                )
                            },
                        )
                        AssistChip(
                            onClick = { vm.setWeatherProvider("caiyun", settings.caiyunToken) },
                            label = {
                                Text(
                                    if (settings.weatherProvider == "caiyun") "彩云天气（当前）" else "彩云天气（需 token）",
                                    fontSize = 11.sp,
                                )
                            },
                        )
                    }
                    if (settings.weatherProvider == "caiyun") {
                        Spacer(Modifier.height(8.dp))
                        var token by remember { mutableStateOf(settings.caiyunToken) }
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("彩云天气 API Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            vm.setWeatherProvider("caiyun", token)
                            message = if (token.isBlank()) "已保存空 token（会回退到 Open-Meteo）" else "彩云天气已启用"
                        }) { Text("保存 Token") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "小米等国产天气常用彩云数据，数值会更接近手机上的显示；彩云免费档每天 1000 次调用足够家用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("气温校正（可选）", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "如果和家里手机上的天气长期差一个固定值，可以整体平移，保持记录口径一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    var minOff by remember { mutableStateOf(settings.tempMinOffsetC.toPlainText()) }
                    var maxOff by remember { mutableStateOf(settings.tempMaxOffsetC.toPlainText()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = minOff,
                            onValueChange = { v -> minOff = v.filter { it.isDigit() || it == '-' || it == '.' } },
                            label = { Text("最低温 +℃") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = maxOff,
                            onValueChange = { v -> maxOff = v.filter { it.isDigit() || it == '-' || it == '.' } },
                            label = { Text("最高温 +℃") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            vm.setTempOffset(
                                Units.parseDoubleOrNull(minOff) ?: 0.0,
                                Units.parseDoubleOrNull(maxOff) ?: 0.0,
                            )
                            message = "气温校正已保存"
                        }) { Text("保存") }
                    }
                }
            }

            item {
                SectionCard(title = "抄表提醒") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每周提醒抄表", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (settings.reminderEnabled) {
                                    "每周${weekdayName(settings.reminderDayOfWeek)} " +
                                        "%02d:%02d".format(settings.reminderHour, settings.reminderMinute) +
                                        " 提醒（若这周已抄表则不打扰）"
                                } else {
                                    "关闭"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.reminderEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                vm.setReminder(
                                    enabled,
                                    settings.reminderDayOfWeek,
                                    settings.reminderHour,
                                    settings.reminderMinute,
                                )
                            },
                        )
                    }
                    if (settings.reminderEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { d ->
                                if (d == settings.reminderDayOfWeek) {
                                    Button(onClick = { }) { Text(weekdayName(d)) }
                                } else {
                                    OutlinedButton(onClick = {
                                        vm.setReminder(
                                            true, d, settings.reminderHour, settings.reminderMinute,
                                        )
                                    }) { Text(weekdayName(d)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(8, 12, 20, 21).forEach { h ->
                                if (h == settings.reminderHour) {
                                    Button(onClick = { }) { Text("$h:00") }
                                } else {
                                    OutlinedButton(onClick = {
                                        vm.setReminder(true, settings.reminderDayOfWeek, h, 0)
                                    }) { Text("$h:00") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "表盘位数",
                    subtitle = "按你家燃气表填，抄表输入器与识别切分都会用这个位数",
                ) {
                    var intText by remember { mutableStateOf(settings.integerDigits.toString()) }
                    var decText by remember { mutableStateOf(settings.decimalDigits.toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = intText,
                            onValueChange = { v -> intText = v.filter { it.isDigit() }.take(2) },
                            label = { Text("整数位") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = decText,
                            onValueChange = { v -> decText = v.filter { it.isDigit() }.take(1) },
                            label = { Text("小数位") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            val i = intText.toIntOrNull() ?: 5
                            val dec = decText.toIntOrNull() ?: 3
                            if (i in 3..7 && dec in 1..4) {
                                vm.setDigitDefaults(i, dec)
                                message = "已设置为 $i 位整数 + $dec 位小数"
                            } else {
                                message = "整数位 3~7，小数位 1~4"
                            }
                        }) { Text("保存位数") }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "当前：${settings.integerDigits} 整 ${settings.decimalDigits} 小",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "AI 兜底识别",
                    subtitle = "识别不准时，可以点「AI 帮我认」让视觉大模型看图读数（可选）",
                ) {
                    var key by remember { mutableStateOf(settings.aiApiKey) }
                    var baseUrl by remember { mutableStateOf(settings.aiBaseUrl) }
                    var model by remember { mutableStateOf(settings.aiModel) }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("接口地址（OpenAI 兼容 /chat/completions）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名（需支持图片输入）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            vm.setAiConfig(key, baseUrl, model)
                            message = if (key.isBlank()) "已清空 AI 配置" else "AI 配置已保存"
                        }) { Text("保存 AI 配置") }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (settings.aiApiKey.isBlank()) "未配置（只在本机保存 Key）" else "已配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "只会把压缩后的表照发给你填的这个接口，不含其它数据。默认填的是 DeepSeek 的兼容地址，" +
                            "若该模型不支持图片，换成任何支持视觉的兼容接口即可（通义、智谱、自建 OpenAI 服务等）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = "数据", subtitle = "所有数据只存在本机") {
                    KeyValueRow("抄表记录", "${snapshot.readings.size} 条")
                    KeyValueRow("交费记录", "${snapshot.payments.size} 条")
                    KeyValueRow("阶梯气价", CostEngine.totalPriceRange(snapshot.tiers))
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    vm.exportCsv { csv ->
                                        Exporter.shareText(
                                            context,
                                            "抄表记录_${Dates.formatDate(System.currentTimeMillis())}.csv",
                                            csv,
                                        )
                                    }
                                }
                            },
                        ) { Text("导出抄表记录 CSV") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                vm.exportBackup { json ->
                                    Exporter.shareText(
                                        context,
                                        "燃气记录备份_${Dates.formatDate(System.currentTimeMillis())}.json",
                                        json,
                                        "application/json",
                                    )
                                }
                            },
                        ) { Text("导出完整备份 JSON") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        ) { Text("从备份文件导入") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showClear = true },
                        ) { Text("清空全部数据") }
                    }
                }
            }

            item {
                SectionCard(title = "隐私") {
                    Text(
                        "1. 照片、读数、费用全部保存在手机本地数据库与应用私有目录；\n" +
                            "2. 联网仅用于：查询拍摄地点当天的气温（Open-Meteo）、地名反查（系统 Geocoder）；\n" +
                            "3. 不上传照片，不收集账号信息，无广告 SDK；\n" +
                            "4. 卸载应用会同时删除数据，请先用「导出完整备份」留档。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                SectionCard(title = "关于") {
                    Text("家用燃气记录 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "拍照/相册导入 → 离线识别表数 → 记录拍摄时间与当日气温 → 推算日/月/区间用气量与阶梯气费。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "表数精度 0.001 m³；跨日用量按气温、日出日落与采暖度分摊，越规律抄表越准确。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(
                            "当前版本 " + BuildConfigBits.VERSION,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空全部数据？") },
            text = { Text("将删除所有抄表记录、交费记录与照片。建议先导出备份。") },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    vm.clearAll { message = it }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}

private fun weekdayName(d: Int): String = arrayOf("一", "二", "三", "四", "五", "六", "日")[(d - 1).coerceIn(0, 6)]

/** 取一次当前位置：优先缓存（10 分钟内），否则请求单次定位，最长等 8 秒 */
private suspend fun currentLocation(context: Context): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
    val cached = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
    if (cached != null && System.currentTimeMillis() - cached.time < 10 * 60 * 1000) {
        return@withContext cached.latitude to cached.longitude
    }
    for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
        if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false).not()) continue
        val loc = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        lm.getCurrentLocation(
                            provider,
                            null,
                            android.os.Looper.getMainLooper().let { looper ->
                                java.util.concurrent.Executor { r -> android.os.Handler(looper).post(r) }
                            },
                        ) { l -> if (cont.isActive) cont.resume(l) }
                    } else {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(
                            provider,
                            { l -> if (cont.isActive) cont.resume(l) },
                            android.os.Looper.getMainLooper(),
                        )
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        if (loc != null) return@withContext loc.latitude to loc.longitude
    }
    cached?.let { it.latitude to it.longitude }
}

object BuildConfigBits {
    const val VERSION = "1.0"
}


private fun Double.toPlainText(): String =
    if (this == toInt().toDouble()) toInt().toString() else String.format(java.util.Locale.US, "%.1f", this)
