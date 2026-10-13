package com.example.watchscribe

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** "Pause for" screen opened from the tile: 15 minutes, 1 hour, or until resumed. */
class PauseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RecorderService.isRunning) { finish(); return }
        val last = Stats.lastPause(this)

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        fun option(label: String, minutes: Int) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            val selected = minutes == last
            setTextColor(if (selected) Color.parseColor("#C4D9FF") else Color.parseColor("#EEF0F2"))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor(if (selected) "#273347" else "#1F2226"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(44)).apply { topMargin = dp(6) }
            setOnClickListener {
                RecorderService.pause(this@PauseActivity, minutes)
                finish()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@PauseActivity).apply {
                text = "Pause for"
                textSize = 16f
                setTextColor(Color.parseColor("#EEF0F2"))
                gravity = Gravity.CENTER
            })
            addView(option("15 minutes", 15))
            addView(option("1 hour", 60))
            addView(option("Until I resume", 0))
        })
    }
}
