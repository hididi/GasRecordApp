package com.hididi.gaslog

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hididi.gaslog.service.MeterOcr
import com.hididi.gaslog.service.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * 用真实燃气表照片回归 OCR。
 *
 * 准备：把照片推到 /sdcard/Download/ocr/ 下（或 androidTest 资源目录），执行
 *   gradle connectedDebugAndroidTest
 * 结果在 logcat 里的标签 OcrBench：
 *   adb logcat -d -s OcrBench
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchTest {

    @Test
    fun runOcrOnRealPhotos() = runBlocking {
        val appContext = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val dirs = listOf(
            File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "ocr"),
        )
        val files = dirs.filter { it.isDirectory }
            .flatMap { it.listFiles()?.filter { f -> f.isFile && f.name.endsWith(".jpg", true) } ?: emptyList() }
            .sortedBy { it.name }
        android.util.Log.i("OcrBench", "found ${files.size} photos")
        MeterOcr.debugLogger = { msg -> android.util.Log.i("OcrBench", "  dbg $msg") }
        files.forEach { file ->
            val bitmap = PhotoStore.loadUprightBitmap(file, 1600)
            if (bitmap == null) {
                android.util.Log.i("OcrBench", "${file.name}: decode failed")
                return@forEach
            }
            val elapsed = measureTimeMillis {
                val outcome = MeterOcr.recognizeRobust(bitmap)
                val top = outcome.candidates.take(3).joinToString(" | ") {
                    "${it.readingText}(${String.format("%.2f", it.score)})${it.note}"
                }
                android.util.Log.i(
                    "OcrBench",
                    "${file.name} -> [$top] raw=${outcome.rawText.take(160)}",
                )
            }
            android.util.Log.i("OcrBench", "${file.name} took ${elapsed}ms")
        }
    }
}