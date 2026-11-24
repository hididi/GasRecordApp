package com.hididi.gaslog.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PhotoExif(
    val takenAtMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val orientation: Int,
)

data class ImportedPhoto(
    val file: File,
    val exif: PhotoExif,
    val originalBytes: Long,
    val savedBytes: Long,
)

/**
 * 照片落地。
 *
 * 表照片只是为了核对读数与留档，没必要保存相机原图：导入时统一压到"长边 1600、JPEG 85"，
 * 单张通常 150~400KB（相机原图常见 3~6MB，日积月累差别很大）。EXIF 里的拍摄时间/位置
 * 在压缩前就读出来存进数据库，所以压缩后照片不带 EXIF 也不影响记账。
 */
object PhotoStore {

    private const val MAX_SIDE = 1600
    private const val QUALITY = 85

    fun photoDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "photos").apply { if (!exists()) mkdirs() }
    }

    fun newPhotoFile(context: Context, epochMillis: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(epochMillis))
        return File(photoDir(context), "meter_$stamp.jpg")
    }

    /** 审核中的照片使用唯一临时文件，避免同一秒导入的图片互相覆盖。 */
    fun newTemporaryPhotoFile(context: Context): File =
        File(photoDir(context), "meter_tmp_${UUID.randomUUID()}.jpg")

    /**
     * 将已确认的临时照片按拍摄时间归档。正式文件名精确到秒，因此同秒确认时最后一张覆盖前一张。
     * 先成功写入目标文件再清理临时文件，写入失败时保留原图，保证审核图片不会丢失。
     */
    fun archiveTemporaryPhoto(context: Context, path: String, epochMillis: Long): String? = runCatching {
        val source = File(path)
        if (!source.exists()) return null
        val target = newPhotoFile(context, epochMillis)
        if (source.absolutePath != target.absolutePath) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        target.absolutePath
    }.getOrNull()

    /** 相册/分享导入：先读 EXIF，再压缩落盘 */
    fun importFromUri(context: Context, uri: Uri): ImportedPhoto? = runCatching {
        val meta = context.contentResolver.openInputStream(uri)?.use { input ->
            readExifFromStream(input)
        } ?: PhotoExif(null, null, null, ExifInterface.ORIENTATION_NORMAL)
        val originalBytes = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        val bitmap = decodeSampled(context, uri, MAX_SIDE) ?: return null
        val upright = applyOrientation(bitmap, meta.orientation)
        val target = newTemporaryPhotoFile(context)
        writeJpeg(upright, target)
        ImportedPhoto(target, meta, originalBytes, target.length())
    }.getOrNull()

    /** 相机拍摄的临时文件：压缩后替换原文件，返回 EXIF 信息 */
    fun compressCaptured(context: Context, file: File): ImportedPhoto? = runCatching {
        val meta = readExif(file)
        val bitmap = decodeSampled(file, MAX_SIDE) ?: return null
        val upright = applyOrientation(bitmap, meta.orientation)
        val originalBytes = file.length()
        writeJpeg(upright, file)
        ImportedPhoto(file, meta, originalBytes, file.length())
    }.getOrNull()

    fun readExif(file: File): PhotoExif = runCatching {
        ExifInterface(file.absolutePath).let { readMeta(it) }
    }.getOrDefault(PhotoExif(null, null, null, ExifInterface.ORIENTATION_NORMAL))

    private fun readExifFromStream(input: InputStream): PhotoExif = runCatching {
        ExifInterface(input).let { readMeta(it) }
    }.getOrDefault(PhotoExif(null, null, null, ExifInterface.ORIENTATION_NORMAL))

    private fun readMeta(exif: ExifInterface): PhotoExif {
        val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val takenAt = dateStr?.let {
            runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
        }
        val latLon = exif.latLong
        val manualLat = parseGpsCoordinate(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            ?.let { v -> if (exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)?.startsWith("S") == true) -v else v }
        val manualLon = parseGpsCoordinate(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            ?.let { v -> if (exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)?.startsWith("W") == true) -v else v }
        val lat = latLon?.getOrNull(0)?.takeIf { it != 0.0 && !it.isNaN() } ?: manualLat
        val lon = latLon?.getOrNull(1)?.takeIf { it != 0.0 && !it.isNaN() } ?: manualLon
        val valid = lat != null && lon != null &&
            kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180 &&
            !(kotlin.math.abs(lat) < 0.01 && kotlin.math.abs(lon) < 0.01)
        return PhotoExif(
            takenAtMillis = takenAt,
            latitude = if (valid) lat else null,
            longitude = if (valid) lon else null,
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    /** 读成"正立 + 最长边 ≤ maxSide"的位图，供 OCR 与压缩共用 */
    fun loadUprightBitmap(file: File, maxSide: Int = 1800): Bitmap? {
        val meta = readExif(file)
        val raw = decodeSampled(file, maxSide) ?: return null
        return applyOrientation(raw, meta.orientation)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }.getOrDefault(bitmap)
    }

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        decodeSampled(context, uri, maxSide, bounds.outWidth, bounds.outHeight)
    }.getOrNull()

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        return scaleDown(raw, maxSide)
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        scaleDown(raw, maxSide)
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
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

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }
    }

    /** "22/1,32/1,2832/100" / "22 32 28.32" 这类写法 → 十进制度 */
    private fun parseGpsCoordinate(value: String?): Double? = runCatching {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun rational(s: String): Double? {
            val slash = s.split('/')
            return if (slash.size == 2) {
                val n = slash[0].toDoubleOrNull() ?: return null
                val d = slash[1].toDoubleOrNull() ?: return null
                if (d == 0.0) null else n / d
            } else {
                s.toDoubleOrNull()
            }
        }
        val deg = rational(parts[0]) ?: return null
        val min = rational(parts[1]) ?: return null
        val sec = rational(parts[2]) ?: return null
        deg + min / 60.0 + sec / 3600.0
    }.getOrNull()

    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }
}
