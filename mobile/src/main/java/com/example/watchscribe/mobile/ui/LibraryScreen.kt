package com.example.watchscribe.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.launch
import com.example.watchscribe.mobile.data.WatchSync
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.PlaceTagger
import com.example.watchscribe.mobile.data.Recording
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    all: List<Recording>,
    opts: ViewOptions,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onOptions: () -> Unit,
    onExport: (List<String>) -> Unit,
    onDelete: (List<String>) -> Unit,
    onStar: (List<String>, Boolean) -> Unit,
    onAsk: () -> Unit,
    bottomNav: @Composable () -> Unit,
) {
    val visible = opts.apply(all)
    val groups = opts.group(visible)
    val selecting = selection.isNotEmpty()
    var digest by remember { mutableStateOf<Pair<String, List<Recording>>?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, List<Recording>>?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Palette.Ground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selecting) SelectionBar(
                onShare = { onExport(selection.toList()) },
                onExport = { onExport(selection.toList()) },
                onStar = { onStar(selection.toList(), !selection.all { id -> all.firstOrNull { it.id == id }?.starred == true }) },
                onDelete = { onDelete(selection.toList()) },
                onSummary = {
                    val picked = all.filter { it.id in selection }
                    digest = "Summary of ${picked.size} ${if (picked.size == 1) "note" else "notes"}" to picked
                },
            ) else bottomNav()
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(
                onClick = onAsk,
                containerColor = Palette.Accent, contentColor = Palette.OnAccent,
                shape = RoundedCornerShape(20.dp),
                icon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) },
                text = { Text("Ask your notes", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (selecting) SelectionHeader(
                    count = selection.size,
                    onClose = { onSelectionChange(emptySet()) },
                    onAll = { onSelectionChange(visible.map { it.id }.toSet()) },
                ) else Header(opts, all, onOptions)
            }
            if (visible.isEmpty()) item { EmptyState(all.isEmpty()) }
            if (!selecting && all.size > visible.size) item(key = "hidden") {
                val n = all.size - visible.size
                Text(
                    "$n ${if (n == 1) "note is" else "notes are"} hidden by filters · Show all",
                    fontSize = 13.sp, color = Palette.Accent,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { opts.reset(); opts.query = "" }.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            groups.forEach { (label, list) ->
                stickyHeader(key = "h_$label") {
                    Row(
                        Modifier.fillMaxWidth().background(Palette.Ground).padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selecting) {
                            TextButton(onClick = { onSelectionChange(selection + list.map { it.id }) }) { Text("Select all", fontSize = 13.sp) }
                        } else {
                            Text(totalLabel(list), fontSize = 12.sp, color = Palette.Muted, fontFamily = FontFamily.Monospace)
                            Box {
                                IconButton(onClick = { menuFor = label }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.MoreVert, "Actions for $label", Modifier.size(20.dp), tint = Palette.Muted)
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = menuFor == label, onDismissRequest = { menuFor = null },
                                    containerColor = Palette.Surface,
                                ) {
                                    val isDay = opts.groupBy == GroupBy.DAY
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (isDay) "Summary of the day" else "Summary of these notes") },
                                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                        onClick = { menuFor = null; digest = "$label · summary" to list },
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Select these notes") },
                                        leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                                        onClick = { menuFor = null; onSelectionChange(list.map { it.id }.toSet()) },
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (isDay) "Delete the day…" else "Delete these notes…", color = Palette.Danger) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Palette.Danger) },
                                        onClick = { menuFor = null; deleting = label to list },
                                    )
                                }
                            }
                        }
                    }
                }
                items(list, key = { it.id }) { r ->
                    RecordingCard(
                        r = r,
                        selecting = selecting,
                        selected = r.id in selection,
                        onClick = {
                            if (selecting) onSelectionChange(if (r.id in selection) selection - r.id else selection + r.id)
                            else onOpen(r.id)
                        },
                        onLongClick = { onSelectionChange(selection + r.id) },
                    )
                }
            }
        }
    }
    digest?.let { (title, notes) ->
        DigestSheet(title, notes, onOpen = { id -> digest = null; onOpen(id) }, onDismiss = { digest = null })
    }
    deleting?.let { (label, notes) ->
        DeleteGroupDialog(label, notes.size, onConfirm = { deleting = null; onDelete(notes.map { it.id }) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun Header(opts: ViewOptions, all: List<Recording>, onOptions: () -> Unit) {
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Notes", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, modifier = Modifier.padding(horizontal = 4.dp))
        WatchStatusCard(all.maxOfOrNull { it.start })
        PhoneMicCard()
        AiCard()
        PlacesPermissionCard()
        SearchField(opts.query) { opts.query = it }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Accent).clickable(onClick = onOptions).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp), tint = Palette.OnAccent)
                Text(opts.summary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent)
            }
            Pill("Starred", opts.starredOnly, { opts.starredOnly = !opts.starredOnly })
            Pill("Last 7 days", opts.lastDays == 7, { opts.lastDays = if (opts.lastDays == 7) null else 7 })
            Pill("Over 5 min", opts.longOnly, { opts.longOnly = !opts.longOnly })
            opts.place?.let { p -> Pill("$p  ✕", true, { opts.place = null }) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = Palette.Muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search titles and places", color = Palette.Muted, fontSize = 15.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = Palette.Muted) }
    }
}

@Composable
private fun WatchStatusCard(lastNote: Long?) {
    val ctx = LocalContext.current
    var watch by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            watch = withContext(Dispatchers.IO) {
                runCatching {
                    Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 5, TimeUnit.SECONDS).firstOrNull()?.displayName
                }.getOrNull()
            }
            checked = true
            tick++
            delay(15_000)
        }
    }
    val scope = rememberCoroutineScope()
    val sync by WatchSync.state.collectAsState()
    val busy = sync == "Asking the watch…" || sync == "Receiving from the watch…"
    LaunchedEffect(sync) { if (sync != null && !busy) { delay(12_000); WatchSync.clear() } }
    val connected = watch != null
    val ago = lastNote?.let { relative(it) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(if (connected) Palette.Live.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Dot(if (connected) Palette.Live else Palette.Muted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when { !checked -> "Checking watch…"; connected -> "$watch · Connected"; else -> "Watch not connected" },
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                sync ?: if (ago != null) "Last note $ago" else "No notes yet",
                fontSize = 12.sp, color = if (sync != null) Palette.Soft else Palette.Muted,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
        else TextButton(onClick = {
            scope.launch(Dispatchers.IO) { WatchSync.request(ctx) }
        }) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = Palette.Accent)
            Spacer(Modifier.size(6.dp))
            Text("Sync", color = Palette.Accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PlacesPermissionCard() {
    val ctx = LocalContext.current
    var fg by remember { mutableStateOf(PlaceTagger.hasPermission(ctx)) }
    var bg by remember { mutableStateOf(Build.VERSION.SDK_INT < 29 || PlaceTagger.hasBackground(ctx)) }
    var dismissed by remember { mutableStateOf(false) }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bg = it }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        fg = res.values.any { it }
    }
    if (dismissed || (fg && bg)) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Palette.Line, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Place, null, Modifier.size(20.dp), tint = Palette.Accent)
        Text(
            if (!fg) "Tag each note with where you were" else "Allow location “all the time” so places work while the app is closed",
            fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            if (!fg) fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }) { Text("Allow") }
        IconButton(onClick = { dismissed = true }) { Icon(Icons.Rounded.Close, "Dismiss", tint = Palette.Muted) }
    }
}

@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Exit selection") }
        Text("$count selected", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onAll) { Text("Select all") }
    }
}

@Composable
private fun SelectionBar(onShare: () -> Unit, onExport: () -> Unit, onStar: () -> Unit, onDelete: () -> Unit, onSummary: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarAction(Icons.Rounded.AutoAwesome, "Summary", Palette.Text, onSummary)
        BarAction(Icons.Rounded.Share, "Share", Palette.Text, onShare)
        BarAction(Icons.Rounded.Download, "Export", Palette.Text, onExport)
        BarAction(Icons.Rounded.Star, "Star", Palette.Text, onStar)
        BarAction(Icons.Rounded.Delete, "Delete", Palette.Danger, onDelete)
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = color)
        Text(label, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun EmptyState(nothingYet: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (nothingYet) "No notes yet" else "Nothing matches", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (nothingYet) "Start Sotto on your watch. Notes appear here a few seconds after you stop talking."
            else "Try clearing the search or the filters.",
            fontSize = 14.sp, color = Palette.Muted, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}

fun relative(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> (m / (24 * 60)).let { d -> if (d == 1L) "yesterday" else "$d days ago" }
    }
}
