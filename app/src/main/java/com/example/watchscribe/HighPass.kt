package com.example.watchscribe

import kotlin.math.PI
import kotlin.math.roundToInt

/** First-order high-pass filter, applied in place. Removes rumble and DC offset. */
class HighPass(sampleRate: Int, cutoffHz: Double) {
    private val a: Double
    private var prevX = 0.0
    private var prevY = 0.0

    init {
        val rc = 1.0 / (2 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        a = rc / (rc + dt)
    }

    fun process(buf: ShortArray, length: Int) {
        for (i in 0 until length) {
            val x = buf[i].toDouble()
            val y = a * (prevY + x - prevX)
            prevX = x
            prevY = y
            buf[i] = y.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
