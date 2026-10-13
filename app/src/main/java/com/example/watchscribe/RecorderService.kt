package com.example.watchscribe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.wear.tiles.TileService
import com.example.watchscribe.Stats.wantListening
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that listens continuously and saves only speech.
 *
 *  - a chunk opens when speech starts (300 ms pre-roll kept)
 *  - it closes after [SILENCE_CLOSE_MS] of silence, or at [MAX_CHUNK_SEC] of audio
 *  - Pause releases the mic completely but keeps the service alive, so it can
 *    resume on its own (Android won't let a background app reopen the mic)
 *  - Mark bookmarks the current second of the open chunk
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val TAG = "Sotto"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        const val SAMPLE_RATE = 32_000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 50   // 20 ms
        const val MAX_CHUNK_SEC = 300.0                      // 5 min
        private const val SILENCE_CLOSE_MS = 8_000L          // close chunk after 8 s quiet
        private const val PREROLL_FRAMES = 15                // 300 ms
        const val UNTIL_RESUME = Long.MAX_VALUE

        // Live state (read by MainActivity and the tile)
        @Volatile var isRunning = false
            private set
        @Volatile var level = 0.0
            private set
        @Volatile var noiseFloor = 0.0
            private set
        @Volatile var speaking = false
            private set
        @Volatile var chunkSeconds = 0.0
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var framesRead = 0L
            private set
        /** 0 = not paused, [UNTIL_RESUME] = until resumed, else epoch millis to auto-resume. */
        @Volatile var pausedUntil = 0L
            private set
        @Volatile var marksInChunk = 0
            private set

        private val markRequests = AtomicInteger(0)
        private val flushRequests = AtomicInteger(0)

        /** Phone asked for everything now: close the open chunk (if any) so it can be sent. */
        fun flush(ctx: Context) {
            if (isRunning && !isPaused && chunkSeconds > 0) flushRequests.incrementAndGet()
            else ChunkSync.requestSync(ctx)
        }
        @Volatile private var appCtx: Context? = null

        val isPaused: Boolean get() = isRunning && pausedUntil != 0L

        fun chunkDir(ctx: Context) = File(ctx.filesDir, "chunks")

        fun savedChunks(ctx: Context): List<File> =
            chunkDir(ctx).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().toList()

        fun pause(ctx: Context, minutes: Int) {
            if (!isRunning) return
            pausedUntil = if (minutes <= 0) UNTIL_RESUME else System.currentTimeMillis() + minutes * 60_000L
            Stats.setLastPause(ctx, minutes)
            stateChanged(ctx)
        }

        fun extend(ctx: Context, minutes: Int) {
            if (!isPaused || pausedUntil == UNTIL_RESUME) return
            pausedUntil += minutes * 60_000L
            stateChanged(ctx)
        }

        fun resume(ctx: Context) {
            if (pausedUntil == 0L) return
            pausedUntil = 0L
            stateChanged(ctx)
        }

        fun mark(ctx: Context) {
            if (!isRunning || isPaused) return
            markRequests.incrementAndGet()
            marksInChunk++
            updateTile(ctx)
        }

        private fun stateChanged(ctx: Context) {
            updateTile(ctx)
            (appCtx as? RecorderService)?.refreshNotification()
        }

        fun updateTile(ctx: Context) {
            try {
                TileService.getUpdater(ctx).requestUpdate(RecorderTileService::class.java)
            } catch (t: Throwable) {
                Log.w(TAG, "Tile update failed", t)
            }
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wantListening = false
            stopSelf()
            return START_NOT_STICKY
        }
        startRecording()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (running) return
        appCtx = this
        createChannel()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        getSystemService(android.app.NotificationManager::class.java).cancel(9) // "Start listening?" from the phone

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sotto:rec")
            .apply { acquire() }

        running = true
        isRunning = true
        wantListening = true
        pausedUntil = 0L
        lastError = null
        framesRead = 0
        worker = Thread({ workerLoop() }, "rec-loop").apply { start() }
        updateTile(this)
        ChunkSync.requestSync(this)
    }

    /** Alternates between capturing and paused until the service stops. */
    private fun workerLoop() {
        var lastSyncAt = System.currentTimeMillis()
        while (running) {
            val until = pausedUntil
            if (until != 0L) {
                if (until != UNTIL_RESUME && System.currentTimeMillis() >= until) {
                    pausedUntil = 0L
                    stateChanged(this)
                } else {
                    Thread.sleep(500)
                }
                continue
            }
            captureSession()
            if (System.currentTimeMillis() - lastSyncAt > 600_000) {
                lastSyncAt = System.currentTimeMillis()
                ChunkSync.requestSync(this)
            }
        }
    }

    /** Captures until paused, stopped, or the mic fails. */
    @SuppressLint("MissingPermission") // checked before starting
    private fun captureSession() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 2 * 10))
        } catch (t: Throwable) {
            micBusy(); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); micBusy(); return
        }

        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(recorder.audioSessionId)?.let { it.enabled = true; effects += it }
        }

        val hpf = HighPass(SAMPLE_RATE, 100.0)
        val vad = Vad()
        val writer = WavChunkWriter(chunkDir(this), SAMPLE_RATE)
        val marks = mutableListOf<Double>()
        var markAtStart = false
        val preroll = ArrayDeque<ShortArray>()
        var lastSpeechAt = 0L
        var lastLogAt = 0L
        var lastTileAt = 0L

        recorder.startRecording()
        if (lastError != null) { lastError = null; updateTile(this) }
        Log.i(TAG, "Capture started")
        try {
            while (running && pausedUntil == 0L) {
                val frame = ShortArray(FRAME_SAMPLES)
                val n = recorder.read(frame, 0, FRAME_SAMPLES)
                if (n < 0) { micBusy(); break }
                if (n == 0) continue
                framesRead++
                hpf.process(frame, n)

                val now = System.currentTimeMillis()
                val speech = vad.isSpeech(frame, n)
                level = vad.lastRms
                noiseFloor = vad.noiseFloor
                speaking = speech

                // Marks: at the current second of an open chunk, else at the start of the next one
                if (flushRequests.getAndSet(0) > 0) {
                    if (writer.isOpen) closeChunk(writer, marks) else ChunkSync.requestSync(this)
                }
                val m = markRequests.getAndSet(0)
                if (m > 0) {
                    if (writer.isOpen) repeat(m) { marks += writer.secondsWritten } else markAtStart = true
                }

                if (speech) {
                    lastSpeechAt = now
                    if (!writer.isOpen) {
                        writer.open()
                        preroll.forEach { writer.write(it, it.size) }
                        preroll.clear()
                        if (markAtStart) { marks += 0.0; markAtStart = false }
                        marksInChunk = marks.size
                        updateTile(this)
                    }
                    writer.write(frame, n)
                } else if (writer.isOpen) {
                    writer.write(frame, n)
                    if (now - lastSpeechAt > SILENCE_CLOSE_MS) closeChunk(writer, marks)
                } else {
                    preroll.addLast(frame.copyOf(n))
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                }

                if (writer.isOpen && writer.secondsWritten >= MAX_CHUNK_SEC) closeChunk(writer, marks)
                chunkSeconds = if (writer.isOpen) writer.secondsWritten else 0.0

                if (writer.isOpen && now - lastTileAt > 60_000) { lastTileAt = now; updateTile(this) }
                if (now - lastLogAt > 5_000) {
                    lastLogAt = now
                    Log.i(TAG, "rms=%.0f floor=%.0f speech=%b chunk=%.1fs".format(level, noiseFloor, speech, chunkSeconds))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture crashed", t)
            lastError = "Crashed: ${t.message}"
        } finally {
            closeChunk(writer, marks)
            speaking = false
            level = 0.0
            effects.forEach { try { it.release() } catch (_: Throwable) {} }
            try { recorder.stop() } catch (_: Throwable) {}
            recorder.release()
        }
    }

    private fun micBusy() {
        lastError = "Mic busy · will retry"
        updateTile(this)
        Log.w(TAG, "Mic unavailable, retrying in 10 s")
        repeat(20) { if (running && pausedUntil == 0L) Thread.sleep(500) }
    }

    private fun closeChunk(writer: WavChunkWriter, marks: MutableList<Double>) {
        if (!writer.isOpen) return
        val seconds = writer.secondsWritten
        val file = writer.close()
        if (file != null) {
            Stats.addChunk(this, seconds)
            if (marks.isNotEmpty()) {
                File(file.parentFile, file.name + ".marks").writeText(marks.joinToString(",") { "%.1f".format(Locale.US, it) })
            }
        }
        marks.clear()
        marksInChunk = 0
        chunkSeconds = 0.0
        updateTile(this)
        ChunkSync.requestSync(this)
    }

    override fun onDestroy() {
        running = false
        worker?.join(2000)
        isRunning = false
        pausedUntil = 0L
        speaking = false
        level = 0.0
        chunkSeconds = 0.0
        appCtx = null
        wakeLock?.let { if (it.isHeld) it.release() }
        updateTile(this)
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW))
    }

    fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val text = when (val u = pausedUntil) {
            0L -> "Listening (speech only)"
            UNTIL_RESUME -> "Paused until you resume"
            else -> "Paused until ${clock.format(Date(u))}"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sotto")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sotto)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
