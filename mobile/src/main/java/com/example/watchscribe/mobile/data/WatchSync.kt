package com.example.watchscribe.mobile.data

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What the watch reported last. */
data class WatchStatus(
    val state: String,          // listening | paused | off
    val pausedUntil: Long,      // 0, Long.MAX_VALUE (until resumed) or epoch ms
    val speaking: Boolean,
    val chunkSec: Double,
    val waiting: Int,
    val todaySec: Int,
    val todayNotes: Int,
    val battery: Int,
    val batteryHours: Double = -1.0, // estimate at the current drain, -1 = not known yet
    val light: Boolean = false,      // watch is in battery saver
    val freeMb: Long,
    val sync: String,
    val error: String,
    val note: String = "",      // "tap" = the watch shows a "Start listening?" prompt
    val at: Long = System.currentTimeMillis(),
)

/** Talks to Sotto on the watch: sync now, status, pause/resume. */
object WatchSync {
    private val _state = MutableStateFlow<String?>(null)
    /** One-line result of the last "Sync" tap; null = idle. */
    val state: StateFlow<String?> = _state

    private val _status = MutableStateFlow<WatchStatus?>(null)
    val status: StateFlow<WatchStatus?> = _status

    private val _watchName = MutableStateFlow<String?>(null)
    val watchName: StateFlow<String?> = _watchName

    @Volatile private var waitingForSync = false

    private fun nodes(ctx: Context) = runCatching {
        Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 10, TimeUnit.SECONDS)
    }.getOrDefault(emptyList()).also { _watchName.value = it.firstOrNull()?.displayName }

    /** Sends [path] to every connected watch; false if none got it. Call off the main thread. */
    fun send(ctx: Context, path: String, body: String = ""): Boolean =
        nodes(ctx).count { n ->
            runCatching { Tasks.await(Wearable.getMessageClient(ctx).sendMessage(n.id, path, body.toByteArray()), 10, TimeUnit.SECONDS) }.isSuccess
        } > 0

    fun refreshStatus(ctx: Context) = send(ctx, "/status")
    fun pause(ctx: Context, minutes: Int) = send(ctx, "/cmd/pause", minutes.toString())
    fun resume(ctx: Context) = send(ctx, "/cmd/resume")
    fun start(ctx: Context) = send(ctx, "/cmd/start")
    fun stop(ctx: Context) = send(ctx, "/cmd/stop")
    fun turnOff(ctx: Context) = send(ctx, "/cmd/off")

    /** "Sync": suspends until the watch answers or 40 s pass. Call off the main thread. */
    suspend fun request(ctx: Context) {
        _state.value = "Asking the watch…"
        if (nodes(ctx).isEmpty()) {
            _state.value = "Watch not connected. Check Bluetooth and the Galaxy Wearable app."
            return
        }
        waitingForSync = true
        if (!send(ctx, "/sync")) {
            waitingForSync = false
            _state.value = "Couldn't reach Sotto on the watch. Open it on the watch once."
            return
        }
        _state.value = "Receiving from the watch…"
        repeat(40) { if (!waitingForSync) return; delay(1000) }
        if (waitingForSync) { waitingForSync = false; _state.value = "No answer from the watch. Open Sotto on the watch and try again." }
    }

    /** Watch reply on "/watch/status". */
    fun onStatus(ctx: Context, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val s = WatchStatus(
            state = o.optString("state", "off"),
            pausedUntil = o.optLong("pausedUntil"),
            speaking = o.optBoolean("speaking"),
            chunkSec = o.optDouble("chunkSec", 0.0),
            waiting = o.optInt("waiting"),
            todaySec = o.optInt("todaySec"),
            todayNotes = o.optInt("todayNotes"),
            battery = o.optInt("battery", -1),
            batteryHours = o.optDouble("batteryHours", -1.0),
            light = o.optBoolean("light", false),
            freeMb = o.optLong("freeMb"),
            sync = o.optString("sync"),
            error = o.optString("error"),
            note = o.optString("note"),
        )
        val prev = _status.value
        _status.value = if (s.note.isEmpty() && prev?.note == "tap" && s.state == "off" && s.at - prev.at < 60_000) s.copy(note = "tap") else s
        if (waitingForSync) {
            waitingForSync = false
            RecordingStore.refresh(ctx)
            val mode = when (s.state) { "listening" -> "listening"; "paused" -> "paused"; else -> "not listening" }
            _state.value = if (s.waiting == 0) "Up to date · watch is $mode" else "${s.waiting} still on the watch (${s.sync}) · watch is $mode"
        }
    }

    fun clear() { _state.value = null }
}
