package com.example.watchscribe

import android.content.Context
import android.os.BatteryManager
import kotlin.math.roundToInt

/**
 * Battery estimate while listening, and the light mode Sotto switches to below [LOW] %.
 * Samples (time, percent) are kept for the last few hours while not charging; the drain
 * rate over the last two hours gives "~9 h left".
 */
object Battery {
    const val LOW = 20
    private const val PREFS = "sotto_battery"
    private const val EVERY_MS = 4 * 60_000L
    private const val WINDOW_MS = 2 * 3_600_000L

    data class Level(val pct: Int, val charging: Boolean)

    fun level(ctx: Context): Level {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return Level(pct, bm?.isCharging == true)
    }

    /** Light mode: no extra audio effects, and fewer syncs. */
    fun light(ctx: Context): Boolean = level(ctx).let { !it.charging && it.pct in 0 until LOW }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun samples(ctx: Context): List<Pair<Long, Int>> =
        p(ctx).getString("s", "").orEmpty().split(';').mapNotNull { s ->
            val (t, v) = s.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val time = t.toLongOrNull() ?: return@mapNotNull null
            val pct = v.toIntOrNull() ?: return@mapNotNull null
            time to pct
        }

    /** Adds a sample (at most every few minutes). Charging, or a higher level than before, starts over. */
    @Synchronized
    fun record(ctx: Context, now: Long = System.currentTimeMillis()) {
        val l = level(ctx)
        if (l.pct < 0) return
        var list = samples(ctx)
        if (l.charging || (list.isNotEmpty() && l.pct > list.last().second)) list = emptyList()
        if (!l.charging && (list.isEmpty() || now - list.last().first >= EVERY_MS)) list = list + (now to l.pct)
        list = list.filter { now - it.first <= WINDOW_MS + 3_600_000L }
        p(ctx).edit().putString("s", list.joinToString(";") { "${it.first}:${it.second}" }).apply()
    }

    /** Hours left at the recent drain rate, or null when there isn't enough to tell yet. */
    fun estimateHours(samples: List<Pair<Long, Int>>, now: Long, pct: Int): Double? {
        val recent = samples.filter { now - it.first <= WINDOW_MS }
        val first = recent.firstOrNull() ?: return null
        val hours = (now - first.first) / 3_600_000.0
        val drop = first.second - pct
        if (hours < 0.5 || drop < 2) return null
        return (pct / (drop / hours)).coerceAtMost(99.0)
    }

    fun hoursLeft(ctx: Context): Double? {
        val l = level(ctx)
        if (l.charging || l.pct < 0) return null
        return estimateHours(samples(ctx), System.currentTimeMillis(), l.pct)
    }

    /** Short text for the tile: "~9 h battery", "Battery saver · 15%", or null. */
    fun label(ctx: Context): String? {
        val l = level(ctx)
        if (l.charging || l.pct < 0) return null
        if (l.pct < LOW) return "Battery saver · ${l.pct}%"
        return hoursLeft(ctx)?.let { format(it) }
    }

    fun format(hours: Double): String = if (hours < 1) "<1 h battery" else "~${hours.roundToInt()} h battery"
}
