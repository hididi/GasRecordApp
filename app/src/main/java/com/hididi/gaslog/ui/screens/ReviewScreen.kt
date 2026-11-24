package com.hididi.gaslog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hididi.gaslog.domain.DailyTemp
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.ui.components.MeterDigitInput
import com.hididi.gaslog.ui.components.NoticeCard
import com.hididi.gaslog.ui.components.PhotoZoomDialog
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.vm.GasLogViewModel
import com.hididi.gaslog.ui.vm.ReadingDraft
import java.io.File
import java.time.Instant
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    vm: GasLogViewModel,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val session by vm.session.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showTempDialog by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }

    val current = session?.current
    if (session == null || current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有待确认的数据") }
        return
    }
    val d = current
    val batchCount = session!!.size
    val index = session!!.index

    val prevReading = remember(snapshot.readings, d.epochMillis, d.editingId) {
        snapshot.readings
            .filter { it.id != d.editingId && it.epochMillis <= d.epochMillis }
            .maxByOrNull { it.epochMillis }
    }
    val parsed = d.readingMilli
    val decrease = parsed != null && prevReading != null && parsed < prevReading.readingMilli
    val duplicate = remember(snapshot.readings, parsed, d.epochMillis, d.editingId) {
        parsed != null && snapshot.readings.any {
            it.id != d.editingId && it.readingMilli == parsed &&
                Dates.dateOf(it.epochMillis) == Dates.dateOf(d.epochMillis)
        }
    }
    val topCandidate = d.ocrCandidates.firstOrNull()
    val digitMismatch = topCandidate != null && topCandidate.digits.length != d.totalDigits
    val historicalMaxCells = remember(snapshot.readings, d.editingId, d.integerCount, d.decimalCount) {
        snapshot.readings
            .asSequence()
            .filter { it.id != d.editingId }
            .maxByOrNull { it.readingMilli }
            ?.let { ReadingDraft.cellsFrom(Units.formatReading(it.readingMilli), d.integerCount, d.decimalCount) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (d.editingId != null) "编辑记录" else "确认抄表数据")
                        if (batchCount > 1) {
                            Text(
                                "第 ${index + 1} / $batchCount 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.discardSession()
                        onCancel()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (batchCount > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.goTo(index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("上一张") }
                        OutlinedButton(
                            onClick = { vm.goTo(index + 1) },
                            enabled = index < batchCount - 1,
                            modifier = Modifier.weight(1f),
                        ) { Text("下一张") }
                    }
                }
                Button(
                    onClick = {
                        vm.saveAll { _, skipped ->
                            if (skipped == 0) onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = batchCount > 1 || (parsed != null && parsed > 0),
                ) {
                    Text(
                        when {
                            d.editingId != null -> "保存修改"
                            batchCount > 1 -> "确认并保存全部 $batchCount 张"
                            else -> "确认入库"
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (d.photoPath != null) {
                item {
                    Box {
                        AsyncImage(
                            model = File(d.photoPath),
                            contentDescription = "燃气表照片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { showZoom = true },
                            contentScale = ContentScale.Fit,
                        )
                        Row(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .clickable { showZoom = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("放大看表盘", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "表数读数",
                    subtitle = "识别只是辅助：对着表盘逐位敲进去最可靠",
                ) {
                    if (parsed != null) {
                        Text(
                            Units.formatReading(parsed) + " m³",
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            "还没有读数",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    MeterDigitInput(
                        cells = d.cells,
                        integerCount = d.integerCount,
                        decimalCount = d.decimalCount,
                        historicalMaxCells = historicalMaxCells,
                        onCellsChange = { v -> vm.updateCurrent { it.copy(cells = v, error = null) } },
                    )
                    if (!d.manual) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = { vm.aiRecognizeCurrent() },
                                label = { Text(if (d.aiBusy) "AI 识别中…" else "AI 帮我认", fontSize = 11.sp) },
                            )
                        }
                        if (d.ocrBusy || d.aiBusy) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (d.aiBusy) "AI 正在看图…" else "正在识别表数…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        topCandidate?.takeIf { digitMismatch }?.let { candidate ->
                            Spacer(Modifier.height(8.dp))
                            NoticeCard("WARN", "识别到 ${candidate.digits.length} 位数字", "这块表应该是 ${d.totalDigits} 位。下面给了几种切分，选最接近的再逐位订正。")
                        }
                        if (d.ocrCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("识别候选（点一下填入，再核对）", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                d.ocrCandidates.take(6).forEach { c ->
                                    AssistChip(
                                        onClick = { vm.updateCurrent { it.copy(cells = ReadingDraft.cellsFrom(c.readingText, it.integerCount, it.decimalCount), error = null) } },
                                        label = { Text(c.readingText, fontFamily = FontFamily.Monospace) },
                                    )
                                }
                            }
                        } else if (!d.ocrBusy && !d.aiBusy && d.photoPath != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("没有识别到可信读数：点「放大看表盘」逐位输入，或配置 AI 兜底识别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!d.ocrRaw.isNullOrBlank()) {
                            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "隐藏原始识别文字" else "查看原始识别文字") }
                            if (showRaw) {
                                Text(d.ocrRaw ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (prevReading != null) {
                item {
                    SectionCard(title = "与上一条记录对比") {
                        Text(
                            "上一条：${Dates.formatDateTime(prevReading.epochMillis)} " +
                                "${Units.formatReading(prevReading.readingMilli)} m³",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (parsed != null && parsed > 0) {
                            val diff = parsed - prevReading.readingMilli
                            Text(
                                if (diff >= 0) "本次比上次多 ${Units.formatVolume(Units.milliToM3(diff))} m³"
                                else "本次比上次少 ${Units.formatVolume(Units.milliToM3(-diff))} m³",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (diff >= 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (decrease) {
                item {
                    NoticeCard(
                        "WARN",
                        "读数小于上一条记录",
                        "如果确实换了表或表具清零，可以继续保存；否则请检查是否录错。",
                    )
                }
            }
            if (d.error != null) {
                item { NoticeCard("WARN", "提示", d.error ?: "") }
            }

            item {
                SectionCard(
                    title = if (d.manual) "记录时间" else "拍摄信息",
                    subtitle = if (d.manual) "手工录入：日期与时间可自行选择" else "自动读取自照片 EXIF，可修改",
                ) {
                    ClickRow(if (d.manual) "日期" else "拍摄日期", Dates.formatDate(d.epochMillis)) {
                        showDatePicker = true
                    }
                    ClickRow(if (d.manual) "时间" else "拍摄时间", Dates.formatTime(d.epochMillis)) {
                        showTimePicker = true
                    }
                    if (!d.manual) {
                        ClickRow(
                            "拍摄地点",
                            d.locationName
                                ?: d.latitude?.let { lat -> d.longitude?.let { lon -> "%.4f, %.4f".format(lat, lon) } }
                                ?: "无（使用家庭位置或手填气温）",
                        ) { }
                    } else {
                        ClickRow("位置（默认）", d.locationName ?: "未设置家庭位置") { }
                    }
                }
            }

            item {
                SectionCard(
                    title = "当日气温区间",
                    subtitle = "记录地点当天的最高/最低气温",
                    action = {
                        IconButton(onClick = { vm.refreshWeatherCurrent() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新获取")
                        }
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d.weatherBusy) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            DailyTemp(Dates.dateOf(d.epochMillis), d.tempMinC, d.tempMaxC).label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(10.dp))
                        if (d.weatherStatus == "NO_LOCATION" || d.weatherStatus == "FAILED") {
                            Icon(
                                Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (d.weatherStatus) {
                            "OK" -> "已按 App 里设置的位置查询历史天气（只发送经纬度与日期）"
                            "NO_LOCATION" -> "还没有位置：请在设置里填家庭位置，或手动填写气温"
                            "FAILED" -> "联网获取失败，可重试或手动填写"
                            "MANUAL" -> "已关闭自动获取，当前为手动填写"
                            else -> "尚未获取"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showTempDialog = true }) { Text("手动填写气温") }
                }
            }

            item {
                SectionCard(title = "备注") {
                    OutlinedTextField(
                        value = d.note,
                        onValueChange = { v -> vm.updateCurrent { it.copy(note = v) } },
                        label = { Text("例如：换了新表 / 外出旅行一周") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }

    if (showZoom && d.photoPath != null) {
        PhotoZoomDialog(path = d.photoPath) { showZoom = false }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = d.epochMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val time = Dates.dateTimeOf(d.epochMillis).toLocalTime()
                        vm.updateCurrent {
                            it.copy(epochMillis = date.atTime(time).atZone(Dates.zone).toInstant().toEpochMilli())
                        }
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val time = Dates.dateTimeOf(d.epochMillis).toLocalTime()
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = Dates.dateOf(d.epochMillis)
                    vm.updateCurrent {
                        it.copy(
                            epochMillis = date.atTime(LocalTime.of(state.hour, state.minute))
                                .atZone(Dates.zone).toInstant().toEpochMilli(),
                        )
                    }
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = state) },
        )
    }

    if (showTempDialog) {
        var minText by remember { mutableStateOf(d.tempMinC?.toString() ?: "") }
        var maxText by remember { mutableStateOf(d.tempMaxC?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showTempDialog = false },
            title = { Text("手动填写当日气温") },
            text = {
                Column {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it },
                        label = { Text("最低温 ℃") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("最高温 ℃") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateCurrent {
                        it.copy(
                            tempMinC = Units.parseDoubleOrNull(minText),
                            tempMaxC = Units.parseDoubleOrNull(maxText),
                            weatherStatus = "MANUAL",
                        )
                    }
                    showTempDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTempDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ClickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
