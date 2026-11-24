package com.hididi.gaslog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hididi.gaslog.domain.CostEngine
import com.hididi.gaslog.domain.DayNightCalculator
import com.hididi.gaslog.domain.DayNightUsage
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.DailyTemp
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.ui.components.EmptyHint
import com.hididi.gaslog.ui.components.Exporter
import com.hididi.gaslog.ui.components.KeyValueRow
import com.hididi.gaslog.ui.components.CombinedChartSeries
import com.hididi.gaslog.ui.components.MetricTile
import com.hididi.gaslog.ui.components.NoticeCard
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.components.TwoColumnMetrics
import com.hididi.gaslog.ui.components.UsageTemperatureChart
import com.hididi.gaslog.ui.vm.GasLogViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private enum class StatPreset(val label: String) {
    WEEK("本周"), MONTH("本月"), LAST_MONTH("上月"), LAST_7("近7天"), LAST_30("近30天"), CUSTOM("自定义")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: GasLogViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val today = Dates.today()
    var preset by remember { mutableStateOf(StatPreset.MONTH) }
    var customFrom by remember { mutableStateOf(today.minusDays(29)) }
    var customTo by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val (from, to) = when (preset) {
        StatPreset.WEEK -> today.with(java.time.DayOfWeek.MONDAY) to today
        StatPreset.MONTH -> YearMonth.from(today).atDay(1) to today
        StatPreset.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        StatPreset.LAST_7 -> today.minusDays(6) to today
        StatPreset.LAST_30 -> today.minusDays(29) to today
        StatPreset.CUSTOM -> customFrom to customTo
    }

    val summary = remember(snapshot.readings, from, to) {
        UsageCalculator.summarize(snapshot.readings, from, to)
    }
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(from, to) { vm.ensureSunTimes(from, to) }
    val dayNight: DayNightUsage = remember(snapshot.readings, from, to, dataVersion) {
        DayNightCalculator.compute(
            points = snapshot.readings,
            from = from,
            to = to,
            sunProvider = { date -> vm.sunTimesFor(date) },
        )
    }
    val prevRange = remember(from, to) { UsageCalculator.previousPeriod(from, to) }
    val prevSummary = remember(snapshot.readings, prevRange) {
        UsageCalculator.summarize(snapshot.readings, prevRange.first, prevRange.second)
    }
    val stateAtStart = remember(snapshot, from) {
        com.hididi.gaslog.domain.BillingReplay.replay(
            snapshot.payments.maxByOrNull { it.paidAtMillis },
            snapshot.tiers,
            snapshot.readings,
            Dates.epochOf(from, 0, 0),
        ).state
    }
    val quote = remember(snapshot.tiers, stateAtStart, summary.volumeM3) {
        CostEngine.quote(stateAtStart, snapshot.tiers, summary.volumeM3)
    }
    val days = summary.dayList
    val tempValues = remember(days) {
        days.map { d ->
            when {
                d.tempMinC != null && d.tempMaxC != null -> (d.tempMinC!! + d.tempMaxC!!) / 2
                d.tempMaxC != null -> d.tempMaxC
                else -> null
            }
        }
    }
    val tempLabels = remember(days) { days.map { "%d/%d".format(Locale.US, it.date.monthValue, it.date.dayOfMonth) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计分析") },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val csv = buildCsv(dayList = days)
                            Exporter.shareText(
                                context,
                                "用气统计_${Dates.formatDate(from)}_${Dates.formatDate(to)}.csv",
                                csv,
                            )
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("导出")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPreset.entries.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = {
                                preset = p
                                if (p == StatPreset.CUSTOM) picking = "from"
                            },
                            label = { Text(p.label) },
                        )
                    }
                }
            }
            item {
                Text(
                    "统计区间：${Dates.formatDate(from)} ~ ${Dates.formatDate(to)}（${summary.days} 天）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionCard(title = "区间汇总") {
                    TwoColumnMetrics(
                        left = {
                            MetricTile(
                                label = "用气量",
                                value = "${Units.formatVolume(summary.volumeM3)} m³",
                                sub = "有效天数 ${summary.daysWithData} 天，均摊估算 ${summary.estimatedDays} 天",
                            )
                        },
                        right = {
                            MetricTile(
                                label = "费用估算",
                                value = Units.formatMoney(quote.totalCents),
                                sub = CostEngine.totalPriceRange(snapshot.tiers),
                                accent = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("日均用气", "${Units.formatVolumeShort(summary.avgPerDayM3)} m³/天")
                    val delta = summary.volumeM3 - prevSummary.volumeM3
                    val pct = if (prevSummary.volumeM3 > 0.001) delta / prevSummary.volumeM3 * 100 else null
                    KeyValueRow(
                        "对比上一周期",
                        (if (delta >= 0) "+" else "") + Units.formatVolumeShort(delta) + " m³" +
                            (pct?.let { "（${String.format(Locale.US, "%+.1f", it)}%）" } ?: ""),
                        valueColor = if (delta > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    if (summary.firstReadingMilli != null && summary.lastReadingMilli != null) {
                        KeyValueRow(
                            "区间首末读数",
                            "${Units.formatReading(summary.firstReadingMilli!!)} → ${Units.formatReading(summary.lastReadingMilli!!)}",
                        )
                    }
                    if (quote.lines.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text("跨档明细", style = MaterialTheme.typography.labelMedium)
                        quote.lines.forEach { line ->
                            KeyValueRow(
                                "第 ${line.tierNo} 档 ${Units.formatVolume(line.volumeM3)} m³ × ${Units.formatPrice(line.priceCentsPerM3)}",
                                Units.formatMoney(line.costCents),
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(title = "当日气温与用气", subtitle = "柱图为每日用气，折线为当日平均气温（℃）") {
                    UsageTemperatureChart(
                        series = CombinedChartSeries(
                            usage = days.map { it.volumeM3 },
                            temperatures = tempValues,
                            labels = tempLabels,
                        ),
                        markers = days.map { it.exact },
                    )
                    val corr = UsageCalculator.pearson(
                        days.filter { it.tempMaxC != null && it.volumeM3 > 0 }
                            .map { (it.tempMinC!! + it.tempMaxC!!) / 2 },
                        days.filter { it.tempMaxC != null && it.volumeM3 > 0 }.map { it.volumeM3 },
                    )
                    if (corr != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${if (corr < 0) "气温越低用气越多" else "气温与用气同向变化"}（相关系数 ${String.format(Locale.US, "%.2f", corr)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionCard(
                    title = "日间 / 夜间用气",
                    subtitle = "按当地日出日落切分：日落至次日日出计为夜间",
                ) {
                    TwoColumnMetrics(
                        left = {
                            MetricTile(
                                label = "白天",
                                value = "${Units.formatVolume(dayNight.dayM3)} m³",
                                sub = if (dayNight.totalM3 > 0) {
                                    "占 ${String.format(Locale.US, "%.0f", (1 - dayNight.nightShare) * 100)}%"
                                } else "—",
                            )
                        },
                        right = {
                            MetricTile(
                                label = "夜间",
                                value = "${Units.formatVolume(dayNight.nightM3)} m³",
                                sub = if (dayNight.totalM3 > 0) {
                                    "占 ${String.format(Locale.US, "%.0f", dayNight.nightShare * 100)}%"
                                } else "—",
                                accent = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    val fraction = if (dayNight.totalM3 > 0) {
                        (dayNight.nightM3 / dayNight.totalM3).toFloat()
                    } else 0f
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    ) {
                        Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.001f)))
                        Box(
                            Modifier
                                .weight(fraction.coerceAtLeast(0.001f))
                                .height(12.dp)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "算法：把每两次读数之间的用量按小时均分，再按当地日出/日落把每个小时归到白天或夜间，" +
                            "所以昼夜比例反映的是时段长短，而不是把 24 小时当成一回事。" +
                            "如果同一天白天和晚上各拍一次，拆分就是精确值。" +
                            "（本次用到 ${dayNight.sunDataDays} 天的日出日落数据，缺数据的日期按 07:00-19:00 兜底）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = "逐日明细") {
                    if (days.isEmpty()) {
                        EmptyHint("该区间没有读数")
                    } else {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("日期", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall)
                            Text("用量(m³)", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("气温（℃）", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text("性质", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall)
                        }
                        days.takeLast(80).reversed().forEach { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${d.date.monthValue}/${d.date.dayOfMonth}",
                                    Modifier.weight(1.1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    Units.formatVolume(d.volumeM3),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    DailyTemp(d.date, d.tempMinC, d.tempMaxC).valueLabel,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    if (d.readingCount == 0) "—" else if (d.exact) "精确" else "估算",
                                    Modifier.weight(0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                NoticeCard(
                    "INFO",
                    "用量是怎么算出来的",
                    "同一天两次拍照 → 当天精确用量；跨天两次读数 → 按间隔天数均摊。所以日均越准，越建议每天固定时间拍一次。",
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (picking != null) {
        val initial = if (picking == "from") customFrom else customTo
        val state = rememberDatePickerState(initialSelectedDateMillis = Dates.epochOf(initial))
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (picking == "from") {
                            customFrom = date
                            if (customTo < customFrom) customTo = customFrom
                        } else {
                            customTo = date
                            if (customFrom > customTo) customFrom = customTo
                        }
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        ) { DatePicker(state = state) }
    }
}

private fun buildCsv(dayList: List<com.hididi.gaslog.domain.DayUsage>): String {
    val sb = StringBuilder("日期,用气量(m³),最低温(℃),最高温(℃),读数次数,是否精确\n")
    dayList.forEach { d ->
        sb.append(Dates.formatDate(d.date)).append(',')
            .append(Units.formatVolume(d.volumeM3)).append(',')
            .append(d.tempMinC?.toString() ?: "").append(',')
            .append(d.tempMaxC?.toString() ?: "").append(',')
            .append(d.readingCount).append(',')
            .append(if (d.exact) "精确" else "估算").append('\n')
    }
    return sb.toString()
}
