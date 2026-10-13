package com.example.watchscribe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "Forget the last few minutes": removes audio recorded after a cutoff, on the watch and
 * (by a message) on the phone. Chunks that started earlier are cut at the cutoff.
 */
object Forget {
    sealed class Action {
        object Keep : Action()
        object Delete : Action()
        data class Trim(val keepSeconds: Double) : Action()
    }

    /** Shorter than this after cutting = not worth keeping. */
    const val MIN_KEEP = 1.5

    fun startOf(name: String): Long? = runCatching {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.removePrefix("chunk_").substringBefore(".wav"))!!.time
    }.getOrNull()

    /** What to do with audio that starts at [start] and lasts [seconds], when forgetting from [cutoff]. */
    fun plan(start: Long, seconds: Double, cutoff: Long): Action {
        val end = start + (seconds * 1000).toLong()
        if (end <= cutoff) return Action.Keep
        val keep = (cutoff - start) / 1000.0
        return if (keep < MIN_KEEP) Action.Delete else Action.Trim(keep)
    }

    /** Applies the cut to the chunks still on the watch. Returns (seconds removed, chunks deleted). */
    fun applyOnWatch(ctx: Context, cutoff: Long): Pair<Double, Int> {
        var removed = 0.0
        var deleted = 0
        for (f in RecorderService.savedChunks(ctx)) {
            val start = startOf(f.name) ?: continue
            val len = WavChunkWriter.seconds(f)
            when (val a = plan(start, len, cutoff)) {
                Action.Keep -> {}
                Action.Delete -> {
                    f.delete(); sidecars(f).forEach { it.delete() }
                    removed += len; deleted++
                }
                is Action.Trim -> {
                    WavChunkWriter.trimFile(f, a.keepSeconds)
                    val m = File(f.parentFile, f.name + ".marks")
                    if (m.exists()) {
                        val kept = m.readText().split(',').mapNotNull { it.trim().toDoubleOrNull() }.filter { it <= a.keepSeconds }
                        if (kept.isEmpty()) m.delete() else m.writeText(kept.joinToString(",") { "%.1f".format(Locale.US, it) })
                    }
                    removed += len - a.keepSeconds
                }
            }
        }
        return removed to deleted
    }

    private fun sidecars(f: File) = listOf(File(f.parentFile, f.name + ".marks"), File(f.parentFile, f.name + ".memo"))
}
