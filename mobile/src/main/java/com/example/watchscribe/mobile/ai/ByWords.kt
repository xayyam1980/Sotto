package com.example.watchscribe.mobile.ai

import android.content.Context
import com.example.watchscribe.mobile.data.Seg
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlin.math.max
import kotlin.math.min

/**
 * The Parakeet way: transcribe everything with word times, then decide per sentence who spoke.
 * Tested on real watch audio: people answering each other quickly are split correctly, because
 * speaker changes almost always fall between sentences.
 */
internal class ByWords(private val ctx: Context, private val eng: SpeechEngines) {

    fun transcribe(x: FloatArray, allowed: List<String>, numSpeakers: Int, onProgress: (Float) -> Unit): Transcriber.Result {
        val total = x.size / 16_000.0

        // 1) Where people speak
        val regions = speechRegions(x)
        onProgress(0.1f)

        // 2) Words with times
        val (words, lang) = wordsOf(x, regions, allowed, onProgress)
        if (words.isEmpty()) return Transcriber.Result(lang, emptyList(), 0, null)

        // 3) Voice fingerprints of short windows over the speech, grouped into people
        onProgress(0.6f)
        val windows = voiceWindows(regions)
        val sents = sentencesOf(words)
        if (windows.isEmpty()) return Transcriber.Result(lang, linesOf(sents, List(sents.size) { 0 }, total), 1, "No voices found")
        val ext = eng.voiceModel()
        try {
            val emb = windows.mapIndexed { i, w ->
                fingerprint(ext, x, w[0], w[1]).also { if (i % 20 == 0) onProgress(0.6f + 0.3f * i / windows.size) }
            }
            val centroids = groupVoices(emb, windows, numSpeakers)
            val lab = IntArray(emb.size) { i -> Voices.nearest(emb[i], centroids) }

            // 4) One speaker per sentence
            val spk = sents.indices.map { k ->
                val a = sents[k].first().t - 0.1
                val next = sents.getOrNull(k + 1)?.first()?.t ?: total
                val b = minOf(next - 0.05, sents[k].last().t + 1.2)
                whoSpoke(a, b, centroids, windows, lab) { fingerprint(ext, x, a, b) }
            }
            onProgress(0.97f)

            // 5) Lines; number people S1, S2… in order of first appearance
            val order = LinkedHashMap<Int, Int>()
            spk.forEach { if (it !in order) order[it] = order.size }
            val lines = linesOf(sents, spk.map { order[it]!! }, total)
            val voices = List(order.size) { idx -> centroids[order.entries.first { it.value == idx }.key] }
            onProgress(1f)
            return Transcriber.Result(lang, lines, order.size, null, voices)
        } finally {
            ext.release()
        }
    }

    /**
     * Speech regions (seconds) from the speech model, joined across pauses under 0.3 s.
     * The audio goes in 10-second pieces (the model's own window): for a single window the
     * library returns the speech straight away, without fingerprinting and grouping voices,
     * which we don't need here. Measured on 10 minutes of audio: 3 s instead of 160 s.
     */
    private fun speechRegions(x: FloatArray): List<DoubleArray> {
        val sd = eng.speakerModel()
        val found = mutableListOf<DoubleArray>()
        try {
            var st = 0
            while (st < x.size) {
                val en = min(x.size, st + SEG_WINDOW)
                if (en - st >= 1_600) { // skip a last scrap under 0.1 s
                    val off = st / 16_000.0
                    sd.process(x.copyOfRange(st, en)).forEach { found += doubleArrayOf(off + it.start, off + it.end) }
                }
                st = en
            }
        } finally {
            sd.release()
        }
        val out = mutableListOf<DoubleArray>()
        found.sortedBy { it[0] }.forEach { r ->
            val last = out.lastOrNull()
            if (last != null && r[0] <= last[1] + 0.3) last[1] = max(last[1], r[1]) else out += r
        }
        return out
    }

    /**
     * All words with their times, and the main language. Long pieces (15-30 s, cut in pauses) give
     * Parakeet more context; quiet audio is made louder first.
     */
    private fun wordsOf(
        x: FloatArray,
        regions: List<DoubleArray>,
        allowed: List<String>,
        onProgress: (Float) -> Unit,
    ): Pair<List<Word>, String> {
        val words = mutableListOf<Word>()
        val langTime = HashMap<String, Double>()
        val pieces = Audio.segment(x, 15.0, 30.0)
        val whisperReady = Models.isReady(ctx, ModelId.WHISPER)
        pieces.forEachIndexed { i, (a, b) ->
            val (w, text) = pieceWords(x, a, b, regions, allowed, whisperReady)
            words += w
            if (text.isNotBlank()) {
                val l = TextChecks.guessLanguage(text, allowed)
                langTime[l] = (langTime[l] ?: 0.0) + (b - a)
            }
            onProgress(0.1f + (i + 1f) / pieces.size * 0.5f)
        }
        return words to (langTime.maxByOrNull { it.value }?.key ?: allowed.firstOrNull() ?: "en")
    }

    /**
     * Words and text of samples [a] until [b]. Whisper is ~4x slower than Parakeet, so it only redoes
     * a piece (when downloaded) where Parakeet clearly failed: almost no words for the speech heard,
     * two alphabets mixed, or Russian/Ukrainian (when those languages are switched on; Whisper turbo
     * is clearly better there on watch audio).
     */
    private fun pieceWords(
        x: FloatArray,
        a: Int,
        b: Int,
        regions: List<DoubleArray>,
        allowed: List<String>,
        whisperReady: Boolean,
    ): Pair<List<Word>, String> {
        val sa = a / 16_000.0
        val sb = b / 16_000.0
        val audio = Audio.normalize(x.copyOfRange(a, b))
        val (w, text) = eng.parakeetWords(audio, sa)
        val spans = regions.mapNotNull { r ->
            val s0 = max(r[0], sa)
            val s1 = minOf(r[1], sb)
            if (s1 > s0) doubleArrayOf(s0, s1) else null
        }
        val speechSec = spans.sumOf { it[1] - it[0] }
        val cyrillic = text.any { it in 'Ѐ'..'ӿ' } && allowed.any { it == "ru" || it == "uk" }
        val mixed = TextChecks.mixedScripts(text)
        val failed = speechSec >= 3.0 && w.size / speechSec < 0.8
        if (!whisperReady || speechSec < 2.0 || !(failed || mixed || cyrillic)) return w to text

        val (wText, _) = eng.whisper(audio, "")
        val more = TextChecks.wordCount(wText) > w.size
        if (wText.isBlank() || TextChecks.looksLikeLoop(wText) || !(more || cyrillic || mixed)) return w to text
        return spreadWords(wText, spans.ifEmpty { listOf(doubleArrayOf(sa, sb)) }) to wText
    }

    /** Windows (seconds) to fingerprint: 1.5 s every 1 s over each region, shorter regions whole. */
    private fun voiceWindows(regions: List<DoubleArray>): List<DoubleArray> {
        val win = 1.5
        val hop = 1.0
        val windows = mutableListOf<DoubleArray>()
        for (r in regions) {
            val len = r[1] - r[0]
            if (len < 0.5) continue
            if (len <= win) {
                windows += doubleArrayOf(r[0], r[1])
                continue
            }
            var t = r[0]
            while (t + win <= r[1]) {
                windows += doubleArrayOf(t, t + win)
                t += hop
            }
            if (windows.last()[1] < r[1] - 0.3) windows += doubleArrayOf(r[1] - win, r[1])
        }
        return windows
    }

    private fun fingerprint(ext: SpeakerEmbeddingExtractor, x: FloatArray, a: Double, b: Double): FloatArray {
        val ia = (a * 16_000).toInt().coerceIn(0, x.size)
        val ib = (b * 16_000).toInt().coerceIn(ia, x.size)
        return Voices.unit(Voices.embed(ext, x, ia, ib))
    }

    /**
     * Groups the window fingerprints into people and returns each person's average voice.
     * At most 400 windows (spread over the recording) are grouped; weights are seconds, so a
     * "person" needs a few seconds of speech, not one stray window.
     */
    private fun groupVoices(emb: List<FloatArray>, windows: List<DoubleArray>, numSpeakers: Int): List<FloatArray> {
        val step = max(1, emb.size / 400)
        val sample = emb.indices.filter { it % step == 0 }
        val labels = if (emb.size < 4 && numSpeakers <= 0) {
            IntArray(sample.size)
        } else {
            val seconds = DoubleArray(sample.size) { k -> windows[sample[k]].let { it[1] - it[0] } }
            Voices.cluster(sample.map { emb[it] }, seconds, numSpeakers)
        }
        return (0..labels.max()).map { g ->
            val m = FloatArray(emb[0].size)
            sample.forEachIndexed { k, i -> if (labels[k] == g) for (d in m.indices) m[d] += emb[i][d] }
            Voices.unit(m)
        }
    }

    /**
     * Who said the sentence at [a]..[b] seconds: its own fingerprint when it is long enough,
     * else a vote of the windows it overlaps ([lab] = person per window).
     */
    private fun whoSpoke(
        a: Double,
        b: Double,
        centroids: List<FloatArray>,
        windows: List<DoubleArray>,
        lab: IntArray,
        ownVoice: () -> FloatArray,
    ): Int {
        if (centroids.size == 1) return 0
        if (b - a >= 0.8) return Voices.nearest(ownVoice(), centroids)
        val votes = DoubleArray(centroids.size)
        windows.forEachIndexed { i, w -> votes[lab[i]] += max(0.0, minOf(b, w[1]) - max(a, w[0])) }
        return votes.indices.maxBy { votes[it] }
    }

    /** Whisper gives no word times: spread its words evenly over the speech it came from. */
    private fun spreadWords(text: String, spans: List<DoubleArray>): List<Word> {
        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val total = spans.sumOf { it[1] - it[0] }
        return parts.mapIndexed { i, p ->
            var pos = total * i / parts.size
            var t = spans.first()[0]
            for (s in spans) {
                val len = s[1] - s[0]
                if (pos <= len) {
                    t = s[0] + pos
                    break
                }
                pos -= len
            }
            Word(t, p)
        }
    }

    /** Sentences from punctuation; titles like "Mr." don't end one; very long ones split at the longest pause. */
    private fun sentencesOf(words: List<Word>): List<List<Word>> {
        val out = mutableListOf<List<Word>>()
        var cur = mutableListOf<Word>()
        for (w in words) {
            cur += w
            if (SENTENCE_END.containsMatchIn(w.text) && w.text.lowercase() !in ABBREVIATIONS) {
                out += cur
                cur = mutableListOf()
            }
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
    private fun linesOf(sents: List<List<Word>>, spk: List<Int>, total: Double): List<Seg> {
        val out = mutableListOf<Seg>()
        sents.forEachIndexed { k, s ->
            val t0 = s.first().t
            val t1 = sents.getOrNull(k + 1)?.first()?.t ?: minOf(total, s.last().t + 1.0)
            val text = s.joinToString(" ") { it.text }
            val who = spk.getOrNull(k)
            val last = out.lastOrNull()
            if (last != null && last.speaker == who && t0 - last.t1 < 2.0 && t1 - last.t0 < 30.0) {
                out[out.size - 1] = last.copy(t1 = t1, text = last.text + " " + text)
            } else {
                out += Seg(t0, t1, text, who)
            }
        }
        return out
    }

    private companion object {
        /** Speech-model window: 10 s at 16 kHz. */
        const val SEG_WINDOW = 160_000
        val SENTENCE_END = Regex("[.?!…]$")
        val ABBREVIATIONS = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "np.", "tzn.", "ul.", "e.g.", "i.e.", "etc.", "vs.", "p.", "nr.")
    }
}
