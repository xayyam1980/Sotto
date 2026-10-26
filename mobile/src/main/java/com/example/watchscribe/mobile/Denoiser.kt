package com.example.watchscribe.mobile

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Spectral noise reduction for mono 16-bit WAV files.
 *
 * 1. Learns the noise spectrum from the quietest 20% of frames (pauses).
 * 2. Removes it from every frame with an over-subtracting Wiener-style gain,
 *    smoothed over time to avoid "musical" artifacts.
 * 3. Rebuilds the audio with 50% overlap-add (sqrt-Hann windows).
 */
object Denoiser {
    private const val OVERSUB = 1.4     // how aggressively to subtract noise (gentle: keeps quiet/far voices)
    private const val FLOOR = 0.12      // never cut a bin below -18 dB (keeps it natural)
    private const val SMOOTH = 0.5      // time smoothing of gains

    fun process(input: File, output: File) {
        val (rate, pcm) = readWav(input)
        // ~32 ms frames whatever the sample rate (watch notes are 16 kHz now, older ones 32 kHz)
        val N = if (rate <= 16_000) 512 else 1024
        val HOP = N / 2
        if (pcm.size < N * 4) { input.copyTo(output, overwrite = true); return }

        val window = DoubleArray(N) { sqrt(0.5 - 0.5 * cos(2 * PI * it / N)) } // periodic sqrt-Hann
        val frames = (pcm.size - N) / HOP + 1
        val bins = N / 2 + 1
        val re = DoubleArray(N)
        val im = DoubleArray(N)

        // Pass 1: frame energies -> pick the quietest 20% as "noise only"
        val energy = DoubleArray(frames) { f ->
            var e = 0.0
            val off = f * HOP
            for (i in 0 until N) { val v = pcm[off + i] * window[i]; e += v * v }
            e
        }
        val threshold = energy.sortedArray()[(frames * 0.2).toInt().coerceAtMost(frames - 1)]

        // Pass 2: average power spectrum of those frames = noise profile
        val noise = DoubleArray(bins)
        var count = 0
        for (f in 0 until frames) {
            if (energy[f] > threshold) continue
            spectrum(pcm, f * HOP, window, re, im)
            for (k in 0 until bins) noise[k] += re[k] * re[k] + im[k] * im[k]
            count++
        }
        if (count == 0) { input.copyTo(output, overwrite = true); return }
        for (k in 0 until bins) noise[k] /= count

        // Pass 3: apply gains and overlap-add
        val out = FloatArray(pcm.size)
        val prevGain = DoubleArray(bins) { 1.0 }
        for (f in 0 until frames) {
            val off = f * HOP
            spectrum(pcm, off, window, re, im)
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k] + 1e-9
                val g0 = max(FLOOR, 1.0 - OVERSUB * noise[k] / p)
                val g = SMOOTH * prevGain[k] + (1 - SMOOTH) * g0
                prevGain[k] = g
                re[k] *= g; im[k] *= g
                if (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] } // keep it real
            }
            fft(re, im, inverse = true)
            for (i in 0 until N) out[off + i] += (re[i] * window[i]).toFloat()
        }

        val result = ShortArray(pcm.size) { i ->
            out[i].toInt().coerceIn(-32768, 32767).toShort()
        }
        Leveler.process(result, rate) // bring quiet/far voices up to the same loudness
        writeWav(output, rate, result)
    }

    private fun spectrum(pcm: ShortArray, off: Int, w: DoubleArray, re: DoubleArray, im: DoubleArray) {
        for (i in w.indices) { re[i] = pcm[off + i] * w[i]; im[i] = 0.0 }
        fft(re, im, inverse = false)
    }

    /** In-place iterative radix-2 FFT. Inverse includes the 1/N scale. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    private fun readWav(f: File): Pair<Int, ShortArray> {
        RandomAccessFile(f, "r").use { r ->
            val header = ByteArray(44)
            r.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val rate = hb.getInt(24)
            val dataLen = (r.length() - 44).toInt()
            val bytes = ByteArray(dataLen)
            r.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(sb.remaining())
            sb.get(pcm)
            return rate to pcm
        }
    }

    private fun writeWav(f: File, rate: Int, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        f.writeBytes(buf.array())
    }

}
