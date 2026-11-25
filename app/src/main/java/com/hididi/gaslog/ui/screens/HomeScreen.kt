package com.hididi.gaslog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hididi.gaslog.domain.CostEngine
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.DayUsage
import com.hididi.gaslog.domain.HeatingDegreeUsageEstimator
import com.hididi.gaslog.domain.Insights
import com.hididi.gaslog.domain.ReadingPoint
import com.hididi.gaslog.domain.TierState
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.ui.components.KeyValueRow
import com.hididi.gaslog.ui.components.MetricTile
import com.hididi.gaslog.ui.components.NoticeCard
import com.hididi.gaslog.ui.components.Pill
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.components.TwoColumnMetrics
import com.hididi.gaslog.ui.vm.GasLogViewModel
import java.io.File

@Composable
fun HomeScreen(
    vm: GasLogViewModel,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenBilling: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val today = Dates.today()
    val latest = snapshot.readings.maxByOrNull { it.epochMillis }

    val monthStart = today.withDayOfMonth(1)
    val monthEpoch = Dates.epochOf(monthStart, 0, 0)
    val monthSummary = remember(snapshot.readings, monthStart) {
        UsageCalculator.summarize(snapshot.readings, monthStart, today)
    }
    val recentFrom = today.minusDays(59)
    val dailyRecentBase = remember(snapshot.readings, today) {
        UsageCalculator.dailyUsage(snapshot.readings, recentFrom, today)
    }
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    LaunchedEffect(recentFrom, today) { vm.ensureSunTimes(recentFrom, today) }
    val recentEstimate = remember(snapshot.readings, recentFrom, today, dataVersion) {
        HeatingDegreeUsageEstimator.estimate(
            points = snapshot.readings,
            from = recentFrom,
            to = today,
            sunProvider = { date -> vm.sunTimesFor(date) },
        )
    }
    val dailyRecent = remember(dailyRecentBase, recentEstimate) {
        dailyRecentBase.map { day -> day.copy(volumeM3 = recentEstimate.dailyM3(day.date)) }
    }
    val todayUsage = dailyRecent.firstOrNull { it.date == today }
    val monthDays = remember(dailyRecent, monthStart) { dailyRecent.filter { it.date >= monthStart } }
    val monthVolume = monthDays.sumOf { it.volumeM3 }
    val state = remember(snapshot, today) { vm.currentTierState(snapshot) }
    val stateAtMonthStart = remember(snapshot, monthStart, today) {
        com.hididi.gaslog.domain.BillingReplay.replay(
            snapshot.payments.maxByOrNull { it.paidAtMillis },
            snapshot.tiers,
            snapshot.readings,
            monthEpoch,
        ).state
    }
    val monthCostCents = remember(snapshot.tiers, stateAtMonthStart, monthVolume) {
        if (snapshot.tiers.isEmpty() || monthVolume <= 0.0) 0L
        else CostEngine.quote(stateAtMonthStart, snapshot.tiers, monthVolume).totalCents
    }
    val projection = remember(snapshot, today, state) {
        Insights.projectMonth(dailyRecent, today, state, snapshot.tiers)
    }
    val insights = remember(snapshot, dailyRecent, today) {
        Insights.detect(snapshot.readings, dailyRecent, state, snapshot.tiers, today)
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "燃气用量",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${Dates.formatDate(today)} ${Dates.weekdayLabel(today)} · 家庭用气账本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCapture,
                    modifier = Modifier.weight(1.4f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("拍照抄表")
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("相册", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onManual,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("手填", maxLines = 1)
                }
            }
        }

        if (snapshot.readings.isEmpty()) {
            item {
                SectionCard(title = "开始使用") {
                    Text(
                        "1. 每天用「拍照抄表」拍下燃气表，或从相册导入照片；\n" +
                            "2. 系统会离线识别表数，并读取照片的拍摄时间、拍摄地点；\n" +
                            "3. 确认后自动补上当地当天的气温区间，写入账本；\n" +
                            "4. 在「费用」里填一次交费读数、所在档位和本档剩余气量，就能推算每天/每月的用气量和费用。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            SectionCard(title = "今日", subtitle = "用量由两次读数差推算，标「估」的按气温、日出日落与采暖度估算") {
                TwoColumnMetrics(
                    left = {
                        MetricTile(
                            label = "今日用气",
                            value = "${Units.formatVolume(todayUsage?.volumeM3 ?: 0.0)} m³",
                            sub = when {
                                todayUsage == null -> "暂无可计算的读数差"
                                todayUsage.exact -> "当天多次读数，精确"
                                else -> "按气温、日出日落与采暖度估算"
                            },
                            estimated = todayUsage?.exact == false,
                        )
                    },
                    right = {
                        MetricTile(
                            label = "最新读数",
                            value = latest?.let { Units.formatReading(it.readingMilli) } ?: "—",
                            sub = latest?.let { "${Dates.formatDateTime(it.epochMillis)}" } ?: "还没有记录",
                            accent = MaterialTheme.colorScheme.secondary,
                        )
                    },
                )
            }
        }

        item {
            SectionCard(
                title = "本月",
                subtitle = Dates.monthLabel(today),
                action = {
                    Pill("预估", MaterialTheme.colorScheme.tertiary, filled = true)
                },
            ) {
                TwoColumnMetrics(
                    left = {
                        MetricTile(
                            label = "本月已用",
                            value = "${Units.formatVolume(monthVolume)} m³",
                            sub = "日均 ${Units.formatVolumeShort(if (monthSummary.days > 0) monthVolume / monthSummary.days else 0.0)} m³",
                        )
                    },
                    right = {
                        MetricTile(
                            label = "本月费用估算",
                            value = Units.formatMoney(monthCostCents),
                            sub = CostEngine.totalPriceRange(snapshot.tiers),
                            accent = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                )
                if (projection != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "预测口径：已用 ${Units.formatVolumeShort(projection.monthToDateM3)} m³ ＋ " +
                            "近 ${projection.basisDays} 天日均 ${Units.formatVolumeShort(projection.avgDailyM3)} m³ × " +
                            "剩余 ${projection.remainingDays} 天 ＝ 全月预计 " +
                            "${Units.formatVolumeShort(projection.projectedMonthM3)} m³；" +
                            "剩余天数预计再花 ${Units.formatMoney(projection.projectedMonthCostCents)}。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            TierStatusCard(
                state = state,
                stateAtMonthStart = stateAtMonthStart,
                tiers = snapshot.tiers,
                monthVolume = monthVolume,
                onOpenBilling = onOpenBilling,
            )
        }

        items(insights.take(3)) { insight ->
            NoticeCard(insight.level.name, insight.title, insight.detail)
        }

        item {
            SectionCard(
                title = "最近记录",
                action = {
                    TextButton(onClick = onOpenRecords) { Text("全部") }
                },
            ) {
                val recent = snapshot.readings.sortedByDescending { it.epochMillis }.take(3)
                if (recent.isEmpty()) {
                    Text("还没有抄表记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    recent.forEach { r ->
                        ReadingRow(r, onClick = { onOpenDetail(r.id) })
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TierStatusCard(
    state: TierState?,
    stateAtMonthStart: TierState?,
    tiers: List<com.hididi.gaslog.domain.TierDef>,
    monthVolume: Double,
    onOpenBilling: () -> Unit,
) {
    SectionCard(
        title = "阶梯气价",
        subtitle = CostEngine.totalPriceRange(tiers),
        action = {
            TextButton(onClick = onOpenBilling) { Text("设置") }
        },
    ) {
        if (state == null) {
            TextButton(onClick = onOpenBilling) { Text("先设置阶梯气价") }
            return@SectionCard
        }
        val def = tiers.firstOrNull { it.tierNo == state.tierNo }
        val prevCap = tiers.filter { it.tierNo < state.tierNo }.maxOfOrNull { it.capM3 ?: 0.0 } ?: 0.0
        val capacity = def?.capM3?.let { (it - prevCap).coerceAtLeast(0.0) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetricTile(
                label = "当前档位",
                value = if (state.unlimited) "第 ${state.tierNo} 档" else "第 ${state.tierNo} 档",
                sub = def?.let { "${Units.formatPrice(it.priceCentsPerM3)} 元/m³" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "本档剩余",
                value = if (state.unlimited) "不限量" else "${Units.formatVolume(state.remainingM3)} m³",
                sub = if (capacity != null) "档位总量 ${Units.formatVolume(capacity)} m³" else "最后一档",
                accent = if (!state.unlimited && state.remainingM3 < 10) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        val fraction = if (capacity != null && capacity > 0) {
            ((capacity - state.remainingM3) / capacity).toFloat().coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "档位位置由「交费记录」推算：交费时的读数与剩余气量 + 之后的真实表读数差。" +
                if (stateAtMonthStart != null) "" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        KeyValueRow("本月用气量", "${Units.formatVolume(monthVolume)} m³")
    }
}

@Composable
fun ReadingRow(
    reading: ReadingPoint,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val path = reading.photoPath
            if (path != null && File(path).exists()) {
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${Units.formatReading(reading.readingMilli)} m³",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Dates.formatDateTime(reading.epochMillis) +
                    (reading.source.let { s -> if (s == "MANUAL") " · 手工" else "" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reading.tempMinC != null || reading.tempMaxC != null) {
            Pill(
                com.hididi.gaslog.domain.DailyTemp(Dates.dateOf(reading.epochMillis), reading.tempMinC, reading.tempMaxC).label,
                MaterialTheme.colorScheme.tertiary,
                filled = true,
            )
        }
        trailing?.invoke()
    }
}
