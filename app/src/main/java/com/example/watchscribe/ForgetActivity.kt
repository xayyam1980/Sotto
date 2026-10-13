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
import android.widget.Toast

/** "Forget the last…" screen opened from the tile. Tapping a choice is the confirmation. */
class ForgetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        fun option(label: String, minutes: Int, main: Boolean) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(if (main) "#3A0E0A" else "#EEF0F2"))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor(if (main) "#FF8A7E" else "#1F2226"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(44)).apply { topMargin = dp(6) }
            setOnClickListener {
                RecorderService.forget(this@ForgetActivity, minutes)
                Toast.makeText(this@ForgetActivity, "Last $label forgotten", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@ForgetActivity).apply {
                text = "Forget the last"
                textSize = 16f
                setTextColor(Color.parseColor("#EEF0F2"))
                gravity = Gravity.CENTER
            })
            addView(option("5 minutes", 5, true))
            addView(option("15 minutes", 15, false))
            addView(option("hour", 60, false).apply { text = "1 hour" })
            addView(TextView(this@ForgetActivity).apply {
                text = "Deleted on the watch and the phone"
                textSize = 11f
                setTextColor(Color.parseColor("#A3A9B0"))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(8), dp(24), 0)
            })
        })
    }
}
