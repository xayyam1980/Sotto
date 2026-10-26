package com.example.watchscribe.mobile.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** The one place that reads and writes the 16-bit mono WAV files the watch and phone record. */
object Wav {
    /** Size of the header; the audio starts right after it. */
    const val HEADER = 44

    /** Sample rate from the header. */
    private fun rateOf(r: RandomAccessFile): Int {
        val h = ByteArray(HEADER)
        r.seek(0)
        r.readFully(h)
        return ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
    }

    /** The whole file: sample rate and samples. */
    fun read(f: File): Pair<Int, ShortArray> {
        RandomAccessFile(f, "r").use { r ->
            val rate = rateOf(r)
            val bytes = ByteArray((r.length() - HEADER).toInt())
            r.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(sb.remaining())
            sb.get(pcm)
            return rate to pcm
        }
    }

    /** Writes [pcm] as a complete WAV file. */
    fun write(f: File, rate: Int, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) data.putShort(s)
        f.writeBytes(header(rate, pcm.size * 2L) + data.array())
    }

    /** [HEADER]-byte header of a 16-bit mono PCM WAV with [dataLen] bytes of audio. */
    fun header(rate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1) // PCM, mono
        b.putInt(rate).putInt(rate * 2).putShort(2).putShort(16) // byte rate, 2 bytes per sample, 16 bits
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        return b.array()
    }

    /** Cuts a WAV file so only its first [seconds] stay (header updated). */
    fun trim(f: File, seconds: Double) {
        if (!f.exists() || f.length() < HEADER) return
        RandomAccessFile(f, "rw").use { r ->
            val rate = rateOf(r)
            if (rate <= 0) return
            val keep = ((seconds * rate).toLong() * 2).coerceIn(0, f.length() - HEADER)
            r.setLength(HEADER + keep)
            r.seek(0)
            r.write(header(rate, keep))
        }
    }

    /** Duration in seconds, from the header's byte rate. */
    fun seconds(f: File): Double {
        if (!f.exists() || f.length() < HEADER) return 0.0
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(HEADER)
            r.readFully(h)
            val byteRate = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(28)
            return if (byteRate > 0) (f.length() - HEADER) / byteRate.toDouble() else 0.0
        }
    }

    /** [count] peak values (0..1) across the file, for a waveform overview. */
    fun peaks(f: File, count: Int = 64): List<Float> {
        if (!f.exists() || f.length() <= HEADER) return List(count) { 0f }
        RandomAccessFile(f, "r").use { r ->
            val samples = (r.length() - HEADER) / 2
            if (samples <= 0) return List(count) { 0f }
            val perBucket = max(1L, samples / count)
            val out = FloatArray(count)
            val buf = ByteArray(8192)
            var overall = 1
            for (b in 0 until count) {
                r.seek(HEADER + b * perBucket * 2)
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
