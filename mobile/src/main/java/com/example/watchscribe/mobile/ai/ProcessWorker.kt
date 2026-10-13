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

        /** What is happening to one note right now; [progress] 0..1, or null when it can't be measured. */
        data class Job(val stage: String, val progress: Float?)
        private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
        /** Per-note progress, shown on the note card and the note screen. */
        val jobs: StateFlow<Map<String, Job>> = _jobs
        private fun job(id: String, j: Job?) { _jobs.value = if (j == null) _jobs.value - id else _jobs.value + (id to j) }

        /** Transcribe a note again (e.g. with a known number of speakers), right away. */
        fun redo(ctx: Context, id: String, numSpeakers: Int = -1) {
            RecordingStore.requestRedo(ctx, id, numSpeakers)
            runNow(ctx)
        }

        /** Queue processing; respects "only while charging". */
        fun schedule(ctx: Context) {
            val c = Constraints.Builder().setRequiresCharging(AiSettings.onlyWhileCharging(ctx)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ProcessWorker>().setConstraints(c).build(),
            )
        }

        /** Start right away, even on battery. A run in progress picks up new work itself. */
        fun runNow(ctx: Context) {
            val running = runCatching {
                WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(WORK).get().any { it.state == androidx.work.WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            if (running) return
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

        // 1) Transcribe (new notes, and notes the user asked to redo). Loops, so notes added meanwhile are done too.
        if (Models.isReady(ctx, AiSettings.asrModel(ctx))) {
            val failed = HashSet<String>()
            var asr: Transcriber? = null
            try {
                while (true) {
                    val todo = RecordingStore.items.value
                        .filter { (it.segments == null || it.redo != null) && it.id !in failed }.sortedBy { it.start }
                    if (todo.isEmpty()) break
                    todo.forEach { if (it.id !in _jobs.value) job(it.id, Job("Waiting to transcribe", null)) }
                    val r = todo.first()
                    if (isStopped) return Result.retry()
                    val label = if (todo.size == 1) "Transcribing" else "Transcribing (${todo.size} left)"
                    _status.value = "$label…"
                    job(r.id, Job("Transcribing", 0f))
                    runCatching { setForeground(info(label, 0f)) }
                    var shown = -1
                    try {
                        val engine = asr ?: Transcriber(ctx).also { asr = it }
                        val res = engine.transcribe(r.raw ?: r.clean, allowed, r.redo ?: -1) { p ->
                            val pct = (p * 100).toInt()
                            _status.value = "$label · $pct%"
                            job(r.id, Job("Transcribing", p))
                            if (pct / 5 != shown) { shown = pct / 5; setForegroundAsync(info("$label · $pct%", p)) }
                        }
                        RecordingStore.setTranscript(ctx, r.id, res.lang, res.segments, res.speakers, res.speakerError)
                        // a redone transcript gets a fresh summary
                        if (r.redo != null && r.summary != null && Gemma.available(ctx)) RecordingStore.clearAi(ctx, r.id, transcriptToo = false)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Transcription failed for ${r.id}", t)
                        failed += r.id
                        RecordingStore.clearRedo(ctx, r.id)
                    }
                    job(r.id, null)
                }
            } finally {
                asr?.close()
                _jobs.value = emptyMap()
            }
        }

        // 2) Summarise
        if (Gemma.available(ctx)) {
            val todo = RecordingStore.items.value.filter { !it.segments.isNullOrEmpty() && it.summary == null }.sortedBy { it.start }
            todo.forEach { job(it.id, Job("Waiting for summary", null)) }
            try {
                todo.forEachIndexed { i, r ->
                    if (isStopped) return Result.retry()
                    val label = "Summarising ${i + 1} of ${todo.size}"
                    _status.value = "$label…"
                    job(r.id, Job("Summarising", null))
                    runCatching { setForeground(info(label)) }
                    val transcript = r.segments!!.joinToString("\n") { s -> (s.speaker?.let { "Speaker ${it + 1}: " } ?: "") + s.text }
                    runCatching { Gemma.summarize(ctx, transcript, r.lang) }.getOrNull()?.let {
                        RecordingStore.setAi(ctx, r.id, it.title.ifBlank { r.title }, it.summary, it.actions)
                    }
                    job(r.id, null)
                }
            } finally {
                todo.forEach { job(it.id, null) }
                Gemma.unload()
            }
        }
        _status.value = ""
        return Result.success()
    }
}
