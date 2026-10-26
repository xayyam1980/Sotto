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
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

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
        val outLang = if (AiSettings.summaryLanguage(ctx) == "same") (AiSettings.ALL_LANGUAGES[recordingLang] ?: "the language of the transcript") else "English"
        val system = """
            You turn rough automatic transcripts of real conversations into short notes.
            The transcript may contain recognition mistakes; infer the meaning, never invent facts.
            Reply ONLY with JSON: {"title": "...", "summary": "...", "actions": ["...", "..."]}
            - title: 3-8 words describing the topic
            - summary: 1-3 sentences
            - actions: concrete commitments or to-dos that were said; empty list if none
            Write in $outLang.
        """.trimIndent()
        val text = transcript.take(12_000) // keeps the prompt small enough to be quick on a phone
        return try {
            withModel {
                conversation(ctx, system, 0.2).use { conv ->
                    parse(conv.sendMessage("Transcript:\n$text").toString())
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Summary failed", t)
            null
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
    fun ask(ctx: Context, question: String, notes: List<String>): Flow<String> {
        val system = """
            You answer questions about the user's recorded conversations, using ONLY the notes below.
            Transcripts are automatic and may contain mistakes. If the notes don't contain the answer, say so.
            Cite the notes you used like [1] or [2]. Be concise. Answer in the language of the question.
        """.trimIndent()
        val context = buildString {
            var used = 0
            for ((i, n) in notes.withIndex()) {
                val block = "[${i + 1}] $n\n\n"
                if (used + block.length > 24_000) break
                append(block); used += block.length
            }
        }
        return stream(ctx, system, 0.3, "Notes:\n$context\nQuestion: $question")
    }

    /**
     * A digest of several notes (a whole day, or a selection), streaming the text.
     * Notes are numbered [1], [2]…
     */
    fun summarizeNotes(ctx: Context, notes: List<String>): Flow<String> {
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
        val budget = 24_000
        val per = max(600, budget / max(1, notes.size))
        val context = buildString {
            for ((i, n) in notes.withIndex()) {
                val block = "[${i + 1}] ${n.take(per)}\n\n"
                if (length + block.length > budget) break
                append(block)
            }
        }
        return stream(ctx, system, 0.2, "Notes:\n$context")
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
