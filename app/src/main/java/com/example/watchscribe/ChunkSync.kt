package com.example.watchscribe

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends finished .wav chunks to the phone app over the Wearable Data Layer.
 * A chunk is deleted from the watch only after the phone acknowledges it.
 */
object ChunkSync {
    private const val TAG = "WatchScribeSync"
    const val CAPABILITY = "watchscribe_receiver"

    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicBoolean(false)

    @Volatile var status: String = "Not synced yet"
        private set
    @Volatile var sentTotal = 0
        private set
    /** null = unknown yet, else whether the phone app was reachable on the last attempt. */
    @Volatile var phoneReachable: Boolean? = null
        private set

    fun requestSync(ctx: Context) {
        val app = ctx.applicationContext
        if (!pending.compareAndSet(false, true)) return // one queued run is enough
        executor.execute {
            pending.set(false)
            try { syncNow(app) } catch (t: Throwable) {
                Log.w(TAG, "Sync failed", t)
                status = "Sync failed: ${t.message}"
            }
            reply(app)
        }
    }

    /** Phone node that asked for a sync; it gets the result back as a message. */
    @Volatile var replyTo: String? = null

    private fun reply(ctx: Context) {
        val node = replyTo ?: return
        replyTo = null
        sendStatus(ctx, node)
    }

    /** Everything the phone's Watch tab shows, as JSON on "/watch/status". */
    fun sendStatus(ctx: Context, node: String, note: String = "") {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val (sec, notes) = Stats.today(ctx)
        val o = org.json.JSONObject()
            .put("state", when { !RecorderService.isRunning -> "off"; RecorderService.isPaused -> "paused"; else -> "listening" })
            .put("pausedUntil", RecorderService.pausedUntil)
            .put("speaking", RecorderService.speaking)
            .put("chunkSec", RecorderService.chunkSeconds)
            .put("waiting", RecorderService.savedChunks(ctx).size)
            .put("todaySec", sec)
            .put("todayNotes", notes)
            .put("battery", bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("freeMb", ctx.filesDir.usableSpace / 1_000_000)
            .put("sync", status)
            .put("error", RecorderService.lastError ?: "")
            .put("note", note)
        runCatching {
            Tasks.await(Wearable.getMessageClient(ctx).sendMessage(node, "/watch/status", o.toString().toByteArray()), 10, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "Status reply failed", it) }
    }

    /** Marks travel with the chunk as "?m=12.3,45.0" on the channel path. */
    private fun marksQuery(f: File): String {
        val m = File(f.parentFile, f.name + ".marks")
        return if (m.exists()) "?m=" + m.readText().trim() else ""
    }

    private fun syncNow(ctx: Context) {
        val files = RecorderService.savedChunks(ctx).sortedBy { it.name }
        if (files.isEmpty()) { status = "All synced"; return }

        val nodes = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE),
            10, TimeUnit.SECONDS).nodes
        // Only a direct Bluetooth/Wi-Fi link to the phone. Never send audio through Google's cloud relay.
        val node = nodes.firstOrNull { it.isNearby }
        if (node == null) {
            phoneReachable = false
            status = "Phone not reachable (${files.size} waiting)"
            RecorderService.updateTile(ctx)
            return
        }
        phoneReachable = true

        val channels = Wearable.getChannelClient(ctx)
        var sent = 0
        for (f in files) {
            status = "Sending ${sent + 1}/${files.size}…"
            val channel = Tasks.await(
                channels.openChannel(node.id, "/chunk/${f.name}" + marksQuery(f)), 15, TimeUnit.SECONDS)
            var acked = false
            try {
                val out = Tasks.await(channels.getOutputStream(channel), 15, TimeUnit.SECONDS)
                out.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
                val input = Tasks.await(channels.getInputStream(channel), 15, TimeUnit.SECONDS)
                acked = input.use { it.read() == 'K'.code }
            } finally {
                try { channels.close(channel) } catch (_: Throwable) {}
            }
            if (!acked) { status = "Phone did not confirm ${f.name}"; return }
            f.delete()
            File(f.parentFile, f.name + ".marks").delete()
            sent++
            sentTotal++
        }
        status = "All synced ($sentTotal sent)"
        Log.i(TAG, "Synced $sent chunks to ${node.displayName}")
        RecorderService.updateTile(ctx)
    }
}
