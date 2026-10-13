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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-phone transcription, the same recipe that worked on the test recording:
 *  - cut at pauses into 6–14 s pieces, skip pieces with only background
 *  - Whisper turbo; if it guesses a language outside the allowed list, redo in the first one
 *  - drop/redo pieces where it loops ("что я не знаю, что я не знаю…")
 *  - speakers: pyannote turns + voice fingerprints, number of people found automatically;
 *    each turn is transcribed separately so lines don't mix people
 */
class Transcriber(private val ctx: Context) : AutoCloseable {
    private val threads = max(2, Runtime.getRuntime().availableProcessors() - 2)
    private val recognizers = HashMap<String, OfflineRecognizer>()

    data class Result(val lang: String, val segments: List<Seg>, val speakers: Int, val speakerError: String? = null)

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

    /** A stretch of one speaker's voice, in samples. */
    private class Turn(val a: Int, val b: Int, val raw: Int, var spk: Int = 0)

    /** A piece to transcribe; [spk] = speaker or null when unknown. */
    private class Piece(val a: Int, val b: Int, val spk: Int?)

    /**
     * Transcribes [wav]. When the speaker models are there, the audio is first split into speaker
     * turns and each turn is transcribed on its own, so one line never mixes two people.
     * [numSpeakers] <= 0 = find out how many people talk.
     */
    fun transcribe(wav: File, allowed: List<String>, numSpeakers: Int = -1, onProgress: (Float) -> Unit = {}): Result {
        val x = load16k(wav)
        val fallback = allowed.firstOrNull() ?: "en"

        var spkError: String? = null
        val turns: List<Turn>? = when {
            !Models.isReady(ctx, ModelId.SPEAKERS) || !Models.isReady(ctx, ModelId.VOICES) -> { spkError = "Speaker models not downloaded"; null }
            else -> try {
                diarize(x, numSpeakers).ifEmpty { spkError = "No voices found"; null }
            } catch (t: Throwable) {
                Log.w("SottoAsr", "Speaker detection failed", t)
                spkError = "Speaker detection failed: ${t.message ?: t.javaClass.simpleName}"; null
            }
        }
        onProgress(0.1f)
        val pieces = if (turns != null) speakerPieces(turns, x)
                     else segment(x).map { Piece(it.first, it.second, null) }

        val segs = mutableListOf<Seg>()
        val langTime = HashMap<String, Int>()
        pieces.forEachIndexed { i, p ->
            val piece = x.copyOfRange(p.a, p.b)
            val main = langTime.maxByOrNull { it.value }?.key
            // Short bits ("yes", "ok") are too short to guess the language: use the one spoken so far
            var (text, lang) = if (main != null && piece.size < 4 * 16_000) decode(piece, main) else decode(piece, "")
            if (lang !in allowed) {
                val r = decode(piece, main ?: fallback); text = r.first; lang = main ?: fallback
            }
            if (looksLikeLoop(text)) {
                val mid = piece.size / 2
                text = listOf(decode(piece.copyOfRange(0, mid), lang).first, decode(piece.copyOfRange(mid, piece.size), lang).first)
                    .filterNot { looksLikeLoop(it) }.joinToString(" ")
            }
            if (text.isNotBlank()) {
                segs += Seg(p.a / 16_000.0, p.b / 16_000.0, text, p.spk)
                langTime[lang] = (langTime[lang] ?: 0) + (p.b - p.a)
            }
            onProgress(0.1f + (i + 1f) / pieces.size * 0.9f)
        }
        // number speakers S1, S2… in order of first appearance
        val order = LinkedHashMap<Int, Int>()
        segs.forEach { s -> s.speaker?.let { if (it !in order) order[it] = order.size } }
        val out = segs.map { s -> s.copy(speaker = s.speaker?.let { order[it] }) }
        onProgress(1f)
        return Result(langTime.maxByOrNull { it.value }?.key ?: fallback, out, order.size, spkError)
    }

    /**
     * Finds who speaks when. pyannote finds the turns; each turn gets a voice fingerprint and the
     * fingerprints are grouped. With [numSpeakers] <= 0 the number of people is chosen by how
     * clearly the voices separate (see [cluster]).
     */
    private fun diarize(x: FloatArray, numSpeakers: Int): List<Turn> {
        val sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = Models.segmentation(ctx)),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads),
                // Deliberately fine-grained: we only use its turn boundaries and regroup below
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        val raw = try { sd.process(x) } finally { sd.release() }
        val turns = raw.map { Turn((it.start * 16_000).toInt().coerceIn(0, x.size), (it.end * 16_000).toInt().coerceIn(0, x.size), it.speaker) }
            .filter { it.b > it.a }.sortedBy { it.a }
        if (turns.isEmpty()) return turns

        // Fingerprint every turn long enough to carry a voice (the longest 300)
        val minLen = 16_000 // 1 s
        val long = turns.filter { it.b - it.a >= minLen }.sortedByDescending { it.b - it.a }.take(300)
        if (long.isEmpty()) { turns.forEach { it.spk = 0 }; return turns }
        val ext = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads))
        val emb = try {
            long.map { t ->
                val len = min(t.b - t.a, 10 * 16_000) // middle 10 s at most
                val a = t.a + (t.b - t.a - len) / 2
                val st = ext.createStream()
                try {
                    st.acceptWaveform(x.copyOfRange(a, a + len), 16_000)
                    st.inputFinished()
                    ext.compute(st)
                } finally { st.release() }
            }
        } finally { ext.release() }
        val labels = cluster(emb, DoubleArray(long.size) { (long[it].b - long[it].a) / 16_000.0 }, numSpeakers)
        long.forEachIndexed { i, t -> t.spk = labels[i] }

        // Short turns: same label as the long turns pyannote grouped them with, else the nearest long turn
        val byRaw = long.groupBy { it.raw }.mapValues { (_, l) -> l.groupBy { it.spk }.maxBy { (_, v) -> v.sumOf { it.b - it.a } }.key }
        val longSorted = long.sortedBy { it.a }
        turns.filter { it.b - it.a < minLen }.forEach { t ->
            t.spk = byRaw[t.raw] ?: longSorted.minBy { l -> abs((l.a + l.b) / 2 - (t.a + t.b) / 2) }.spk
        }
        return turns
    }

    /** Speaker turns -> pieces to transcribe: same-speaker turns joined, long ones split at pauses. */
    private fun speakerPieces(turns: List<Turn>, x: FloatArray): List<Piece> {
        val size = x.size
        val pad = 16_000 / 8 // 125 ms either side
        val blocks = mutableListOf<IntArray>() // a, b, spk
        for (t in turns.sortedBy { it.a }) {
            val last = blocks.lastOrNull()
            val a = max(t.a, last?.get(1) ?: 0) // overlapping speech goes to whoever started first
            if (t.b - a < 16_000 * 3 / 10) continue
            if (last != null && last[2] == t.spk && a - last[1] < 16_000 * 8 / 10) last[1] = max(last[1], t.b)
            else blocks += intArrayOf(a, t.b, t.spk)
        }
        val out = mutableListOf<Piece>()
        for ((a0, b0, spk) in blocks.map { Triple(it[0], it[1], it[2]) }) {
            val a = max(0, a0 - pad); val b = min(size, b0 + pad)
            if (b - a < 16_000 * 6 / 10) continue
            if (b - a <= 14 * 16_000) out += Piece(a, b, spk)
            else segment(x.copyOfRange(a, b), keepAll = true).forEach { (pa, pb) -> out += Piece(a + pa, a + pb, spk) }
        }
        return out
    }

    override fun close() {
        recognizers.values.forEach { runCatching { it.release() } }
        recognizers.clear()
    }

    companion object {
        const val MAX_SPEAKERS = 6
        /** Two groups whose voices are on average this similar are one person. */
        const val SAME_PERSON = 0.45
        /** Minimum separation quality (silhouette) to believe there is more than one person. */
        const val MIN_SILHOUETTE = 0.2

        private fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }

        /**
         * Groups voice fingerprints [e] (weights [w] = seconds of speech) into speakers.
         * [k] > 0: exactly k groups. Otherwise: average-linkage grouping, the number of people
         * picked by the best silhouette score; 1 when even the best split is weak or the two
         * main groups still sound like the same person. Tiny groups (a cough, a TV) are folded in.
         */
        fun cluster(e: List<FloatArray>, w: DoubleArray, k: Int): IntArray {
            val n = e.size
            if (n <= 1) return IntArray(n)
            val u = e.map { v -> val nn = sqrt(dot(v, v)).takeIf { it > 0 } ?: 1.0; FloatArray(v.size) { (v[it] / nn).toFloat() } }
            val sim = Array(n) { i -> DoubleArray(n) { j -> dot(u[i], u[j]) } }

            // Agglomerative, average linkage; remember the grouping at every count <= maxC
            val maxC = min(n, if (k > 0) max(k, MAX_SPEAKERS) else MAX_SPEAKERS)
            val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
            val levels = HashMap<Int, IntArray>()
            fun snapshot() = IntArray(n).also { lab -> groups.forEachIndexed { g, m -> m.forEach { lab[it] = g } } }
            fun link(a: List<Int>, b: List<Int>): Double { var s = 0.0; for (i in a) for (j in b) s += sim[i][j]; return s / (a.size * b.size) }
            if (groups.size <= maxC) levels[groups.size] = snapshot()
            while (groups.size > 1) {
                var bi = 0; var bj = 1; var best = Double.NEGATIVE_INFINITY
                for (i in groups.indices) for (j in i + 1 until groups.size) {
                    val l = link(groups[i], groups[j]); if (l > best) { best = l; bi = i; bj = j }
                }
                groups[bi].addAll(groups[bj]); groups.removeAt(bj)
                if (groups.size <= maxC) levels[groups.size] = snapshot()
            }
            if (k > 0) return levels[min(k, n)]!!

            fun silhouette(lab: IntArray): Double {
                val c = lab.max() + 1
                var tot = 0.0; var wsum = 0.0
                for (i in 0 until n) {
                    val d = DoubleArray(c); val ws = DoubleArray(c)
                    for (j in 0 until n) if (j != i) { d[lab[j]] += (1 - sim[i][j]) * w[j]; ws[lab[j]] += w[j] }
                    val own = lab[i]
                    val s = if (ws[own] == 0.0) 0.0 else {
                        val a = d[own] / ws[own]
                        val b = (0 until c).filter { it != own && ws[it] > 0 }.minOfOrNull { d[it] / ws[it] } ?: return 0.0
                        (b - a) / max(a, b).coerceAtLeast(1e-9)
                    }
                    tot += s * w[i]; wsum += w[i]
                }
                return tot / wsum
            }
            val two = levels[2]!!
            val g0 = (0 until n).filter { two[it] == 0 }; val g1 = (0 until n).filter { two[it] == 1 }
            var cross = 0.0; var cw = 0.0
            for (i in g0) for (j in g1) { cross += sim[i][j] * w[i] * w[j]; cw += w[i] * w[j] }
            val bestC = (2..maxC).maxBy { silhouette(levels[it]!!) }
            if (cross / cw > SAME_PERSON || silhouette(levels[bestC]!!) < MIN_SILHOUETTE) return IntArray(n)

            // Fold groups with under max(2 s, 5%) of the speech into the most similar bigger group
            val lab = levels[bestC]!!.copyOf()
            val total = w.sum()
            while (true) {
                val dur = HashMap<Int, Double>(); lab.forEachIndexed { i, g -> dur[g] = (dur[g] ?: 0.0) + w[i] }
                if (dur.size <= 1) break
                val small = dur.filter { it.value < max(2.0, 0.05 * total) }.minByOrNull { it.value }?.key ?: break
                val members = (0 until n).filter { lab[it] == small }
                val target = dur.keys.filter { it != small }.maxBy { g -> link(members, (0 until n).filter { lab[it] == g }) }
                members.forEach { lab[it] = target }
            }
            // renumber 0..c-1
            val re = LinkedHashMap<Int, Int>()
            return IntArray(n) { re.getOrPut(lab[it]) { re.size } }
        }

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

        /** Cuts at the quietest 20 ms frame 6–14 s after the previous cut; drops background-only pieces unless [keepAll]. */
        fun segment(x: FloatArray, minS: Double = 6.0, maxS: Double = 14.0, keepAll: Boolean = false): List<Pair<Int, Int>> {
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
                if (keepAll || peak >= floor * 2.5f) out += a to b
            }
            return out
        }

        fun looksLikeLoop(text: String): Boolean {
            val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            return w.size >= 12 && w.toSet().size.toDouble() / w.size < 0.35
        }
    }
}
