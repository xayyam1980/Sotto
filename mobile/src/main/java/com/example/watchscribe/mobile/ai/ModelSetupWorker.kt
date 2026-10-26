package com.example.watchscribe.mobile.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.watchscribe.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unpacks downloaded model archives in the background, with a notification, so it finishes
 * even when the app is closed or the screen goes off. Then starts processing waiting notes.
 */
class ModelSetupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val WORK = "sotto-models"
        private const val CHANNEL = "models"

        /** Starts unpacking; does nothing when it is already running. */
        fun start(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ModelSetupWorker>().build(),
            )
        }
    }

    private fun info(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing AI models", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Sotto")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(8, n)
    }

    override suspend fun getForegroundInfo() = info("Preparing AI models…")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        while (true) {
            val m = Models.pendingUnpack(ctx).firstOrNull() ?: break
            // Keeps the work alive while the app is in the background; unpacking also works without it
            runCatching { setForeground(info("Unpacking ${m.label}, a few minutes…")) }
            Models.unpack(ctx, m)
            Models.refresh(ctx)
        }
        ProcessWorker.schedule(ctx)
        Result.success()
    }
}
