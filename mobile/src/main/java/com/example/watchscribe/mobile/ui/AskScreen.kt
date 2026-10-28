package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.ai.Gemma
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording
import com.example.watchscribe.mobile.data.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Turn(val question: String) {
    var answer by mutableStateOf("")
    var done by mutableStateOf(false)
    var sources: List<Recording> = emptyList()
}

private val when_ = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

/** Picks the notes most likely to answer [q]: word overlap, then recency. */
private fun pick(all: List<Recording>, q: String, max: Int = 12): List<Recording> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    return all.filter { !it.segments.isNullOrEmpty() }
        .map { r ->
            val text = (r.title + " " + (r.summary ?: "") + " " + r.transcriptText).lowercase()
            r to words.count { text.contains(it.take(5)) }
        }
        .sortedWith(compareByDescending<Pair<Recording, Int>> { it.second }.thenByDescending { it.first.start })
        .take(max).map { it.first }
}

private fun noteText(r: Recording) = Gemma.Note(
    about = "${r.title} — ${when_.format(Date(r.start))}, ${r.placeOrUnknown}" + (r.summary?.let { "\nSummary: $it" } ?: ""),
    transcript = r.segments!!.joinToString(" ") { s -> (r.who(s.speaker)?.let { "$it: " } ?: "") + s.text },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(bottomNav: @Composable () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by RecordingStore.items.collectAsState()
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    val ready = remember { Gemma.available(ctx) }
    val list = rememberLazyListState()
    val busy = turns.lastOrNull()?.done == false

    LaunchedEffect(turns.size, turns.lastOrNull()?.answer) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

    fun ask() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        val t = Turn(q)
        t.sources = pick(all, q)
        turns += t
        if (t.sources.isEmpty()) { t.answer = "No transcribed notes yet."; t.done = true; return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Gemma.ask(ctx, q, t.sources.map(::noteText)) }.flowOn(Dispatchers.IO).collect { t.answer = it }
            } catch (e: Throwable) {
                t.answer = "Couldn't answer: ${e.message}"
            } finally {
                t.done = true
            }
        }
    }

    Scaffold(containerColor = Palette.Ground, bottomBar = bottomNav) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 20.dp)) {
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.height(20.dp))
                        Text("Ask your notes", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                        if (turns.isEmpty()) Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (ready) "Answered on your phone" else "Needs the Gemma model", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ready) "Try “What did I promise to do this week?” or “What did we decide about the trip?”. Tap a source to open it."
                                else "Download it from the AI card on the Notes screen (Wi-Fi, one time).",
                                fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Muted,
                            )
                        }
                    }
                }
                items(turns) { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                t.question, fontSize = 15.sp, color = Palette.OnAccent,
                                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Accent).padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                        Text(
                            t.answer.ifEmpty { "Thinking…" }, fontSize = 15.sp, lineHeight = 22.sp,
                            color = if (t.answer.isEmpty()) Palette.Muted else Palette.Text,
                        )
                        if (t.done) {
                            val cited = Regex("\\[(\\d+)]").findAll(t.answer).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct()
                                .mapNotNull { n -> t.sources.getOrNull(n - 1)?.let { n to it } }.toList()
                            if (cited.isNotEmpty()) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                cited.forEach { (n, r) ->
                                    Text(
                                        "[$n] ${r.title} · ${Exporter.length(r.seconds)}", fontSize = 12.sp, color = Palette.Accent,
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.AccentContainer)
                                            .clickable { onOpen(r.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(28.dp)).background(Palette.Surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    input, { input = it }, Modifier.weight(1f),
                    placeholder = { Text("Ask about any conversation", color = Palette.Muted) },
                    enabled = ready, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ask() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Surface, unfocusedContainerColor = Palette.Surface, disabledContainerColor = Palette.Surface,
                        focusedIndicatorColor = Palette.Surface, unfocusedIndicatorColor = Palette.Surface, disabledIndicatorColor = Palette.Surface,
                    ),
                )
                IconButton(onClick = { ask() }, enabled = ready && !busy && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Ask", Modifier.size(22.dp), tint = if (ready && !busy) Palette.Accent else Palette.Muted)
                }
            }
        }
    }
}
