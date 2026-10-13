package com.example.watchscribe

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice activity detector with an adaptive noise floor.
 * Tuned low for the watch mic: speech at arm's length is quiet.
 */
class Vad(
    private val thresholdRatio: Double = 2.0,  // speech must be ~2x the noise floor
    private val minAbsoluteRms: Double = 80.0, // ignore near-silence
    private val hangoverFrames: Int = 50,      // 50 x 20 ms = 1 s
) {
    var noiseFloor = 60.0
        private set
    var lastRms = 0.0
        private set
    private var hangover = 0

    fun isSpeech(frame: ShortArray, length: Int): Boolean {
        var sum = 0.0
        for (i in 0 until length) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / max(1, length))
        lastRms = rms

        val loud = rms > noiseFloor * thresholdRatio && rms > minAbsoluteRms

        if (!loud) {
            // fast down, slow up
            noiseFloor = if (rms < noiseFloor) 0.95 * noiseFloor + 0.05 * rms
                         else 0.998 * noiseFloor + 0.002 * rms
            noiseFloor = max(noiseFloor, 20.0)
        }

        if (loud) hangover = hangoverFrames else if (hangover > 0) hangover--
        return loud || hangover > 0
    }
}
