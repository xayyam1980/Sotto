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
 * Tags a recording with where the phone is when it arrives.
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

    fun tag(ctx: Context, id: String) {
        if (!hasPermission(ctx)) return
        try {
            val loc = locate(ctx) ?: return
            RecordingStore.setPlace(ctx, id, describe(ctx, loc.latitude, loc.longitude), loc.latitude, loc.longitude)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not tag place for $id", t)
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
    }

    private fun alias(ctx: Context, name: String): String = prefs(ctx).getString(name, null) ?: name
}
