package com.hididi.gaslog.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hididi.gaslog.MainActivity
import com.hididi.gaslog.R
import com.hididi.gaslog.data.GasRepository
import com.hididi.gaslog.data.SettingsRepository
import com.hididi.gaslog.domain.Dates
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** 抄表提醒：每周固定时间检查，如果这周还没抄表就发一条本地通知 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.value
        if (!settings.reminderEnabled) return Result.success()
        val repo = GasRepository(applicationContext)
        val latest = repo.latestReading()
        val daysSince: Int = latest?.let {
            (Dates.today().toEpochDay() - Dates.dateOf(it.epochMillis).toEpochDay()).toInt()
        } ?: 999
        if (daysSince >= 6) {
            notify(applicationContext, daysSince)
        }
        return Result.success()
    }

    private fun notify(context: Context, daysSince: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val channelId = "meter_reminder"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "抄表提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "提醒给燃气表拍照抄表"
                },
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            1024,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (daysSince >= 999) "还没有抄表记录，先拍一张燃气表照片吧" else "已经 $daysSince 天没有抄表了"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle("该抄燃气表了")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(2048, notification) }
    }
}

object ReminderScheduler {
    private const val WORK_NAME = "gaslog-weekly-reminder"

    fun apply(context: Context, enabled: Boolean, dayOfWeek: Int, hour: Int, minute: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = java.time.ZonedDateTime.now()
        val daysUntil = ((dayOfWeek - now.dayOfWeek.value) + 7) % 7
        var next = now.toLocalDate().plusDays(daysUntil.toLong())
            .atTime(LocalTime.of(hour, minute))
        if (!next.atZone(java.time.ZoneId.systemDefault()).isAfter(now)) {
            next = next.plusWeeks(1)
        }
        val delay = java.time.Duration.between(now, next.atZone(java.time.ZoneId.systemDefault()))
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}