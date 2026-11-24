package com.hididi.gaslog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun firstNonZeroDigitIndex(cells: String, total: Int): Int =
    cells.take(total).indexOfFirst { it in '1'..'9' }.takeIf { it >= 0 } ?: 0

fun initialFocusDigitIndex(cells: String, total: Int, historicalMaxCells: String?): Int =
    firstNonZeroDigitIndex(historicalMaxCells ?: cells, total)

/**
 * 抄表输入器：按燃气表"黑字整数 + 红字小数"的样子逐位输入。
 *
 * - 点某一位可以选中它，直接改这一位；
 * - 输入一个数字后自动跳到下一位，可以连续输入；
 * - ⌫ 回退一位，清空从头再来。
 * 识别不准时，看一眼表盘、按格子敲进去即可。
 */
@Composable
fun MeterDigitInput(
    cells: String,
    integerCount: Int,
    decimalCount: Int,
    historicalMaxCells: String? = null,
    onCellsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = integerCount + decimalCount
    // 按表盘样式显示：整数位左侧补 0，小数位右侧补 0（未输入时保持占位符）
    val display = if (cells.isEmpty()) {
        ""
    } else {
        cells.take(integerCount).padStart(integerCount, '0') +
            cells.drop(integerCount).padEnd(decimalCount, '0')
    }
    var focused by remember(total) { mutableStateOf<Int?>(null) }
    // 首次输入时以历史最大读数定位；无历史记录时沿用当前输入的第一个非零位。
    val activeIndex = focused ?: initialFocusDigitIndex(cells, total, historicalMaxCells)
    val cellHeight = 56

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(total) { index ->
                val isDecimal = index >= integerCount
                if (index == integerCount) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.width(6.dp).height(cellHeight.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(5.dp)
                                .height(5.dp)
                                .background(Color(0xFFE23C32), RoundedCornerShape(3.dp)),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                DigitCell(
                    text = display.getOrNull(index)?.toString() ?: "",
                    selected = index == activeIndex,
                    modifier = Modifier.weight(1f),
                    background = if (isDecimal) Color(0xFFB3261E) else Color(0xFF1C1C1E),
                    contentColor = if (isDecimal) Color(0xFFFDF6F4) else Color(0xFFF7F7F2),
                    height = cellHeight,
                    onClick = { focused = index },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "黑格=整数（m³），红格=小数（0.001 m³）。点某一位可直接改这一位，输入后自动跳到下一位。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        DigitKeypad(
            onDigit = { d ->
                val idx = activeIndex.coerceIn(0, total - 1)
                val chars = CharArray(total) { i -> cells.getOrNull(i) ?: ' ' }
                chars[idx] = d.first()
                // 去掉尾部空格
                val newCells = String(chars).trimEnd()
                onCellsChange(newCells)
                focused = (idx + 1).coerceAtMost(total - 1)
            },
            onBackspace = {
                val idx = activeIndex
                if (idx <= 0 && cells.isEmpty()) {
                    onCellsChange("")
                    focused = 0
                } else {
                    val chars = CharArray(total) { i -> cells.getOrNull(i) ?: ' ' }
                    chars[idx] = ' '
                    onCellsChange(String(chars).trimEnd())
                    focused = (idx - 1).coerceAtLeast(0)
                }
            },
            onClear = {
                onCellsChange("")
                focused = 0
            },
        )
    }
}

@Composable
private fun DigitCell(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    background: Color,
    contentColor: Color,
    height: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(height.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.ifEmpty { "·" },
            color = contentColor.copy(alpha = if (text.isEmpty()) 0.35f else 1f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DigitKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("清空", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(2.2f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (key.all { it.isDigit() }) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                            .clickable {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "清空" -> onClear()
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (key.all { it.isDigit() }) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
