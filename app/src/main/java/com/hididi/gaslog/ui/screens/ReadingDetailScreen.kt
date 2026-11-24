package com.hididi.gaslog.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hididi.gaslog.domain.DailyTemp
import com.hididi.gaslog.domain.Dates
import com.hididi.gaslog.domain.DayUsage
import com.hididi.gaslog.domain.HeatingDegreeUsageEstimator
import com.hididi.gaslog.domain.ReadingPoint
import com.hididi.gaslog.domain.Units
import com.hididi.gaslog.domain.UsageCalculator
import com.hididi.gaslog.ui.components.KeyValueRow
import com.hididi.gaslog.ui.components.PhotoZoomDialog
import com.hididi.gaslog.ui.components.SectionCard
import com.hididi.gaslog.ui.vm.GasLogViewModel
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class DetailSwipeTarget {
    Previous,
    Current,
    Next,
}

internal data class DetailNavigationState(val activeIndex: Int, val offsetX: Float)

/** Commits the index before settling, so the next drag always starts from the visible card. */
internal fun detailNavigationState(
    activeIndex: Int,
    dragOffsetX: Float,
    width: Int,
    direction: Int,
): DetailNavigationState = DetailNavigationState(
    activeIndex = activeIndex - direction,
    offsetX = dragOffsetX - direction * width,
)

internal fun detailSwipeTarget(
    dragOffsetX: Float,
    contentWidth: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
): DetailSwipeTarget {
    val threshold = contentWidth * 0.15f
    return when {
        dragOffsetX > threshold && hasPrevious -> DetailSwipeTarget.Previous
        dragOffsetX < -threshold && hasNext -> DetailSwipeTarget.Next
        else -> DetailSwipeTarget.Current
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingDetailScreen(
    vm: GasLogViewModel,
    readingId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var currentId by remember(readingId) { mutableStateOf(readingId) }
    var contentWidth by remember { mutableIntStateOf(0) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sorted = remember(snapshot.readings) { snapshot.readings.sortedBy { it.epochMillis } }
    val reading = sorted.firstOrNull { it.id == currentId }
    val idx = sorted.indexOfFirst { it.id == currentId }
    val visibleDates = remember(sorted, idx) {
        listOfNotNull(
            sorted.getOrNull(idx - 1),
            sorted.getOrNull(idx),
            sorted.getOrNull(idx + 1),
        ).map { Dates.dateOf(it.epochMillis) }.distinct().sorted()
    }
    val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
    if (visibleDates.isNotEmpty()) {
        val from = visibleDates.first()
        val to = visibleDates.last()
        LaunchedEffect(from, to) { vm.ensureSunTimes(from, to) }
        val usageEstimate = remember(snapshot.readings, from, to, dataVersion) {
            HeatingDegreeUsageEstimator.estimate(
                points = snapshot.readings,
                from = from,
                to = to,
                sunProvider = { date -> vm.sunTimesFor(date) },
            )
        }
        val dayUsage = remember(snapshot.readings, visibleDates, usageEstimate) {
            UsageCalculator.dailyUsage(snapshot.readings, from, to)
                .associate { it.date to it.copy(volumeM3 = usageEstimate.dailyM3(it.date)) }
        }
    suspend fun animateOffset(target: Float) {
        Animatable(dragOffsetX).animateTo(
            target,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        ) { dragOffsetX = value }
    }
    fun springBack() {
        settleJob?.cancel()
        settleJob = scope.launch {
            animateOffset(0f)
        }
    }
    fun navigateTo(id: Long, direction: Int) {
        val width = contentWidth.toFloat()
        if (width <= 0f) {
            currentId = id
            return
        }
        settleJob?.cancel()
        val state = detailNavigationState(idx, dragOffsetX, contentWidth, direction)
        currentId = id
        dragOffsetX = state.offsetX
        settleJob = scope.launch {
            animateOffset(0f)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("记录详情")
                        Text(
                            "${idx + 1} / ${sorted.size} · 左右滑动可切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { onEdit(currentId) }) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                },
            )
        },
    ) { padding ->
        if (reading == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("记录不存在或已删除")
            }
            return@Scaffold
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { contentWidth = it.width }
                .pointerInput(currentId, contentWidth, sorted.map { it.id }) {
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob?.cancel() },
                        onDragCancel = { springBack() },
                        onDragEnd = {
                            when (detailSwipeTarget(
                                dragOffsetX = dragOffsetX,
                                contentWidth = contentWidth,
                                hasPrevious = idx > 0,
                                hasNext = idx < sorted.lastIndex,
                            )) {
                                DetailSwipeTarget.Previous ->
                                    navigateTo(sorted[idx - 1].id, 1)
                                DetailSwipeTarget.Next ->
                                    navigateTo(sorted[idx + 1].id, -1)
                                DetailSwipeTarget.Current -> springBack()
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val isDraggingPastStart = amount > 0f && idx == 0
                            val isDraggingPastEnd = amount < 0f && idx == sorted.lastIndex
                            dragOffsetX += if (isDraggingPastStart || isDraggingPastEnd) amount * 0.35f else amount
                        },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                sorted.getOrNull(idx - 1)?.let { previous ->
                    DetailPage(
                        modifier = Modifier.offset { IntOffset(dragOffsetX.roundToInt() - contentWidth, 0) },
                        reading = previous,
                        sorted = sorted,
                        index = idx - 1,
                        dayUsage = dayUsage[Dates.dateOf(previous.epochMillis)],
                        onShowZoom = { showZoom = true },
                        onRefreshWeather = { vm.refreshWeatherForDate(Dates.dateOf(previous.epochMillis)) },
                        onPrevious = {},
                        onNext = {},
                        navigationEnabled = false,
                    )
                }
                sorted.getOrNull(idx + 1)?.let { next ->
                    DetailPage(
                        modifier = Modifier.offset { IntOffset(dragOffsetX.roundToInt() + contentWidth, 0) },
                        reading = next,
                        sorted = sorted,
                        index = idx + 1,
                        dayUsage = dayUsage[Dates.dateOf(next.epochMillis)],
                        onShowZoom = { showZoom = true },
                        onRefreshWeather = { vm.refreshWeatherForDate(Dates.dateOf(next.epochMillis)) },
                        onPrevious = {},
                        onNext = {},
                        navigationEnabled = false,
                    )
                }
                DetailPage(
                    modifier = Modifier.offset { IntOffset(dragOffsetX.roundToInt(), 0) },
                    reading = reading,
                    sorted = sorted,
                    index = idx,
                    dayUsage = dayUsage[Dates.dateOf(reading.epochMillis)],
                    onShowZoom = { showZoom = true },
                    onRefreshWeather = { vm.refreshWeatherForDate(Dates.dateOf(reading.epochMillis)) },
                    onPrevious = { sorted.getOrNull(idx - 1)?.let { navigateTo(it.id, 1) } },
                    onNext = { sorted.getOrNull(idx + 1)?.let { navigateTo(it.id, -1) } },
                )
            }
        }
        }
    }

    if (showZoom && reading?.photoPath != null) {
        PhotoZoomDialog(path = reading!!.photoPath!!) { showZoom = false }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("照片与读数会一起删除，删除后无法恢复（除非重新导入）。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.deleteReading(currentId)
                    onBack()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DetailPage(
    modifier: Modifier = Modifier,
    reading: ReadingPoint,
    sorted: List<ReadingPoint>,
    index: Int,
    dayUsage: DayUsage?,
    onShowZoom: () -> Unit,
    onRefreshWeather: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    navigationEnabled: Boolean = true,
) {
    val prev = sorted.getOrNull(index - 1)
    val next = sorted.getOrNull(index + 1)
    LazyColumn(
        modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (reading.photoPath != null && File(reading.photoPath).exists()) {
            item {
                Box {
                    AsyncImage(
                        model = File(reading.photoPath),
                        contentDescription = "燃气表照片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit,
                    )
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        TextButton(onClick = onShowZoom) {
                            Text("放大看表盘", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "读数") {
                Text(
                    "${Units.formatReading(reading.readingMilli)} m³",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${Dates.formatDateTime(reading.epochMillis)}（${Dates.weekdayLabel(Dates.dateOf(reading.epochMillis))}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                KeyValueRow(
                    "来源",
                    when (reading.source) {
                        "CAMERA" -> "相机拍摄"
                        "GALLERY" -> "相册导入"
                        else -> "手工录入"
                    },
                )
                KeyValueRow(
                    "当日气温",
                    DailyTemp(Dates.dateOf(reading.epochMillis), reading.tempMinC, reading.tempMaxC).label,
                )
                if (reading.note != null) KeyValueRow("备注", reading.note)
            }
        }
        item {
            SectionCard(title = "用气量推算") {
                if (prev != null) {
                    val diff = reading.readingMilli - prev.readingMilli
                    KeyValueRow(
                        "距上一条记录",
                        if (diff >= 0) "+${Units.formatVolume(Units.milliToM3(diff))} m³"
                        else "表数回退 ${Units.formatVolume(Units.milliToM3(-diff))} m³",
                    )
                    KeyValueRow("上一条时间", Dates.formatDateTime(prev.epochMillis))
                }
                if (next != null) {
                    val diff = next.readingMilli - reading.readingMilli
                    KeyValueRow(
                        "到下一条记录",
                        if (diff >= 0) "+${Units.formatVolume(Units.milliToM3(diff))} m³" else "表数回退",
                    )
                    KeyValueRow("下一条时间", Dates.formatDateTime(next.epochMillis))
                }
                if (dayUsage != null) {
                    KeyValueRow(
                        "当天推算用量",
                        "${Units.formatVolume(dayUsage.volumeM3)} m³" +
                            if (dayUsage.exact) "（精确）" else "（估算）",
                    )
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshWeather) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新获取气温", modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "重新获取当天全部记录的气温",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrevious, enabled = navigationEnabled && prev != null) { Text("← 上一条") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNext, enabled = navigationEnabled && next != null) { Text("下一条 →") }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
