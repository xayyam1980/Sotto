package com.example.watchscribe.mobile.ai

import android.content.Context
import android.util.Log
import com.example.watchscribe.mobile.data.Seg
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-phone transcription, the same recipe that worked on the test recording:
 *  - cut at pauses into 6–14 s pieces, skip pieces with only background
 *  - Whisper turbo; if it guesses a language outside the allowed list, redo in the first one
 *  - drop/redo pieces where it loops ("что я не знаю, что я не знаю…")
 *  - speakers from pyannote + voice fingerprints (threshold 0.9), matched by overlap
 */
class Transcriber(private val ctx: Context) : AutoCloseable {
    private val threads = max(2, Runtime.getRuntime().availableProcessors() - 2)
    private val recognizers = HashMap<String, OfflineRecognizer>()

    data class Result(val lang: String, val segments: List<Seg>, val speakers: Int)

    private fun recognizer(lang: String): OfflineRecognizer = recognizers.getOrPut(lang) {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = Models.whisperEncoder(ctx),
                        decoder = Models.whisperDecoder(ctx),
                        language = lang,
                        task = "transcribe",
                    ),
                    tokens = Models.whisperTokens(ctx),
                    numThreads = threads,
                ),
            ),
        )
    }

    private fun decode(samples: FloatArray, lang: String): Pair<String, String> {
        val rec = recognizer(lang)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            rec.decode(stream)
            val r = rec.getResult(stream)
            return r.text.trim() to r.lang.trim('<', '|', '>', ' ').ifBlank { lang }
        } finally {
            stream.release()
        }
    }

    fun transcribe(wav: File, allowed: List<String>, onProgress: (Float) -> Unit = {}): Result {
        val x = load16k(wav)
        val pieces = segment(x)
        val fallback = allowed.firstOrNull() ?: "en"
        val segs = mutableListOf<Seg>()
        val langTime = HashMap<String, Int>()
        pieces.forEachIndexed { i, (a, b) ->
            val piece = x.copyOfRange(a, b)
            var (text, lang) = decode(piece, "")
            if (lang !in allowed) {
                val r = decode(piece, fallback); text = r.first; lang = fallback
            }
            if (looksLikeLoop(text)) {
                val mid = piece.size / 2
                text = listOf(decode(piece.copyOfRange(0, mid), lang).first, decode(piece.copyOfRange(mid, piece.size), lang).first)
                    .filterNot { looksLikeLoop(it) }.joinToString(" ")
            }
            if (text.isNotBlank()) {
                segs += Seg(a / 16_000.0, b / 16_000.0, text)
                langTime[lang] = (langTime[lang] ?: 0) + (b - a)
            }
            onProgress((i + 1f) / pieces.size * 0.85f)
        }
        val (labelled, n) = labelSpeakers(x, segs)
        onProgress(1f)
        return Result(langTime.maxByOrNull { it.value }?.key ?: fallback, labelled, n)
    }

    private fun labelSpeakers(x: FloatArray, segs: List<Seg>): Pair<List<Seg>, Int> {
        if (segs.isEmpty() || !Models.isReady(ctx, ModelId.SPEAKERS) || !Models.isReady(ctx, ModelId.VOICES)) return segs to 0
        return try {
            val sd = OfflineSpeakerDiarization(
                config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = Models.segmentation(ctx)),
                        numThreads = threads,
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = 0.9f),
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f,
                ),
            )
            val turns = try { sd.process(x) } finally { sd.release() }
            // renumber speakers in order of first appearance
            val order = LinkedHashMap<Int, Int>()
            turns.sortedBy { it.start }.forEach { if (it.speaker !in order) order[it.speaker] = order.size }
            val out = segs.map { s ->
                val overlap = HashMap<Int, Double>()
                for (t in turns) {
                    val o = min(s.t1, t.end.toDouble()) - max(s.t0, t.start.toDouble())
                    if (o > 0) overlap[t.speaker] = (overlap[t.speaker] ?: 0.0) + o
                }
                val best = overlap.maxByOrNull { it.value }?.key
                s.copy(speaker = best?.let { order[it] })
            }
            out to order.size
        } catch (t: Throwable) {
            Log.w("SottoAsr", "Speaker detection failed", t)
            segs to 0
        }
    }

    override fun close() {
        recognizers.values.forEach { runCatching { it.release() } }
        recognizers.clear()
    }

    companion object {
        /** Reads a 16-bit mono WAV and returns 16 kHz float samples. */
        fun load16k(f: File): FloatArray {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(44); r.readFully(h)
                val rate = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
                val bytes = ByteArray((r.length() - 44).toInt()); r.readFully(bytes)
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

        /** Cuts at the quietest 20 ms frame 6–14 s after the previous cut; drops background-only pieces. */
        fun segment(x: FloatArray, minS: Double = 6.0, maxS: Double = 14.0): List<Pair<Int, Int>> {
            val sr = 16_000
            val f = sr / 50
            val n = x.size / f
            if (n == 0) return emptyList()
            val e = FloatArray(n) { k ->
                var s = 0.0
                for (i in k * f until (k + 1) * f) s += x[i] * x[i]
                sqrt(s / f).toFloat()
            }
            val floor = e.sortedArray()[(n * 0.15).toInt().coerceAtMost(n - 1)]
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
                val a = cuts[i]; val b = cuts[i + 1]
                val fa = a / f; val fb = max(fa + 1, min(n, b / f))
                var peak = 0f
                for (k in fa until fb) if (e[k] > peak) peak = e[k]
                if (peak >= floor * 2.5f) out += a to b
            }
            return out
        }

        fun looksLikeLoop(text: String): Boolean {
            val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            return w.size >= 12 && w.toSet().size.toDouble() / w.size < 0.35
        }
    }
}
