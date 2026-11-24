package com.hididi.gaslog.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** A single aligned time series for the combined usage and temperature chart. */
data class CombinedChartSeries(
    val usage: List<Double>,
    val temperatures: List<Double?>,
    val labels: List<String>,
) {
    init {
        require(usage.size == temperatures.size && usage.size == labels.size) {
            "Usage, temperatures, and labels must have the same length"
        }
    }

    val size: Int get() = labels.size
}

/** 用气柱状图与平均气温折线图，共用同一条日期横轴。 */
@Composable
fun UsageTemperatureChart(
    series: CombinedChartSeries,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF1565C0),
    lineColor: Color = Color(0xFFE65100),
    markers: List<Boolean> = emptyList(),
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    if (series.size == 0) {
        Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("该区间没有数据", style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant)
        }
        return
    }

    val temperaturePoints = series.temperatures.filterNotNull()
    val density = LocalDensity.current
    val axisTextSize = with(density) { 11.sp.toPx() }
    val valueTextSize = with(density) { 13.sp.toPx() }
    val bottomSpace = with(density) { 11.sp.toPx() + 10.dp.toPx() }
    val topSpace = with(density) { 13.sp.toPx() + 8.dp.toPx() }
    val valueGap = with(density) { 4.dp.toPx() }
    Canvas(modifier.fillMaxWidth().height(210.dp).padding(top = 8.dp)) {
        val labelPaint = android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = axisTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val usagePaint = android.graphics.Paint().apply {
            color = barColor.toArgb()
            textSize = valueTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val temperaturePaint = android.graphics.Paint().apply {
            color = lineColor.toArgb()
            textSize = valueTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val chartHeight = size.height - bottomSpace - topSpace
        val maxUsage = series.usage.maxOrNull()?.coerceAtLeast(0.001) ?: 0.001
        val temperatureMin = temperaturePoints.minOrNull() ?: 0.0
        val temperatureMax = temperaturePoints.maxOrNull() ?: 0.0
        val temperatureSpan = (temperatureMax - temperatureMin).takeIf { it > 0.5 } ?: 1.0
        val slot = size.width / series.size
        val barWidth = (slot * 0.55f).coerceAtLeast(2f)
        val labelEvery = max(1, series.size / 6)

        drawLine(
            color = onSurfaceVariant.copy(alpha = 0.35f),
            start = Offset(0f, size.height - bottomSpace),
            end = Offset(size.width, size.height - bottomSpace),
            strokeWidth = 2f,
        )

        series.usage.forEachIndexed { index, value ->
            val height = chartHeight * (value / maxUsage).toFloat().coerceIn(0f, 1f)
            val x = slot * index + (slot - barWidth) / 2f
            val y = size.height - bottomSpace - height
            drawRoundRect(
                color = barColor.copy(alpha = if (markers.getOrElse(index) { false }) 0.95f else 0.75f),
                topLeft = Offset(x, y),
                size = Size(barWidth, height.coerceAtLeast(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3f),
            )
            if (value > 0 && (series.size <= 16 || value == maxUsage)) {
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "%.1f", value),
                    slot * index + slot / 2f,
                    (y - valueGap).coerceAtLeast(valueTextSize),
                    usagePaint,
                )
            }
        }

        if (temperaturePoints.isNotEmpty()) {
            val path = Path()
            var started = false
            series.temperatures.forEachIndexed { index, value ->
                if (value == null) return@forEachIndexed
                val x = slot * index + slot / 2f
                val y = topSpace + chartHeight * (1f - ((value - temperatureMin) / temperatureSpan).toFloat())
                if (started) path.lineTo(x, y) else {
                    path.moveTo(x, y)
                    started = true
                }
                drawCircle(lineColor, radius = 3f, center = Offset(x, y))
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "%.1f", value),
                    x,
                    (y - valueGap).coerceAtLeast(valueTextSize),
                    temperaturePaint,
                )
            }
            drawPath(path, color = lineColor, style = Stroke(width = 3f))
        }

        series.labels.forEachIndexed { index, label ->
            if (index % labelEvery == 0 || index == series.labels.lastIndex) {
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    slot * index + slot / 2f,
                    size.height - 6f,
                    labelPaint,
                )
            }
        }
    }
}

/** 用气量柱状图：按天显示，自动省略密集标签 */
@Composable
fun UsageBarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    markerColor: Color = MaterialTheme.colorScheme.tertiary,
    markers: List<Boolean> = emptyList(),
) {
    val surfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("该区间没有数据", style = MaterialTheme.typography.bodySmall, color = surfaceVariant)
        }
        return
    }
    val maxValue = values.maxOrNull()?.coerceAtLeast(0.001) ?: 0.001
    Canvas(modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
        val labelPaint = android.graphics.Paint().apply {
            color = surfaceVariant.toArgb()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val valuePaint = android.graphics.Paint().apply {
            color = barColor.toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val bottomSpace = 26f
        val topSpace = 18f
        val chartHeight = size.height - bottomSpace - topSpace
        val slot = size.width / values.size
        val barWidth = (slot * 0.55f).coerceAtLeast(2f)

        // 基线
        drawLine(
            color = surfaceVariant.copy(alpha = 0.35f),
            start = Offset(0f, size.height - bottomSpace),
            end = Offset(size.width, size.height - bottomSpace),
            strokeWidth = 2f,
        )
        values.forEachIndexed { i, v ->
            val ratio = (v / maxValue).toFloat().coerceIn(0f, 1f)
            val h = chartHeight * ratio
            val x = slot * i + (slot - barWidth) / 2f
            val y = size.height - bottomSpace - h
            drawRoundRect(
                color = barColor.copy(alpha = if (markers.getOrElse(i) { false }) 0.95f else 0.75f),
                topLeft = Offset(x, y),
                size = Size(barWidth, h.coerceAtLeast(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3f),
            )
            val labelEvery = max(1, values.size / 6)
            if (i % labelEvery == 0 || i == values.lastIndex) {
                labels.getOrNull(i)?.let { label ->
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        slot * i + slot / 2f,
                        size.height - 6f,
                        labelPaint,
                    )
                }
            }
            if (v > 0 && (values.size <= 16 || v == maxValue)) {
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "%.1f", v),
                    slot * i + slot / 2f,
                    (y - 4f).coerceAtLeast(12f),
                    valuePaint,
                )
            }
        }
    }
}

/** 折线图：用于气温走势等 */
@Composable
fun SimpleLineChart(
    values: List<Double?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val points = values.filterNotNull()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(140.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("数据不足", style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    Canvas(modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
        val minV = points.min()
        val maxV = points.max()
        val span = (maxV - minV).takeIf { it > 0.5 } ?: 1.0
        val paint = android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val valuePaint = android.graphics.Paint().apply {
            color = lineColor.toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val bottomSpace = 26f
        val topSpace = 22f
        val chartHeight = size.height - bottomSpace - topSpace
        val slot = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = slot * i
            val y = topSpace + chartHeight * (1f - ((v - minV) / span).toFloat())
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            drawCircle(lineColor, radius = 3f, center = Offset(x, y))
            drawContext.canvas.nativeCanvas.drawText(
                String.format(java.util.Locale.US, "%.1f", v),
                x,
                (y - 6f).coerceAtLeast(12f),
                valuePaint,
            )
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
        val labelEvery = max(1, values.size / 5)
        labels.forEachIndexed { i, label ->
            if (i % labelEvery == 0 || i == labels.lastIndex) {
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    slot * i,
                    size.height - 6f,
                    paint,
                )
            }
        }
    }
}
