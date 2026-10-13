package com.example.watchscribe.mobile.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Dest(val label: String, val mark: String, val pkg: String?) {
    GMAIL("Gmail", "Gm", "com.google.android.gm"),
    NOTEBOOKLM("NotebookLM", "NL", "com.google.android.apps.labs.language.tailwind"),
    CLAUDE("Claude", "Cl", "com.anthropic.claude"),
    DRIVE("Drive", "Dr", "com.google.android.apps.docs"),
    NOTION("Notion", "No", "notion.id"),
    GEMINI("Gemini", "Ge", "com.google.android.apps.bard"),
    FILES("Files", "Fi", null),
    MORE("More", "•••", null),
}

enum class NoteFormat(val label: String, val ext: String, val mime: String) {
    MARKDOWN("Markdown", "md", "text/markdown"),
    PDF("PDF", "pdf", "application/pdf"),
    TEXT("Text", "txt", "text/plain"),
}

data class ExportOptions(
    val notes: Boolean = true,
    val audio: Boolean = true,  // on by default until transcripts exist
    val cleanAudio: Boolean = true,
    val format: NoteFormat = NoteFormat.MARKDOWN,
    val onePerNote: Boolean = false,
    val hidePersonal: Boolean = false, // blank out e-mails, IBANs, phone and card numbers
)

/** Builds export files in the cache and hands them to other apps. */
object Exporter {
    private val dayFmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)

    fun length(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun stamp(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Plain-text body: used as the email body and as a fallback for apps that ignore files. */
    fun bodyText(recs: List<Recording>): String = buildString {
        recs.sortedBy { it.start }.forEach { r ->
            appendLine(r.title)
            appendLine("${dayFmt.format(Date(r.start))} · ${length(r.seconds)} · ${r.placeOrUnknown}")
            r.summary?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            r.actions.forEach { appendLine("• $it") }
            appendLine(r.transcriptText.ifBlank { "Transcript: not transcribed yet." })
            appendLine()
        }
    }.trim()

    private fun markdown(recs: List<Recording>): String = buildString {
        recs.sortedBy { it.start }.forEachIndexed { i, r ->
            if (i > 0) appendLine("\n---\n")
            appendLine("# ${r.title}")
            appendLine()
            appendLine("- **When:** ${dayFmt.format(Date(r.start))}")
            appendLine("- **Length:** ${length(r.seconds)}")
            appendLine("- **Place:** ${r.placeOrUnknown}")
            if (r.starred) appendLine("- **Starred**")
            if (r.marks.isNotEmpty()) appendLine("- **Marked at:** ${r.marks.joinToString(", ") { stamp(it) }}")
            appendLine()
            appendLine("## Summary")
            val sum = r.summary
            if (sum.isNullOrBlank()) appendLine("_Not summarised yet._") else appendLine(sum)
            if (r.actions.isNotEmpty()) {
                appendLine()
                appendLine("## Action items")
                r.actions.forEach { appendLine("- [ ] $it") }
            }
            appendLine()
            appendLine("## Transcript")
            val segs = r.segments
            if (segs.isNullOrEmpty()) appendLine("_Not transcribed yet._")
            else segs.forEach { s ->
                val marked = r.marks.any { it >= s.t0 - 1 && it < s.t1 }
                appendLine("- `${stamp(s.t0)}` ${r.who(s.speaker)?.let { "**$it:** " } ?: ""}${if (marked) "🔖 **" + s.text + "**" else s.text}")
            }
        }
    }

    private fun plain(recs: List<Recording>) = markdown(recs)
        .replace("**", "").replace("`", "").replace("_Not", "Not").replace("yet._", "yet.").replace(Regex("(?m)^#+ "), "")

    private fun pdf(recs: List<Recording>, out: File) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 56f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var y = margin
        fun line(text: String, p: Paint, gap: Float) {
            var rest = text
            while (true) {
                if (y + gap > h - margin) {
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create())
                    y = margin
                }
                val n = p.breakText(rest, true, w - 2 * margin, null).coerceAtLeast(1)
                val cut = if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > 0 } ?: n else n
                page.canvas.drawText(rest.substring(0, cut), margin, y + p.textSize, p)
                y += gap
                rest = rest.substring(cut).trimStart()
                if (rest.isEmpty()) break
            }
        }
        recs.sortedBy { it.start }.forEach { r ->
            line(r.title, title, 26f)
            line("${dayFmt.format(Date(r.start))} · ${length(r.seconds)} · ${r.placeOrUnknown}", body, 18f)
            y += 8
            line(r.summary?.let { "Summary: $it" } ?: "Summary: not summarised yet.", body, 16f)
            r.actions.forEach { line("• $it", body, 16f) }
            val segs = r.segments
            if (segs.isNullOrEmpty()) line("Transcript: not transcribed yet.", body, 16f)
            else segs.forEach { line("${stamp(it.t0)}  ${r.who(it.speaker)?.let { w -> "$w: " } ?: ""}${it.text}", body, 16f) }
            y += 24
        }
        doc.finishPage(page)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun writeNotes(dir: File, recs: List<Recording>, fmt: NoteFormat, baseName: String): File {
        val f = File(dir, "$baseName.${fmt.ext}")
        when (fmt) {
            NoteFormat.MARKDOWN -> f.writeText(markdown(recs))
            NoteFormat.TEXT -> f.writeText(plain(recs))
            NoteFormat.PDF -> pdf(recs, f)
        }
        return f
    }

    private fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifBlank { "note" }

    /** Creates the files to send, in cache/export (emptied each time). */
    fun build(ctx: Context, recs: List<Recording>, opt: ExportOptions): List<File> {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val out = mutableListOf<File>()
        val sorted = (if (opt.hidePersonal) recs.map(Redact::recording) else recs).sortedBy { it.start }
        if (opt.notes) {
            if (opt.onePerNote || sorted.size == 1) {
                sorted.forEach { r -> out += writeNotes(dir, listOf(r), opt.format, "${fileFmt.format(Date(r.start))} ${safe(r.title)}") }
            } else {
                out += writeNotes(dir, sorted, opt.format, "Sotto notes ${fileFmt.format(Date(sorted.first().start))}")
            }
        }
        if (opt.audio) {
            sorted.forEach { r ->
                val src = if (opt.cleanAudio || r.raw == null) r.clean else r.raw
                val dst = File(dir, "${fileFmt.format(Date(r.start))} ${safe(r.title)}${if (opt.cleanAudio) "" else " (original)"}.wav")
                src.copyTo(dst, overwrite = true)
                out += dst
            }
        }
        return out
    }

    fun estimateBytes(recs: List<Recording>, opt: ExportOptions): Long =
        (if (opt.audio) recs.sumOf { if (opt.cleanAudio || it.raw == null) it.sizeBytes else it.raw.length() } else 0L) +
            (if (opt.notes) recs.size * 2_000L else 0L)

    fun zip(ctx: Context, files: List<File>): File {
        val z = File(ctx.cacheDir, "export/Sotto export.zip")
        ZipOutputStream(z.outputStream()).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return z
    }

    private fun uri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    private fun mimeOf(files: List<File>): String {
        val types = files.map {
            when (it.extension) {
                "wav" -> "audio/wav"; "md" -> "text/markdown"; "txt" -> "text/plain"; "pdf" -> "application/pdf"; else -> "*/*"
            }
        }.distinct()
        return when {
            types.size == 1 -> types[0]
            types.all { it.startsWith("text/") } -> "text/*"
            else -> "*/*"
        }
    }

    /**
     * Sends [files] to [dest]. Opens that app directly when it is installed,
     * otherwise the system share sheet.
     */
    fun send(ctx: Context, dest: Dest, files: List<File>, recs: List<Recording>) {
        val uris = files.map { uri(ctx, it) }
        val subject = if (recs.size == 1) recs[0].title else "${recs.size} Sotto notes"
        val intent = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))).apply {
            type = if (uris.isEmpty()) "text/plain" else mimeOf(files)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, bodyText(recs))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            }
        }
        val pkg = dest.pkg
        if (pkg != null && isInstalled(ctx, pkg)) {
            try {
                ctx.startActivity(Intent(intent).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) { /* fall through to the share sheet */ }
        }
        ctx.startActivity(Intent.createChooser(intent, "Share $subject").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies [src] into a document the user picked with the system file picker. */
    fun saveTo(ctx: Context, src: File, target: Uri) {
        ctx.contentResolver.openOutputStream(target)?.use { out -> src.inputStream().use { it.copyTo(out) } }
    }
}
