package com.example.watchscribe

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one place on the watch that knows the WAV layout of the chunks (16-bit mono PCM).
 * The phone has a matching `Wav` with the same names; the two apps are built separately.
 */
object Wav {
    /** Size of the header; the audio starts right after it. */
    const val HEADER = 44

    /** [HEADER]-byte header of a 16-bit mono PCM WAV with [dataLen] bytes of audio. */
    fun header(rate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1) // PCM, mono
        b.putInt(rate).putInt(rate * 2).putShort(2).putShort(16) // byte rate, 2 bytes per sample, 16 bits
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        return b.array()
    }

    /** Sample rate from the header. */
    private fun rateOf(r: RandomAccessFile): Int {
        val h = ByteArray(4)
        r.seek(24)
        r.readFully(h)
        return ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Length of a finished chunk in seconds. */
    fun seconds(f: File): Double {
        if (f.length() <= HEADER) return 0.0
        return RandomAccessFile(f, "r").use { r ->
            val rate = rateOf(r).takeIf { it > 0 } ?: return 0.0
            (f.length() - HEADER) / (rate * 2.0)
        }
    }

    /** Cuts a finished chunk so only its first [seconds] stay (header updated). */
    fun trim(f: File, seconds: Double) {
        RandomAccessFile(f, "rw").use { r ->
            val rate = rateOf(r).takeIf { it > 0 } ?: return
            val keep = ((seconds * rate).toLong() * 2).coerceIn(0, f.length() - HEADER)
            r.setLength(HEADER + keep)
            r.seek(0)
            r.write(header(rate, keep))
        }
    }
}
