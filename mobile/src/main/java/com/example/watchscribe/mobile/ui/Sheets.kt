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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.Dest
import com.example.watchscribe.mobile.data.ExportOptions
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.NoteFormat
import com.example.watchscribe.mobile.data.Recording

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ViewOptionsSheet(opts: ViewOptions, shown: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("View options", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { opts.reset() }) { Text("Reset") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Group by")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GroupBy.entries.forEachIndexed { i, g ->
                        SegmentedButton(
                            selected = opts.groupBy == g,
                            onClick = { opts.groupBy = g },
                            shape = SegmentedButtonDefaults.itemShape(i, GroupBy.entries.size),
                        ) { Text(g.label) }
                    }
                }
            }

            Column {
                SectionLabel("Sort by", Modifier.padding(bottom = 4.dp))
                SortBy.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp)
                            .selectable(selected = opts.sortBy == s, onClick = { opts.sortBy = s }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RadioButton(selected = opts.sortBy == s, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
                        Text(s.label, fontSize = 15.sp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Filter")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Last 7 days", opts.lastDays == 7, { opts.lastDays = if (opts.lastDays == 7) null else 7 })
                    Pill("Last 30 days", opts.lastDays == 30, { opts.lastDays = if (opts.lastDays == 30) null else 30 })
                    Pill("Starred", opts.starredOnly, { opts.starredOnly = !opts.starredOnly })
                    Pill("Longer than 5 min", opts.longOnly, { opts.longOnly = !opts.longOnly })
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .toggleable(value = opts.hideShort, onValueChange = { opts.hideShort = it }, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hide recordings under 30 s", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = opts.hideShort, onCheckedChange = null)
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) { Text("Show $shown ${if (shown == 1) "note" else "notes"}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    recs: List<Recording>,
    onDismiss: () -> Unit,
    onSend: (Dest, ExportOptions) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var opt by remember { mutableStateOf(ExportOptions(hidePersonal = com.example.watchscribe.mobile.data.Redact.on(ctx))) }
    val bytes = Exporter.estimateBytes(recs, opt)
    val size = if (bytes > 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"
    val totalMin = (recs.sumOf { it.seconds } / 60).toInt()
    val nothing = !opt.notes && !opt.audio

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (recs.size == 1) "Export note" else "Export ${recs.size} notes", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("$totalMin min of audio", fontSize = 13.sp, color = Palette.Muted)
            }

            Column {
                SectionLabel("Include", Modifier.padding(bottom = 4.dp))
                CheckRow("Note: title, time, place", "transcript when ready", opt.notes) { opt = opt.copy(notes = it) }
                CheckRow("Audio", "$totalMin min", opt.audio) { opt = opt.copy(audio = it) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Format")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NoteFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = opt.format == f,
                            onClick = { opt = opt.copy(format = f) },
                            shape = SegmentedButtonDefaults.itemShape(i, NoteFormat.entries.size),
                            enabled = opt.notes,
                        ) { Text(f.label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Audio", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Pill("Clean · WAV", opt.cleanAudio, { opt = opt.copy(cleanAudio = true) })
                    Spacer(Modifier.size(6.dp))
                    Pill("Original · WAV", !opt.cleanAudio, { opt = opt.copy(cleanAudio = false) })
                }
                Row(
                    Modifier.fillMaxWidth().height(56.dp).toggleable(value = opt.hidePersonal, onValueChange = {
                        opt = opt.copy(hidePersonal = it); com.example.watchscribe.mobile.data.Redact.setOn(ctx, it)
                    }, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide personal details", fontSize = 14.sp)
                        Text("E-mails, IBANs, phone and card numbers become [email], [IBAN], [number]", fontSize = 12.sp, color = Palette.Muted)
                    }
                    Switch(checked = opt.hidePersonal, onCheckedChange = null)
                }
                if (recs.size > 1) Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .toggleable(value = opt.onePerNote, onValueChange = { opt = opt.copy(onePerNote = it) }, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("One file per note", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = opt.onePerNote, onCheckedChange = null)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Send to")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(146.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false,
                ) {
                    items(Dest.entries.toList()) { d ->
                        Column(
                            Modifier.height(68.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface2)
                                .clickable(enabled = !nothing) { onSend(d, opt) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                        ) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).background(Palette.AccentContainer),
                                contentAlignment = Alignment.Center,
                            ) { Text(d.mark, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Accent) }
                            Text(d.label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onSend(Dest.FILES, opt) },
                    enabled = !nothing,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("Save") }
                Button(
                    onClick = { onSend(Dest.MORE, opt) },
                    enabled = !nothing,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                ) { Text("Share · $size", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Palette.OnAccent))
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(hint, fontSize = 12.sp, color = Palette.Muted)
    }
}
