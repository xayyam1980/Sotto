package com.example.watchscribe.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.ai.Gemma
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.watchscribe.mobile.data.Redact
import kotlinx.coroutines.flow.MutableStateFlow

/** Saved digests, so reopening a day shows its summary straight away. Keyed by the exact set of notes. */
object Digests {
    /** Set when a notification asks to show a summary ("week"); the Notes screen opens it. */
    val request = MutableStateFlow<String?>(null)

    private fun dir(ctx: Context) = File(ctx.filesDir, "digests").apply { mkdirs() }
    private fun file(ctx: Context, ids: List<String>) =
        File(dir(ctx), Integer.toHexString(ids.sorted().joinToString(",").hashCode()) + ".txt")

    fun get(ctx: Context, ids: List<String>): String? = file(ctx, ids).takeIf { it.exists() }?.readText()
    fun put(ctx: Context, ids: List<String>, text: String) = file(ctx, ids).writeText(text)
}

private val timeFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

private fun noteText(r: Recording) = Gemma.Note(
    about = buildString {
        append("${r.title} (${timeFmt.format(Date(r.start))}, ${Exporter.length(r.seconds)}, ${r.placeOrUnknown})")
        r.summary?.let { append("\nSummary: $it") }
        if (r.actions.isNotEmpty()) append("\nAction items: ${r.actions.joinToString("; ")}")
    },
    transcript = r.segments.orEmpty().joinToString(" ") { s -> (r.who(s.speaker)?.let { "$it: " } ?: "") + s.text },
)

/** Asks before deleting a whole group of notes (a day, a place). Undo still works afterwards. */
@Composable
fun DeleteGroupDialog(label: String, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count ${if (count == 1) "note" else "notes"}?") },
        text = { Text("All notes in “$label”, with their audio and transcripts, are deleted from this phone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Palette.Surface,
    )
}

/** Summary of several notes: a whole day, or a selection. Made on the phone by Gemma. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DigestSheet(title: String, notes: List<Recording>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = remember { notes.filter { !it.segments.isNullOrEmpty() }.sortedBy { it.start } }
    val ids = remember { ready.map { it.id } }
    var text by remember { mutableStateOf(Digests.get(ctx, ids) ?: "") }
    var running by remember { mutableStateOf(false) }
    val available = remember { Gemma.available(ctx) }

    fun run() {
        if (running || ready.isEmpty() || !available) return
        running = true; text = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Gemma.summarizeNotes(ctx, ready.map(::noteText)) }
                    .flowOn(Dispatchers.IO).collect { text = it }
                if (text.isNotBlank()) withContext(Dispatchers.IO) { Digests.put(ctx, ids, text.trim()) }
            } catch (t: Throwable) {
                text = "Couldn't make the summary: ${t.message}"
            } finally {
                running = false
            }
        }
    }
    LaunchedEffect(Unit) { if (text.isBlank()) run() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${ready.size} of ${notes.size} notes · ${totalLabel(notes)}" +
                    if (ready.size < notes.size) " · notes not transcribed yet are left out" else "",
                fontSize = 13.sp, color = Palette.Muted,
            )
            when {
                !available -> Text("Download the Gemma model in AI settings to make summaries.", fontSize = 14.sp, color = Palette.Muted)
                ready.isEmpty() -> Text("None of these notes is transcribed yet.", fontSize = 14.sp, color = Palette.Muted)
                text.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
                    Text("Reading ${ready.size} ${if (ready.size == 1) "note" else "notes"}…", fontSize = 14.sp, color = Palette.Muted)
                }
                else -> Text(text, fontSize = 15.sp, lineHeight = 22.sp)
            }
            if (text.isNotBlank() && !running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Share") {
                    val body = "$title\n\n$text".let { if (Redact.on(ctx)) Redact.text(it) else it }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    ctx.startActivity(Intent.createChooser(send, "Share summary"))
                }
                Pill("Copy") {
                    val body = "$title\n\n$text".let { if (Redact.on(ctx)) Redact.text(it) else it }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, body))
                }
                Pill("Redo") { run() }
            }
            if (ready.isNotEmpty()) {
                Text("NOTES", fontSize = 12.sp, color = Palette.Muted, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ready.forEachIndexed { i, r ->
                        Text(
                            "[${i + 1}] ${r.title}", fontSize = 12.sp, color = Palette.Accent,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.AccentContainer)
                                .clickable { onOpen(r.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}
