package com.example.watchscribe.mobile.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.ai.AiSettings
import com.example.watchscribe.mobile.data.Backup
import com.example.watchscribe.mobile.data.BackupCrypto
import com.example.watchscribe.mobile.data.People
import com.example.watchscribe.mobile.data.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Label(t: String) {
    Text(
        t.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = Palette.Muted,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp),
    )
}

/** People, Keep audio, Weekly summary and Backup sections of the Settings sheet. */
@Composable
fun MoreSettings() {
    PeopleSection()
    KeepAudioSection()
    WeeklySection()
    BackupSection()
}

/** Known voices: learnt names, with Forget. */
@Composable
private fun PeopleSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val people by People.all.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { People.load(ctx) } }
    var forget by remember { mutableStateOf<People.Person?>(null) }
    Label("People")
    if (people.isEmpty()) Text(
        "In a transcript, tap a speaker (S1, S2) and say who it is. Sotto then recognises that voice in every note.",
        fontSize = 12.sp, color = Palette.Muted,
    )
    people.forEach { p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 15.sp)
                Text("Voice learnt from ${p.samples} ${if (p.samples == 1) "note" else "notes"}", fontSize = 12.sp, color = Palette.Muted)
            }
            TextButton(onClick = { forget = p }) { Text("Forget", color = Palette.Danger) }
        }
    }
    forget?.let { p ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Forget ${p.name}?") },
            text = { Text("Sotto deletes this voice fingerprint. Names you set by hand in notes stay.") },
            confirmButton = {
                TextButton(onClick = {
                    forget = null
                    scope.launch(Dispatchers.IO) { People.delete(ctx, p.id); RecordingStore.rematchAll(ctx) }
                }) { Text("Forget", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}

/** How long audio is kept; transcripts always stay. */
@Composable
private fun KeepAudioSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var keep by remember { mutableStateOf(AiSettings.keepAudioDays(ctx)) }
    Label("Keep audio")
    Text("Transcripts and summaries are always kept. Deleting old audio saves space and keeps less personal data.", fontSize = 12.sp, color = Palette.Muted)
    listOf(0 to "Forever", 90 to "90 days", 30 to "30 days", 7 to "7 days").forEach { (days, label) ->
        Row(Modifier.fillMaxWidth().clickable {
            keep = days; AiSettings.setKeepAudioDays(ctx, days)
            scope.launch(Dispatchers.IO) { RecordingStore.deleteOldAudio(ctx, days) }
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(keep == days, null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
            Text(label, fontSize = 14.sp)
        }
    }
}

/** Friday reminder for the week's summary. */
@Composable
private fun WeeklySection() {
    val ctx = LocalContext.current

    var weekly by remember { mutableStateOf(AiSettings.weeklySummary(ctx)) }
    Label("Weekly summary")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Friday reminder", fontSize = 15.sp)
            Text("On Friday afternoon, a notification offers a summary of the week's decisions and to-dos.", fontSize = 12.sp, color = Palette.Muted)
        }
        Switch(weekly, { weekly = it; AiSettings.setWeeklySummary(ctx, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent))
    }
}

/** Password-protected backup and restore. */
@Composable
private fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var ask by remember { mutableStateOf<String?>(null) } // "backup" | "restore"
    var withAudio by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = password.toCharArray(); password = ""
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Making the backup…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { Backup.create(ctx, uri, pw, withAudio) }
                    .fold({ "Backup saved: $it notes" }, { "Backup failed: ${it.message}" })
            }
            busy = ""; Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; ask = "restore" }
    }

    Label("Backup")
    Text(
        "One password-protected file with all your notes, to keep on a USB stick, computer or your own cloud. " +
            "Without the password nobody can open it, not even you, so keep it safe.",
        fontSize = 12.sp, color = Palette.Muted,
    )
    if (busy.isNotEmpty()) Text(busy, fontSize = 13.sp, color = Palette.Accent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { ask = "backup" }, enabled = busy.isEmpty()) { Text("Back up…", color = Palette.Accent) }
        TextButton(onClick = { openDoc.launch(arrayOf("*/*")) }, enabled = busy.isEmpty()) { Text("Restore…", color = Palette.Accent) }
    }

    ask?.let { mode ->
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        val ok = if (mode == "backup") pw.length >= 8 && pw == pw2 else pw.isNotEmpty()
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(if (mode == "backup") "Back up all notes" else "Restore a backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (mode == "backup") {
                        OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text("Repeat password") },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Text("At least 8 characters.", fontSize = 12.sp, color = Palette.Muted)
                        Row(Modifier.clickable { withAudio = !withAudio }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(withAudio, { withAudio = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.Accent))
                            Text("Include audio (much bigger file)", fontSize = 13.sp)
                        }
                    } else Text("Notes already on this phone are kept; only missing ones are added.", fontSize = 12.sp, color = Palette.Muted)
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    ask = null
                    if (mode == "backup") {
                        password = pw
                        createDoc.launch("Sotto-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.sotto")
                    } else {
                        val uri = restoreUri ?: return@TextButton
                        val chars = pw.toCharArray()
                        busy = "Restoring…"
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                runCatching { Backup.restore(ctx, uri, chars) }.fold(
                                    { "Restored $it notes" },
                                    { if (it is BackupCrypto.WrongPassword) it.message!! else "Restore failed: ${it.message}" },
                                )
                            }
                            busy = ""; Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(if (mode == "backup") "Choose where to save" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}
