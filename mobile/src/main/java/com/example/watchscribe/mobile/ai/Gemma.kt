package com.example.watchscribe.mobile.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Semaphore
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemma 4 on the phone (LiteRT-LM): note summaries and Ask your notes.
 *
 * The model is used by one thing at a time (background summaries, Ask, day summaries): the others
 * wait their turn. Unloading waits too, so the model is never freed under an answer that's still coming.
 */
object Gemma {
    private const val TAG = "SottoGemma"
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private val turn = Semaphore(1, true)
    @Volatile private var unloadWhenFree = false

    /** Runs [block] while holding the model; frees it afterwards if an unload was asked for meanwhile. */
    private inline fun <T> withModel(block: () -> T): T {
        turn.acquire()
        try { return block() } finally {
            if (unloadWhenFree) { unloadWhenFree = false; closeEngine() }
            turn.release()
        }
    }

    data class Summary(val title: String, val summary: String, val actions: List<String>)

    /** A note given to the model: [about] (title, time, summary, to-dos) always fits; the [transcript] is cut to fit. */
    data class Note(val about: String, val transcript: String)

    /**
     * The model's window on the phone is 4096 tokens for the question and the answer together.
     * This much is kept for the notes; the rest is for the instructions and the answer.
     */
    private const val INPUT_TOKENS = 2_800

    /** Rough token count, on the safe side: about 3 characters a token for Latin text, fewer for Cyrillic. */
    internal fun tokens(s: String): Int {
        var t = 0.0
        for (c in s) t += if (c.code < 128) 0.3 else 0.45
        return t.toInt() + 1
    }

    /** The first [budget] tokens of [s] (by the same rough count), cut at a word. */
    internal fun cut(s: String, budget: Int): String {
        if (tokens(s) <= budget) return s
        var t = 0.0
        var i = 0
        while (i < s.length) {
            val next = t + if (s[i].code < 128) 0.3 else 0.45
            if (next > budget - 1) break
            t = next
            i++
        }
        return s.take(i).substringBeforeLast(' ', s.take(i)) + "…"
    }

    /**
     * Notes numbered [1], [2]… within [budget] tokens: every note's [Note.about] first, then the
     * transcripts share what is left (a short transcript leaves its unused share to the others).
     */
    internal fun fit(notes: List<Note>, budget: Int): String {
        val abouts = notes.map { cut(it.about, max(40, budget / 2 / max(1, notes.size))) }
        var open = notes.indices.filter { notes[it].transcript.isNotBlank() }
        // "[1] ", line breaks, "Transcript: " and "…" count too
        var left = budget - abouts.withIndex().sumOf { (i, a) -> tokens("[${i + 1}] $a\n\n") } - open.size * tokens("Transcript: …\n")
        val shares = IntArray(notes.size)
        while (left > 0 && open.isNotEmpty()) {
            val each = left / open.size
            if (each <= 0) break
            open = open.filter { i ->
                val need = tokens(notes[i].transcript) - shares[i]
                val give = minOf(need, each)
                shares[i] += give
                left -= give
                need > give
            }
        }
        return buildString {
            notes.forEachIndexed { i, n ->
                append("[${i + 1}] ${abouts[i]}\n")
                if (shares[i] > 0) append("Transcript: ${cut(n.transcript, shares[i])}\n")
                append("\n")
            }
        }
    }

    private fun tooLong(t: Throwable) = generateSequence(t) { it.cause }.any { it.message.orEmpty().contains("too long", true) }

    fun available(ctx: Context) = Models.isReady(ctx, AiSettings.summaryModel(ctx))

    @Synchronized
    private fun engine(ctx: Context): Engine {
        val path = Models.gemma(ctx, AiSettings.summaryModel(ctx))
        engine?.let { if (loadedPath == path) return it }
        engine?.close()
        val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path))
        e.initialize() // takes several seconds
        engine = e
        loadedPath = path
        return e
    }

    /** Frees the ~2 GB the model uses: now if it's free, else as soon as the current answer is done. */
    fun unload() {
        if (turn.tryAcquire()) {
            try { closeEngine() } finally { turn.release() }
        } else unloadWhenFree = true
    }

    @Synchronized
    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    private fun conversation(ctx: Context, system: String, temperature: Double) =
        engine(ctx).createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = temperature),
            )
        )

    /** Title, short summary and action items for one transcript. Blocking. */
    fun summarize(ctx: Context, transcript: String, recordingLang: String?): Summary? {
        val outLang = if (AiSettings.summaryLanguage(ctx) != "same") "English"
            else AiSettings.ALL_LANGUAGES[recordingLang] ?: "the language of the transcript"
        val system = """
            You turn rough automatic transcripts of real conversations into short notes.
            The transcript may contain recognition mistakes; infer the meaning, never invent facts.
            Reply ONLY with JSON: {"title": "...", "summary": "...", "actions": ["...", "..."]}
            - title: 3-8 words describing the topic
            - summary: 1-3 sentences
            - actions: concrete commitments or to-dos that were said; empty list if none
            Write in $outLang.
        """.trimIndent()
        var budget = INPUT_TOKENS
        while (true) {
            try {
                val text = cut(transcript, budget)
                return withModel {
                    conversation(ctx, system, 0.2).use { conv -> parse(conv.sendMessage("Transcript:\n$text").toString()) }
                }
            } catch (t: Throwable) {
                // The count is an estimate: if the model still finds it too long, try with less
                if (tooLong(t) && budget > 600) { budget /= 2; continue }
                Log.e(TAG, "Summary failed", t)
                return null
            }
        }
    }

    internal fun parse(raw: String): Summary? {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val o = JSONObject(raw.substring(a, b + 1))
            val acts = o.optJSONArray("actions") ?: JSONArray()
            Summary(
                o.optString("title").trim().take(80),
                o.optString("summary").trim(),
                List(acts.length()) { acts.optString(it).trim() }.filter { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /**
     * Answers a question from the given notes, streaming the text.
     * Notes are numbered [1], [2]…; the answer cites them that way.
     */
    fun ask(ctx: Context, question: String, notes: List<Note>): Flow<String> {
        val system = """
            You answer questions about the user's recorded conversations, using ONLY the notes below.
            Transcripts are automatic and may contain mistakes. If the notes don't contain the answer, say so.
            Cite the notes you used like [1] or [2]. Be concise. Answer in the language of the question.
        """.trimIndent()
        return fitted(ctx, system, 0.3) { budget -> "Notes:\n${fit(notes, budget - tokens(question))}\nQuestion: $question" }
    }

    /**
     * A digest of several notes (a whole day, or a selection), streaming the text.
     * Notes are numbered [1], [2]…
     */
    fun summarizeNotes(ctx: Context, notes: List<Note>): Flow<String> {
        val outLang = if (AiSettings.summaryLanguage(ctx) == "same") "the language most of the notes are in" else "English"
        val system = """
            You write a short digest of several recorded conversations from one person's day.
            Transcripts are automatic and may contain mistakes; infer the meaning, never invent facts.
            Use these plain-text sections, in this order, and leave out any that would be empty:
            Overview: 2-4 sentences about what the day was about.
            Decisions: one per line, each starting with "- ".
            To-dos: one per line, each starting with "- ", with who and by when if that was said.
            People and places: one line.
            Point to notes like [1] or [2] where it helps. No other formatting. Write in $outLang.
        """.trimIndent()
        return fitted(ctx, system, 0.2) { budget -> "Notes:\n${fit(notes, budget)}" }
    }

    /** [stream] with a message made to fit the model's window; if the model still says it's too long, with less. */
    private fun fitted(ctx: Context, system: String, temperature: Double, message: (Int) -> String): Flow<String> = flow {
        var budget = INPUT_TOKENS
        while (true) {
            try {
                emitAll(stream(ctx, system, temperature, message(budget)))
                return@flow
            } catch (t: Throwable) {
                if (!tooLong(t) || budget <= 600) throw t
                budget /= 2
            }
        }
    }

    /**
     * Streams the growing answer. The model is taken only when the flow is collected (on a background
     * dispatcher) and given back when it ends or is cancelled, e.g. when the user leaves the screen.
     */
    private fun stream(ctx: Context, system: String, temperature: Double, message: String): Flow<String> = flow {
        turn.acquire()
        try {
            val conv = conversation(ctx, system, temperature)
            try {
                val acc = StringBuilder()
                conv.sendMessageAsync(message).collect { m -> acc.append(m.toString()); emit(acc.toString()) }
            } finally { runCatching { conv.close() } }
        } finally {
            if (unloadWhenFree) { unloadWhenFree = false; closeEngine() }
            turn.release()
        }
    }
}
