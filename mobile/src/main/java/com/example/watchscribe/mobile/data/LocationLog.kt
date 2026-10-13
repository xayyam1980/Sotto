package com.example.watchscribe.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

/**
 * A short on-phone history of where the phone was (last 2 days), so a watch note can be placed
 * where it was *recorded*, not where the phone was when it arrived.
 *
 * It only listens to the PASSIVE provider: fixes other apps (maps, weather, the camera…) already
 * got. Sotto asks for nothing itself, so it costs no battery and sends nothing anywhere.
 * Stored in filesDir/locations.csv as "time,lat,lon".
 */
object LocationLog {
    private const val TAG = "WatchScribePlace"
    private const val KEEP_MS = 48 * 3_600_000L
    /** A fix this close in time to the start of a note is good enough to place it. */
    const val MATCH_MS = 30 * 60_000L

    data class Fix(val time: Long, val lat: Double, val lon: Double)

    private fun file(ctx: Context) = File(ctx.filesDir, "locations.csv")

    @Synchronized
    fun all(ctx: Context): List<Fix> = runCatching {
        file(ctx).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { l ->
            val p = l.split(',')
            if (p.size != 3) null else Fix(p[0].toLong(), p[1].toDouble(), p[2].toDouble())
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(ctx: Context, loc: Location) {
        val now = System.currentTimeMillis()
        val time = loc.time.takeIf { it in (now - KEEP_MS)..(now + 60_000) } ?: now
        val list = (all(ctx) + Fix(time, loc.latitude, loc.longitude))
            .filter { now - it.time <= KEEP_MS }.sortedBy { it.time }
        file(ctx).writeText(list.joinToString("\n") { "${it.time},${it.lat},${it.lon}" })
    }

    /** Fix closest in time to [at], within [MATCH_MS]. Pure logic, also used by tests. */
    fun nearest(fixes: List<Fix>, at: Long, maxGap: Long = MATCH_MS): Fix? =
        fixes.filter { abs(it.time - at) <= maxGap }.minByOrNull { abs(it.time - at) }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 41, Intent(ctx, PassiveLocationReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Starts listening to other apps' fixes (safe to call often; Android keeps one registration). */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            app.getSystemService(LocationManager::class.java)
                ?.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 30f, pending(app))
        }.onFailure { Log.w(TAG, "Passive location not available", it) }
    }
}

/** Receives fixes from the passive provider, logs them and places notes still without a place. */
class PassiveLocationReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val loc = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
        val pr = goAsync()
        Thread {
            try {
                LocationLog.add(ctx, loc)
                PlaceTagger.backfill(ctx)
            } catch (t: Throwable) {
                Log.w("WatchScribePlace", "Location log failed", t)
            } finally {
                pr.finish()
            }
        }.start()
    }
}
