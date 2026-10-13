package com.example.watchscribe.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The library of recordings. Files live in filesDir:
 *   chunks/<id>  cleaned audio      raw/<id>  original audio
 *   meta/<id>.json  title, star, place, waveform
 *   trash/...    recently deleted (for Undo), emptied on the next delete or launch
 */
object RecordingStore {
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items

    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val titleFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun chunkDir(ctx: Context) = File(ctx.filesDir, "chunks").apply { mkdirs() }
    fun rawDir(ctx: Context) = File(ctx.filesDir, "raw").apply { mkdirs() }
    private fun metaDir(ctx: Context) = File(ctx.filesDir, "meta").apply { mkdirs() }
    private fun trashDir(ctx: Context) = File(ctx.filesDir, "trash").apply { mkdirs() }

    @Synchronized
    fun refresh(ctx: Context) {
        val files = chunkDir(ctx).listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
        _items.value = files.map { load(ctx, it) }.sortedByDescending { it.start }
    }

    private fun load(ctx: Context, f: File): Recording {
        val id = f.name
        val metaFile = File(metaDir(ctx), "$id.json")
        var meta = if (metaFile.exists()) runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
        if (meta == null || !meta.has("peaks") || !meta.has("seconds")) {
            meta = (meta ?: JSONObject()).apply {
                put("seconds", Wav.seconds(f))
                put("peaks", JSONArray(Wav.peaks(f).map { it.toDouble() }))
            }
            metaFile.writeText(meta.toString())
        }
        val start = runCatching {
            nameFmt.parse(id.removePrefix("chunk_").removeSuffix(".wav"))!!.time
        }.getOrDefault(f.lastModified())
        val peaksJson = meta.optJSONArray("peaks") ?: JSONArray()
        val custom = meta.optString("title", "").isNotBlank()
        val ai = meta.optJSONObject("ai")
        val aiTitle = ai?.optString("title", "")?.ifBlank { null }
        val tr = meta.optJSONObject("transcript")
        val segs = tr?.optJSONArray("segments")?.let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Seg(o.optDouble("t0", 0.0), o.optDouble("t1", 0.0), o.optString("text", ""),
                    if (o.has("spk")) o.getInt("spk") else null)
            }
        }
        return Recording(
            id = id,
            clean = f,
            raw = File(rawDir(ctx), id).takeIf { it.exists() },
            start = start,
            seconds = meta.optDouble("seconds", 0.0),
            sizeBytes = f.length(),
            title = when {
                custom -> meta.getString("title")
                aiTitle != null -> aiTitle
                else -> "Recording · ${titleFmt.format(start)}"
            },
            customTitle = custom,
            starred = meta.optBoolean("starred", false),
            place = meta.optString("place", "").ifBlank { null },
            lat = if (meta.has("lat")) meta.getDouble("lat") else null,
            lon = if (meta.has("lon")) meta.getDouble("lon") else null,
            peaks = List(peaksJson.length()) { peaksJson.getDouble(it).toFloat() },
            segments = segs,
            lang = tr?.optString("lang", "")?.ifBlank { null },
            sync = meta.optString("sync", "").ifBlank { null },
            marks = meta.optJSONArray("marks")?.let { a -> List(a.length()) { a.getDouble(it) } } ?: emptyList(),
            summary = ai?.optString("summary", "")?.ifBlank { null },
            actions = ai?.optJSONArray("actions")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            speakers = tr?.optInt("speakers", 0) ?: 0,
        )
    }

    private fun editMeta(ctx: Context, id: String, change: (JSONObject) -> Unit) {
        val metaFile = File(metaDir(ctx), "$id.json")
        val meta = if (metaFile.exists()) runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        change(meta)
        metaFile.writeText(meta.toString())
    }

    fun setMarks(ctx: Context, id: String, marks: List<Double>) {
        editMeta(ctx, id) { it.put("marks", JSONArray(marks)) }
        refresh(ctx)
    }

    fun setSync(ctx: Context, id: String, state: String?) {
        editMeta(ctx, id) { if (state != null) it.put("sync", state) else it.remove("sync") }
        refresh(ctx)
    }

    fun setTranscript(ctx: Context, id: String, lang: String, segments: List<Seg>, speakers: Int) {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject().put("t0", s.t0).put("t1", s.t1).put("text", s.text).apply { s.speaker?.let { put("spk", it) } })
        }
        editMeta(ctx, id) { m ->
            m.put("transcript", JSONObject().put("lang", lang).put("segments", arr).put("speakers", speakers))
            m.remove("sync")
        }
        refresh(ctx)
    }

    fun setAi(ctx: Context, id: String, title: String, summary: String, actions: List<String>) {
        editMeta(ctx, id) { m ->
            m.put("ai", JSONObject().put("title", title).put("summary", summary).put("actions", JSONArray(actions)))
        }
        refresh(ctx)
    }

    /** Clears AI results so the note is processed again (e.g. after changing the model). */
    fun clearAi(ctx: Context, id: String, transcriptToo: Boolean) {
        editMeta(ctx, id) { m -> m.remove("ai"); if (transcriptToo) m.remove("transcript") }
        refresh(ctx)
    }

    fun rename(ctx: Context, id: String, title: String) {
        editMeta(ctx, id) { it.put("title", title.trim()) }
        refresh(ctx)
    }

    fun setStarred(ctx: Context, ids: Collection<String>, starred: Boolean) {
        ids.forEach { id -> editMeta(ctx, id) { it.put("starred", starred) } }
        refresh(ctx)
    }

    fun setPlace(ctx: Context, id: String, place: String?, lat: Double?, lon: Double?) {
        editMeta(ctx, id) { m ->
            if (place != null) m.put("place", place) else m.remove("place")
            if (lat != null && lon != null) { m.put("lat", lat); m.put("lon", lon) }
        }
        refresh(ctx)
    }

    /** Renames a place on every recording that has it. */
    fun renamePlace(ctx: Context, from: String, to: String) {
        _items.value.filter { it.place == from }.forEach { r -> editMeta(ctx, r.id) { it.put("place", to.trim()) } }
        PlaceTagger.setAlias(ctx, from, to.trim())
        refresh(ctx)
    }

    /** Moves recordings to the trash so the delete can be undone. */
    fun delete(ctx: Context, ids: Collection<String>) {
        emptyTrash(ctx)
        val trash = trashDir(ctx)
        ids.forEach { id ->
            File(chunkDir(ctx), id).renameTo(File(trash, "clean_$id"))
            File(rawDir(ctx), id).takeIf { it.exists() }?.renameTo(File(trash, "raw_$id"))
            File(metaDir(ctx), "$id.json").takeIf { it.exists() }?.renameTo(File(trash, "meta_$id.json"))
        }
        refresh(ctx)
    }

    fun undoDelete(ctx: Context) {
        trashDir(ctx).listFiles().orEmpty().forEach { f ->
            when {
                f.name.startsWith("clean_") -> f.renameTo(File(chunkDir(ctx), f.name.removePrefix("clean_")))
                f.name.startsWith("raw_") -> f.renameTo(File(rawDir(ctx), f.name.removePrefix("raw_")))
                f.name.startsWith("meta_") -> f.renameTo(File(metaDir(ctx), f.name.removePrefix("meta_")))
            }
        }
        refresh(ctx)
    }

    fun emptyTrash(ctx: Context) {
        trashDir(ctx).listFiles().orEmpty().forEach { it.delete() }
    }

    fun byId(id: String): Recording? = _items.value.firstOrNull { it.id == id }
}
