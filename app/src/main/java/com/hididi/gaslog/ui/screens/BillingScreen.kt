package com.hididi.gaslog.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hididi.gaslog.domain.BillingReplay
import com.hididi.gaslog.domain.CostEngine
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.Insights
import com.hididi.gaslog.domain.HeatingDegreeUsageEstimator
import com.hididi.gaslog.domain.PaymentInfo
import com.hididi.gaslog.domain.TierDef
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.ui.components.KeyValueRow
import com.hididi.gaslog.ui.components.MetricTile
import com.hididi.gaslog.ui.components.NoticeCard
import com.hididi.gaslog.ui.components.Pill
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.components.TwoColumnMetrics
import com.hididi.gaslog.ui.vm.GasLogViewModel
import java.time.Instant
import java.time.YearMonth

private data class TierRow(val capText: String, val priceText: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(vm: GasLogViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val today = Dates.today()

    // ---------- 阶梯气价编辑 ----------
    var rows by remember(snapshot.tiers) {
        mutableStateOf(
            snapshot.tiers.map { TierRow(it.capM3?.let { c -> Units.formatVolumeShort(c) } ?: "", Units.formatPrice(it.priceCentsPerM3)) },
        )
    }
    var tierError by remember { mutableStateOf<String?>(null) }

    // ---------- 交费记录 ----------
    var paymentDialog by remember { mutableStateOf<PaymentInfo?>(null) }
    var newPayment by remember { mutableStateOf(false) }

    // ---------- 核算区间 ----------
    var from by remember { mutableStateOf(YearMonth.from(today).atDay(1)) }
    var to by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf<String?>(null) }

    val state = remember(snapshot) { vm.currentTierState(snapshot) }
    val replay = remember(snapshot) { vm.replayResult(snapshot) }
    val stateAtStart = remember(snapshot, from) {
        BillingReplay.replay(
            snapshot.payments.maxByOrNull { it.paidAtMillis },
            snapshot.tiers,
            snapshot.readings,
            Dates.epochOf(from, 0, 0),
        ).state
    }
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(from, to) { vm.ensureDailyTemps(from, to) }
    val rangeSummary = remember(snapshot.readings, from, to, dataVersion) {
        UsageCalculator.summarize(snapshot.readings, from, to) { d -> vm.dailyTempFor(d) }
    }
    val recentFrom = today.minusDays(29)
    val dailyBase = remember(snapshot.readings, today) {
        UsageCalculator.dailyUsage(snapshot.readings, recentFrom, today)
    }
    val estimateFrom = minOf(from, recentFrom)
    val estimateTo = maxOf(to, today)
    LaunchedEffect(estimateFrom, estimateTo) { vm.ensureSunTimes(estimateFrom, estimateTo) }
    val usageEstimate = remember(snapshot.readings, estimateFrom, estimateTo, dataVersion) {
        HeatingDegreeUsageEstimator.estimate(
            points = snapshot.readings,
            from = estimateFrom,
            to = estimateTo,
            sunProvider = { date -> vm.sunTimesFor(date) },
            dailyTemp = { date -> vm.dailyTempFor(date) },
        )
    }
    val rangeDays = remember(rangeSummary.dayList, usageEstimate) {
        rangeSummary.dayList.map { day -> day.copy(volumeM3 = usageEstimate.dailyM3(day.date)) }
    }
    val rangeVolume = rangeDays.sumOf { it.volumeM3 }
    val quote = remember(snapshot.tiers, stateAtStart, rangeVolume) {
        CostEngine.quote(stateAtStart, snapshot.tiers, rangeVolume)
    }
    val daily = remember(dailyBase, usageEstimate) {
        dailyBase.map { day -> day.copy(volumeM3 = usageEstimate.dailyM3(day.date)) }
    }
    val projection = remember(snapshot, daily, state) {
        Insights.projectMonth(daily, today, state, snapshot.tiers)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("费用与阶梯气价") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(
                    title = "阶梯气价",
                    subtitle = "按年累计用气量分档，最后一档上限留空表示不限量",
                ) {
                    rows.forEachIndexed { i, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}档", Modifier.width(38.dp), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = row.capText,
                                onValueChange = { v ->
                                    rows = rows.toMutableList().also { l ->
                                        l[i] = row.copy(capText = v.filter { c -> c.isDigit() || c == '.' })
                                    }
                                },
                                label = { Text("累计上限 m³") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = row.priceText,
                                onValueChange = { v ->
                                    rows = rows.toMutableList().also { l ->
                                        l[i] = row.copy(priceText = v.filter { c -> c.isDigit() || c == '.' })
                                    }
                                },
                                label = { Text("单价 元/m³") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            if (rows.size > 1) {
                                IconButton(onClick = { rows = rows.toMutableList().also { it.removeAt(i) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除该档")
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val lastCap = rows.lastOrNull()?.capText?.toDoubleOrNull()
                            rows = rows.dropLast(1).map { it.copy(capText = it.capText) } +
                                if (lastCap != null) TierRow("", "") else TierRow("", "")
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加档位")
                        }
                        Button(onClick = {
                            val parsed = ArrayList<TierDef>()
                            var error: String? = null
                            rows.forEachIndexed { i, r ->
                                val price = Units.parseMoneyToCents(r.priceText)
                                if (price == null) error = "第 ${i + 1} 档单价未填写"
                                val cap = if (r.capText.isBlank()) null else r.capText.toDoubleOrNull()
                                if (r.capText.isNotBlank() && cap == null) error = "第 ${i + 1} 档上限格式有误"
                                if (cap != null && i > 0) {
                                    val prevCap = rows[i - 1].capText.toDoubleOrNull() ?: 0.0
                                    if (cap <= prevCap) error = "第 ${i + 1} 档上限必须大于上一档"
                                }
                                parsed += TierDef(i + 1, cap, price ?: 0)
                            }
                            if (parsed.dropLast(1).any { it.capM3 == null }) {
                                error = "只有最后一档可以留空（不限量）"
                            }
                            tierError = error
                            if (error == null) vm.saveTiers(parsed)
                        }) { Text("保存阶梯气价") }
                    }
                    tierError?.let {
                        Spacer(Modifier.height(6.dp))
                        NoticeCard("WARN", "请检查", it)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "示例：${CostEngine.totalPriceRange(snapshot.tiers)}。请按当地燃气公司公示的阶梯价格与年度上限修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(
                    title = "交费记录",
                    subtitle = "填一次交费时的读数/档位/剩余气量，之后就能自动推算档位位置",
                    action = {
                        TextButton(onClick = {
                            newPayment = true
                        }) { Text("添加") }
                    },
                ) {
                    val payments = snapshot.payments.sortedByDescending { it.paidAtMillis }
                    if (payments.isEmpty()) {
                        Text(
                            "还没有交费记录。填写后，App 会从这条记录出发，用真实表读数推算现在还剩多少低价气。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        payments.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${Dates.formatDate(p.paidAtMillis)} · 读数 ${Units.formatReading(p.readingMilli)} m³",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        "第 ${p.tierNo} 档" +
                                            (p.remainingM3?.let { " · 本档剩 ${Units.formatVolume(it)} m³" } ?: "") +
                                            (p.amountCents?.let { " · 交费 ${Units.formatMoney(it)}" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { paymentDialog = p }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                                }
                                IconButton(onClick = { vm.deletePayment(p.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                    if (state != null) {
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow(
                            "推算当前档位",
                            if (state.unlimited) "第 ${state.tierNo} 档（不限量）"
                            else "第 ${state.tierNo} 档 · 本档剩 ${Units.formatVolume(state.remainingM3)} m³",
                        )
                        if (replay.volumeSincePaymentM3 > 0) {
                            KeyValueRow(
                                "自上次交费已用",
                                "${Units.formatVolume(replay.volumeSincePaymentM3)} m³ · ${Units.formatMoney(replay.costSincePaymentCents)}",
                            )
                        }
                        replay.anchorMissingReason?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                SectionCard(title = "区间费用核算", subtitle = "选区间 → 算用气量 → 按阶梯价折算费用") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) {
                            Text("起 ${Dates.formatDate(from)}")
                        }
                        OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) {
                            Text("止 ${Dates.formatDate(to)}")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TwoColumnMetrics(
                        left = {
                            MetricTile(
                                label = "区间用气量",
                                value = "${Units.formatVolume(rangeVolume)} m³",
                                sub = "${rangeSummary.days} 天，含估算 ${rangeSummary.estimatedDays} 天",
                            )
                        },
                        right = {
                            MetricTile(
                                label = "区间费用",
                                value = Units.formatMoney(quote.totalCents),
                                sub = "均价 ${Units.formatPrice((quote.avgPricePerM3Cents).toLong())} 元/m³",
                                accent = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                    if (quote.lines.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        quote.lines.forEach { line ->
                            KeyValueRow(
                                "第 ${line.tierNo} 档 ${Units.formatVolume(line.volumeM3)} m³ × ${Units.formatPrice(line.priceCentsPerM3)} 元",
                                Units.formatMoney(line.costCents),
                            )
                        }
                    }
                    quote.warnings.forEach {
                        Spacer(Modifier.height(6.dp))
                        NoticeCard("WARN", "注意", it)
                    }
                    if (quote.lines.isEmpty() && rangeVolume > 0) {
                        Spacer(Modifier.height(6.dp))
                        NoticeCard("WARN", "无法计价", "请先在「阶梯气价」里填写价格。")
                    }
                }
            }

            if (projection != null) {
                item {
                    SectionCard(title = "本月预测", subtitle = "基于近 ${projection.basisDays} 天的日均用量") {
                        TwoColumnMetrics(
                            left = {
                                MetricTile(
                                    label = "全月预计用气",
                                    value = "${Units.formatVolumeShort(projection.projectedMonthM3)} m³",
                                    sub = "已用 ${Units.formatVolumeShort(projection.monthToDateM3)} + 日均 ${Units.formatVolumeShort(projection.avgDailyM3)} × ${projection.remainingDays} 天",
                                )
                            },
                            right = {
                                MetricTile(
                                    label = "本月预计支出",
                                    value = Units.formatMoney(projection.projectedMonthCostCents),
                                    sub = "今天到月底新增支出",
                                    accent = MaterialTheme.colorScheme.tertiary,
                                )
                            },
                        )
                        projection.daysToNextTier?.let { days ->
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("约 $days 天后进入更高档位", MaterialTheme.colorScheme.error, filled = true)
                            }
                            Text(
                                "按当前日均用量，本档剩余气量将在约 $days 天后用完，之后单价会上涨，建议留意用气节奏。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        projection.annualUsedM3?.let { used ->
                            Spacer(Modifier.height(6.dp))
                            KeyValueRow("本计费年度已累计", "${Units.formatVolume(used)} m³")
                        }
                    }
                }
            }

            item {
                NoticeCard(
                    "INFO",
                    "费用口径说明",
                    "费用 = 从「区间起点时的阶梯位置」开始，按各档单价逐段累加；区间起点位置由最近一次交费记录 + 期间真实表读数推进得到。" +
                        "估算只影响用气量分摊，不改变单价。实际账单以燃气公司数据为准。",
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newPayment || paymentDialog != null) {
        val editing = paymentDialog
        PaymentDialog(
            initial = editing,
            tierCount = snapshot.tiers.size.coerceAtLeast(1),
            onDismiss = { newPayment = false; paymentDialog = null },
            onSave = { paidAt, readingMilli, amountCents, tierNo, remaining, note ->
                vm.savePayment(paidAt, readingMilli, amountCents, tierNo, remaining, note, editing?.id)
                newPayment = false
                paymentDialog = null
            },
        )
    }

    if (picking != null) {
        val initial = if (picking == "from") from else to
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = Dates.epochOf(initial))
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (picking == "from") {
                            from = date
                            if (to < from) to = from
                        } else {
                            to = date
                            if (from > to) from = to
                        }
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentDialog(
    initial: PaymentInfo?,
    tierCount: Int,
    onDismiss: () -> Unit,
    onSave: (Long, Long, Long?, Int, Double?, String?) -> Unit,
) {
    var dateText by remember { mutableStateOf(Dates.formatDate(initial?.paidAtMillis ?: System.currentTimeMillis())) }
    var epoch by remember { mutableStateOf(initial?.paidAtMillis ?: System.currentTimeMillis()) }
    var reading by remember { mutableStateOf(initial?.let { Units.formatReading(it.readingMilli) } ?: "") }
    var amount by remember { mutableStateOf(initial?.amountCents?.let { Units.formatMoneyPlain(it) } ?: "") }
    var tier by remember { mutableStateOf((initial?.tierNo ?: 1).toString()) }
    var remaining by remember { mutableStateOf(initial?.remainingM3?.let { Units.formatVolumeShort(it) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var showDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑交费记录" else "添加交费记录") },
        text = {
            Column {
                OutlinedButton(onClick = { showDate = true }) { Text("交费日期：$dateText") }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it },
                    label = { Text("交费时的表读数 (m³)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("交费金额（元，可不填）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(
                        value = tier,
                        onValueChange = { tier = it.filter { c -> c.isDigit() } },
                        label = { Text("当前档位") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        supportingText = { Text("共 $tierCount 档") },
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = remaining,
                        onValueChange = { remaining = it },
                        label = { Text("本档剩余 m³") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（选填）") },
                    singleLine = true,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val milli = Units.parseReading(reading)
                val tierNo = tier.toIntOrNull()
                if (milli == null || milli <= 0) {
                    error = "请填写交费时的表读数"
                } else if (tierNo == null || tierNo < 1 || tierNo > tierCount) {
                    error = "档位需在 1~$tierCount 之间"
                } else {
                    onSave(
                        epoch,
                        milli,
                        Units.parseMoneyToCents(amount),
                        tierNo,
                        Units.parseDoubleOrNull(remaining),
                        note.ifBlank { null },
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = epoch)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        epoch = millis
                        dateText = Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
}
