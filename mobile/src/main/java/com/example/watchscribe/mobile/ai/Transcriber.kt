package com.example.watchscribe.mobile.ai

import android.content.Context
import android.util.Log
import com.example.watchscribe.mobile.data.Seg
import java.io.File

/**
 * On-phone transcription with speakers. Two ways, chosen by the model set in Settings:
 *  - Parakeet v3 (fast, accurate, European languages): [ByWords] transcribes the whole recording with
 *    word times and decides per sentence who spoke; Whisper redoes pieces where Parakeet struggles.
 *  - Whisper turbo (all languages), or when the Parakeet way fails: [ByTurns] finds speaker turns,
 *    groups them into people and transcribes each turn on its own.
 *
 * The other parts: [SpeechEngines] (the models), [Audio] (reading and cutting audio),
 * [Voices] (fingerprints and grouping people), [TextChecks] (language, signs of a bad transcript).
 */
class Transcriber(private val ctx: Context) : AutoCloseable {
    private val engines = SpeechEngines(ctx)
    private val engine = AiSettings.asrModel(ctx)

    data class Result(
        val lang: String,
        val segments: List<Seg>,
        val speakers: Int,
        val speakerError: String? = null,
        val voices: List<FloatArray> = emptyList(), // voice fingerprint per speaker (S1, S2…); empty = unknown
    )

    /** Transcribes [wav] in the [allowed] languages. [numSpeakers] <= 0 = find out how many people talk. */
    fun transcribe(wav: File, allowed: List<String>, numSpeakers: Int = -1, onProgress: (Float) -> Unit = {}): Result {
        val x = Audio.load16k(wav)
        val parakeet = engine == ModelId.PARAKEET
        if (parakeet && Models.isReady(ctx, ModelId.SPEECH) && Models.isReady(ctx, ModelId.VOICES_EN)) {
            try {
                return ByWords(ctx, engines).transcribe(x, allowed, numSpeakers, onProgress)
            } catch (t: Throwable) {
                Log.w("SottoAsr", "Word-level speakers failed; falling back", t)
            }
        }
        return ByTurns(ctx, engines, parakeet).transcribe(x, allowed, numSpeakers, onProgress)
    }

    override fun close() = engines.close()
}
