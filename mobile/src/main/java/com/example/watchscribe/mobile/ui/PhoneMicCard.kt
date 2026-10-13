package com.example.watchscribe.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.PhoneRecorder
import kotlinx.coroutines.delay

/** "Record with phone": for meetings where the phone on the table hears better than the watch. */
@Composable
fun PhoneMicCard() {
    val ctx = LocalContext.current
    val live by PhoneRecorder.live.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.running) { while (live.running) { now = System.currentTimeMillis(); delay(500) } }

    // Microphones: the phone's own, plus any connected headset (updates when one connects or disconnects)
    var inputs by remember { mutableStateOf(PhoneRecorder.inputs(ctx)) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { inputs = PhoneRecorder.inputs(ctx) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { inputs = PhoneRecorder.inputs(ctx) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    val prefs = remember { ctx.getSharedPreferences("phone_mic", android.content.Context.MODE_PRIVATE) }
    var wanted by remember { mutableStateOf(prefs.getString("mic", "Phone microphone")!!) }
    val selected = inputs.firstOrNull { it.label == wanted } ?: inputs.first()

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) PhoneRecorder.start(ctx, selected.id) else Toast.makeText(ctx, "Microphone permission is needed", Toast.LENGTH_SHORT).show()
    }
    val consent = rememberConsentGate()
    fun startRec() = consent.ask {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) PhoneRecorder.start(ctx, selected.id)
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (!live.running) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).clickable { startRec() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.Live.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp), tint = Palette.Live)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Record with phone", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("For meetings: put the phone on the table", fontSize = 12.sp, color = Palette.Muted)
            }
            Text("Start", color = Palette.Live, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mic", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
            inputs.forEach { input ->
                val on = input == selected
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) Palette.AccentContainer else Palette.Surface)
                        .border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(16.dp))
                        .clickable { wanted = input.label; prefs.edit().putString("mic", input.label).apply() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (input.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) Icons.Rounded.PhoneAndroid else Icons.Rounded.Headset, null,
                        Modifier.size(14.dp), tint = if (on) Palette.Accent else Palette.Muted,
                    )
                    Box(Modifier.width(6.dp))
                    Text(input.label, fontSize = 12.sp, color = if (on) Palette.Text else Palette.Soft, maxLines = 1)
                }
            }
            if (inputs.size == 1) Text(
                "Connect a headset to use its mic", fontSize = 12.sp, color = Palette.Muted, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        return
    }

    val sec = ((now - live.startedAt) / 1000).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Live.copy(alpha = 0.10f))
            .border(1.dp, Palette.Live.copy(alpha = 0.45f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Live))
            Column(Modifier.weight(1f)) {
                Text("Recording with phone", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (live.mic.isNotBlank()) Text("Mic: ${live.mic}", fontSize = 12.sp, color = Palette.Muted, maxLines = 1)
                if (live.silenced) Text("Another app (e.g. Google Translate) is using the mic. Recording continues when it's free; the watch can record meanwhile.", fontSize = 12.sp, color = Palette.Amber)
                else if (live.note.isNotBlank()) Text(live.note, fontSize = 12.sp, color = Palette.Muted)
            }
            Text("%d:%02d".format(sec / 60, sec % 60), fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = Palette.Live)
        }
        // level meter
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Surface2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(live.level.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(Palette.Live))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, Palette.Line, RoundedCornerShape(22.dp))
                    .clickable { PhoneRecorder.mark() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Bookmark, null, Modifier.size(16.dp), tint = Palette.Amber)
                Box(Modifier.width(6.dp))
                Text(if (live.marks > 0) "Mark · ${live.marks}" else "Mark", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Live).clickable { PhoneRecorder.stop(ctx) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp), tint = Palette.Ground)
                Box(Modifier.width(6.dp))
                Text("Stop", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ground)
            }
        }
        Text("Saved as a note when you stop (a new note every 10 min). Keeps recording with the screen off.", fontSize = 11.sp, color = Palette.Muted)
    }
}
