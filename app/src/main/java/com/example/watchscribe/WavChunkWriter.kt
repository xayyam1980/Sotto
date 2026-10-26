package com.example.watchscribe

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Writes mono PCM16 audio into timestamped WAV files.
 * On close, each chunk is normalized to a comfortable loudness with one steady
 * gain (no pumping), using the 99.9th-percentile level so a single click
 * can't block the boost; rare peaks above that are soft-limited.
 * Files are written as *.wav.part and renamed to *.wav when closed,
 * so the sync step (step 2) only ever picks up finished chunks.
 */
class WavChunkWriter(private val dir: File, private val sampleRate: Int) {

    private var raf: RandomAccessFile? = null
    private var partFile: File? = null
    private var dataBytes = 0L
    private val buf = ByteArray(8192)
    private val hist = LongArray(1024) // |sample| histogram, 32-wide bins

    val isOpen get() = raf != null
    val secondsWritten get() = dataBytes / (sampleRate * 2.0)
    /** Epoch millis when the open chunk started. */
    var openedAt = 0L
        private set

    fun open() {
        if (isOpen) return
        dir.mkdirs()
        // the name is the start time; step a second on if a chunk already has it
        var t = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        while (File(dir, "chunk_${fmt.format(Date(t))}.wav").exists() || File(dir, "chunk_${fmt.format(Date(t))}.wav.part").exists()) t += 1000
        val stamp = fmt.format(Date(t))
        openedAt = t
        val f = File(dir, "chunk_$stamp.wav.part")
        raf = RandomAccessFile(f, "rw").apply {
            setLength(0)
            write(ByteArray(Wav.HEADER)) // header placeholder, filled on close
        }
        partFile = f
        dataBytes = 0
        hist.fill(0)
    }

    fun write(samples: ShortArray, length: Int) {
        val r = raf ?: return
        var i = 0
        while (i < length) {
            val n = minOf(length - i, buf.size / 2)
            for (j in 0 until n) {
                val s = samples[i + j].toInt()
                hist[(abs(s) shr 5).coerceAtMost(1023)]++
                buf[2 * j] = (s and 0xFF).toByte()
                buf[2 * j + 1] = ((s shr 8) and 0xFF).toByte()
            }
            r.write(buf, 0, n * 2)
            dataBytes += n * 2
            i += n
        }
    }

    /** Drops the open chunk without saving anything. */
    fun discard() {
        val r = raf ?: return
        raf = null
        r.close()
        partFile?.delete()
        partFile = null
        dataBytes = 0
    }

    /** Cuts the open chunk so only its first [seconds] are kept. */
    fun truncate(seconds: Double) {
        val r = raf ?: return
        val keep = ((seconds * sampleRate).toLong() * 2).coerceIn(0, dataBytes)
        dataBytes = keep
        r.setLength(Wav.HEADER + keep)
        r.seek(Wav.HEADER + keep)
    }

    /** Finalizes the file and returns it. Chunks shorter than [minSeconds] are discarded (returns null). */
    fun close(minSeconds: Double = 1.5): File? {
        val r = raf ?: return null
        val f = partFile!!
        raf = null
        partFile = null

        if (secondsWritten < minSeconds) {
            r.close(); f.delete(); return null
        }
        normalize(r)
        r.seek(0)
        r.write(Wav.header(sampleRate, dataBytes))
        r.close()
        val done = File(f.parentFile, f.name.removeSuffix(".part"))
        f.renameTo(done)
        return done
    }

    private fun normalize(r: RandomAccessFile) {
        val total = hist.sum()
        if (total == 0L) return
        // level that 99.9% of samples stay under
        var acc = 0L
        var bin = 0
        while (bin < 1023 && acc + hist[bin] < total * 0.999) { acc += hist[bin]; bin++ }
        val ref = ((bin + 1) * 32).toDouble()
        val gain = (TARGET / ref).coerceIn(1.0, MAX_GAIN)
        if (gain < 1.15) return

        val block = ByteArray(64 * 1024)
        var pos = Wav.HEADER.toLong()
        val end = pos + dataBytes
        while (pos < end) {
            val len = minOf(block.size.toLong(), end - pos).toInt()
            r.seek(pos)
            r.readFully(block, 0, len)
            var k = 0
            while (k + 1 < len) {
                val s = ((block[k + 1].toInt() shl 8) or (block[k].toInt() and 0xFF)).toShort()
                val v = softLimit(s * gain).toInt()
                block[k] = (v and 0xFF).toByte()
                block[k + 1] = ((v shr 8) and 0xFF).toByte()
                k += 2
            }
            r.seek(pos)
            r.write(block, 0, len)
            pos += len
        }
    }

    /** Linear up to the knee, then smoothly compresses toward full scale. */
    private fun softLimit(x: Double): Double {
        val knee = 24000.0
        val ax = abs(x)
        if (ax <= knee) return x
        val room = 32767.0 - knee
        val y = knee + room * tanh((ax - knee) / room)
        return if (x < 0) -y else y
    }

    companion object {
        private const val TARGET = 20000.0 // ~ -4 dBFS for the loud parts of speech
        private const val MAX_GAIN = 12.0  // cap so silence-heavy chunks don't blow up hiss
    }
}
