package com.example.watchscribe.mobile

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Speech leveler (slow automatic gain control), applied in place.
 *
 * Brings every voice in a recording to about the same loudness, so a person
 * a metre away is as audible as the wearer. Works on 20 ms frames:
 *  - speech frames get gain = TARGET / level, between 0.25x and MAX_GAIN
 *  - pauses let the gain drift back toward 1x, so silence is not boosted
 *  - gain falls fast (no blasting when a loud voice starts) and rises slowly
 *    (no pumping), and is interpolated per sample so it never clicks
 */
object Leveler {
    private const val TARGET = 5000.0   // ~ -16 dBFS RMS for speech
    private const val MAX_GAIN = 16.0   // up to +24 dB for distant voices
    private const val MIN_GAIN = 0.25
    private const val ATTACK = 0.5      // per-frame smoothing when gain must drop
    private const val RELEASE = 0.93    // per-frame smoothing when gain may rise
    private const val SPEECH_OVER_FLOOR = 3.0
    private const val PEAK_CEIL = 26000.0  // ~ -2 dBFS: headroom, so the soft limiter rarely engages

    fun process(pcm: ShortArray, sampleRate: Int) {
        val frame = sampleRate / 50
        val frames = pcm.size / frame
        if (frames < 5) return

        // RMS per frame
        val rms = DoubleArray(frames) { f ->
            var sum = 0.0
            val off = f * frame
            for (i in 0 until frame) { val v = pcm[off + i].toDouble(); sum += v * v }
            sqrt(sum / frame)
        }
        // Peak per frame: gain is capped so no frame (or the next one) exceeds PEAK_CEIL.
        // This keeps sudden loud sounds (dishes, doors) from being pushed into clipping.
        val peak = DoubleArray(frames) { f ->
            var m = 0
            val off = f * frame
            for (i in 0 until frame) { val v = abs(pcm[off + i].toInt()); if (v > m) m = v }
            m.toDouble().coerceAtLeast(1.0)
        }

        // Background level = 10th percentile of frame levels
        val floor = rms.sortedArray()[frames / 10].coerceAtLeast(20.0)

        // Smoothed gain per frame
        val gains = DoubleArray(frames)
        var g = 1.0
        for (f in 0 until frames) {
            val want = if (rms[f] > floor * SPEECH_OVER_FLOOR) {
                (TARGET / rms[f]).coerceIn(MIN_GAIN, MAX_GAIN)
            } else {
                // pause: hold, but drift slowly toward 1x
                g + (1.0 - g) * 0.02
            }
            val coeff = if (want < g) ATTACK else RELEASE
            g = coeff * g + (1 - coeff) * want
            // look-ahead cap: this frame and the next must stay under the ceiling
            val cap = min(PEAK_CEIL / peak[f], if (f + 1 < frames) PEAK_CEIL / peak[f + 1] else Double.MAX_VALUE)
            if (g > cap) g = cap
            gains[f] = g
        }

        // Apply with per-sample interpolation + soft limiter
        for (f in 0 until frames) {
            val g0 = if (f == 0) gains[0] else gains[f - 1]
            val g1 = gains[f]
            val off = f * frame
            for (i in 0 until frame) {
                val gi = g0 + (g1 - g0) * i / frame
                pcm[off + i] = softLimit(pcm[off + i] * gi).toInt().toShort()
            }
        }
        // tail after the last full frame
        val last = gains[frames - 1]
        for (i in frames * frame until pcm.size) {
            pcm[i] = softLimit(pcm[i] * last).toInt().toShort()
        }
    }

    private fun softLimit(x: Double): Double {
        val knee = 24000.0
        val ax = abs(x)
        if (ax <= knee) return x
        val room = 32767.0 - knee
        val y = knee + room * tanh((ax - knee) / room)
        return if (x < 0) -y else y
    }
}
