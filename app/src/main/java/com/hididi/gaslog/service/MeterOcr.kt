package com.hididi.gaslog.service

import android.graphics.Bitmap
import android.graphics.Rect
import com.hididi.gaslog.domain.Units
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * 一位候选读数。机械表是"黑色整数 + 红色小数"结构，红字检测用来确定整数位与小数位的边界，
 * 比"默认取最后三位"可靠得多。最终读数一律由用户在确认页核对后才入库。
 */
data class OcrCandidate(
    val digits: String,
    val readingText: String,
    val readingMilli: Long,
    val score: Double,
    val hasRedTail: Boolean,
    val note: String = "",
)

data class OcrOutcome(
    val candidates: List<OcrCandidate>,
    val rawText: String,
    val error: String? = null,
)

private data class ElementInfo(
    val text: String,
    val digits: String,
    val red: Boolean,
    val box: Rect?,
    val confidence: Float,
)

private data class LineInfo(
    val text: String,
    val box: Rect?,
    val maxDigitHeight: Int,
    val digitCount: Int,
)

private data class Scan(
    val lines: List<String>,
    val lineInfos: List<LineInfo>,
    val elements: List<ElementInfo>,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
)

/**
 * 燃气表读数识别（离线 ML Kit 文字识别）。
 *
 * 真实照片里读数窗往往只占画面很小一块，直接整图识别容易漏一位、错一位。因此分两步：
 *  1. 整图粗扫，定位"读数带"——画面里最高、最密集的那组数字（读数窗的字最大）；
 *  2. 把读数带裁出来放大 3 倍再识别一次，这一轮通常能读全 8 位数字；
 * 然后按"非红字=整数位、红字=小数位"分组给出候选；没有红字时退化为"末 3 位为小数"。
 * 所有候选都会列在确认页，用户看到的读数才是最终值——识别只是替你少打几个字。
 */
object MeterOcr {

    /** 调试用：把内部决策（读数带位置、各行字号）吐出来，测试代码会接管 */
    internal var debugLogger: ((String) -> Unit)? = null

    private fun dbg(msg: String) {
        debugLogger?.invoke(msg)
    }

    suspend fun recognizeRobust(bitmap: Bitmap, decimalDigits: Int = 3): OcrOutcome {
        val attempts = ArrayList<Pair<List<OcrCandidate>, Double>>()
        val rawTexts = ArrayList<String>()
        var error: String? = null

        val first = scan(bitmap)
        if (first == null) {
            return OcrOutcome(emptyList(), "", "文字识别初始化失败")
        }
        rawTexts += first.lines
        val band = findReadingBand(first)
        attempts += candidatesFrom(first, decimalDigits, band) to 0.0

        // 裁出读数带放大后二次识别
        if (band != null) {
            val zoomed = cropAndScale(bitmap, band, 3.0f)
            if (zoomed != null) {
                val second = scan(zoomed)
                if (second != null) {
                    rawTexts += second.lines
                    // 放大后的结果更可信：给加成，让"8 位数字"的候选排前面
                    attempts += candidatesFrom(second, decimalDigits, findReadingBand(second)) to 0.45
                }
            }
        }

        // 仍然读不全时，再试 ±90°（有的照片是横着拍的）
        if (attempts.maxOfOrNull { (list, _) -> list.maxOfOrNull { it.digits.length } ?: 0 } ?: 0 < 7) {
            listOf(90f, 270f).forEach { deg ->
                val rotated = rotate(bitmap, deg) ?: return@forEach
                val s = scan(rotated) ?: return@forEach
                rawTexts += s.lines
                attempts += candidatesFrom(s, decimalDigits, findReadingBand(s)) to 0.1
                val b = findReadingBand(s)
                if (b != null) {
                    val z = cropAndScale(rotated, b, 3.0f)
                    if (z != null) {
                        scan(z)?.let { sz ->
                            rawTexts += sz.lines
                            attempts += candidatesFrom(sz, decimalDigits, findReadingBand(sz)) to 0.5
                        }
                    }
                }
            }
        }

        val merged = LinkedHashMap<String, OcrCandidate>()
        attempts.forEach { (list, bonus) ->
            list.forEach { c ->
                val boosted = c.copy(score = c.score + bonus)
                val key = c.readingMilli.toString()
                val existing = merged[key]
                if (existing == null || existing.score < boosted.score) merged[key] = boosted
            }
        }
        val sorted = merged.values.sortedWith(
            compareByDescending<OcrCandidate> { it.score }
                .thenByDescending { it.digits.length },
        ).take(6)
        return OcrOutcome(
            candidates = sorted,
            rawText = rawTexts.filter { it.isNotBlank() }.joinToString(" / "),
            error = if (sorted.isEmpty()) error else null,
        )
    }

    // ---------------- ML Kit 扫描 ----------------

    private suspend fun scan(bitmap: Bitmap): Scan? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val scan = runCatching { toScan(text, bitmap) }.getOrNull()
                    recognizer.close()
                    if (cont.isActive) cont.resume(scan)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    private fun toScan(text: Text, bitmap: Bitmap): Scan {
        val lines = ArrayList<String>()
        val elements = ArrayList<ElementInfo>()
        val lineInfos = ArrayList<LineInfo>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (line.text.isNotBlank()) lines += line.text
                for (e in line.elements) {
                    val digits = e.text.filter { it.isDigit() }
                    if (digits.isEmpty()) continue
                    val box = e.boundingBox
                    elements += ElementInfo(
                        text = e.text,
                        digits = digits,
                        red = box != null && isReddish(bitmap, box),
                        box = box,
                        confidence = runCatching { e.confidence }.getOrDefault(-1f),
                    )
                }
                val boxes = line.elements.mapNotNull { it.boundingBox }
                if (boxes.isNotEmpty()) {
                    val union = Rect(
                        boxes.minOf { it.left },
                        boxes.minOf { it.top },
                        boxes.maxOf { it.right },
                        boxes.maxOf { it.bottom },
                    )
                    lineInfos += LineInfo(
                        text = line.text,
                        box = union,
                        maxDigitHeight = boxes.maxOf { it.height() },
                        digitCount = line.text.count { it.isDigit() },
                    )
                }
            }
        }
        return Scan(lines, lineInfos, elements, bitmap.width, bitmap.height)
    }

    /**
     * 读数带：画面里"数字最高"的那一行就是表盘读数窗（表壳上的印刷编号字更小）。
     * 以这行的高和宽为基准外扩，得到比原行更宽一点的裁切区，避免首位/末位被切掉。
     */
    private fun findReadingBand(scan: Scan): Rect? {
        val lines = scan.lineInfos.filter { it.box != null && it.digitCount >= 3 }
        if (lines.isEmpty()) return null
        val maxH = lines.maxOf { it.maxDigitHeight }.coerceAtLeast(1)
        // 优先数字高度接近最大值、且数字个数多的行
        val best = lines
            .filter { it.maxDigitHeight >= maxH * 0.75 }
            .maxByOrNull { it.digitCount * 1.0 + it.maxDigitHeight / 20.0 }
            ?: return null
        scan.lineInfos.take(14).forEach { li ->
            dbg("line h=${li.maxDigitHeight} digits=${li.digitCount} box=${li.box} text=${li.text.take(24)}")
        }
        val box = best.box ?: return null
        val h = box.height()
        // 外扩：读数窗首位/末位可能没被识别，按行高外扩约半个数字宽即可，避免把整片印刷文字带进来
        val extra = (h * 0.55f).toInt().coerceAtMost((scan.bitmapWidth * 0.12f).toInt())
        var left = (box.left - extra).coerceIn(0, scan.bitmapWidth - 2)
        var right = (box.right + extra).coerceIn(left + 2, scan.bitmapWidth)
        var top = (box.top - h * 0.45f).toInt().coerceIn(0, scan.bitmapHeight - 2)
        var bottom = (box.bottom + h * 0.45f).toInt().coerceIn(top + 2, scan.bitmapHeight)
        val band = Rect(left, top, right, bottom)
        dbg("band chosen=${band} fromText=${best.text.take(24)} h=${best.maxDigitHeight}")
        return band
    }

    private fun cropAndScale(bitmap: Bitmap, band: Rect, factor: Float): Bitmap? = runCatching {
        val crop = Bitmap.createBitmap(bitmap, band.left, band.top, band.width(), band.height())
        val scale = minOf(factor, 2400f / maxOf(crop.width, crop.height))
        if (scale <= 1.0f) crop else Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).toInt().coerceAtLeast(2),
            (crop.height * scale).toInt().coerceAtLeast(2),
            true,
        )
    }.getOrNull()

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap? = runCatching {
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }.getOrNull()

    // ---------------- 候选生成 ----------------

    private fun candidatesFrom(scan: Scan, decimalDigits: Int, band: Rect?): List<OcrCandidate> {
        val out = ArrayList<OcrCandidate>()
        val cx = scan.bitmapWidth / 2.0
        val cy = scan.bitmapHeight / 2.0
        val maxDist = kotlin.math.hypot(cx, cy)

        fun lengthPrior(len: Int): Double = when (len) {
            7, 8, 9 -> 0.35
            6, 10 -> 0.10
            else -> 0.0
        }

        fun add(digits: String, milli: Long, score: Double, red: Boolean, note: String) {
            if (digits.length < 3 || digits.length > 11) return
            if (milli <= 0L) return
            out += OcrCandidate(
                digits = digits,
                readingText = Units.formatReading(milli),
                readingMilli = milli,
                score = score + lengthPrior(digits.length),
                hasRedTail = red,
                note = note,
            )
        }

        fun positionScore(info: ElementInfo): Double {
            val box = info.box ?: return 0.0
            val dist = kotlin.math.hypot(box.exactCenterX().toDouble() - cx, box.exactCenterY().toDouble() - cy) / maxDist
            var s = 0.25 * (1.0 - dist.coerceIn(0.0, 1.0))
            s += ((box.height().toDouble() / scan.bitmapHeight) * 6.0).coerceAtMost(0.3)
            if (info.confidence > 0f) s += info.confidence * 0.15
            return s
        }

        // 1) 单个元素：红黑混合时拆不开，按末 3 位取小数。
        //    只保留"这一轮里字最大的那批数字"，表壳印刷编号字更小，直接被滤掉。
        val tallThreshold = (scan.elements.filter { it.box != null }.maxOfOrNull { it.box!!.height() } ?: 0) * 0.45
        scan.elements.filter { e ->
            val box = e.box
            box != null && box.height() >= tallThreshold &&
                (band == null || band.contains(box.centerX(), box.centerY()))
        }.forEach { e ->
            if (e.digits.length in (decimalDigits + 1)..10) {
                val intPart = e.digits.dropLast(decimalDigits)
                val decPart = e.digits.takeLast(decimalDigits)
                combine(intPart, decPart)?.let { milli ->
                    var score = 0.55 + positionScore(e)
                    val box = e.box
                    if (box != null && band != null) {
                        score += if (band.contains(box.centerX(), box.centerY())) 0.55 else -0.25
                    }
                    if (e.text.any { it.isLetter() }) score -= 0.15
                    val hFill = if (box != null && band != null && band.height() > 0) {
                        box.height().toDouble() / band.height()
                    } else 0.0
                    if (hFill > 0.45) score += 0.15
                    add(e.digits, milli, score, e.red, "整串数字")
                }
            }
        }

        // 2) 红黑分组（同一行里 非红=整数 / 红=小数）
        val rows = scan.elements.filter { it.box != null }.groupBy { it.box!!.centerY() / 24 }
        rows.values.forEach { row ->
            val whites = row.filter { !it.red }.sortedBy { it.box!!.left }
            val reds = row.filter { it.red }.sortedBy { it.box!!.left }
            if (whites.isEmpty() || reds.isEmpty()) return@forEach
            val intDigits = whites.joinToString("") { it.digits }
            val decDigits = reds.joinToString("") { it.digits }
            if (intDigits.length in 1..7 && decDigits.length in 1..4) {
                combine(intDigits, decDigits)?.let { milli ->
                    var score = 0.55 + row.sumOf { positionScore(it) } / row.size
                    if (band != null) {
                        val inside = row.count { it.box != null && band.contains(it.box!!.centerX(), it.box!!.centerY()) }
                        score += if (inside.toDouble() / row.size >= 0.5) 0.55 else -0.25
                    }
                    add(
                        intDigits + decDigits, milli, score, true,
                        "黑字 $intDigits + 红字 $decDigits",
                    )
                }
            }
        }

        // 3) 相邻数字元素按 x 拼成一整串（有时每个数字是独立元素）
        scan.elements.filter { it.box != null && it.digits.length <= 2 }
            .sortedBy { it.box!!.left }
            .let { small ->
                if (small.size >= 5) {
                    val all = small.joinToString("") { it.digits }
                    if (all.length in (decimalDigits + 3)..10) {
                        val intPart = all.dropLast(decimalDigits)
                        val decPart = all.takeLast(decimalDigits)
                        combine(intPart, decPart)?.let { milli ->
                            add(all, milli, 0.4, small.any { it.red }, "逐位拼接")
                        }
                    }
                }
            }

        // 4) 同一串数字给出 2/3/4 位小数的多种切分：漏一位时另一种切分常常正好是对的
        scan.elements.filter { it.box != null }.forEach { e ->
            val d = e.digits
            if (d.length in 5..10) {
                listOf(2, 3, 4).forEach { dec ->
                    if (d.length - dec >= 3) {
                        val intPart = d.dropLast(dec)
                        val decPart = d.takeLast(dec)
                        combine(intPart, decPart)?.let { milli ->
                            val bonus = if (dec == decimalDigits) 0.0 else -0.28
                            add(d, milli, 0.30 + positionScore(e) + bonus, e.red, "末 ${dec} 位为小数")
                        }
                    }
                }
            }
        }

        // 5) "01150 224" 这种带空格分组的行：右侧 2~3 位视为小数
        scan.lines.forEach { text ->
            val trimmed = text.trim()
            val numericOnly = trimmed.filter { !it.isWhitespace() }
                .all { it.isDigit() || it in ".,:：|" }
            if (trimmed.length in 5..24 && numericOnly) {
                val groups = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (groups.size >= 2) {
                    val left = groups.dropLast(1).joinToString("").filter { it.isDigit() }
                    val right = groups.last().filter { it.isDigit() }
                    if (left.length in 2..7 && right.length in 2..4) {
                        combine(left, right)?.let { milli ->
                            add(left + right, milli, 0.5, false, "空格分组")
                        }
                    }
                }
            }
        }

        return out
    }

    /** 整数位 + 小数位 → 千分之一立方米，允许 1~4 位小数 */
    private fun combine(intDigits: String, decDigits: String): Long? {
        if (intDigits.isEmpty() && decDigits.isEmpty()) return null
        val intValue = intDigits.toLongOrNull() ?: return null
        if (intValue > 9_999_999) return null
        val decValue = decDigits.toLongOrNull() ?: 0L
        val scale = when (decDigits.length) {
            0 -> 0.0
            1 -> decValue * 100.0
            2 -> decValue * 10.0
            3 -> decValue.toDouble()
            4 -> decValue / 10.0
            else -> return null
        }
        return intValue * 1000L + scale.roundToLong()
    }

    /** 采样文字框内的像素，判断是否为红色数字/红底白字（机械表红色小数位） */
    private fun isReddish(bitmap: Bitmap, box: Rect): Boolean {
        val l = box.left.coerceIn(0, bitmap.width - 1)
        val t = box.top.coerceIn(0, bitmap.height - 1)
        val r = box.right.coerceIn(1, bitmap.width)
        val b = box.bottom.coerceIn(1, bitmap.height)
        if (r <= l || b <= t) return false
        var count = 0
        var redCount = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val stepX = ((r - l) / 24).coerceAtLeast(1)
        val stepY = ((b - t) / 24).coerceAtLeast(1)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val p = runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                val max = maxOf(rr, gg, bb)
                val min = minOf(rr, gg, bb)
                val brightness = (rr + gg + bb) / 3
                if (brightness in 25..235) {
                    count++
                    if (max - min > 30 && rr > gg * 1.15 && rr > bb * 1.15) redCount++
                    sumR += rr
                    sumG += gg
                    sumB += bb
                }
                x += stepX
            }
            y += stepY
        }
        if (count < 8) return false
        // 红底白字时整体色偏明显；红字黑底时只有部分像素偏红
        if (redCount.toDouble() / count > 0.18) return true
        val r0 = sumR / count
        val g0 = sumG / count
        val b0 = sumB / count
        return r0 > 80 && r0 > g0 * 1.12 && r0 > b0 * 1.12
    }
}