package com.example.watchscribe.mobile.ai

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Voice fingerprints and grouping them into people. */
object Voices {
    const val MAX_SPEAKERS = 6

    /** Two groups whose voices are on average this similar are one person. */
    const val SAME_PERSON = 0.45

    /** Minimum separation quality (silhouette) to believe there is more than one person. */
    const val MIN_SILHOUETTE = 0.2

    fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** [v] scaled to length 1, so [dot] of two fingerprints is their similarity. */
    fun unit(v: FloatArray): FloatArray {
        val n = sqrt(dot(v, v)).takeIf { it > 0 } ?: 1.0
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    /** Index of the group in [centroids] that [e] sounds most like. */
    fun nearest(e: FloatArray, centroids: List<FloatArray>): Int = centroids.indices.maxBy { g -> dot(e, centroids[g]) }

    /** Voice fingerprint of samples [a] until [b] of [x]. */
    internal fun embed(ext: SpeakerEmbeddingExtractor, x: FloatArray, a: Int, b: Int): FloatArray {
        val st = ext.createStream()
        try {
            st.acceptWaveform(x.copyOfRange(a, b), 16_000)
            st.inputFinished()
            return ext.compute(st)
        } finally {
            st.release()
        }
    }

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
                var j = i
                while (j + 1 < out.size && out[j + 1] == out[i]) j++
                val len = j - i + 1
                if (len < minRun && out.size > len) {
                    val fill = if (i > 0) out[i - 1] else out[j + 1]
                    for (k in i..j) out[k] = fill
                    changed = true
                    break
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
        val u = e.map(::unit)
        val sim = Array(n) { i -> DoubleArray(n) { j -> dot(u[i], u[j]) } }
        fun link(a: List<Int>, b: List<Int>): Double {
            var s = 0.0
            for (i in a) for (j in b) s += sim[i][j]
            return s / (a.size * b.size)
        }

        val maxC = min(n, if (k > 0) max(k, MAX_SPEAKERS) else MAX_SPEAKERS)
        val levels = groupingLevels(n, maxC, ::link)
        if (k > 0) return levels[min(k, n)]!!

        val two = levels[2]!!
        val g0 = (0 until n).filter { two[it] == 0 }
        val g1 = (0 until n).filter { two[it] == 1 }
        var cross = 0.0
        var cw = 0.0
        for (i in g0) for (j in g1) {
            cross += sim[i][j] * w[i] * w[j]
            cw += w[i] * w[j]
        }
        val bestC = (2..maxC).maxBy { silhouette(levels[it]!!, sim, w) }
        if (cross / cw > SAME_PERSON || silhouette(levels[bestC]!!, sim, w) < MIN_SILHOUETTE) return IntArray(n)
        return foldSmallGroups(levels[bestC]!!.copyOf(), w, ::link)
    }

    /**
     * Agglomerative grouping of [n] items with average linkage [link]: merges the two closest groups
     * until one is left, and returns the grouping (label per item) at every count up to [maxC].
     */
    private fun groupingLevels(n: Int, maxC: Int, link: (List<Int>, List<Int>) -> Double): Map<Int, IntArray> {
        val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
        val levels = HashMap<Int, IntArray>()
        fun snapshot() = IntArray(n).also { lab -> groups.forEachIndexed { g, m -> m.forEach { lab[it] = g } } }
        if (groups.size <= maxC) levels[groups.size] = snapshot()
        while (groups.size > 1) {
            var bi = 0
            var bj = 1
            var best = Double.NEGATIVE_INFINITY
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val l = link(groups[i], groups[j])
                if (l > best) {
                    best = l
                    bi = i
                    bj = j
                }
            }
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
            if (groups.size <= maxC) levels[groups.size] = snapshot()
        }
        return levels
    }

    /** How clearly the groups in [lab] separate, from -1 to 1, weighted by seconds of speech [w]. */
    private fun silhouette(lab: IntArray, sim: Array<DoubleArray>, w: DoubleArray): Double {
        val n = lab.size
        val c = lab.max() + 1
        var tot = 0.0
        var wsum = 0.0
        for (i in 0 until n) {
            val d = DoubleArray(c)
            val ws = DoubleArray(c)
            for (j in 0 until n) if (j != i) {
                d[lab[j]] += (1 - sim[i][j]) * w[j]
                ws[lab[j]] += w[j]
            }
            val own = lab[i]
            val s = if (ws[own] == 0.0) 0.0 else {
                val a = d[own] / ws[own]
                val b = (0 until c).filter { it != own && ws[it] > 0 }.minOfOrNull { d[it] / ws[it] } ?: return 0.0
                (b - a) / max(a, b).coerceAtLeast(1e-9)
            }
            tot += s * w[i]
            wsum += w[i]
        }
        return tot / wsum
    }

    /** Folds groups with under max(3 s, 7 %) of the speech into the most similar bigger group, then numbers them 0, 1, 2… */
    private fun foldSmallGroups(lab: IntArray, w: DoubleArray, link: (List<Int>, List<Int>) -> Double): IntArray {
        val n = lab.size
        val total = w.sum()
        while (true) {
            val dur = HashMap<Int, Double>()
            lab.forEachIndexed { i, g -> dur[g] = (dur[g] ?: 0.0) + w[i] }
            if (dur.size <= 1) break
            val small = dur.filter { it.value < max(3.0, 0.07 * total) }.minByOrNull { it.value }?.key ?: break
            val members = (0 until n).filter { lab[it] == small }
            val target = dur.keys.filter { it != small }.maxBy { g -> link(members, (0 until n).filter { lab[it] == g }) }
            members.forEach { lab[it] = target }
        }
        val re = LinkedHashMap<Int, Int>()
        return IntArray(n) { re.getOrPut(lab[it]) { re.size } }
    }
}
