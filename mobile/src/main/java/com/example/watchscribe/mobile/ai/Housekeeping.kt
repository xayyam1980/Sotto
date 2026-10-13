package com.example.watchscribe.mobile.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.watchscribe.mobile.MainActivity
import com.example.watchscribe.mobile.data.RecordingStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs a few times a day: deletes old audio (if the user chose that) and, on Friday afternoon,
 * offers a summary of the week. The summary itself is made when the user opens it.
 */
class Housekeeping(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val WORK = "sotto-housekeeping"
        private const val CHANNEL = "weekly"
        const val EXTRA_DIGEST = "digest"

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Housekeeping>(3, TimeUnit.HOURS).build(),
            )
        }

        /** Monday 00:00 of the current week. */
        fun weekStart(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
            timeInMillis = now
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            if (timeInMillis > now) add(Calendar.WEEK_OF_YEAR, -1)
        }.timeInMillis
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        RecordingStore.refresh(ctx)
        RecordingStore.deleteOldAudio(ctx, AiSettings.keepAudioDays(ctx))
        weekly(ctx)
        return Result.success()
    }

    private fun weekly(ctx: Context) {
        if (!AiSettings.weeklySummary(ctx)) return
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY || now.get(Calendar.HOUR_OF_DAY) < 16) return
        val prefs = ctx.getSharedPreferences("housekeeping", Context.MODE_PRIVATE)
        val week = weekStart()
        if (prefs.getLong("weeklySent", 0) == week) return
        val count = RecordingStore.items.value.count { it.start >= week && !it.segments.isNullOrEmpty() }
        if (count == 0) return
        prefs.edit().putLong("weeklySent", week).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_DIGEST, "week").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            21, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.example.watchscribe.mobile.R.drawable.ic_launcher_monochrome)
                .setContentTitle("Your week in $count notes")
                .setContentText("Tap for a summary of decisions and to-dos, made on your phone.")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
