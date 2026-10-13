package com.example.watchscribe.mobile.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.watchscribe.mobile.data.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transcribes and summarises waiting notes on the phone, in the background.
 * By default it only runs while charging (it is heavy work); "Process now" ignores that.
 */
class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "SottoWorker"
        private const val WORK = "sotto-process"
        private const val CHANNEL = "processing"

        private val _status = MutableStateFlow("")
        val status: StateFlow<String> = _status

        /** Queue processing; respects "only while charging". */
        fun schedule(ctx: Context) {
            val c = Constraints.Builder().setRequiresCharging(AiSettings.onlyWhileCharging(ctx)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProcessWorker>().setConstraints(c).build(),
            )
        }

        /** Start right away, even on battery. */
        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ProcessWorker>().build(),
            )
        }
    }

    private fun info(text: String, progress: Float? = null): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Processing notes", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.example.watchscribe.mobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Sotto")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (progress != null) setProgress(100, (progress * 100).toInt(), false) }
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(7, n)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Models.refresh(ctx)
        RecordingStore.refresh(ctx)
        val allowed = AiSettings.languages(ctx)

        // 1) Transcribe
        if (Models.TRANSCRIPTION.first().let { Models.isReady(ctx, it) }) {
            val todo = RecordingStore.items.value.filter { it.segments == null }.sortedBy { it.start }
            if (todo.isNotEmpty()) Transcriber(ctx).use { asr ->
                todo.forEachIndexed { i, r ->
                    if (isStopped) return Result.retry()
                    val label = "Transcribing ${i + 1} of ${todo.size}"
                    _status.value = "$label…"
                    runCatching { setForeground(info(label)) }
                    try {
                        val res = asr.transcribe(r.raw ?: r.clean, allowed) { p -> _status.value = "$label · ${(p * 100).toInt()}%" }
                        RecordingStore.setTranscript(ctx, r.id, res.lang, res.segments, res.speakers)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Transcription failed for ${r.id}", t)
                    }
                }
            }
        }

        // 2) Summarise
        if (Gemma.available(ctx)) {
            val todo = RecordingStore.items.value.filter { !it.segments.isNullOrEmpty() && it.summary == null }.sortedBy { it.start }
            try {
                todo.forEachIndexed { i, r ->
                    if (isStopped) return Result.retry()
                    val label = "Summarising ${i + 1} of ${todo.size}"
                    _status.value = "$label…"
                    runCatching { setForeground(info(label)) }
                    val transcript = r.segments!!.joinToString("\n") { s -> (s.speaker?.let { "Speaker ${it + 1}: " } ?: "") + s.text }
                    Gemma.summarize(ctx, transcript, r.lang)?.let {
                        RecordingStore.setAi(ctx, r.id, it.title.ifBlank { r.title }, it.summary, it.actions)
                    }
                }
            } finally {
                Gemma.unload()
            }
        }
        _status.value = ""
        return Result.success()
    }
}
