package com.example.watchscribe.mobile

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
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.watchscribe.mobile.ai.ProcessWorker
import com.example.watchscribe.mobile.data.PlaceTagger
import com.example.watchscribe.mobile.data.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Records with the phone's own microphone (for meetings where the phone is on the table).
 * Notes are cut every 10 minutes and go through the same pipeline as watch notes:
 * cleaned copy, place, then transcription and summary on the phone.
 */
class PhoneRecorder : Service() {

    data class Live(val running: Boolean = false, val startedAt: Long = 0, val level: Float = 0f, val marks: Int = 0)

    companion object {
        private const val TAG = "SottoPhoneMic"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "phone_mic"
        private const val NOTIF = 11
        private const val RATE = 16_000
        private const val PIECE_SEC = 600 // a new note every 10 minutes

        private val _live = MutableStateFlow(Live())
        val live: StateFlow<Live> = _live
        @Volatile private var markAt = mutableListOf<Double>()
        @Volatile private var pieceSeconds = 0.0

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, PhoneRecorder::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, PhoneRecorder::class.java).setAction(ACTION_STOP))

        /** Bookmark the current moment (shown highlighted in the transcript). */
        fun mark() {
            if (!_live.value.running) return
            synchronized(this) { markAt.add(pieceSeconds) }
            _live.value = _live.value.copy(marks = _live.value.marks + 1)
        }

        private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        /** Ids of notes recorded on the phone, so the list can label them. */
        fun isPhoneNote(ctx: Context, id: String) = File(ctx.filesDir, "phone_notes/$id").exists()
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sotto:phonemic").apply { acquire(4 * 3600_000L) }
        _live.value = Live(running = true, startedAt = System.currentTimeMillis())
        worker = Thread({ record() }, "phone-mic").apply { start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        } catch (t: Throwable) { Log.e(TAG, "Mic unavailable", t); finishService(); return }
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val buf = ShortArray(RATE / 10) // 100 ms
        var piece: Piece? = null
        try {
            rec.startRecording()
            piece = Piece.open(this)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                piece!!.write(buf, n)
                pieceSeconds = piece.seconds
                var s = 0.0
                for (i in 0 until n) s += buf[i] * buf[i].toDouble()
                val rms = sqrt(s / n) / 32768.0
                _live.value = _live.value.copy(level = (rms * 8).toFloat().coerceIn(0f, 1f))
                if (piece.seconds >= PIECE_SEC) {
                    finishPiece(piece)
                    piece = Piece.open(this)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recording failed", t)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            ns?.release()
            piece?.let { finishPiece(it) }
            finishService()
        }
    }

    private fun finishPiece(p: Piece) {
        val marks = synchronized(Companion) { markAt.toList().also { markAt = mutableListOf() } }
        val raw = p.close() ?: return
        Thread {
            try {
                val id = raw.name
                File(filesDir, "phone_notes").mkdirs()
                File(filesDir, "phone_notes/$id").writeText("phone")
                val clean = File(RecordingStore.chunkDir(this), "$id.part")
                try { Denoiser.process(raw, clean) } catch (t: Throwable) { raw.copyTo(clean, overwrite = true) }
                clean.renameTo(File(RecordingStore.chunkDir(this), id))
                RecordingStore.refresh(this)
                if (marks.isNotEmpty()) RecordingStore.setMarks(this, id, marks)
                PlaceTagger.tag(this, id)
                ProcessWorker.schedule(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Saving phone note failed", t)
            }
        }.start()
    }

    private fun finishService() {
        running = false
        _live.value = Live()
        runCatching { wake?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        worker?.join(3000)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone microphone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneRecorder::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Recording with the phone")
            .setContentText("Sotto · tap to open")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    /** One WAV file being written (16 kHz mono 16-bit). */
    private class Piece(val file: File, val raf: RandomAccessFile) {
        var samples = 0L
        val seconds get() = samples / RATE.toDouble()
        private val bytes = ByteArray(RATE / 10 * 2)

        fun write(buf: ShortArray, n: Int) {
            for (i in 0 until n) { bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
            raf.write(bytes, 0, 2 * n)
            samples += n
        }

        /** Finalizes the header; returns the finished file, or null if too short to keep. */
        fun close(): File? {
            val dataLen = samples * 2
            raf.seek(0); raf.write(header(dataLen)); raf.close()
            if (seconds < 2.0) { file.delete(); return null }
            val done = File(file.parentFile, file.name.removeSuffix(".part"))
            file.renameTo(done)
            return done
        }

        companion object {
            fun open(ctx: Context): Piece {
                val name = "chunk_${nameFmt.format(Date())}.wav"
                val f = File(RecordingStore.rawDir(ctx), "$name.part")
                val raf = RandomAccessFile(f, "rw")
                raf.setLength(0)
                raf.write(header(0))
                return Piece(f, raf)
            }

            fun header(dataLen: Long): ByteArray = com.example.watchscribe.mobile.data.Wav.header(RATE, dataLen)
        }
    }
}
