package com.example.watchscribe.mobile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.watchscribe.mobile.data.Recording
import com.example.watchscribe.mobile.data.Seg
import com.example.watchscribe.mobile.data.WatchSync
import com.example.watchscribe.mobile.ui.LibraryScreen
import com.example.watchscribe.mobile.ui.PlacesScreen
import com.example.watchscribe.mobile.ui.RecordingScreen
import com.example.watchscribe.mobile.ui.ViewOptions
import com.example.watchscribe.mobile.ui.WatchScreen
import com.example.watchscribe.mobile.ui.WatchScribeTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Draws the real phone screens with made-up sample notes and saves them as PNGs in
 * docs/screens (used by the README). Run it like any other test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotsTest {
    private val out = "../docs/screens"
    private val now = System.currentTimeMillis()
    private val noNav: @Composable () -> Unit = {}

    private fun note(
        id: Int, minutesAgo: Long, sec: Double, title: String, place: String?, summary: String?,
        segs: List<Seg>?, starred: Boolean = false, actions: List<String> = emptyList(), marks: List<Double> = emptyList(),
        names: List<String?> = emptyList(),
    ) = Recording(
        id = "chunk_sample_$id.wav", clean = File("sample_$id.wav"), raw = null, start = now - minutesAgo * 60_000, seconds = sec,
        sizeBytes = 0, title = title, customTitle = false, starred = starred, place = place, lat = null, lon = null,
        peaks = List(64) { i -> (0.25f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.37 + id)).toFloat()) },
        segments = segs, lang = "en", sync = null, marks = marks, summary = summary, actions = actions,
        speakers = segs?.mapNotNull { it.speaker }?.distinct()?.size ?: 0,
        names = names,
    )

    private val meeting = note(
        1, 25, 412.0, "Q4 roadmap and hiring plan", "Office, Gdańsk",
        "The team agreed to ship the mobile release in November and to open two backend roles. Budget review moves to next week.",
        listOf(
            Seg(0.0, 6.5, "Okay, let's start with the roadmap for the fourth quarter.", 0),
            Seg(6.5, 14.0, "The mobile release is ready for testing, so November looks realistic.", 1),
            Seg(14.0, 21.0, "Good. What do we need on the backend side to support it?", 0),
            Seg(21.0, 30.0, "Honestly, two more engineers. We're already behind on the API work.", 2),
            Seg(30.0, 38.0, "Then let's open both roles this week. Anna, can you write the job posts?", 0),
            Seg(38.0, 44.0, "Sure, I'll have drafts by Friday.", 1),
            Seg(44.0, 52.0, "And we move the budget review to next Tuesday.", 0),
        ),
        starred = true,
        actions = listOf("Anna: draft two backend job posts by Friday", "Move budget review to next Tuesday", "Plan mobile release testing for November"),
        marks = listOf(30.5),
        names = listOf("Me", "Anna", "Piotr"),
    )
    private val notes = listOf(
        meeting,
        note(2, 95, 185.0, "Call with the supplier about delivery", "Office, Gdańsk",
            "Delivery is delayed by one week; the supplier offered a 5% discount.", listOf(Seg(0.0, 5.0, "They said the delivery slips by a week.", 0)),
            actions = listOf("Confirm new delivery date by e-mail")),
        note(3, 240, 64.0, "Groceries and weekend plans", "Home", "Buy fruit, coffee and bread. Visit the old town on Saturday.",
            listOf(Seg(0.0, 4.0, "We need coffee and bread for the weekend.", 0))),
        note(4, 26 * 60, 1260.0, "Workshop: user interviews", "Coworking, Gdynia",
            "Users want faster search and offline access. Three interviews planned next sprint.", listOf(Seg(0.0, 5.0, "Search is the most requested feature.", 0))),
        note(5, 27 * 60, 22.0, "Recording · 14:10", "Coworking, Gdynia", null, null),
    )

    private fun shoot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("$out/$name.png") { WatchScribeTheme { content() } }

    @Test fun notes() {
        // A connected Bluetooth headset, so the "Record with phone" card shows the mic choice
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        org.robolectric.Shadows.shadowOf(am).setInputDevices(
            listOf(
                org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                    .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                    .build(),
            ),
        )
        notesShot()
    }

    private fun notesShot() = shoot("phone-notes") {
        LibraryScreen(
            all = notes, opts = ViewOptions(), selection = emptySet(), onSelectionChange = {}, snackbar = SnackbarHostState(),
            onOpen = {}, onOptions = {}, onExport = {}, onDelete = {}, onStar = { _, _ -> }, onAsk = {}, bottomNav = noNav,
        )
    }

    @Test fun noteDetail() = shoot("phone-note") {
        RecordingScreen(r = meeting, onBack = {}, onStar = {}, onShare = {}, onDelete = {}, onRename = {})
    }

    /** Settings: People, Keep audio, Weekly summary, Backup. */
    @Test fun settings() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "people.json").writeText(
            """[{"id":"1","name":"Me","n":12,"voice":[1,0,0]},{"id":"2","name":"Anna","n":5,"voice":[0,1,0]},{"id":"3","name":"Piotr","n":2,"voice":[0,0,1]}]""",
        )
        com.example.watchscribe.mobile.data.People.invalidate()
        com.example.watchscribe.mobile.data.People.load(ctx)
        shoot("phone-settings") {
            // Same colours as inside the real Settings sheet
            androidx.compose.material3.Surface(
                color = com.example.watchscribe.mobile.ui.Palette.Surface,
                contentColor = com.example.watchscribe.mobile.ui.Palette.Text,
            ) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                ) { com.example.watchscribe.mobile.ui.MoreSettings() }
            }
        }
    }

    @Test fun places() = shoot("phone-places") {
        PlacesScreen(all = notes, onOpenPlace = {}, onRename = { _, _ -> }, bottomNav = noNav)
    }

    @Test fun watch() {
        WatchSync.onStatus(
            ApplicationProvider.getApplicationContext(),
            """{"state":"listening","pausedUntil":0,"speaking":true,"chunkSec":83,"waiting":0,"todaySec":4920,"todayNotes":14,"battery":71,"freeMb":5200,"sync":"All synced","error":"","note":""}""",
        )
        shoot("phone-watch") { WatchScreen(bottomNav = noNav) }
    }
}
