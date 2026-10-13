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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
 *  - Parakeet v3 (fast, accurate, European languages) or Whisper turbo (all languages), set in Settings
 *  - Whisper: if it guesses a language outside the allowed list, redo in the first one
 *  - drop/redo pieces where it loops ("что я не знаю, что я не знаю…")
 *  - Parakeet: whole recording with word times; speakers decided per sentence from voice fingerprints (3D-Speaker);
 *  - Whisper: speakers from pyannote turns + voice fingerprints, number of people found automatically;
 *    each turn is transcribed separately so lines don't mix people
 */
class Transcriber(private val ctx: Context) : AutoCloseable {
    private val threads = max(2, Runtime.getRuntime().availableProcessors() - 2)
    private val recognizers = HashMap<String, OfflineRecognizer>()
    private val engine = AiSettings.asrModel(ctx)
    private var parakeetRec: OfflineRecognizer? = null

    private fun parakeet(): OfflineRecognizer = parakeetRec ?: OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = Models.parakeet(ctx, "encoder.int8.onnx"),
                    decoder = Models.parakeet(ctx, "decoder.int8.onnx"),
                    joiner = Models.parakeet(ctx, "joiner.int8.onnx"),
                ),
                tokens = Models.parakeet(ctx, "tokens.txt"),
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
        ),
    ).also { parakeetRec = it }

    /** Parakeet finds the language itself; we only name it for the note. */
    private fun decodeParakeet(samples: FloatArray, allowed: List<String>): Pair<String, String> {
        val rec = parakeet()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            return text to guessLanguage(text, allowed)
        } finally {
            stream.release()
        }
    }

    data class Result(
        val lang: String, val segments: List<Seg>, val speakers: Int, val speakerError: String? = null,
        val voices: List<FloatArray> = emptyList(), // voice fingerprint per speaker (S1, S2…); empty = unknown
    )

    /** Voice fingerprint per cluster from the last diarize(), for recognising people. */
    private var lastCentroids: List<FloatArray> = emptyList()

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
        if (engine == ModelId.PARAKEET && Models.isReady(ctx, ModelId.SPEECH) && Models.isReady(ctx, ModelId.VOICES_EN)) {
            try {
                return transcribeByWords(x, allowed, numSpeakers, onProgress)
            } catch (t: Throwable) {
                Log.w("SottoAsr", "Word-level speakers failed; falling back", t)
            }
        }

        var spkError: String? = null
        val turns: List<Turn>? = when {
            !Models.isReady(ctx, ModelId.SPEECH) || !Models.isReady(ctx, ModelId.VOICES_EN) -> { spkError = "Speaker models not downloaded"; null }
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
            var text: String; var lang: String
            if (engine == ModelId.PARAKEET) {
                val r = decodeParakeet(piece, allowed); text = r.first; lang = r.second
            } else {
                // Short bits ("yes", "ok") are too short to guess the language: use the one spoken so far
                val r = if (main != null && piece.size < 4 * 16_000) decode(piece, main) else decode(piece, "")
                text = r.first; lang = r.second
                if (lang !in allowed) {
                    val r2 = decode(piece, main ?: fallback); text = r2.first; lang = main ?: fallback
                }
            }
            if (engine != ModelId.PARAKEET && looksLikeLoop(text)) {
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
        val byIndex = order.entries.associate { (label, idx) -> idx to label }
        val voices = List(order.size) { idx -> byIndex[idx]?.let { lastCentroids.getOrNull(it) } ?: FloatArray(0) }
        return Result(langTime.maxByOrNull { it.value }?.key ?: fallback, out, order.size, spkError, voices)
    }


    // ---------------------------------------------------------------------------------------------
    // Parakeet path: transcribe everything with word times, then decide per sentence who spoke.
    // Tested on real watch audio: people answering each other quickly are split correctly, because
    // speaker changes almost always fall between sentences.

    private class Word(val t: Double, val text: String)

    /** Speech regions (seconds) from the speech model, joined across pauses under 0.3 s. */
    private fun speechRegions(x: FloatArray): List<DoubleArray> {
        val sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = Models.segmentation(ctx)),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.2f,
            ),
        )
        val raw = try { sd.process(x) } finally { sd.release() }
        val out = mutableListOf<DoubleArray>()
        raw.map { doubleArrayOf(it.start.toDouble(), it.end.toDouble()) }.sortedBy { it[0] }.forEach { r ->
            val last = out.lastOrNull()
            if (last != null && r[0] <= last[1] + 0.3) last[1] = max(last[1], r[1]) else out += r
        }
        return out
    }

    private fun parakeetWords(samples: FloatArray, offset: Double): Pair<List<Word>, String> {
        val rec = parakeet()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            rec.decode(stream)
            val r = rec.getResult(stream)
            val text = r.text.trim()
            val tokens = r.tokens; val times = r.timestamps
            if (tokens.isEmpty() || times.size != tokens.size) return (if (text.isBlank()) emptyList() else listOf(Word(offset, text))) to text
            val words = mutableListOf<Word>()
            val cur = StringBuilder(); var t0 = 0.0
            for (i in tokens.indices) {
                val tok = tokens[i]
                if (tok.startsWith(" ") || tok.startsWith("\u2581")) {
                    if (cur.isNotBlank()) words += Word(offset + t0, cur.toString())
                    cur.setLength(0); cur.append(tok.trimStart(' ', '\u2581')); t0 = times[i].toDouble()
                } else {
                    if (cur.isEmpty()) t0 = times[i].toDouble()
                    cur.append(tok)
                }
            }
            if (cur.isNotBlank()) words += Word(offset + t0, cur.toString())
            return words to text
        } finally {
            stream.release()
        }
    }

    private fun transcribeByWords(x: FloatArray, allowed: List<String>, numSpeakers: Int, onProgress: (Float) -> Unit): Result {
        val fallback = allowed.firstOrNull() ?: "en"
        val total = x.size / 16_000.0

        // 1) Words with times. Long pieces (15-30 s, cut in pauses) give Parakeet more context.
        val words = mutableListOf<Word>()
        val langTime = HashMap<String, Double>()
        val pieces = segment(x, 15.0, 30.0)
        pieces.forEachIndexed { i, (a, b) ->
            val (w, text) = parakeetWords(x.copyOfRange(a, b), a / 16_000.0)
            words += w
            if (text.isNotBlank()) { val l = guessLanguage(text, allowed); langTime[l] = (langTime[l] ?: 0.0) + (b - a) }
            onProgress(0.05f + (i + 1f) / pieces.size * 0.5f)
        }
        val lang = langTime.maxByOrNull { it.value }?.key ?: fallback
        if (words.isEmpty()) return Result(lang, emptyList(), 0, null)

        // 2) Voice fingerprints of short windows over the speech, grouped into people
        val regions = speechRegions(x)
        onProgress(0.6f)
        val win = 1.5; val hop = 1.0
        val windows = mutableListOf<DoubleArray>()
        for (r in regions) {
            val len = r[1] - r[0]
            if (len < 0.5) continue
            if (len <= win) { windows += doubleArrayOf(r[0], r[1]); continue }
            var t = r[0]
            while (t + win <= r[1]) { windows += doubleArrayOf(t, t + win); t += hop }
            if (windows.last()[1] < r[1] - 0.3) windows += doubleArrayOf(r[1] - win, r[1])
        }
        val ext = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads))
        try {
            fun fp(a: Double, b: Double): FloatArray {
                val ia = (a * 16_000).toInt().coerceIn(0, x.size); val ib = (b * 16_000).toInt().coerceIn(ia, x.size)
                return unit(embed(ext, x, ia, ib))
            }
            if (windows.isEmpty()) return Result(lang, linesOf(words, List(sentencesOf(words).size) { 0 }, total), 1, "No voices found")
            val emb = windows.mapIndexed { i, w -> fp(w[0], w[1]).also { if (i % 20 == 0) onProgress(0.6f + 0.3f * i / windows.size) } }
            // Group at most 400 windows (spread over the recording), then give every window its nearest group
            val step = max(1, emb.size / 400)
            val sample = emb.indices.filter { it % step == 0 }
            val sLab = cluster(sample.map { emb[it] }, DoubleArray(sample.size) { 1.0 }, numSpeakers)
            val c = sLab.max() + 1
            val centroids = (0 until c).map { g ->
                val m = FloatArray(emb[0].size)
                sample.forEachIndexed { k, i -> if (sLab[k] == g) for (d in m.indices) m[d] += emb[i][d] }
                unit(m)
            }
            val lab = IntArray(emb.size) { i -> centroids.indices.maxBy { g -> dot(emb[i], centroids[g]) } }

            // 3) One speaker per sentence: its own fingerprint if long enough, else the windows' vote
            val sents = sentencesOf(words)
            val spk = sents.indices.map { k ->
                if (c == 1) return@map 0
                val s = sents[k]
                val a = s.first().t - 0.1
                val next = sents.getOrNull(k + 1)?.first()?.t ?: total
                val b = minOf(next - 0.05, s.last().t + 1.2)
                if (b - a >= 0.8) {
                    val e = fp(a, b)
                    centroids.indices.maxBy { g -> dot(e, centroids[g]) }
                } else {
                    val votes = DoubleArray(c)
                    windows.forEachIndexed { i, w -> votes[lab[i]] += max(0.0, minOf(b, w[1]) - max(a, w[0])) }
                    votes.indices.maxBy { votes[it] }
                }
            }
            onProgress(0.97f)

            // 4) Lines; number people S1, S2… in order of first appearance
            val order = LinkedHashMap<Int, Int>()
            spk.forEach { if (it !in order) order[it] = order.size }
            val lines = linesOf(words, spk.map { order[it]!! }, total)
            val voices = List(order.size) { idx -> centroids[order.entries.first { it.value == idx }.key] }
            onProgress(1f)
            return Result(lang, lines, order.size, null, voices)
        } finally {
            ext.release()
        }
    }

    /** Sentences from punctuation; titles like "Mr." don't end one; very long ones split at the longest pause. */
    private fun sentencesOf(words: List<Word>): List<List<Word>> {
        val out = mutableListOf<List<Word>>()
        var cur = mutableListOf<Word>()
        for (w in words) {
            cur += w
            if (Regex("[.?!…]$").containsMatchIn(w.text) && w.text.lowercase() !in ABBREVIATIONS) { out += cur; cur = mutableListOf() }
        }
        if (cur.isNotEmpty()) out += cur
        fun split(s: List<Word>): List<List<Word>> {
            if (s.size < 4 || s.last().t - s.first().t <= 12.0) return listOf(s)
            val k = (1 until s.size).maxBy { s[it].t - s[it - 1].t }
            return split(s.subList(0, k)) + split(s.subList(k, s.size))
        }
        return out.flatMap(::split)
    }

    /** Joins consecutive sentences of the same person (pauses under 2 s, lines under 30 s). */
    private fun linesOf(words: List<Word>, spk: List<Int>, total: Double): List<Seg> {
        val sents = sentencesOf(words)
        val out = mutableListOf<Seg>()
        sents.forEachIndexed { k, s ->
            val t0 = s.first().t
            val t1 = sents.getOrNull(k + 1)?.first()?.t ?: minOf(total, s.last().t + 1.0)
            val text = s.joinToString(" ") { it.text }
            val who = spk.getOrNull(k)
            val last = out.lastOrNull()
            if (last != null && last.speaker == who && t0 - last.t1 < 2.0 && t1 - last.t0 < 30.0)
                out[out.size - 1] = last.copy(t1 = t1, text = last.text + " " + text)
            else out += Seg(t0, t1, text, who)
        }
        return out
    }

    /**
     * Finds who speaks when. the speaker model finds the turns; each turn gets a voice fingerprint and the
     * fingerprints are grouped. With [numSpeakers] <= 0 the number of people is chosen by how
     * clearly the voices separate (see [cluster]).
     */
    private fun diarize(x: FloatArray, numSpeakers: Int): List<Turn> {
        lastCentroids = emptyList()
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
                minDurationOff = 0.2f, // keep short pauses: that is where people take turns
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
        try {
            val emb = long.map { t ->
                val len = min(t.b - t.a, 10 * 16_000) // middle 10 s at most
                val a = t.a + (t.b - t.a - len) / 2
                embed(ext, x, a, a + len)
            }
            val labels = cluster(emb, DoubleArray(long.size) { (long[it].b - long[it].a) / 16_000.0 }, numSpeakers)
            long.forEachIndexed { i, t -> t.spk = labels[i] }

            // Other turns (short ones): same label as the long turns pyannote grouped them with, else the nearest long turn
            val byRaw = long.groupBy { it.raw }.mapValues { (_, l) -> l.groupBy { it.spk }.maxBy { (_, v) -> v.sumOf { it.b - it.a } }.key }
            val longSorted = long.sortedBy { it.a }
            val fingerprinted = long.toHashSet()
            turns.filter { it !in fingerprinted }.forEach { t ->
                t.spk = byRaw[t.raw] ?: longSorted.minBy { l -> abs((l.a + l.b) / 2 - (t.a + t.b) / 2) }.spk
            }

            // A "turn" can still hold two people when they answer each other quickly.
            // Listen through every longer turn in short windows and cut it where the voice changes.
            val c = labels.max() + 1
            val centroids = (0 until c).map { g ->
                val dim = emb[0].size
                val m = FloatArray(dim)
                emb.forEachIndexed { i, e -> if (labels[i] == g) { val u = unit(e); val wt = (long[i].b - long[i].a).toFloat(); for (d in 0 until dim) m[d] += u[d] * wt } }
                unit(m)
            }
            lastCentroids = centroids
            if (c < 2) return turns
            return splitAtVoiceChanges(turns, x, ext, centroids)
        } finally { ext.release() }
    }

    private fun embed(ext: SpeakerEmbeddingExtractor, x: FloatArray, a: Int, b: Int): FloatArray {
        val st = ext.createStream()
        try {
            st.acceptWaveform(x.copyOfRange(a, b), 16_000)
            st.inputFinished()
            return ext.compute(st)
        } finally { st.release() }
    }

    /** Cuts turns of 3 s or more where 1.5 s windows (every 0.75 s) sound like a different person. */
    private fun splitAtVoiceChanges(turns: List<Turn>, x: FloatArray, ext: SpeakerEmbeddingExtractor, centroids: List<FloatArray>): List<Turn> {
        val win = 24_000; val hop = 12_000
        val out = mutableListOf<Turn>()
        for (t in turns.sortedBy { it.a }) {
            if (t.b - t.a < 3 * 16_000) { out += t; continue }
            val starts = generateSequence(t.a) { it + hop }.takeWhile { it + win <= t.b }.toList()
            val raw = IntArray(starts.size) { i ->
                val u = unit(embed(ext, x, starts[i], starts[i] + win))
                centroids.indices.maxBy { k -> dot(u, centroids[k]) }
            }
            val lab = smoothLabels(raw)
            var from = t.a; var cur = lab[0]
            for (i in 1 until lab.size) if (lab[i] != cur) {
                val cut = (starts[i - 1] + starts[i]) / 2 + win / 2
                out += Turn(from, cut, t.raw, cur); from = cut; cur = lab[i]
            }
            out += Turn(from, t.b, t.raw, cur)
        }
        return out
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
        // Speech the speaker model missed (quiet or far voices): transcribe it too, with the nearest speaker
        val speech = speechFrames(x)
        val f = 16_000 / 50
        val edges = listOf(intArrayOf(0, 0, -1)) + blocks + listOf(intArrayOf(size, size, -1))
        val extra = mutableListOf<IntArray>()
        for (i in 0 until edges.size - 1) {
            val g0 = edges[i][1]; val g1 = edges[i + 1][0]
            if (g1 - g0 < 16_000) continue
            val frames = (g0 / f until min(speech.size, g1 / f)).filter { speech[it] }
            if (frames.size < 15) continue // under 0.3 s of sound
            val a = frames.first() * f; val b = min(size, (frames.last() + 1) * f)
            val prev = edges[i].takeIf { it[2] >= 0 }; val next = edges[i + 1].takeIf { it[2] >= 0 }
            val spk = when {
                prev == null && next == null -> 0
                prev == null -> next!![2]
                next == null -> prev[2]
                a - prev[1] <= next[0] - b -> prev[2]
                else -> next[2]
            }
            extra += intArrayOf(a, b, spk)
        }
        val all = (blocks + extra).sortedBy { it[0] }
        val out = mutableListOf<Piece>()
        for ((a0, b0, spk) in all.map { Triple(it[0], it[1], it[2]) }) {
            val a = max(0, a0 - pad); val b = min(size, b0 + pad)
            // short replies ("yes", "no") stay as their own line, so they keep their speaker
            if (b - a < 16_000 * 4 / 10) continue
            if (b - a <= 14 * 16_000) out += Piece(a, b, spk)
            else segment(x.copyOfRange(a, b), keepAll = true).forEach { (pa, pb) -> out += Piece(a + pa, a + pb, spk) }
        }
        return out
    }

    override fun close() {
        recognizers.values.forEach { runCatching { it.release() } }
        recognizers.clear()
        parakeetRec?.let { runCatching { it.release() } }
        parakeetRec = null
    }

    companion object {
        const val MAX_SPEAKERS = 6
        private val ABBREVIATIONS = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "np.", "tzn.", "ul.", "e.g.", "i.e.", "etc.", "vs.", "p.", "nr.")
        /** Two groups whose voices are on average this similar are one person. */
        const val SAME_PERSON = 0.45
        /** Minimum separation quality (silhouette) to believe there is more than one person. */
        const val MIN_SILHOUETTE = 0.2

        private fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
        private fun unit(v: FloatArray): FloatArray { val n = sqrt(dot(v, v)).takeIf { it > 0 } ?: 1.0; return FloatArray(v.size) { (v[it] / n).toFloat() } }

        /**
         * Steadies per-window speaker guesses: a lone window that disagrees with both neighbours
         * follows them, and any run shorter than [minRun] windows joins the run before it.
         */
        fun smoothLabels(lab: IntArray, minRun: Int = 2): IntArray {
            val out = lab.copyOf()
            for (i in 1 until out.size - 1) if (lab[i - 1] == lab[i + 1] && lab[i] != lab[i - 1]) out[i] = lab[i - 1]
            var changed = true
            while (changed) {
                changed = false
                var i = 0
                while (i < out.size) {
                    var j = i; while (j + 1 < out.size && out[j + 1] == out[i]) j++
                    val len = j - i + 1
                    if (len < minRun && out.size > len) {
                        val fill = if (i > 0) out[i - 1] else out[j + 1]
                        for (k in i..j) out[k] = fill
                        changed = true; break
                    }
                    i = j + 1
                }
            }
            return out
        }

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

        /** 20 ms frames that are clearly louder than the background. */
        fun speechFrames(x: FloatArray): BooleanArray {
            val f = 16_000 / 50
            val n = x.size / f
            if (n == 0) return BooleanArray(0)
            val e = FloatArray(n) { k -> var s = 0.0; for (i in k * f until (k + 1) * f) s += x[i] * x[i]; sqrt(s / f).toFloat() }
            val floor = e.sortedArray()[(n * 0.15).toInt().coerceAtMost(n - 1)].coerceAtLeast(1e-4f)
            return BooleanArray(n) { e[it] >= floor * 2.5f }
        }

        /** Names the language of a transcribed line from its letters, among the user's languages. */
        fun guessLanguage(text: String, allowed: List<String>): String {
            val t = text.lowercase()
            fun ok(l: String) = l in allowed
            return when {
                t.any { it in "іїєґ" } && ok("uk") -> "uk"
                t.any { it in 'а'..'я' || it == 'ё' } -> if (ok("ru")) "ru" else if (ok("uk")) "uk" else allowed.firstOrNull() ?: "ru"
                t.any { it in "ąćęłńśźż" } && ok("pl") -> "pl"
                t.any { it in "äöüß" } && ok("de") -> "de"
                t.any { it in "àâçèéêëîïôœùûÿ" } && ok("fr") -> "fr"
                ok("en") -> "en"
                else -> allowed.firstOrNull() ?: "en"
            }
        }

        fun looksLikeLoop(text: String): Boolean {
            val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            return w.size >= 12 && w.toSet().size.toDouble() / w.size < 0.35
        }
    }
}
