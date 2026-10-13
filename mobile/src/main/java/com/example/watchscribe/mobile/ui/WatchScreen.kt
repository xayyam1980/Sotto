package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.RecordingStore
import com.example.watchscribe.mobile.data.WatchSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dur(sec: Int) = if (sec >= 3600) "${sec / 3600} h ${sec / 60 % 60} min" else "${(sec + 59) / 60} min"
private fun mmss(sec: Double) = "%d:%02d".format(sec.toInt() / 60, sec.toInt() % 60)

/** Watch tab: live state of Sotto on the watch, sync and pause/resume from the phone. */
@Composable
fun WatchScreen(bottomNav: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by WatchSync.status.collectAsState()
    val name by WatchSync.watchName.collectAsState()
    val syncLine by WatchSync.state.collectAsState()
    val all by RecordingStore.items.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var reachable by remember { mutableLongStateOf(-1L) } // -1 checking, 0 no, 1 yes

    // Ask the watch for its status every 5 s while this tab is open
    LaunchedEffect(Unit) {
        while (true) {
            val ok = withContext(Dispatchers.IO) { WatchSync.refreshStatus(ctx) }
            reachable = if (ok) 1 else 0
            repeat(5) { now = System.currentTimeMillis(); delay(1000) }
        }
    }
    val syncing = syncLine == "Asking the watch…" || syncLine == "Receiving from the watch…"
    val fresh = st != null && now - st!!.at < 20_000

    Scaffold(containerColor = Palette.Ground, bottomBar = bottomNav) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("Watch", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

            // ---- Hero: state
            val s = st
            val (label, color) = when {
                reachable == 0L && !fresh -> "Watch not connected" to Palette.Muted
                s == null -> "Checking…" to Palette.Muted
                s.state == "listening" -> (if (s.speaking) "Capturing" else "Listening") to Palette.Live
                s.state == "paused" && s.pausedUntil == Long.MAX_VALUE -> "Stopped" to Palette.Muted
                s.state == "paused" -> "Paused" to Palette.Amber
                else -> "Not listening" to Palette.Muted
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.Surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Watch, null, Modifier.size(20.dp), tint = Palette.Muted)
                    Text(name ?: "Galaxy Watch", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
                    if (s != null && s.battery >= 0) {
                        Icon(Icons.Rounded.BatteryStd, null, Modifier.size(16.dp), tint = if (s.battery <= 15) Palette.Danger else Palette.Muted)
                        Text("${s.battery}%", fontSize = 13.sp, color = Palette.Muted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
                    Text(label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (s?.state == "listening" && s.chunkSec > 0)
                        Text(mmss(s.chunkSec), fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = Palette.Live)
                }
                Text(
                    when {
                        reachable == 0L && !fresh -> "Check Bluetooth and the Galaxy Wearable app."
                        s == null -> "Waiting for the watch to answer…"
                        s.state == "paused" && s.pausedUntil == Long.MAX_VALUE -> "Microphone off. Start it again from here or the watch."
                        s.state == "paused" -> "Until " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.pausedUntil))
                        s.state == "listening" -> if (s.chunkSec > 0) "Recording a note now" else "Waiting for speech"
                        else -> "Sotto is off on the watch."
                    } + if (s != null && !fresh) " · last seen ${relative(s.at)}" else "",
                    fontSize = 14.sp, color = Palette.Soft, lineHeight = 20.sp,
                )
                if (s != null && s.error.isNotBlank()) Text(s.error, fontSize = 13.sp, color = Palette.Danger)

                if (s?.note == "tap" && s.state == "off") Text(
                    "Tap Start on your watch to confirm. Android lets the microphone switch on only from the watch itself; after that the phone can stop and start it.",
                    fontSize = 13.sp, color = Palette.Amber, lineHeight = 19.sp,
                )

                // Controls
                val stopped = s?.state == "paused" && s.pausedUntil == Long.MAX_VALUE
                fun io(block: () -> Unit) = scope.launch(Dispatchers.IO) { block() }
                if (s != null && (reachable != 0L || fresh)) {
                    when {
                        s.state == "listening" -> {
                            Action("Stop listening", Icons.Rounded.Stop, Modifier.fillMaxWidth(), danger = true) { io { WatchSync.stop(ctx) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Action("Pause 15 min", Icons.Rounded.Pause, Modifier.weight(1f)) { io { WatchSync.pause(ctx, 15) } }
                                Action("Pause 1 hour", Icons.Rounded.Pause, Modifier.weight(1f)) { io { WatchSync.pause(ctx, 60) } }
                            }
                        }
                        s.state == "paused" -> Action(if (stopped) "Start listening" else "Resume now", Icons.Rounded.PlayArrow, Modifier.fillMaxWidth(), primary = true) {
                            io { WatchSync.start(ctx) }
                        }
                        else -> Action("Start listening", Icons.Rounded.Mic, Modifier.fillMaxWidth(), primary = true) { io { WatchSync.start(ctx) } }
                    }
                    if (s.state != "off") Text(
                        "Turn off completely", fontSize = 13.sp, color = Palette.Muted,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { io { WatchSync.turnOff(ctx) } }.padding(vertical = 4.dp),
                    )
                }
            }

            // ---- Sync
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Sync", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    syncLine ?: when {
                        s == null -> "Notes move to the phone by themselves when a note ends."
                        s.waiting == 0 -> "Everything is on the phone"
                        else -> "${s.waiting} ${if (s.waiting == 1) "note" else "notes"} waiting on the watch · ${s.sync}"
                    },
                    fontSize = 14.sp, color = Palette.Soft, lineHeight = 20.sp,
                )
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Accent)
                        .clickable(enabled = !syncing) { scope.launch(Dispatchers.IO) { WatchSync.request(ctx) } },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.OnAccent, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp), tint = Palette.OnAccent)
                    Spacer(Modifier.size(8.dp))
                    Text(if (syncing) "Syncing…" else "Sync now", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
                }
                Text("Also sends the note being recorded right now.", fontSize = 12.sp, color = Palette.Muted)
            }

            // ---- Today
            val today = all.filter { System.currentTimeMillis() - it.start < 24 * 3600_000L && SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.start)) == SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Captured today", s?.let { dur(it.todaySec) } ?: "—", Modifier.weight(1f))
                Stat("Notes on phone today", today.size.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Waiting on watch", s?.waiting?.toString() ?: "—", Modifier.weight(1f))
                Stat("Watch storage free", s?.let { if (it.freeMb >= 1000) "%.1f GB".format(it.freeMb / 1000.0) else "${it.freeMb} MB" } ?: "—", Modifier.weight(1f))
            }

            Text(
                "Tip: add the Sotto tile on the watch (swipe left on the watch face → + ) for Pause, Mark and Sync with one tap.",
                fontSize = 12.sp, color = Palette.Muted, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Action(text: String, icon: ImageVector, modifier: Modifier, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .then(
                when {
                    primary -> Modifier.background(Palette.Accent)
                    danger -> Modifier.background(Palette.Live.copy(alpha = 0.16f)).border(1.dp, Palette.Live.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    else -> Modifier.border(1.dp, Palette.Line, RoundedCornerShape(22.dp))
                }
            )
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = when { primary -> Palette.OnAccent; danger -> Palette.Live; else -> Palette.Text }
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.size(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(label, fontSize = 12.sp, color = Palette.Muted)
    }
}
