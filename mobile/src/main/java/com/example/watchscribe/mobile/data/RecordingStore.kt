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
        runCatching { com.example.watchscribe.mobile.SottoWidget.update(ctx) }
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
            speakerError = tr?.optString("spkErr", "")?.ifBlank { null },
            redo = if (meta.has("redo")) meta.getInt("redo") else null,
            names = tr?.optJSONArray("names")?.let { a -> List(a.length()) { if (a.isNull(it)) null else a.optString(it).ifBlank { null } } } ?: emptyList(),
            audioDeleted = meta.optBoolean("audioDeleted", false),
            memo = meta.optBoolean("memo", false),
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

    fun setMemo(ctx: Context, id: String) {
        editMeta(ctx, id) { it.put("memo", true) }
        refresh(ctx)
    }

    /**
     * The watch's "Forget the last minutes": watch notes recorded between [from] and [to] are deleted,
     * and a note that started earlier is cut at [from] and transcribed again. Phone notes are left alone.
     */
    fun forget(ctx: Context, from: Long, to: Long) {
        refresh(ctx)
        for (r in _items.value) {
            if (r.start > to || com.example.watchscribe.mobile.PhoneRecorder.isPhoneNote(ctx, r.id)) continue
            val end = r.start + (r.seconds * 1000).toLong()
            if (end <= from) continue
            val keep = (from - r.start) / 1000.0
            runCatching {
                if (keep < 1.5) {
                    File(chunkDir(ctx), r.id).delete()
                    File(rawDir(ctx), r.id).delete()
                    File(metaDir(ctx), "${r.id}.json").delete()
                } else {
                    if (!r.audioDeleted) {
                        Wav.trim(r.clean, keep)
                        r.raw?.let { Wav.trim(it, keep) }
                    }
                    editMeta(ctx, r.id) { m ->
                        m.remove("peaks"); m.remove("seconds"); m.remove("ai")
                        m.optJSONArray("marks")?.let { a ->
                            m.put("marks", JSONArray((0 until a.length()).map { a.getDouble(it) }.filter { it <= keep }))
                        }
                        if (r.audioDeleted) {
                            // no audio to transcribe again: cut the transcript instead
                            m.optJSONObject("transcript")?.let { t ->
                                val segs = t.optJSONArray("segments") ?: JSONArray()
                                t.put("segments", JSONArray((0 until segs.length()).map { segs.getJSONObject(it) }.filter { it.optDouble("t0") < keep }))
                            }
                            m.put("seconds", keep)
                        } else m.remove("transcript")
                    }
                }
            }
        }
        refresh(ctx)
        com.example.watchscribe.mobile.ai.ProcessWorker.schedule(ctx)
    }

    fun setSync(ctx: Context, id: String, state: String?) {
        editMeta(ctx, id) { if (state != null) it.put("sync", state) else it.remove("sync") }
        refresh(ctx)
    }

    fun setTranscript(
        ctx: Context, id: String, lang: String, segments: List<Seg>, speakers: Int, speakerError: String? = null,
        voices: List<FloatArray>? = null,
    ) {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject().put("t0", s.t0).put("t1", s.t1).put("text", s.text).apply { s.speaker?.let { put("spk", it) } })
        }
        val names = voices?.let { People.names(ctx, it) }
        editMeta(ctx, id) { m ->
            m.put("transcript", JSONObject().put("lang", lang).put("segments", arr).put("speakers", speakers)
                .apply { if (speakerError != null) put("spkErr", speakerError) }
                .apply {
                    if (voices != null) {
                        put("voices", JSONArray().apply { voices.forEach { v -> put(JSONArray().apply { v.forEach { put(it.toDouble()) } }) } })
                        put("names", JSONArray().apply { names!!.forEach { put(it ?: JSONObject.NULL) } })
                    } else m.optJSONObject("transcript")?.let { old ->
                        // keep known people when only the text changes
                        old.optJSONArray("voices")?.let { put("voices", it) }
                        old.optJSONArray("names")?.let { put("names", it) }
                        old.optJSONArray("manual")?.let { put("manual", it) }
                    }
                })
            m.remove("sync")
            m.remove("redo")
        }
        refresh(ctx)
    }

    /** Marks a note to be transcribed again; [numSpeakers] <= 0 = find the number of people automatically. */
    fun requestRedo(ctx: Context, id: String, numSpeakers: Int) {
        editMeta(ctx, id) { it.put("redo", if (numSpeakers > 0) numSpeakers else -1) }
        refresh(ctx)
    }

    fun clearRedo(ctx: Context, id: String) {
        editMeta(ctx, id) { it.remove("redo") }
        refresh(ctx)
    }

    /** Voice fingerprints of a note's speakers (S1, S2…), if known. */
    fun voices(ctx: Context, id: String): List<FloatArray> {
        val f = File(metaDir(ctx), "$id.json")
        val a = runCatching { JSONObject(f.readText()).optJSONObject("transcript")?.optJSONArray("voices") }.getOrNull() ?: return emptyList()
        return List(a.length()) { i -> a.getJSONArray(i).let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } } }
    }

    /**
     * The user says who a speaker is ("S2 is Anna"). Learns the voice, then names that person
     * in every other note where they were not named yet.
     */
    fun setSpeakerName(ctx: Context, id: String, spk: Int, name: String?) {
        val voice = voices(ctx, id).getOrNull(spk)
        editMeta(ctx, id) { m ->
            val tr = m.optJSONObject("transcript") ?: return@editMeta
            val n = tr.optInt("speakers", 0).coerceAtLeast(spk + 1)
            val old = tr.optJSONArray("names")
            val names = JSONArray().apply { for (i in 0 until n) put(if (i == spk) (name?.trim()?.ifBlank { null } ?: JSONObject.NULL) else old?.opt(i) ?: JSONObject.NULL) }
            tr.put("names", names)
            val manual = (tr.optJSONArray("manual")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList()).toMutableSet().apply { add(spk) }
            tr.put("manual", JSONArray(manual.toList()))
        }
        if (name != null && voice != null && voice.isNotEmpty()) {
            People.teach(ctx, name, voice)
            rematchAll(ctx)
        } else refresh(ctx)
    }

    /** Re-applies the known people to every note (names the user set by hand stay). */
    fun rematchAll(ctx: Context) {
        val people = People.load(ctx)
        metaDir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
            runCatching {
                val m = JSONObject(f.readText())
                val tr = m.optJSONObject("transcript") ?: return@runCatching
                val va = tr.optJSONArray("voices") ?: return@runCatching
                val voices = List(va.length()) { i -> va.getJSONArray(i).let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } } }
                val manual = tr.optJSONArray("manual")?.let { a -> List(a.length()) { a.getInt(it) }.toSet() } ?: emptySet()
                val old = tr.optJSONArray("names")
                val auto = People.match(voices, people)
                val names = JSONArray().apply {
                    voices.indices.forEach { i -> put(if (i in manual) old?.opt(i) ?: JSONObject.NULL else auto[i] ?: JSONObject.NULL) }
                }
                tr.put("names", names)
                f.writeText(m.toString())
            }
        }
        refresh(ctx)
    }

    /**
     * Deletes the audio of transcribed notes older than [days] days; the transcript, summary and
     * a tiny silent placeholder stay, so the note keeps its place in the list. 0 = keep forever.
     */
    fun deleteOldAudio(ctx: Context, days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        _items.value.filter { it.start < cutoff && it.segments != null && !it.audioDeleted && it.redo == null }.forEach { r ->
            runCatching {
                r.raw?.delete()
                r.clean.writeBytes(Wav.header(16_000, 0)) // empty placeholder keeps the note listed
                editMeta(ctx, r.id) { it.put("audioDeleted", true) }
            }
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
