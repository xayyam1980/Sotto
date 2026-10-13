package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording
import java.text.SimpleDateFormat
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
fun clock(t: Long): String = timeFmt.format(t)

@Composable
fun MiniWave(peaks: List<Float>, color: Color, modifier: Modifier = Modifier, bars: Int = 14, height: Int = 16) {
    val step = (peaks.size / bars).coerceAtLeast(1)
    Row(modifier.height(height.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until bars) {
            val v = peaks.getOrNull(i * step) ?: 0.1f
            Box(Modifier.width(2.dp).fillMaxHeight(v.coerceIn(0.15f, 1f)).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

@Composable
fun MetaChip(text: String, fg: Color = Palette.Soft, bg: Color = Palette.Surface2, icon: Boolean = false) {
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon) Icon(Icons.Rounded.Place, null, Modifier.size(12.dp), tint = fg)
        Text(text, color = fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier, color = Palette.Muted, fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingCard(
    r: Recording,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF1E2A3D) else Palette.Surface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) Palette.Accent else Palette.Muted,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${clock(r.start)} · ${Exporter.length(r.seconds)}",
                    color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
                if (r.starred) Icon(Icons.Rounded.Star, "Starred", Modifier.size(14.dp), tint = Palette.Amber)
                Spacer(Modifier.weight(1f))
                MiniWave(r.peaks, Color(0xFF4A5059))
            }
            Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            NoteProgress(r)
            val gist = r.summary ?: r.transcriptText
            Text(
                when {
                    gist.isNotBlank() -> gist
                    r.segments != null -> "No speech found"
                    else -> "Waiting to be transcribed"
                },
                fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (gist.isNotBlank()) Palette.Soft else Palette.Muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(r.placeOrUnknown, icon = true)
                if (com.example.watchscribe.mobile.PhoneRecorder.isPhoneNote(androidx.compose.ui.platform.LocalContext.current, r.id))
                    MetaChip("Phone mic")
                if (r.marks.isNotEmpty()) MetaChip(
                    "${r.marks.size} ${if (r.marks.size == 1) "mark" else "marks"}",
                    fg = Palette.Amber, bg = Palette.AmberContainer,
                )
            }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Palette.AccentContainer) else Modifier.border(1.dp, Palette.Line, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Color(0xFFC4D9FF) else Palette.Text, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** "Transcribing 42%" with a bar, while the phone works on this note. Shows nothing otherwise. */
@Composable
fun NoteProgress(r: Recording) {
    val jobs by com.example.watchscribe.mobile.ai.ProcessWorker.jobs.collectAsState()
    val j = jobs[r.id] ?: if (r.redo != null) com.example.watchscribe.mobile.ai.ProcessWorker.Companion.Job("Waiting to transcribe again", null) else return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            j.stage + (j.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…"),
            fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.Medium,
        )
        val mod = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        if (j.progress != null) androidx.compose.material3.LinearProgressIndicator(
            progress = { j.progress ?: 0f }, modifier = mod, color = Palette.Accent, trackColor = Palette.Line,
        ) else androidx.compose.material3.LinearProgressIndicator(modifier = mod, color = Palette.Accent, trackColor = Palette.Line)
    }
}
