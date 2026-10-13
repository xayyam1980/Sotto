package com.example.watchscribe.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.watchscribe.mobile.data.Dest
import com.example.watchscribe.mobile.data.ExportOptions
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.ai.Models
import com.example.watchscribe.mobile.ai.ProcessWorker
import com.example.watchscribe.mobile.data.RecordingStore
import com.example.watchscribe.mobile.ui.AskScreen
import com.example.watchscribe.mobile.ui.ExportSheet
import com.example.watchscribe.mobile.ui.LibraryScreen
import com.example.watchscribe.mobile.ui.Palette
import com.example.watchscribe.mobile.ui.PlacesScreen
import com.example.watchscribe.mobile.ui.RecordingScreen
import com.example.watchscribe.mobile.ui.Screen
import com.example.watchscribe.mobile.ui.ViewOptions
import com.example.watchscribe.mobile.ui.WatchScreen
import com.example.watchscribe.mobile.ui.LockScreen
import com.example.watchscribe.mobile.ui.ViewOptionsSheet
import com.example.watchscribe.mobile.ui.WatchScribeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RecordingStore.emptyTrash(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            WatchScribeTheme {
                val lock by AppLock.locked.collectAsState()
                if (lock.value) LockScreen { AppLock.prompt(this@MainActivity) {} } else App()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop()
    }

    override fun onResume() {
        super.onResume()
        Thread { RecordingStore.refresh(applicationContext) }.start()
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by RecordingStore.items.collectAsState()
    val opts = remember { ViewOptions() }
    val snackbar = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var showOptions by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingStore.refresh(ctx) }
        ProcessWorker.schedule(ctx)
        // While the app is open: keep model download progress fresh
        while (true) {
            withContext(Dispatchers.IO) { runCatching { Models.refresh(ctx) } }
            delay(2_000)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val src = pendingSave
        if (uri != null && src != null) {
            scope.launch(Dispatchers.IO) { Exporter.saveTo(ctx, src, uri) }
            Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
        }
        pendingSave = null
    }

    fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        RecordingStore.delete(ctx, ids)
        selection = emptySet()
        if (screen is Screen.Detail) screen = Screen.Library
        scope.launch {
            val res = snackbar.showSnackbar(
                "${ids.size} ${if (ids.size == 1) "note" else "notes"} deleted",
                actionLabel = "Undo", duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) RecordingStore.undoDelete(ctx)
        }
    }

    fun send(dest: Dest, opt: ExportOptions, ids: List<String>) {
        val recs = all.filter { it.id in ids }
        if (recs.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) { Exporter.build(ctx, recs, opt) }
            if (dest == Dest.FILES) {
                val one = if (files.size == 1) files[0] else withContext(Dispatchers.IO) { Exporter.zip(ctx, files) }
                pendingSave = one
                saveLauncher.launch(one.name)
            } else {
                Exporter.send(ctx, dest, files, recs)
            }
            exportIds = null
            selection = emptySet()
        }
    }

    val nav: @Composable () -> Unit = {
        NavigationBar(containerColor = Palette.Surface) {
            val colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.AccentContainer, selectedIconColor = Palette.Accent, selectedTextColor = Palette.Text, unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted)
            NavigationBarItem(screen == Screen.Library, { screen = Screen.Library }, { Icon(Icons.AutoMirrored.Rounded.Notes, null) }, label = { Text("Notes") }, colors = colors)
            NavigationBarItem(screen == Screen.Places, { screen = Screen.Places }, { Icon(Icons.Rounded.Place, null) }, label = { Text("Places") }, colors = colors)
            NavigationBarItem(screen == Screen.Ask, { screen = Screen.Ask }, { Icon(Icons.AutoMirrored.Rounded.Chat, null) }, label = { Text("Ask") }, colors = colors)
            NavigationBarItem(screen == Screen.Watch, { screen = Screen.Watch }, { Icon(Icons.Rounded.Watch, null) }, label = { Text("Watch") }, colors = colors)
        }
    }

    BackHandler(enabled = screen != Screen.Library || selection.isNotEmpty()) {
        if (selection.isNotEmpty()) selection = emptySet() else screen = Screen.Library
    }

    when (val s = screen) {
        Screen.Library -> LibraryScreen(
            all = all, opts = opts, selection = selection, onSelectionChange = { selection = it },
            snackbar = snackbar,
            onOpen = { screen = Screen.Detail(it) },
            onOptions = { showOptions = true },
            onExport = { exportIds = it },
            onDelete = { delete(it) },
            onStar = { ids, star -> RecordingStore.setStarred(ctx, ids, star); selection = emptySet() },
            onAsk = { screen = Screen.Ask },
            bottomNav = nav,
        )
        Screen.Places -> PlacesScreen(
            all = all,
            onOpenPlace = { opts.place = it; screen = Screen.Library },
            onRename = { from, to -> RecordingStore.renamePlace(ctx, from, to) },
            bottomNav = nav,
        )
        Screen.Watch -> WatchScreen(bottomNav = nav)
        Screen.Ask -> AskScreen(bottomNav = nav, onOpen = { screen = Screen.Detail(it) })
        is Screen.Detail -> {
            val r = all.firstOrNull { it.id == s.id }
            if (r == null) {
                LaunchedEffect(s.id) { screen = Screen.Library }
            } else {
                RecordingScreen(
                    r = r,
                    onBack = { screen = Screen.Library },
                    onStar = { RecordingStore.setStarred(ctx, listOf(r.id), it) },
                    onShare = { exportIds = listOf(r.id) },
                    onDelete = { delete(listOf(r.id)) },
                    onRename = { RecordingStore.rename(ctx, r.id, it) },
                )
            }
        }
    }

    if (showOptions) ViewOptionsSheet(opts, opts.apply(all).size) { showOptions = false }
    exportIds?.let { ids ->
        ExportSheet(
            recs = all.filter { it.id in ids },
            onDismiss = { exportIds = null },
            onSend = { dest, opt -> send(dest, opt, ids) },
        )
    }
}
