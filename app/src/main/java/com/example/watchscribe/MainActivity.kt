package com.example.watchscribe

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sotto on the watch: same look as the tile — status, today's stats, Pause/Mark, sync. */
class MainActivity : Activity() {

    private val white = Color.parseColor("#EEF0F2")
    private val muted = Color.parseColor("#A3A9B0")
    private val accent = Color.parseColor("#9EC1FF")
    private val onAccent = Color.parseColor("#0E1A33")
    private val surface = Color.parseColor("#1F2226")
    private val live = Color.parseColor("#FF6A5C")
    private val amber = Color.parseColor("#F2C46D")
    private val green = Color.parseColor("#9ED9B4")

    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var meter: View
    private lateinit var meterTrack: FrameLayout
    private lateinit var startBtn: TextView
    private lateinit var controls: LinearLayout
    private lateinit var pauseBtn: ImageButton
    private lateinit var markBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var syncChip: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var waiting = 0
    private var waitingAt = 0L
    private var lastPaused: Boolean? = null
    private var lastMarked: Boolean? = null
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 300) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun pill(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private fun roundButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        background = pill(surface, 28)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = label(20f, white, bold = true)
        sub = label(13f, muted)
        meter = View(this).apply { background = pill(accent, 2) }
        meterTrack = FrameLayout(this).apply {
            background = pill(surface, 2)
            addView(meter, FrameLayout.LayoutParams(0, dp(4)))
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(4)).apply { topMargin = dp(8) }
        }
        startBtn = label(16f, onAccent, bold = true).apply {
            text = "Start listening"
            background = pill(accent, 26)
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(52)).apply { topMargin = dp(12) }
            setOnClickListener { startActivity(ToggleActivity.intent(this@MainActivity)) }
        }
        pauseBtn = roundButton(R.drawable.ic_pause, "Pause") {
            if (RecorderService.isPaused) RecorderService.resume(this)
            else startActivity(Intent(this, PauseActivity::class.java))
        }
        markBtn = roundButton(R.drawable.ic_mark, "Mark this moment") { RecorderService.mark(this) }
        stopBtn = roundButton(R.drawable.ic_stop, "Stop listening") {
            startService(Intent(this@MainActivity, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        }.apply {
            background = pill(Color.parseColor("#3A1E1C"), 26)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#FF8A7E"))
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pauseBtn)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(markBtn)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(stopBtn)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        syncChip = label(12f, green).apply {
            background = pill(Color.parseColor("#17191D"), 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { ChunkSync.requestSync(this@MainActivity); waitingAt = 0L }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(label(13f, muted).apply {
                text = "sotto"
                setOnClickListener {
                    android.widget.Toast.makeText(this@MainActivity, "Sotto · by Khayyam Bayramov\nxayyam@gmail.com", android.widget.Toast.LENGTH_LONG).show()
                }
            })
            addView(status)
            addView(sub)
            addView(meterTrack)
            addView(startBtn)
            addView(controls)
            addView(syncChip)
        })
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun refresh() {
        val on = RecorderService.isRunning
        val paused = RecorderService.isPaused
        val capturing = on && !paused && RecorderService.chunkSeconds > 0
        val (sec, notes) = Stats.today(this)
        val nowMs = System.currentTimeMillis()
        if (nowMs - waitingAt > 3_000) { waitingAt = nowMs; waiting = RecorderService.savedChunks(this).size }
        val err = RecorderService.lastError

        when {
            !on -> { status.text = "Off"; status.setTextColor(white); sub.text = "Today ${Stats.duration(sec)} · $notes ${if (notes == 1) "note" else "notes"}" }
            paused -> {
                status.text = "Paused"; status.setTextColor(amber)
                val u = RecorderService.pausedUntil
                sub.text = if (u == RecorderService.UNTIL_RESUME) "Until you resume"
                else "Resumes ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(u))}"
            }
            err != null -> { status.text = "⚠ Mic busy"; status.setTextColor(amber); sub.text = "Will retry in a moment" }
            capturing -> {
                val s = RecorderService.chunkSeconds.toInt()
                status.text = "● Capturing"; status.setTextColor(live)
                sub.text = "%d:%02d".format(s / 60, s % 60) +
                    if (RecorderService.marksInChunk > 0) " · ${RecorderService.marksInChunk} marked" else ""
            }
            else -> { status.text = "● Listening"; status.setTextColor(white); sub.text = "Today ${Stats.duration(sec)} · $notes ${if (notes == 1) "note" else "notes"}" }
        }

        startBtn.visibility = if (on) View.GONE else View.VISIBLE
        controls.visibility = if (on) View.VISIBLE else View.GONE
        meterTrack.visibility = if (on && !paused) View.VISIBLE else View.INVISIBLE

        // live mic level, so you can see it hears you
        val lvl = (RecorderService.level / 3000.0).coerceIn(0.0, 1.0)
        meter.layoutParams = (meter.layoutParams as FrameLayout.LayoutParams).apply { width = (dp(110) * lvl).toInt() }
        meter.requestLayout()

        if (lastPaused != paused) {
            lastPaused = paused
            pauseBtn.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            pauseBtn.background = pill(if (paused) accent else surface, 26)
            pauseBtn.imageTintList = ColorStateList.valueOf(if (paused) onAccent else white)
            pauseBtn.contentDescription = if (paused) "Resume" else "Pause"
        }

        val marked = RecorderService.marksInChunk > 0
        markBtn.isEnabled = on && !paused
        markBtn.alpha = if (markBtn.isEnabled) 1f else 0.35f
        if (lastMarked != marked) {
            lastMarked = marked
            markBtn.setImageResource(if (marked) R.drawable.ic_mark_filled else R.drawable.ic_mark)
            markBtn.background = pill(if (marked) amber else accent, 26)
            markBtn.imageTintList = ColorStateList.valueOf(if (marked) Color.parseColor("#2E2104") else onAccent)
        }

        if (waiting == 0) {
            syncChip.text = "✓ All synced"; syncChip.setTextColor(green)
        } else {
            syncChip.text = if (ChunkSync.phoneReachable == false) "$waiting waiting · phone away" else "$waiting waiting · Sync"
            syncChip.setTextColor(white)
        }
    }
}
