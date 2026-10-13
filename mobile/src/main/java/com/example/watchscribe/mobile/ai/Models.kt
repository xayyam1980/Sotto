package com.example.watchscribe.mobile.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/** The on-phone models Sotto can download. */
enum class ModelId(
    val label: String,
    val purpose: String,
    val url: String,
    val approxBytes: Long,
    val fileName: String,        // downloaded file
    val archive: Boolean,
) {
    PARAKEET(
        "Parakeet v3", "Transcription · fast, English, Polish + 23 European languages",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        500_000_000, "parakeet-v3.tar.bz2", true,
    ),
    WHISPER(
        "Whisper turbo", "Transcription · all languages, slower",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
        563_790_207, "whisper-turbo.tar.bz2", true,
    ),
    SPEAKERS(
        "Speaker detection", "Who is speaking",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        6_958_444, "pyannote.tar.bz2", true,
    ),
    VOICES(
        "Voice fingerprints", "Telling speakers apart",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        39_593_761, "voice-embedding.onnx", false,
    ),
    GEMMA_E2B(
        "Gemma 4 E2B", "Summaries & Ask (faster)",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        2_590_000_000, "gemma-4-E2B-it.litertlm", false,
    ),
    GEMMA_E4B(
        "Gemma 4 E4B", "Summaries & Ask (better)",
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        3_660_000_000, "gemma-4-E4B-it.litertlm", false,
    ),
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val fraction: Float) : ModelState
    data object Unpacking : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Downloads models with Android's DownloadManager (resumes, survives app restarts,
 * Wi-Fi only) into the app's own storage, then unpacks archives.
 */
object Models {
    private const val TAG = "SottoModels"
    private const val PREFS = "models"

    private val _states = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())
    val states: StateFlow<Map<ModelId, ModelState>> = _states

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null)!!, "models").apply { mkdirs() }
    private fun marker(ctx: Context, m: ModelId) = File(dir(ctx), "${m.name}.ok")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isReady(ctx: Context, m: ModelId) = marker(ctx, m).exists()

    // ---- paths used by the engines
    fun whisperEncoder(ctx: Context) = File(dir(ctx), "sherpa-onnx-whisper-turbo/turbo-encoder.int8.onnx").path
    fun whisperDecoder(ctx: Context) = File(dir(ctx), "sherpa-onnx-whisper-turbo/turbo-decoder.int8.onnx").path
    fun whisperTokens(ctx: Context) = File(dir(ctx), "sherpa-onnx-whisper-turbo/turbo-tokens.txt").path
    private const val PARAKEET_DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    fun parakeet(ctx: Context, part: String) = File(dir(ctx), "$PARAKEET_DIR/$part").path
    fun segmentation(ctx: Context) = File(dir(ctx), "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx").path
    fun voiceEmbedding(ctx: Context) = File(dir(ctx), ModelId.VOICES.fileName).path
    fun gemma(ctx: Context, m: ModelId) = File(dir(ctx), m.fileName).path

    /** Models needed for transcription with speaker labels, with the speech model chosen in Settings. */
    fun transcription(ctx: Context) = listOf(AiSettings.asrModel(ctx), ModelId.SPEAKERS, ModelId.VOICES)

    fun download(ctx: Context, m: ModelId) {
        if (isReady(ctx, m) || prefs(ctx).getLong(m.name, -1L) >= 0) return
        File(dir(ctx), m.fileName).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("Sotto · ${m.label}")
            .setDescription(m.purpose)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setDestinationInExternalFilesDir(ctx, null, "models/${m.fileName}")
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        prefs(ctx).edit().putLong(m.name, id).apply()
        refresh(ctx)
    }

    fun delete(ctx: Context, m: ModelId) {
        cancel(ctx, m)
        marker(ctx, m).delete()
        File(dir(ctx), m.fileName).delete()
        if (m == ModelId.WHISPER) File(dir(ctx), "sherpa-onnx-whisper-turbo").deleteRecursively()
        if (m == ModelId.PARAKEET) File(dir(ctx), PARAKEET_DIR).deleteRecursively()
        if (m == ModelId.SPEAKERS) File(dir(ctx), "sherpa-onnx-pyannote-segmentation-3-0").deleteRecursively()
        refresh(ctx)
    }

    private fun cancel(ctx: Context, m: ModelId) {
        val id = prefs(ctx).getLong(m.name, -1L)
        if (id >= 0) ctx.getSystemService(DownloadManager::class.java).remove(id)
        prefs(ctx).edit().remove(m.name).apply()
    }

    /** Updates states; unpacks finished archives. Call from a background thread. */
    @Synchronized
    fun refresh(ctx: Context) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val out = mutableMapOf<ModelId, ModelState>()
        for (m in ModelId.entries) {
            if (isReady(ctx, m)) { out[m] = ModelState.Ready; continue }
            val id = prefs(ctx).getLong(m.name, -1L)
            if (id < 0) { out[m] = _states.value[m] as? ModelState.Failed ?: ModelState.Missing; continue }
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) { prefs(ctx).edit().remove(m.name).apply(); out[m] = ModelState.Missing; return@use }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: m.approxBytes
                out[m] = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _states.value = _states.value + (m to ModelState.Unpacking)
                        finish(ctx, m)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs(ctx).edit().remove(m.name).apply()
                        ModelState.Failed("Download failed (code ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})")
                    }
                    else -> ModelState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        _states.value = out
    }

    private fun finish(ctx: Context, m: ModelId): ModelState = try {
        val f = File(dir(ctx), m.fileName)
        // Sanity check: a download much smaller than expected is broken or not the real file
        if (f.length() < m.approxBytes / 2) throw IllegalStateException("file is incomplete (${f.length()} bytes)")
        if (m.archive) {
            untarBz2(f, dir(ctx))
            f.delete()
        }
        marker(ctx, m).writeText("ok")
        prefs(ctx).edit().remove(m.name).apply()
        ModelState.Ready
    } catch (t: Throwable) {
        Log.e(TAG, "Unpack failed for $m", t)
        prefs(ctx).edit().remove(m.name).apply()
        ModelState.Failed("Couldn't unpack: ${t.message}")
    }

    private fun untarBz2(archive: File, into: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory || e.name.contains("test_wavs") || e.name.contains("..") || e.name.startsWith("/")) continue
                // only the files the engines need
                if (!(e.name.endsWith(".onnx") || e.name.endsWith("tokens.txt"))) continue
                if (e.name.contains("encoder.onnx") || e.name.contains("decoder.onnx") || e.name.endsWith("/model.onnx")) continue // keep int8 only
                val out = File(into, e.name)
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }
}
