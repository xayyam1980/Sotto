package com.example.watchscribe.mobile.ai

import android.content.Context
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
import kotlin.math.max
import kotlin.math.min

/** A word and the time (seconds) it starts. */
internal class Word(val t: Double, val text: String)

/**
 * The speech models on the phone. The two speech-to-text models stay loaded between recordings
 * and are released in [close]; the speaker models are made fresh by the caller and released by it.
 */
internal class SpeechEngines(private val ctx: Context) : AutoCloseable {
    // At most 4 threads: phones have 4 or fewer fast cores, and adding the slow ones makes the models slower
    val threads = min(4, max(2, Runtime.getRuntime().availableProcessors() - 2))
    private val whisperByLang = HashMap<String, OfflineRecognizer>()
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

    /** Whisper for one language ("" = Whisper guesses it). */
    private fun whisperFor(lang: String): OfflineRecognizer = whisperByLang.getOrPut(lang) {
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

    /** Parakeet text and its language. Parakeet finds the language itself; we only name it for the note. */
    fun parakeetText(samples: FloatArray, allowed: List<String>): Pair<String, String> {
        val rec = parakeet()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            return text to TextChecks.guessLanguage(text, allowed)
        } finally {
            stream.release()
        }
    }

    /** Parakeet words with their times, shifted by [offset] seconds, plus the plain text. */
    fun parakeetWords(samples: FloatArray, offset: Double): Pair<List<Word>, String> {
        val rec = parakeet()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            rec.decode(stream)
            val r = rec.getResult(stream)
            val text = r.text.trim()
            val tokens = r.tokens
            val times = r.timestamps
            if (tokens.isEmpty() || times.size != tokens.size) {
                return (if (text.isBlank()) emptyList() else listOf(Word(offset, text))) to text
            }
            // Tokens are word parts; one starting with a space (or "▁") begins a new word
            val words = mutableListOf<Word>()
            val cur = StringBuilder()
            var t0 = 0.0
            for (i in tokens.indices) {
                val tok = tokens[i]
                if (tok.startsWith(" ") || tok.startsWith("▁")) {
                    if (cur.isNotBlank()) words += Word(offset + t0, cur.toString())
                    cur.setLength(0)
                    cur.append(tok.trimStart(' ', '▁'))
                    t0 = times[i].toDouble()
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

    /** Whisper text and the language it heard; [lang] "" lets Whisper guess the language. */
    fun whisper(samples: FloatArray, lang: String): Pair<String, String> {
        val rec = whisperFor(lang)
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

    /**
     * The speaker model (pyannote) that finds where people speak. Its own grouping is deliberately
     * fine-grained: we only use its turn boundaries and regroup the voices ourselves.
     * Short pauses are kept, because that is where people take turns. The caller releases it.
     */
    fun speakerModel() = OfflineSpeakerDiarization(
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

    /** The voice fingerprint model (3D-Speaker). The caller releases it. */
    fun voiceModel() = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = Models.voiceEmbedding(ctx), numThreads = threads),
    )

    override fun close() {
        whisperByLang.values.forEach { runCatching { it.release() } }
        whisperByLang.clear()
        parakeetRec?.let { runCatching { it.release() } }
        parakeetRec = null
    }
}
