package com.example.watchscribe.mobile.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Small helpers for the 16-bit mono WAV files the watch produces. */
object Wav {

    /** 44-byte header of a 16-bit mono PCM WAV with [dataLen] bytes of audio. */
    fun header(rate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }

    /** Cuts a WAV file so only its first [seconds] stay (header updated). */
    fun trim(f: File, seconds: Double) {
        if (!f.exists() || f.length() < 44) return
        RandomAccessFile(f, "rw").use { r ->
            val h = ByteArray(44).also { r.readFully(it) }
            val rate = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
            if (rate <= 0) return
            val keep = ((seconds * rate).toLong() * 2).coerceIn(0, f.length() - 44)
            r.setLength(44 + keep)
            r.seek(0)
            r.write(header(rate, keep))
        }
    }

    /** Duration in seconds, from the header's byte rate. */
    fun seconds(f: File): Double {
        if (!f.exists() || f.length() < 44) return 0.0
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(44)
            r.readFully(h)
            val byteRate = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(28)
            return if (byteRate > 0) (f.length() - 44) / byteRate.toDouble() else 0.0
        }
    }

    /** [count] peak values (0..1) across the file, for a waveform overview. */
    fun peaks(f: File, count: Int = 64): List<Float> {
        if (!f.exists() || f.length() <= 44) return List(count) { 0f }
        RandomAccessFile(f, "r").use { r ->
            val samples = (r.length() - 44) / 2
            if (samples <= 0) return List(count) { 0f }
            val perBucket = max(1L, samples / count)
            val out = FloatArray(count)
            val buf = ByteArray(8192)
            var overall = 1
            for (b in 0 until count) {
                r.seek(44 + b * perBucket * 2)
                var left = perBucket * 2
                var peak = 0
                // sample sparsely inside each bucket to stay fast on long files
                var skip = 0
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    var i = 0
                    while (i + 1 < n) {
                        val s = abs(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt())
                        if (s > peak) peak = s
                        i += 2
                    }
                    left -= n
                    if (++skip > 8) break
                }
                out[b] = peak.toFloat()
                if (peak > overall) overall = peak
            }
            return out.map { (it / overall).coerceIn(0.04f, 1f) }
        }
    }
}
