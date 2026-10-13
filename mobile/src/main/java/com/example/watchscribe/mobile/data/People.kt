package com.example.watchscribe.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * People Sotto recognises by voice. Each person is a name plus an average voice fingerprint,
 * learnt when the user names a speaker in a note. Stored only on this phone (filesDir/people.json).
 */
object People {
    const val ME = "Me"
    /** Similarity (cosine) above which a speaker is taken to be a known person. */
    const val MATCH = 0.55

    data class Person(val id: String, val name: String, val voice: FloatArray, val samples: Int)

    private val _all = MutableStateFlow<List<Person>>(emptyList())
    val all: StateFlow<List<Person>> = _all
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "people.json")

    /** Re-reads people.json next time (after a restore). */
    fun invalidate() { loaded = false }

    @Synchronized
    fun load(ctx: Context): List<Person> {
        if (loaded) return _all.value
        val f = file(ctx)
        _all.value = if (!f.exists()) emptyList() else runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val v = o.getJSONArray("voice")
                Person(o.getString("id"), o.getString("name"), FloatArray(v.length()) { v.getDouble(it).toFloat() }, o.optInt("n", 1))
            }
        }.getOrDefault(emptyList())
        loaded = true
        return _all.value
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Person>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("n", p.samples)
                .put("voice", JSONArray().apply { p.voice.forEach { put(it.toDouble()) } }))
        }
        file(ctx).writeText(arr.toString())
        _all.value = list
    }

    /** Learns (or refines) a person's voice from a speaker the user has named. */
    fun teach(ctx: Context, name: String, voice: FloatArray) {
        if (voice.isEmpty() || name.isBlank()) return
        val list = load(ctx)
        val u = unit(voice)
        val i = list.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
        save(ctx, if (i < 0) list + Person(UUID.randomUUID().toString(), name.trim(), u, 1) else list.toMutableList().also { l ->
            val p = l[i]
            if (p.voice.size != u.size) { l[i] = p.copy(voice = u, samples = 1); return@also }
            val n = p.samples.coerceAtMost(20) // later notes keep refining the voice
            l[i] = p.copy(voice = unit(FloatArray(u.size) { k -> p.voice[k] * n + u[k] }), samples = p.samples + 1)
        })
    }

    fun rename(ctx: Context, id: String, name: String) =
        save(ctx, load(ctx).map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun delete(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.id == id })

    /** Names for each speaker voice (null = unknown), using the people the user has taught. */
    fun names(ctx: Context, voices: List<FloatArray>): List<String?> = match(voices, load(ctx))

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return -1.0
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) -1.0 else d / sqrt(na * nb)
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0; for (x in v) n += x * x
        val s = sqrt(n).takeIf { it > 0 } ?: 1.0
        return FloatArray(v.size) { (v[it] / s).toFloat() }
    }

    /** Best one-to-one matching of speakers to people above [MATCH] (most similar pairs first). */
    fun match(voices: List<FloatArray>, people: List<Person>): List<String?> {
        val out = MutableList<String?>(voices.size) { null }
        val pairs = voices.indices.flatMap { s -> people.indices.map { p -> Triple(s, p, cosine(voices[s], people[p].voice)) } }
            .filter { it.third >= MATCH }.sortedByDescending { it.third }
        val usedP = HashSet<Int>()
        for ((s, p, _) in pairs) if (out[s] == null && p !in usedP) { out[s] = people[p].name; usedP += p }
        return out
    }
}
