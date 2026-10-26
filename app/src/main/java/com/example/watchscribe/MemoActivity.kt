package com.example.watchscribe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import android.widget.Toast

/**
 * Note to self: records a short memo (up to a minute) that the phone turns into a to-do.
 * While it records, normal listening steps aside so the two don't fight over the mic.
 */
class MemoActivity : Activity() {
    companion object {
        const val MAX_SECONDS = 60.0
        private const val RATE = RecorderService.SAMPLE_RATE
    }

    @Volatile private var recording = true
    @Volatile private var cancelled = false
    private lateinit var clock: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(ToggleActivity.EXTRA_TOKEN) != Stats.token(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        fun button(label: String, bg: String, fg: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(fg))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Color.parseColor(bg)) }
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(44)).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }
        clock = TextView(this).apply {
            text = "● 0:00"
            textSize = 22f
            setTextColor(Color.parseColor("#FF6A5C"))
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@MemoActivity).apply {
                text = "Note to self"
                textSize = 14f
                setTextColor(Color.parseColor("#A3A9B0"))
                gravity = Gravity.CENTER
            })
            addView(clock)
            addView(button("Done", "#9EC1FF", "#0E1A33") { recording = false })
            addView(button("Cancel", "#1F2226", "#EEF0F2") { cancelled = true; recording = false })
        })

        RecorderService.hold(true)
        Thread({ record() }, "memo").start()
    }

    /** Leaving the screen any other way keeps what was said. */
    override fun onDestroy() {
        recording = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked in onCreate
    private fun record() {
        var file: File? = null
        try {
            // wait for listening to let go of the mic
            var waited = 0
            while (RecorderService.micOpen && waited < 3000) { Thread.sleep(50); waited += 50 }
            val frame = RATE / 50
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 20))
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                runOnUiThread { clock.text = "Mic busy" }
                Thread.sleep(1500)
                return
            }
            val hpf = HighPass(RATE, 100.0)
            val writer = WavChunkWriter(RecorderService.chunkDir(this), RATE)
            writer.open()
            recorder.startRecording()
            var shown = -1
            try {
                val buf = ShortArray(frame)
                while (recording && writer.secondsWritten < MAX_SECONDS) {
                    val n = recorder.read(buf, 0, frame)
                    if (n < 0) break
                    if (n == 0) continue
                    hpf.process(buf, n)
                    writer.write(buf, n)
                    val s = writer.secondsWritten.toInt()
                    if (s != shown) { shown = s; runOnUiThread { clock.text = "● %d:%02d".format(s / 60, s % 60) } }
                }
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                recorder.release()
            }
            if (cancelled) writer.discard() else {
                val seconds = writer.secondsWritten
                val done = writer.close(minSeconds = 0.8)
                if (done != null) {
                    File(done.parentFile, done.name + ".memo").writeText("1")
                    Stats.addChunk(this, seconds)
                    file = done
                }
            }
        } catch (t: Throwable) {
            Log.e(RecorderService.TAG, "Memo failed", t)
        } finally {
            RecorderService.hold(false)
            val saved = file != null
            if (saved) ChunkSync.requestSync(this)
            runOnUiThread {
                if (saved) Toast.makeText(applicationContext, "Saved · goes to your phone as a to-do", Toast.LENGTH_SHORT).show()
                if (!isFinishing) finish()
            }
        }
    }
}
