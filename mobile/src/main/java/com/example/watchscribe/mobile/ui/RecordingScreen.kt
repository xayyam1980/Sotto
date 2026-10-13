package com.example.watchscribe.mobile.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private val detailFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingScreen(
    r: Recording,
    onBack: () -> Unit,
    onStar: (Boolean) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var seekReq by remember { mutableStateOf<Int?>(null) }
    var naming by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = Palette.Ground,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(top = 32.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notes") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onStar(!r.starred) }) {
                    Icon(if (r.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (r.starred) "Unstar" else "Star", tint = if (r.starred) Palette.Amber else Palette.Text)
                }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share or export") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        },
        bottomBar = {
            if (r.audioDeleted) Text(
                "The audio was deleted to save space (Settings → Keep audio). The text stays.",
                fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.navigationBarsPadding().padding(20.dp),
            ) else Player(r, seekReq) { seekReq = null }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.clickable { renaming = true }, verticalAlignment = Alignment.Top) {
                Text(r.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Edit, "Rename", Modifier.padding(start = 8.dp, top = 6.dp).size(18.dp), tint = Palette.Muted)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip("${detailFmt.format(r.start)} · ${Exporter.length(r.seconds)}", bg = Palette.Surface)
                MetaChip(r.placeOrUnknown, bg = Palette.Surface, icon = true)
            }
            NoteProgress(r)
            if (r.marks.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.marks.forEach { m ->
                    Box(
                        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(Palette.AmberContainer)
                            .clickable { seekReq = (m * 1000).toInt() }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Marked ${Exporter.stamp(m)}", fontSize = 12.sp, color = Palette.Amber) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Summary", "Transcript").forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            val segs = r.segments
            if (tab == 1 && !segs.isNullOrEmpty()) {
                Text(
                    "${langName(r.lang)} · ${r.transcriptText.split(Regex("\\s+")).size} words · tap a line to play, tap a name to say who it is",
                    fontSize = 12.sp, color = Palette.Muted,
                )
                SpeakerControl(r)
                segs.forEach { sg ->
                    val marked = r.marks.any { it >= sg.t0 - 1 && it < sg.t1 }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (marked) Palette.AmberContainer else Color.Transparent)
                            .clickable { seekReq = (sg.t0 * 1000).toInt() }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.width(52.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(Exporter.stamp(sg.t0), fontSize = 12.sp, color = Palette.Accent, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp))
                            sg.speaker?.let { spk ->
                                Text(
                                    r.who(spk)!!, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = Palette.PlaceTints[spk % Palette.PlaceTints.size],
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { naming = spk },
                                )
                            }
                        }
                        Text(sg.text, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFDDE1E5), modifier = Modifier.weight(1f))
                    }
                }
            } else if (tab == 0 && r.summary != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel("Summary")
                    Text(r.summary, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFDDE1E5))
                    if (r.speakers > 0) Text(
                        (0 until r.speakers).joinToString(", ") { r.who(it)!! } + " · ${langName(r.lang)}",
                        fontSize = 12.sp, color = Palette.Muted,
                    )
                }
                if (!r.segments.isNullOrEmpty()) SpeakerControl(r)
                if (r.actions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Action items")
                    r.actions.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("•", color = Palette.Amber, fontSize = 15.sp)
                            Text(a, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                            ActionButtons(r, a)
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (head, body) = when {
                        segs == null -> "Not transcribed yet" to
                            if (com.example.watchscribe.mobile.ai.AiSettings.onlyWhileCharging(ctx)) "Sotto transcribes on this phone while it's charging. Use “Process now” on the Notes screen to start immediately."
                            else "Sotto is transcribing on this phone. It appears here when ready."
                        segs.isEmpty() -> "No speech found" to "No clear speech was found in this recording."
                        else -> "Summary not ready" to "The transcript is ready. The summary is made on this phone after transcription (download Gemma in AI settings)."
                    }
                    Text(head, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Muted)
                }
                if (segs != null && segs.isEmpty()) SpeakerControl(r)
            }

            // Hand-off to your own Claude / Gemini app (uses your subscription there)
            if (!segs.isNullOrEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.example.watchscribe.mobile.ai.Handoff.App.entries.forEach { app ->
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Palette.Line, RoundedCornerShape(22.dp))
                            .clickable { com.example.watchscribe.mobile.ai.Handoff.send(ctx, app, r) },
                        contentAlignment = Alignment.Center,
                    ) { Text("Open in ${app.label}", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    naming?.let { spk -> NameSpeakerDialog(r, spk) { naming = null } }

    if (renaming) {
        var text by remember { mutableStateOf(if (r.customTitle) r.title else "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename note") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(r.title) }) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onRename(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}

private fun langName(code: String?): String = when (code) {
    "ru" -> "Russian"; "en" -> "English"; "pl" -> "Polish"; "az" -> "Azerbaijani"
    null, "" -> "Transcript"; else -> code
}

@Composable
private fun Player(r: Recording, seekReq: Int?, onSeekDone: () -> Unit) {
    var clean by remember(r.id) { mutableStateOf(true) }
    val file: File = if (clean || r.raw == null) r.clean else r.raw
    val player = remember(file) { MediaPlayer() }
    var ready by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var pos by remember(file) { mutableIntStateOf(0) }
    var dur by remember(file) { mutableIntStateOf(1) }
    var speed by remember { mutableFloatStateOf(1f) }

    DisposableEffect(file) {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            dur = player.duration.coerceAtLeast(1)
            ready = true
        }
        player.setOnCompletionListener { playing = false; pos = 0; runCatching { it.seekTo(0) } }
        onDispose { player.release() }
    }
    LaunchedEffect(playing, file) {
        while (playing) {
            pos = runCatching { player.currentPosition }.getOrDefault(pos)
            delay(100)
        }
    }

    fun play() {
        if (!ready) return
        player.start()
        runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
        playing = true
    }
    fun pause() { if (ready) player.pause(); playing = false }
    fun seek(ms: Int) { val t = ms.coerceIn(0, dur); if (ready) player.seekTo(t); pos = t }

    LaunchedEffect(seekReq) {
        val target = seekReq ?: return@LaunchedEffect
        seek(target)
        if (!playing) play()
        onSeekDone()
    }

    Column(
        Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.Surface)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val frac = pos.toFloat() / dur
        Row(
            Modifier.fillMaxWidth().height(36.dp).pointerInput(dur, ready) {
                detectTapGestures { o -> seek((o.x / size.width * dur).toInt()) }
            },
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            r.peaks.forEachIndexed { i, p ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(p.coerceIn(0.1f, 1f)).clip(RoundedCornerShape(1.dp))
                        .background(if (i.toFloat() / r.peaks.size <= frac) Palette.Accent else Palette.Line)
                )
            }
        }
        Row {
            Text(Exporter.length(pos / 1000.0), fontSize = 12.sp, color = Palette.Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(Exporter.length(dur / 1000.0), fontSize = 12.sp, color = Palette.Muted, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SmallPillButton(if (speed == 1f) "1×" else "${speed}×") {
                speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
                if (playing) runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
            }
            IconButton(onClick = { seek(pos - 10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Accent)
                    .clickable { if (playing) pause() else play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Palette.OnAccent, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { seek(pos + 10_000) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
            SmallPillButton(if (clean) "Clean" else "Original", if (clean) Palette.Green else Palette.Text) {
                if (r.raw != null) clean = !clean
            }
        }
    }
}

@Composable
private fun SmallPillButton(label: String, color: androidx.compose.ui.graphics.Color = Palette.Text, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = color, fontFamily = FontFamily.Monospace) }
}


/**
 * "How many people spoke?" Auto, or a fixed count, which makes speaker detection much more reliable.
 * Re-runs only the speaker step on the phone (a few seconds per minute of audio).
 */
@Composable
private fun SpeakerControl(r: com.example.watchscribe.mobile.data.Recording) {
    if (r.audioDeleted) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val jobs by com.example.watchscribe.mobile.ai.ProcessWorker.jobs.collectAsState()
    val busy = r.redo != null || jobs.containsKey(r.id)
    fun redo(n: Int) = com.example.watchscribe.mobile.ai.ProcessWorker.redo(ctx, r.id, n)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(Palette.Surface)
                    .border(1.dp, Palette.Accent, RoundedCornerShape(15.dp))
                    .clickable(enabled = !busy) { redo(-1) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Re-transcribe", fontSize = 12.sp, color = if (busy) Palette.Muted else Palette.Accent) }
            Text("Speakers", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
            listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6").forEach { (n, label) ->
                val on = !busy && r.speakers == n
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (on) Palette.AccentContainer else Palette.Surface)
                        .border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(15.dp))
                        .clickable(enabled = !busy) { redo(n) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 12.sp, color = if (on) Palette.Text else Palette.Soft) }
            }
        }
        when {
            busy -> {}
            r.speakerError != null -> Text(r.speakerError, fontSize = 12.sp, color = Palette.Danger)
            r.speakers <= 1 -> Text("One voice found. If more people spoke, tap how many.", fontSize = 12.sp, color = Palette.Muted)
            else -> Text("Speakers found automatically. Wrong? Tap the right number.", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

/** "Who is S2?": pick Me, a known person, or type a name. Sotto then recognises that voice in other notes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameSpeakerDialog(r: Recording, spk: Int, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val people by com.example.watchscribe.mobile.data.People.all.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.watchscribe.mobile.data.People.load(ctx) } }
    var text by remember { mutableStateOf("") }
    fun pick(name: String?) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.example.watchscribe.mobile.data.RecordingStore.setSpeakerName(ctx, r.id, spk, name) }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Who is ${r.who(spk)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sotto learns this voice on your phone and names this person in other notes too.", fontSize = 13.sp, color = Palette.Muted)
                val known = (listOf(com.example.watchscribe.mobile.data.People.ME) + people.map { it.name }).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    known.forEach { n ->
                        Text(
                            n, fontSize = 13.sp, color = Palette.Accent,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.AccentContainer)
                                .clickable { pick(n) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Another name") })
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) pick(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (r.names.getOrNull(spk) != null) TextButton(onClick = { pick(null) }) { Text("Forget name") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
        containerColor = Palette.Surface,
    )
}

/** Puts an action item in the calendar or a to-do app (Google Tasks, Todoist… via Share). */
@Composable
private fun ActionButtons(r: Recording, item: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = {
        // Tomorrow 9:00 as a starting point; the calendar app lets you change it before saving
        val start = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val i = android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, item)
            .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "From the note “${r.title}” in Sotto")
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, start + 30 * 60_000L)
        runCatching { ctx.startActivity(i) }.onFailure {
            android.widget.Toast.makeText(ctx, "No calendar app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Rounded.Event, "Add to calendar", Modifier.size(18.dp), tint = Palette.Muted)
    }
    IconButton(onClick = {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, item)
            .putExtra(android.content.Intent.EXTRA_TEXT, item)
        ctx.startActivity(android.content.Intent.createChooser(send, "Add to tasks"))
    }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Rounded.AddTask, "Add to a to-do app", Modifier.size(18.dp), tint = Palette.Muted)
    }
}
