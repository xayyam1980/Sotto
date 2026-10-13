package com.example.watchscribe.mobile

import android.util.Log
import com.example.watchscribe.mobile.ai.ProcessWorker
import com.example.watchscribe.mobile.data.PlaceTagger
import com.example.watchscribe.mobile.data.RecordingStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.example.watchscribe.mobile.data.WatchSync
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Receives a chunk over a Data Layer channel, saves the original, acknowledges
 * it to the watch with one byte "K", then makes the cleaned copy and tags the place.
 */
class ChunkReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchScribePhone"
        private val NAME = Regex("^chunk_\\d{8}_\\d{6}\\.wav$")
        /** True only for the watch's own chunk file names. */
        internal fun isValidName(name: String) = NAME.matches(name)
        /** "a=1&b=2" after the '?' of a channel path. */
        internal fun query(path: String): Map<String, String> =
            path.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        private const val MAX_BYTES = 200L * 1024 * 1024 // a 5-min watch chunk is ~19 MB
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/watch/status" -> WatchSync.onStatus(this, String(event.data))
            // "Forget the last minutes" on the watch: body "from,to" (epoch millis)
            "/watch/forget" -> {
                val (from, to) = String(event.data).split(',').mapNotNull { it.trim().toLongOrNull() }
                    .takeIf { it.size == 2 } ?: return
                RecordingStore.forget(this, from, to)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        // Path: /chunk/<name>.wav, optionally ?m=12.3,45.0 (marks in seconds) and &memo=1 (note to self)
        val path = channel.path
        val name = path.substringBefore('?').substringAfterLast('/')
        // Only accept the watch's own file names (no paths, nothing unexpected)
        if (!path.startsWith("/chunk/") || !NAME.matches(name)) {
            Log.w(TAG, "Rejected unexpected chunk name")
            return
        }
        if (filesDir.usableSpace < 500L * 1024 * 1024) {
            Log.w(TAG, "Phone storage almost full; leaving $name on the watch")
            Wearable.getChannelClient(this).close(channel)
            return
        }
        val query = query(path)
        val marks = query["m"].orEmpty().split(',').mapNotNull { it.trim().toDoubleOrNull() }
        val memo = query["memo"] == "1"
        val client = Wearable.getChannelClient(this)
        val dir = RecordingStore.chunkDir(this)
        val part = File(dir, "$name.part")
        try {
            val input = Tasks.await(client.getInputStream(channel), 30, TimeUnit.SECONDS)
            input.use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("chunk too large")
                        o.write(buf, 0, n)
                    }
                }
            }

            // Basic sanity check: a WAV header plus at least some audio
            if (part.length() <= 44) throw IllegalStateException("empty chunk")
            val raw = File(RecordingStore.rawDir(this), name)
            part.renameTo(raw)

            // Confirm to the watch as soon as the original is safely stored
            val out = Tasks.await(client.getOutputStream(channel), 30, TimeUnit.SECONDS)
            out.use { it.write('K'.code); it.flush() }
            Log.i(TAG, "Received $name (${raw.length()} bytes)")

            // Cleaned copy for listening; the raw original is kept for transcription
            val cleanPart = File(dir, "$name.part")
            try {
                Denoiser.process(raw, cleanPart)
            } catch (t: Throwable) {
                Log.w(TAG, "Denoise failed for $name, using original", t)
                raw.copyTo(cleanPart, overwrite = true)
            }
            cleanPart.renameTo(File(dir, name))
            RecordingStore.refresh(this)
            if (marks.isNotEmpty()) RecordingStore.setMarks(this, name, marks)
            if (memo) RecordingStore.setMemo(this, name)
            PlaceTagger.tag(this, name, RecordingStore.byId(name)?.start ?: System.currentTimeMillis())
            ProcessWorker.schedule(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to receive $name", t)
            part.delete()
            client.close(channel)
        }
    }
}
