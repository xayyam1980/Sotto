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

    /** Takes forgotten audio off today's totals. */
    @Synchronized
    fun remove(ctx: Context, seconds: Double, notes: Int) {
        val pr = p(ctx)
        if (pr.getString("day", null) != today()) return
        pr.edit()
            .putFloat("seconds", (pr.getFloat("seconds", 0f) - seconds.toFloat()).coerceAtLeast(0f))
            .putInt("notes", (pr.getInt("notes", 0) - notes).coerceAtLeast(0))
            .apply()
    }

    /** "Forget" ranges (from,to epoch millis) the phone hasn't confirmed yet. */
    fun pendingForgets(ctx: Context): List<String> =
        p(ctx).getString("forget", "").orEmpty().split(';').filter { it.isNotBlank() }

    @Synchronized
    fun setPendingForgets(ctx: Context, list: List<String>) =
        p(ctx).edit().putString("forget", list.joinToString(";")).apply()

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

    /** Very short form for a watch-face complication: "12m", "1h05". */
    fun shortDuration(sec: Int): String = when {
        sec >= 3600 -> "%dh%02d".format(sec / 3600, sec / 60 % 60)
        else -> "${(sec + 59) / 60}m"
    }
}
