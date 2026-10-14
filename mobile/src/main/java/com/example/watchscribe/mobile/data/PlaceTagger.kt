package com.example.watchscribe.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tags a recording with where it was recorded, trying in turn:
 *  1. a fresh GPS fix (only for a note that just ended),
 *  2. the phone's location log ([LocationLog]) closest to when the note started,
 *  3. Wi-Fi seen right now (indoors, where GPS doesn't work): a network already linked to a place,
 *     else the Wi-Fi's own name, which the user can rename once ("Home", "Office").
 * Wi-Fi is only read from what the phone already sees; nothing is sent anywhere.
 *
 * Privacy: uses the phone's own GPS only (no Google location service), and names places
 * on the phone by matching earlier notes nearby. Turning addresses into street names
 * (Android's Geocoder) sends coordinates to Google, so it is OFF unless the user turns it on.
 */
object PlaceTagger {
    private const val TAG = "WatchScribePlace"
    private const val PREFS = "place_aliases"
    private const val SAME_PLACE_M = 200f

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun addressLookup(ctx: Context) = prefs(ctx).getBoolean("__lookup", false)
    fun setAddressLookup(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("__lookup", on).apply()

    @SuppressLint("MissingPermission")
    private fun locate(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        // 1) a fresh GPS fix (satellites only, nothing sent anywhere)
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val latch = CountDownLatch(1)
            var got: Location? = null
            val cancel = CancellationSignal()
            val ex = Executors.newSingleThreadExecutor()
            runCatching {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, cancel, ex) { got = it; latch.countDown() }
                if (!latch.await(30, TimeUnit.SECONDS)) cancel.cancel()
            }
            ex.shutdown()
            got?.let { return it }
        }
        // 2) a recent fix the phone already has (we don't request anything from network providers)
        val recent = listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 15 * 60_000L }
            .maxByOrNull { it.time }
        return recent
    }

    /** [start] = when the note began (epoch ms). */
    fun tag(ctx: Context, id: String, start: Long = System.currentTimeMillis()) {
        if (!hasPermission(ctx)) return
        LocationLog.start(ctx)
        try {
            val now = System.currentTimeMillis()
            val end = start + ((RecordingStore.byId(id)?.seconds ?: 0.0) * 1000).toLong()
            val fresh = now - end < 10 * 60_000L
            val nets = if (fresh) wifi(ctx) else emptyList()
            // A note recorded just now: where the phone is now is where it was said
            val live = if (fresh) locate(ctx)?.also { LocationLog.add(ctx, it) } else null
            val fix = live?.let { it.latitude to it.longitude }
                ?: LocationLog.nearest(LocationLog.all(ctx), start)?.let { it.lat to it.lon }
            if (fix != null) {
                val name = describe(ctx, fix.first, fix.second)
                RecordingStore.setPlace(ctx, id, name, fix.first, fix.second)
                learn(ctx, nets, name, fix.first, fix.second)
                return
            }
            // Indoors without GPS: recognise the place by its Wi-Fi
            val known = nets.firstNotNullOfOrNull { wifiPlace(ctx, it.first) }
            when {
                known != null -> RecordingStore.setPlace(ctx, id, alias(ctx, known.name), known.lat, known.lon)
                nets.any { it.second.isNotBlank() } -> {
                    val ssid = nets.first { it.second.isNotBlank() }.second
                    val name = alias(ctx, "Wi-Fi “$ssid”")
                    RecordingStore.setPlace(ctx, id, name, null, null)
                    learn(ctx, nets, name, null, null)
                }
                // else: placed later, when a fix from around that time turns up
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not tag place for $id", t)
        }
    }

    /** Places recent notes that have no place yet, using fixes logged around when they started. */
    fun backfill(ctx: Context) {
        if (!hasPermission(ctx)) return
        val fixes = LocationLog.all(ctx)
        if (fixes.isEmpty()) return
        if (RecordingStore.items.value.isEmpty()) RecordingStore.refresh(ctx)
        val since = System.currentTimeMillis() - 48 * 3_600_000L
        RecordingStore.items.value.filter { it.place == null && it.lat == null && it.start > since }.forEach { r ->
            val f = LocationLog.nearest(fixes, r.start) ?: return@forEach
            runCatching { RecordingStore.setPlace(ctx, r.id, describe(ctx, f.lat, f.lon), f.lat, f.lon) }
        }
    }

    private fun describe(ctx: Context, lat: Double, lon: Double): String {
        // Same spot as an earlier note? Reuse its (possibly renamed) name.
        nearestKnown(lat, lon)?.let { return it }
        if (addressLookup(ctx)) lookup(ctx, lat, lon)?.let { return alias(ctx, it) }
        return "Place near %.3f, %.3f".format(Locale.US, lat, lon)
    }

    /** Place name of the closest earlier note within [SAME_PLACE_M]. Pure logic, also used by tests. */
    fun nearestKnown(lat: Double, lon: Double, notes: List<Recording> = RecordingStore.items.value): String? {
        val d = FloatArray(1)
        return notes.asSequence()
            .filter { it.place != null && it.lat != null && it.lon != null }
            .map { r -> Location.distanceBetween(lat, lon, r.lat!!, r.lon!!, d); r.place!! to d[0] }
            .filter { it.second <= SAME_PLACE_M }
            .minByOrNull { it.second }?.first
    }

    @Suppress("DEPRECATION")
    private fun lookup(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val a = runCatching { Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            ?: return null
        val street = a.thoroughfare
        val area = a.subLocality ?: a.locality
        return listOfNotNull(street, area).distinct().joinToString(", ").ifBlank { a.featureName }
    }

    fun setAlias(ctx: Context, from: String, to: String) {
        prefs(ctx).edit().putString(from, to).apply()
        // networks linked to the old name follow the rename
        val w = wifiPrefs(ctx)
        val e = w.edit()
        w.all.forEach { (k, v) -> (v as? String)?.let { if (it.substringBefore('|') == from) e.putString(k, to + "|" + it.substringAfter('|', "")) } }
        e.apply()
    }

    // ---------------------------------------------------------------- Wi-Fi places

    data class WifiPlace(val name: String, val lat: Double?, val lon: Double?)

    private fun wifiPrefs(ctx: Context) = ctx.getSharedPreferences("wifi_places", Context.MODE_PRIVATE)

    /** (BSSID, SSID) of the connected network and the strongest ones nearby, from the phone's last scan. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifi(ctx: Context): List<Pair<String, String>> = runCatching {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val wm = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        wm.connectionInfo?.let { ci ->
            val b = ci.bssid
            if (b != null && b != "02:00:00:00:00:00") out += b to ci.ssid.orEmpty().trim('"').takeUnless { it == "<unknown ssid>" }.orEmpty()
        }
        val fresh = android.os.SystemClock.elapsedRealtime() * 1000 - 15 * 60_000_000L
        wm.scanResults.orEmpty()
            .filter { it.level > -80 && it.timestamp > fresh }
            .sortedByDescending { it.level }.take(6)
            .forEach { r -> if (out.none { it.first == r.BSSID }) out += r.BSSID to r.SSID.orEmpty() }
        out
    }.getOrDefault(emptyList())

    private fun wifiPlace(ctx: Context, bssid: String): WifiPlace? {
        val v = wifiPrefs(ctx).getString(bssid.lowercase(), null) ?: return null
        val name = v.substringBefore('|')
        val ll = v.substringAfter('|', "").split(',')
        return WifiPlace(name, ll.getOrNull(0)?.toDoubleOrNull(), ll.getOrNull(1)?.toDoubleOrNull())
    }

    /** Links the networks seen at a place to its name, so the place is recognised next time indoors. */
    private fun learn(ctx: Context, nets: List<Pair<String, String>>, name: String, lat: Double?, lon: Double?) {
        if (nets.isEmpty()) return
        val e = wifiPrefs(ctx).edit()
        nets.forEach { (b, _) ->
            // a GPS-named place wins over a Wi-Fi-only name
            if (lat == null && wifiPlace(ctx, b)?.lat != null) return@forEach
            e.putString(b.lowercase(), name + "|" + (if (lat != null && lon != null) "$lat,$lon" else ""))
        }
        e.apply()
    }

    private fun alias(ctx: Context, name: String): String = prefs(ctx).getString(name, null) ?: name
}
