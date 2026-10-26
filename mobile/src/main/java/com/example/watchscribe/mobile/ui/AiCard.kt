package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.example.watchscribe.mobile.ai.AiSettings
import com.example.watchscribe.mobile.ai.ModelId
import com.example.watchscribe.mobile.ai.ModelState
import com.example.watchscribe.mobile.ai.Models
import com.example.watchscribe.mobile.ai.ProcessWorker
import com.example.watchscribe.mobile.data.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import com.example.watchscribe.mobile.AppLock
import com.example.watchscribe.mobile.data.PlaceTagger

private fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB"

/** On the Notes screen: what the on-phone AI is doing and what it still needs. */
@Composable
fun AiCard() {
    val ctx = LocalContext.current
    val states by Models.states.collectAsState()
    val status by ProcessWorker.status.collectAsState()
    val all by RecordingStore.items.collectAsState()
    var settings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val llm = AiSettings.summaryModel(ctx)
    val needed = Models.transcription(ctx) + llm
    val missing = needed.filter { states[it] != ModelState.Ready }
    val downloading = needed.mapNotNull { (states[it] as? ModelState.Downloading)?.fraction }
    val unpacking = needed.any { states[it] == ModelState.Unpacking }
    val waitingAsr = all.count { it.segments == null }
    val waitingSum = all.count { !it.segments.isNullOrEmpty() && it.summary == null }

    val (title, sub) = when {
        status.isNotBlank() -> "Working on your notes" to status
        unpacking -> "Preparing models…" to "Unpacking, this takes a minute"
        downloading.isNotEmpty() -> "Downloading AI models" to "${(downloading.average() * 100).toInt()}% · Wi-Fi only, keeps going in the background"
        missing.isNotEmpty() -> "Set up on-phone AI" to "One-time download, ${gb(missing.sumOf { it.approxBytes })} on Wi-Fi. Nothing leaves your phone."
        waitingAsr + waitingSum > 0 -> "${waitingAsr + waitingSum} notes waiting" to
            if (AiSettings.onlyWhileCharging(ctx)) "Processed while charging, or tap Now" else "Processing soon"
        else -> "On-phone AI ready" to "Transcripts, speakers and summaries are up to date"
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Palette.Accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 12.sp, color = Palette.Muted)
            }
            when {
                missing.isNotEmpty() && downloading.isEmpty() && !unpacking ->
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { needed.forEach { Models.download(ctx, it) } } }) {
                        Text("Download", color = Palette.Accent)
                    }
                missing.isEmpty() && status.isBlank() && waitingAsr + waitingSum > 0 ->
                    TextButton(onClick = { ProcessWorker.runNow(ctx) }) { Text("Now", color = Palette.Accent) }
            }
            Icon(Icons.Rounded.Settings, "AI settings", Modifier.size(20.dp).clickable { settings = true }, tint = Palette.Muted)
        }
        if (downloading.isNotEmpty()) LinearProgressIndicator(
            progress = { downloading.average().toFloat() },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = Palette.Accent, trackColor = Palette.Surface2,
        )
    }
    if (settings) AiSettingsSheet { settings = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Everything runs on this phone. No account, no API key, nothing sent to Google or anyone else.",
                fontSize = 13.sp, color = Palette.Muted,
            )
            PrivacySection()
            ModelsSection()
            TranscriptionModelSection()
            SummaryModelSection()
            LanguagesSection()
            SummaryLanguageSection()
            BatterySection()
            MoreSettings()
            RedoSection()
            AboutSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A setting with a title, a short explanation and a switch. */
@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(note, fontSize = 12.sp, color = Palette.Muted)
        }
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent),
        )
    }
}

/** One choice of several (radio button), with an optional second line. */
@Composable
private fun RadioRow(selected: Boolean, title: String, note: String? = null, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
        Column {
            Text(title, fontSize = 14.sp)
            if (note != null) Text(note, fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun PrivacySection() {
    val ctx = LocalContext.current
    var locked by remember { mutableStateOf(AppLock.enabled(ctx)) }
    var lookup by remember { mutableStateOf(PlaceTagger.addressLookup(ctx)) }
    var remind by remember { mutableStateOf(Consent.remind(ctx)) }
    Section("Privacy")
    SwitchRow(
        "App lock",
        if (AppLock.available(ctx)) "Fingerprint, face or PIN, checked on this phone only. Also hides notes in recent apps."
        else "Set up a screen lock in Android settings first",
        locked, enabled = AppLock.available(ctx),
    ) { on ->
        val act = ctx.findActivity() ?: return@SwitchRow
        AppLock.setEnabled(act, on) { ok -> if (ok) locked = on }
    }
    SwitchRow(
        "Street names for places",
        "Off: places are named on the phone (rename them once). On: coordinates are sent to Google's address lookup.",
        lookup,
    ) { lookup = it; PlaceTagger.setAddressLookup(ctx, it) }
    SwitchRow("Consent reminder", "Before recording starts, reminds you to get everyone's OK.", remind) {
        remind = it; Consent.setRemind(ctx, it)
    }
}

/** Every model with its size and state: Get, Cancel or Delete. */
@Composable
private fun ModelsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by Models.states.collectAsState()
    Section("Models")
    ModelId.entries.forEach { m ->
        val st = states[m] ?: ModelState.Missing
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, fontSize = 15.sp)
                Text(
                    "${m.purpose} · ${gb(m.approxBytes)} · " + when (st) {
                        ModelState.Ready -> "ready"
                        ModelState.Missing -> "not downloaded"
                        ModelState.Unpacking -> "unpacking…"
                        is ModelState.Downloading -> "${(st.fraction * 100).toInt()}%"
                        is ModelState.Failed -> st.reason
                    },
                    fontSize = 12.sp, color = if (st is ModelState.Failed) Palette.Danger else Palette.Muted,
                )
            }
            when (st) {
                ModelState.Ready, is ModelState.Downloading ->
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { Models.delete(ctx, m) } }) {
                        Text(if (st == ModelState.Ready) "Delete" else "Cancel", color = Palette.Danger)
                    }
                ModelState.Unpacking -> {}
                else -> TextButton(onClick = { scope.launch(Dispatchers.IO) { Models.download(ctx, m) } }) {
                    Text("Get", color = Palette.Accent)
                }
            }
        }
    }
}

@Composable
private fun TranscriptionModelSection() {
    val ctx = LocalContext.current
    var asr by remember { mutableStateOf(AiSettings.asrModel(ctx)) }
    Section("Transcription model")
    listOf(ModelId.PARAKEET, ModelId.WHISPER).forEach { m ->
        RadioRow(asr == m, m.label, m.purpose.substringAfter("· ")) { asr = m; AiSettings.setAsrModel(ctx, m) }
    }
    Text("After switching, open a note and tap Re-transcribe to redo it with the new model.", fontSize = 12.sp, color = Palette.Muted)
}

@Composable
private fun SummaryModelSection() {
    val ctx = LocalContext.current
    var llm by remember { mutableStateOf(AiSettings.summaryModel(ctx)) }
    Section("Summary model")
    listOf(ModelId.GEMMA_E2B, ModelId.GEMMA_E4B).forEach { m ->
        RadioRow(llm == m, "${m.label} — ${m.purpose.substringAfter("(").trimEnd(')')}") {
            llm = m; AiSettings.setSummaryModel(ctx, m)
        }
    }
}

@Composable
private fun LanguagesSection() {
    val ctx = LocalContext.current
    var langs by remember { mutableStateOf(AiSettings.languages(ctx)) }
    Section("Languages you speak")
    Text("Transcription only picks from these. The first one is the fallback.", fontSize = 12.sp, color = Palette.Muted)
    AiSettings.ALL_LANGUAGES.forEach { (code, name) ->
        val on = code in langs
        Row(Modifier.fillMaxWidth().clickable {
            langs = if (on) langs - code else langs + code
            if (langs.isNotEmpty()) AiSettings.setLanguages(ctx, langs)
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Palette.OnAccent))
            Text(name, fontSize = 14.sp)
            if (langs.firstOrNull() == code) Text("  · fallback", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun SummaryLanguageSection() {
    val ctx = LocalContext.current
    var sumLang by remember { mutableStateOf(AiSettings.summaryLanguage(ctx)) }
    Section("Summaries in")
    listOf("English" to "English", "same" to "Language of the recording").forEach { (v, label) ->
        RadioRow(sumLang == v, label) { sumLang = v; AiSettings.setSummaryLanguage(ctx, v) }
    }
}

@Composable
private fun BatterySection() {
    val ctx = LocalContext.current
    var charging by remember { mutableStateOf(AiSettings.onlyWhileCharging(ctx)) }
    Section("Battery")
    SwitchRow("Only while charging", "Processing is heavy; overnight on the charger is ideal", charging) {
        charging = it; AiSettings.setOnlyWhileCharging(ctx, it)
        WorkManager.getInstance(ctx).cancelUniqueWork("sotto-process")
        ProcessWorker.schedule(ctx)
    }
}

@Composable
private fun RedoSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Section("Redo")
    TextButton(onClick = {
        scope.launch(Dispatchers.IO) {
            RecordingStore.items.value.forEach { RecordingStore.clearAi(ctx, it.id, false) }
            ProcessWorker.runNow(ctx)
        }
    }) { Text("Re-summarise all notes", color = Palette.Accent) }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    Section("About")
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    Text("Sotto $version", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text("Created by Khayyam Bayramov", fontSize = 14.sp, color = Palette.Soft)
    Text(
        "xayyam@gmail.com", fontSize = 14.sp, color = Palette.Accent,
        modifier = Modifier.clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:xayyam@gmail.com"))
                    .putExtra(Intent.EXTRA_SUBJECT, "Sotto $version"))
            }
        }.padding(vertical = 4.dp),
    )
    Text(
        "Your recordings, transcripts and places are stored only on this phone and your watch. " +
            "Nothing is uploaded unless you share a note yourself.",
        fontSize = 12.sp, color = Palette.Muted, lineHeight = 17.sp,
    )
}

@Composable
private fun Section(t: String) {
    Text(
        t.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = Palette.Muted,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp),
    )
}
