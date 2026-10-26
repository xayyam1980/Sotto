package com.example.watchscribe.mobile.ai

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Preparing recorded audio for the speech models: reading, cutting into pieces, loudness. */
object Audio {
    /** Reads a 16-bit mono WAV and returns 16 kHz float samples. */
    fun load16k(f: File): FloatArray {
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(44)
            r.readFully(h)
            val rate = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            val bytes = ByteArray((r.length() - 44).toInt())
            r.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val n = sb.remaining()
            val src = FloatArray(n) { sb.get(it) / 32768f }
            return when (rate) {
                16_000 -> src
                32_000 -> FloatArray(n / 2) { (src[2 * it] + src[2 * it + 1]) * 0.5f }
                else -> {
                    val outN = (n.toLong() * 16_000 / rate).toInt()
                    FloatArray(outN) { i ->
                        val pos = i.toDouble() * rate / 16_000
                        val j = pos.toInt().coerceAtMost(n - 2)
                        val fr = (pos - j).toFloat()
                        src[j] * (1 - fr) + src[j + 1] * fr
                    }
                }
            }
        }
    }

    /** Loudness (RMS) of each 20 ms frame. */
    private fun frameLoudness(x: FloatArray): FloatArray {
        val f = 16_000 / 50
        return FloatArray(x.size / f) { k ->
            var s = 0.0
            for (i in k * f until (k + 1) * f) s += x[i] * x[i]
            sqrt(s / f).toFloat()
        }
    }

    /** The background level: the loudness that 15 % of frames stay under. */
    private fun background(e: FloatArray) = e.sortedArray()[(e.size * 0.15).toInt().coerceAtMost(e.size - 1)]

    /** Cuts at the quietest 20 ms frame 6–14 s after the previous cut; drops background-only pieces unless [keepAll]. */
    fun segment(x: FloatArray, minS: Double = 6.0, maxS: Double = 14.0, keepAll: Boolean = false): List<Pair<Int, Int>> {
        val sr = 16_000
        val f = sr / 50
        val e = frameLoudness(x)
        val n = e.size
        if (n == 0) return emptyList()
        val floor = background(e)
        val cuts = mutableListOf(0)
        while ((x.size - cuts.last()) / sr.toDouble() > maxS) {
            val a = cuts.last() / f + (minS / 0.02).toInt()
            val b = min(n, cuts.last() / f + (maxS / 0.02).toInt())
            var best = a
            for (k in a until b) if (e[k] < e[best]) best = k
            cuts += best * f
        }
        cuts += x.size
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]
            val b = cuts[i + 1]
            val fa = a / f
            val fb = max(fa + 1, min(n, b / f))
            var peak = 0f
            for (k in fa until fb) if (e[k] > peak) peak = e[k]
            if (keepAll || peak >= floor * 2.5f) out += a to b
        }
        return out
    }

    /** 20 ms frames that are clearly louder than the background. */
    fun speechFrames(x: FloatArray): BooleanArray {
        val e = frameLoudness(x)
        if (e.isEmpty()) return BooleanArray(0)
        val floor = background(e).coerceAtLeast(1e-4f)
        return BooleanArray(e.size) { e[it] >= floor * 2.5f }
    }

    /** Makes quiet recordings louder (peak to 90 %, at most 10x); loud ones stay as they are. */
    fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= 0f || peak >= 0.5f) return x
        val g = minOf(10f, 0.9f / peak)
        return FloatArray(x.size) { x[it] * g }
    }
}
