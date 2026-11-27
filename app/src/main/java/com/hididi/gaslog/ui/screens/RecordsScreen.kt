package com.hididi.gaslog.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.DayUsage
import com.hididi.gaslog.domain.HeatingDegreeUsageEstimator
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.ui.components.EmptyHint
import com.hididi.gaslog.ui.vm.GasLogViewModel
import java.time.LocalDate
import java.time.YearMonth

private enum class RecordFilter(val label: String) {
    THIS_MONTH("本月"), LAST_MONTH("上月"), ALL("全部")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    vm: GasLogViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(RecordFilter.THIS_MONTH) }
    val today = Dates.today()
    val month = YearMonth.from(today)

    val fromTo = when (filter) {
        RecordFilter.THIS_MONTH -> month.atDay(1) to month.atEndOfMonth()
        RecordFilter.LAST_MONTH -> month.minusMonths(1).atDay(1) to month.minusMonths(1).atEndOfMonth()
        RecordFilter.ALL -> {
            val first = snapshot.readings.minByOrNull { it.epochMillis }?.let { Dates.dateOf(it.epochMillis) }
                ?: today
            first to today
        }
    }

    val filtered = snapshot.readings.filter {
        val d = Dates.dateOf(it.epochMillis)
        d >= fromTo.first && d <= fromTo.second
    }.sortedByDescending { it.epochMillis }

    val dayUsageBase = remember(snapshot.readings, fromTo) {
        UsageCalculator.dailyUsage(snapshot.readings, fromTo.first, fromTo.second)
    }
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    LaunchedEffect(fromTo) { vm.ensureSunTimes(fromTo.first, fromTo.second) }
    val usageEstimate = remember(snapshot.readings, fromTo, dataVersion) {
        HeatingDegreeUsageEstimator.estimate(
            points = snapshot.readings,
            from = fromTo.first,
            to = fromTo.second,
            sunProvider = { date -> vm.sunTimesFor(date) },
            dailyTemp = { date -> vm.dailyTempFor(date) },
        )
    }
    val dayUsage = remember(dayUsageBase, usageEstimate) {
        dayUsageBase.map { day -> day.copy(volumeM3 = usageEstimate.dailyM3(day.date)) }.associateBy { it.date }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抄表记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { EmptyHint("这个区间还没有记录，去拍一张燃气表吧") }
            }
            val grouped = filtered.groupBy { Dates.dateOf(it.epochMillis) }
            grouped.toSortedMap(compareByDescending { it }).forEach { (date, list) ->
                item(key = "day-$date") {
                    DayHeader(date, dayUsage[date], list.size)
                }
                items(list.sortedByDescending { it.epochMillis }, key = { it.id }) { r ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp)) {
                            ReadingRow(r, onClick = { onOpenDetail(r.id) })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, usage: DayUsage?, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${Dates.formatDate(date)} ${Dates.weekdayLabel(date)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "· $count 次读数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (usage != null && usage.volumeM3 > 0) {
            Text(
                "${Units.formatVolume(usage.volumeM3)} m³" + if (usage.exact) "" else "(估)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
