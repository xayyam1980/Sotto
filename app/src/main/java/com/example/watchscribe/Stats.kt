package com.example.watchscribe

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Today's captured time and note count, plus a few persisted settings. */
object Stats {
    private const val PREFS = "sotto_stats"
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun addChunk(ctx: Context, seconds: Double) {
        val pr = p(ctx)
        val d = today()
        val sameDay = pr.getString("day", null) == d
        val sec = (if (sameDay) pr.getFloat("seconds", 0f) else 0f) + seconds.toFloat()
        val n = (if (sameDay) pr.getInt("notes", 0) else 0) + 1
        pr.edit().putString("day", d).putFloat("seconds", sec).putInt("notes", n).apply()
    }

    /** Seconds captured today and number of notes today. */
    fun today(ctx: Context): Pair<Int, Int> {
        val pr = p(ctx)
        if (pr.getString("day", null) != today()) return 0 to 0
        return pr.getFloat("seconds", 0f).toInt() to pr.getInt("notes", 0)
    }

    /** True while the user wants listening on (so a system kill can be detected). */
    var Context.wantListening: Boolean
        get() = p(this).getBoolean("want", false)
        set(v) { p(this).edit().putBoolean("want", v).apply() }

    /**
     * Random secret only Sotto knows. The tile and Sotto's own screens pass it to ToggleActivity,
     * so other apps on the watch can't switch the microphone on or off.
     */
    fun token(ctx: Context): String = p(ctx).getString("token", null) ?: java.util.UUID.randomUUID().toString().also {
        p(ctx).edit().putString("token", it).apply()
    }

    fun setLastPause(ctx: Context, minutes: Int) = p(ctx).edit().putInt("lastPause", minutes).apply()
    fun lastPause(ctx: Context): Int = p(ctx).getInt("lastPause", 60)

    fun duration(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600} h ${sec / 60 % 60} min"
        else -> "${(sec + 59) / 60} min"
    }
}
