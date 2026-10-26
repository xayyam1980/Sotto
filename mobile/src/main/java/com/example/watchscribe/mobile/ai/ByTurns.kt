package com.example.watchscribe.mobile.ai

import android.content.Context
import android.util.Log
import com.example.watchscribe.mobile.data.Seg
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The backup way, used with Whisper or when the Parakeet way fails: the speaker model finds
 * speaker turns, the turns are grouped into people by voice fingerprint, and each turn is
 * transcribed on its own so one line never mixes two people.
 */
internal class ByTurns(private val ctx: Context, private val eng: SpeechEngines, private val parakeet: Boolean) {

    /** A stretch of one speaker's voice, in samples; [raw] = the speaker model's own label. */
    private class Turn(val a: Int, val b: Int, val raw: Int, var spk: Int = 0)

    /** A piece to transcribe; [spk] = speaker or null when unknown. */
    private class Piece(val a: Int, val b: Int, val spk: Int?)

    /** Speaker turns and each person's average voice (empty when no turn was long enough to fingerprint). */
    private class Diarized(val turns: List<Turn>, val voices: List<FloatArray>)

    fun transcribe(x: FloatArray, allowed: List<String>, numSpeakers: Int, onProgress: (Float) -> Unit): Transcriber.Result {
        val fallback = allowed.firstOrNull() ?: "en"
        var spkError: String? = null
        val found: Diarized? = when {
            !Models.isReady(ctx, ModelId.SPEECH) || !Models.isReady(ctx, ModelId.VOICES_EN) -> {
                spkError = "Speaker models not downloaded"
                null
            }
            else -> try {
                diarize(x, numSpeakers).takeIf { it.turns.isNotEmpty() } ?: run {
                    spkError = "No voices found"
                    null
                }
            } catch (t: Throwable) {
                Log.w("SottoAsr", "Speaker detection failed", t)
                spkError = "Speaker detection failed: ${t.message ?: t.javaClass.simpleName}"
                null
            }
        }
        onProgress(0.1f)
        val pieces = if (found != null) {
            speakerPieces(found.turns, x)
        } else {
            Audio.segment(x).map { Piece(it.first, it.second, null) }
        }

        val segs = mutableListOf<Seg>()
        val langTime = HashMap<String, Int>()
        pieces.forEachIndexed { i, p ->
            val main = langTime.maxByOrNull { it.value }?.key
            val (text, lang) = pieceText(Audio.normalize(x.copyOfRange(p.a, p.b)), allowed, main)
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
        val known = found?.voices.orEmpty()
        val byIndex = order.entries.associate { (label, idx) -> idx to label }
        val voices = List(order.size) { idx -> byIndex[idx]?.let { known.getOrNull(it) } ?: FloatArray(0) }
        return Transcriber.Result(langTime.maxByOrNull { it.value }?.key ?: fallback, out, order.size, spkError, voices)
    }

    /**
     * Text and language of one piece. Whisper: short bits ("yes", "ok") are too short to guess the
     * language, so they use [main], the one spoken so far; a language outside [allowed] is redone in
     * [main] or the first allowed one; a piece where Whisper loops is redone in two halves.
     */
    private fun pieceText(piece: FloatArray, allowed: List<String>, main: String?): Pair<String, String> {
        if (parakeet) return eng.parakeetText(piece, allowed)
        var (text, lang) = eng.whisper(piece, if (main != null && piece.size < 4 * 16_000) main else "")
        if (lang !in allowed) {
            lang = main ?: allowed.firstOrNull() ?: "en"
            text = eng.whisper(piece, lang).first
        }
        if (TextChecks.looksLikeLoop(text)) {
            val mid = piece.size / 2
            text = listOf(piece.copyOfRange(0, mid), piece.copyOfRange(mid, piece.size))
                .map { eng.whisper(it, lang).first }
                .filterNot { TextChecks.looksLikeLoop(it) }
                .joinToString(" ")
        }
        return text to lang
    }

    /**
     * Finds who speaks when. The speaker model finds the turns; each turn gets a voice fingerprint and the
     * fingerprints are grouped. With [numSpeakers] <= 0 the number of people is chosen by how
     * clearly the voices separate (see [Voices.cluster]).
     */
    private fun diarize(x: FloatArray, numSpeakers: Int): Diarized {
        val sd = eng.speakerModel()
        val raw = try { sd.process(x) } finally { sd.release() }
        fun at(sec: Float) = (sec * 16_000).toInt().coerceIn(0, x.size)
        val turns = raw.map { Turn(at(it.start), at(it.end), it.speaker) }.filter { it.b > it.a }.sortedBy { it.a }
        if (turns.isEmpty()) return Diarized(turns, emptyList())

        // Fingerprint every turn long enough to carry a voice (the longest 300)
        val minLen = 16_000 // 1 s
        val long = turns.filter { it.b - it.a >= minLen }.sortedByDescending { it.b - it.a }.take(300)
        if (long.isEmpty()) {
            turns.forEach { it.spk = 0 }
            return Diarized(turns, emptyList())
        }
        val ext = eng.voiceModel()
        try {
            val emb = long.map { t ->
                val len = min(t.b - t.a, 10 * 16_000) // middle 10 s at most
                val a = t.a + (t.b - t.a - len) / 2
                Voices.embed(ext, x, a, a + len)
            }
            val labels = Voices.cluster(emb, DoubleArray(long.size) { (long[it].b - long[it].a) / 16_000.0 }, numSpeakers)
            long.forEachIndexed { i, t -> t.spk = labels[i] }
            labelShortTurns(turns, long)

            // A "turn" can still hold two people when they answer each other quickly.
            // Listen through every longer turn in short windows and cut it where the voice changes.
            val centroids = averageVoices(emb, labels, long)
            if (centroids.size < 2) return Diarized(turns, centroids)
            return Diarized(splitAtVoiceChanges(turns, x, ext, centroids), centroids)
        } finally {
            ext.release()
        }
    }

    /**
     * Turns too short to fingerprint get the label of the long turns the speaker model grouped them
     * with, else that of the nearest long turn.
     */
    private fun labelShortTurns(turns: List<Turn>, long: List<Turn>) {
        val byRaw = long.groupBy { it.raw }.mapValues { (_, l) -> l.groupBy { it.spk }.maxBy { (_, v) -> v.sumOf { it.b - it.a } }.key }
        val longSorted = long.sortedBy { it.a }
        val fingerprinted = long.toHashSet()
        turns.filter { it !in fingerprinted }.forEach { t ->
            t.spk = byRaw[t.raw] ?: longSorted.minBy { l -> abs((l.a + l.b) / 2 - (t.a + t.b) / 2) }.spk
        }
    }

    /** Each person's voice: their turns' fingerprints averaged, longer turns counting more. */
    private fun averageVoices(emb: List<FloatArray>, labels: IntArray, long: List<Turn>): List<FloatArray> {
        val dim = emb[0].size
        return (0..labels.max()).map { g ->
            val m = FloatArray(dim)
            emb.forEachIndexed { i, e ->
                if (labels[i] != g) return@forEachIndexed
                val u = Voices.unit(e)
                val wt = (long[i].b - long[i].a).toFloat()
                for (d in 0 until dim) m[d] += u[d] * wt
            }
            Voices.unit(m)
        }
    }

    /** Cuts turns of 3 s or more where 1.5 s windows (every 0.75 s) sound like a different person. */
    private fun splitAtVoiceChanges(turns: List<Turn>, x: FloatArray, ext: SpeakerEmbeddingExtractor, centroids: List<FloatArray>): List<Turn> {
        val win = 24_000
        val hop = 12_000
        val out = mutableListOf<Turn>()
        for (t in turns.sortedBy { it.a }) {
            if (t.b - t.a < 3 * 16_000) {
                out += t
                continue
            }
            val starts = generateSequence(t.a) { it + hop }.takeWhile { it + win <= t.b }.toList()
            val raw = IntArray(starts.size) { i -> Voices.nearest(Voices.unit(Voices.embed(ext, x, starts[i], starts[i] + win)), centroids) }
            val lab = Voices.smoothLabels(raw)
            var from = t.a
            var cur = lab[0]
            for (i in 1 until lab.size) if (lab[i] != cur) {
                val cut = (starts[i - 1] + starts[i]) / 2 + win / 2
                out += Turn(from, cut, t.raw, cur)
                from = cut
                cur = lab[i]
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
            if (last != null && last[2] == t.spk && a - last[1] < 16_000 * 8 / 10) {
                last[1] = max(last[1], t.b)
            } else {
                blocks += intArrayOf(a, t.b, t.spk)
            }
        }
        val all = (blocks + missedSpeech(blocks, x)).sortedBy { it[0] }
        val out = mutableListOf<Piece>()
        for ((a0, b0, spk) in all.map { Triple(it[0], it[1], it[2]) }) {
            val a = max(0, a0 - pad)
            val b = min(size, b0 + pad)
            // short replies ("yes", "no") stay as their own line, so they keep their speaker
            if (b - a < 16_000 * 4 / 10) continue
            if (b - a <= 14 * 16_000) {
                out += Piece(a, b, spk)
            } else {
                Audio.segment(x.copyOfRange(a, b), keepAll = true).forEach { (pa, pb) -> out += Piece(a + pa, a + pb, spk) }
            }
        }
        return out
    }

    /**
     * Speech the speaker model missed (quiet or far voices) in the gaps of 1 s or more between
     * [blocks] (a, b, speaker): returned as extra blocks, given to the nearest speaker.
     */
    private fun missedSpeech(blocks: List<IntArray>, x: FloatArray): List<IntArray> {
        val size = x.size
        val speech = Audio.speechFrames(x)
        val f = 16_000 / 50
        val edges = listOf(intArrayOf(0, 0, -1)) + blocks + listOf(intArrayOf(size, size, -1))
        val extra = mutableListOf<IntArray>()
        for (i in 0 until edges.size - 1) {
            val g0 = edges[i][1]
            val g1 = edges[i + 1][0]
            if (g1 - g0 < 16_000) continue
            val frames = (g0 / f until min(speech.size, g1 / f)).filter { speech[it] }
            if (frames.size < 15) continue // under 0.3 s of sound
            val a = frames.first() * f
            val b = min(size, (frames.last() + 1) * f)
            val prev = edges[i].takeIf { it[2] >= 0 }
            val next = edges[i + 1].takeIf { it[2] >= 0 }
            val spk = when {
                prev == null && next == null -> 0
                prev == null -> next!![2]
                next == null -> prev[2]
                a - prev[1] <= next[0] - b -> prev[2]
                else -> next[2]
            }
            extra += intArrayOf(a, b, spk)
        }
        return extra
    }
}
