package com.hididi.gaslog.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用支持视觉的大模型做"认不出来的兜底"。
 *
 * 走 OpenAI 兼容的 /chat/completions 接口（DeepSeek、通义、智谱、自建的都行），
 * 用户在设置里填 baseUrl / model / key。只在用户主动点「AI 识别」时才发请求，
 * 发送的是一张压缩到 1024px 的 JPEG，不包含其它任何数据。
 */
class AiVisionService(private val context: Context) {

    data class Config(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
    ) {
        val enabled: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    }

    suspend fun recognizeReading(
        bitmap: Bitmap,
        config: Config,
        integerCount: Int,
        decimalCount: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = scale(bitmap, 1024)
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val prompt = """
                这是一张家用燃气表的照片。表盘是滚轮式读数：黑色或白字部分是整数（m³），
                红色部分是小数。请只输出一个数字，格式为「%d 位整数.%d 位小数」，例如 1142.545。
                如果某一位正在滚动看不清，按更靠近正位的数字判断。
                只输出这个数字，不要输出任何其它文字。
            """.trimIndent().format(integerCount, decimalCount)

            val body = JSONObject().apply {
                put("model", config.model)
                put("temperature", 0)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                                    .put(
                                        JSONObject().apply {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                JSONObject().put("url", "data:image/jpeg;base64,$base64"),
                                            )
                                        },
                                    ),
                            )
                        },
                    ),
                )
            }

            val response = post(config, body.toString()) ?: error("网络请求失败，请检查网络与接口地址")
            val content = JSONObject(response)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: error("接口返回格式不认识：$response")
            val match = Regex("""\d{1,7}\.\d{1,4}""").find(content) ?: Regex("""\d{4,10}""").find(content)
                ?: error("AI 没有返回数字：$content")
            match.value
        }
    }

    private fun scale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun post(config: Config, body: String): String? = runCatching {
        val conn = (URL(config.baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) {
                error("HTTP $code: ${text?.take(200) ?: ""}")
            }
            text
        } finally {
            conn.disconnect()
        }
    }.getOrElse { throw it }
}