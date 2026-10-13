package com.example.watchscribe

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.watchscribe.Stats.wantListening
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant

/**
 * Sotto tile. States: Off, Listening, Capturing, Paused, Needs attention.
 * Buttons either act right away (LoadAction → handled in onTileRequest)
 * or open a small screen (LaunchAction).
 */
class RecorderTileService : TileService() {

    private companion object {
        const val RES = "4"
        const val ID_MARK = "mark"
        const val ID_RESUME = "resume"
        const val ID_EXTEND = "extend"
        const val ID_SYNC = "sync"

        const val WHITE = 0xFFEEF0F2.toInt()
        const val MUTED = 0xFFA3A9B0.toInt()
        const val ACCENT = 0xFF9EC1FF.toInt()
        const val ON_ACCENT = 0xFF0E1A33.toInt()
        const val SURFACE = 0xFF1F2226.toInt()
        const val LIVE = 0xFFFF6A5C.toInt()
        const val LIVE_TRACK = 0xFF2A1614.toInt()
        const val AMBER = 0xFFF2C46D.toInt()
        const val ON_AMBER = 0xFF2E2104.toInt()
        const val AMBER_TRACK = 0xFF2A2416.toInt()
        const val GREEN = 0xFF9ED9B4.toInt()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        when (requestParams.currentState.lastClickableId) {
            ID_MARK -> RecorderService.mark(this)
            ID_RESUME -> RecorderService.resume(this)
            ID_EXTEND -> RecorderService.extend(this, 15)
            ID_SYNC -> ChunkSync.requestSync(this)
        }
        val device = requestParams.deviceConfiguration
        val root = when {
            attention() != null -> attentionLayout(device, attention()!!)
            !RecorderService.isRunning -> offLayout(device)
            RecorderService.isPaused -> pausedLayout(device)
            RecorderService.chunkSeconds > 0 -> capturingLayout(device)
            else -> listeningLayout(device)
        }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setFreshnessIntervalMillis(if (RecorderService.isRunning) 60_000L else 900_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val b = ResourceBuilders.Resources.Builder().setVersion(RES)
        mapOf(
            "pause" to R.drawable.ic_pause, "play" to R.drawable.ic_play, "mark" to R.drawable.ic_mark,
            "mark_on" to R.drawable.ic_mark_filled, "sync" to R.drawable.ic_sync, "mic" to R.drawable.ic_mic,
            "forget" to R.drawable.ic_forget, "memo" to R.drawable.ic_memo,
        ).forEach { (id, res) ->
            b.addIdToImageMapping(id, ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
                .build())
        }
        return Futures.immediateFuture(b.build())
    }

    // ---------------------------------------------------------------- states

    /** Something the user should know about, or null. */
    private fun attention(): Pair<String, String>? {
        val waiting = RecorderService.savedChunks(this).size
        val freeMb = filesDir.usableSpace / 1_000_000
        return when {
            !RecorderService.isRunning && wantListening -> "Listening stopped" to "Tap to restart"
            RecorderService.lastError != null && RecorderService.isRunning -> RecorderService.lastError!! to "Another app is using the mic"
            freeMb < 200 -> "Storage low" to "$waiting waiting · ${freeMb} MB free"
            // phone away while listening is shown on the normal tile (buttons stay usable)
            !RecorderService.isRunning && ChunkSync.phoneReachable == false && waiting >= 3 ->
                "$waiting waiting" to "Phone out of reach. Kept on the watch · ${gb(freeMb)} free"
            else -> null
        }
    }

    private fun gb(mb: Long) = if (mb >= 1000) "%.1f GB".format(mb / 1000.0) else "$mb MB"

    private fun listeningLayout(device: DeviceParameters): LayoutElement {
        val (sec, notes) = Stats.today(this)
        val waiting = RecorderService.savedChunks(this).size
        // second line: battery estimate and anything still waiting for the phone
        val info = listOfNotNull(
            Battery.label(this),
            when {
                waiting == 0 -> null
                ChunkSync.phoneReachable == false -> "$waiting waiting · phone away"
                else -> "$waiting waiting"
            },
        ).joinToString(" · ").ifEmpty { "✓ All synced" }
        val low = RecorderService.lightMode
        return PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(label("sotto"))
            .setContent(column(
                text("● Listening", Typography.TYPOGRAPHY_TITLE3, WHITE),
                text("Today ${Stats.duration(sec)} · $notes ${if (notes == 1) "note" else "notes"}", Typography.TYPOGRAPHY_CAPTION2, MUTED),
                // tapping the line syncs now
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(load(ID_SYNC)).build())
                    .addContent(text(info, Typography.TYPOGRAPHY_CAPTION2, if (low) AMBER else if (waiting == 0) GREEN else MUTED))
                    .build(),
                spacer(6f),
                row(pauseButton(), spacerW(8f), markButton(false), spacerW(8f), memoButton()),
            ))
            .setPrimaryChipContent(forgetChip(device))
            .build()
    }

    private fun capturingLayout(device: DeviceParameters): LayoutElement {
        val sec = RecorderService.chunkSeconds.toInt()
        val marks = RecorderService.marksInChunk
        // The watch runs the clock itself: elapsed = now - chunk start, ticking every second.
        val start = Instant.ofEpochMilli(System.currentTimeMillis() - sec * 1000L)
        val elapsed = DynamicBuilders.DynamicInstant.withSecondsPrecision(start)
            .durationUntil(DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision())
        val twoDigits = DynamicBuilders.DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build()
        val clock = elapsed.toIntMinutes().format()
            .concat(DynamicBuilders.DynamicString.constant(":"))
            .concat(elapsed.getSecondsPart().format(twoDigits))
        val progress = elapsed.toIntSeconds().asFloat().div(RecorderService.MAX_CHUNK_SEC.toFloat())
        val staticClock = "%d:%02d".format(sec / 60, sec % 60)
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(liveRing((sec / RecorderService.MAX_CHUNK_SEC).toFloat(), progress))
            .setPrimaryLabelTextContent(label("sotto"))
            .setContent(column(
                text("● Capturing", Typography.TYPOGRAPHY_TITLE2, LIVE),
                dynamicText(staticClock, clock, "00:00", if (marks > 0) " · $marks marked" else ""),
                spacer(6f),
                row(pauseButton(), spacerW(8f), markButton(marks > 0), spacerW(8f), memoButton()),
                spacer(6f),
                forgetChip(device),
            ))
            .build()
    }

    private fun pausedLayout(device: DeviceParameters): LayoutElement {
        val until = RecorderService.pausedUntil
        val forever = until == RecorderService.UNTIL_RESUME
        val left = if (forever) 0 else ((until - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(0) + 1
        val total = Stats.lastPause(this).coerceAtLeast(1)
        val sub = if (forever) "Until you resume" else "Resumes ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(until))} · $left min"
        val content = column(
            text("Paused", Typography.TYPOGRAPHY_TITLE2, AMBER),
            text(sub, Typography.TYPOGRAPHY_CAPTION2, MUTED),
            spacer(8f),
            if (forever) resumeButton() else row(resumeButton(), spacerW(14f), extendButton()),
        )
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(ring(if (forever) 1f else (left.toFloat() / total).coerceIn(0f, 1f), AMBER, AMBER_TRACK))
            .setPrimaryLabelTextContent(label("sotto"))
            .setContent(content)
            .build()
    }

    private fun offLayout(device: DeviceParameters): LayoutElement =
        PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(label("sotto"))
            .setContent(column(
                text("Off", Typography.TYPOGRAPHY_TITLE2, WHITE),
                text("Not listening", Typography.TYPOGRAPHY_CAPTION2, MUTED),
            ))
            .setPrimaryChipContent(
                Chip.Builder(this, launch(ToggleActivity::class.java.name), device)
                    .setPrimaryLabelContent("Start listening")
                    .setIconContent("mic")
                    .setChipColors(ChipColors(ACCENT, ON_ACCENT))
                    .build()
            )
            .build()

    private fun attentionLayout(device: DeviceParameters, a: Pair<String, String>): LayoutElement {
        val restart = !RecorderService.isRunning
        return PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(label("sotto"))
            .setContent(column(
                text("⚠ ${a.first}", Typography.TYPOGRAPHY_TITLE3, AMBER),
                text(a.second, Typography.TYPOGRAPHY_CAPTION2, MUTED, maxLines = 3),
            ))
            .setPrimaryChipContent(
                if (restart) CompactChip.Builder(this, "Restart", launch(ToggleActivity::class.java.name), device)
                    .setChipColors(ChipColors(ACCENT, ON_ACCENT)).build()
                else CompactChip.Builder(this, "Sync now", load(ID_SYNC), device)
                    .setChipColors(ChipColors(SURFACE, WHITE)).build()
            )
            .build()
    }

    // ---------------------------------------------------------------- pieces

    private fun load(id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()

    private fun launch(activity: String) = ModifiersBuilders.Clickable.Builder()
        .setId("launch_$activity")
        .setOnClick(ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName).setClassName(activity)
                .addKeyToExtraMapping(ToggleActivity.EXTRA_TOKEN, ActionBuilders.AndroidStringExtra.Builder().setValue(Stats.token(this)).build())
                .build())
            .build())
        .build()

    private fun label(s: String) = Text.Builder(this, s).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(MUTED)).build()

    private fun text(s: String, typo: Int, color: Int, maxLines: Int = 1): LayoutElement =
        Text.Builder(this, s).setTypography(typo).setColor(argb(color)).setMaxLines(maxLines).build()

    private fun column(vararg items: LayoutElement): LayoutElement {
        val b = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        items.forEach { b.addContent(it) }
        return b.build()
    }

    private fun row(vararg items: LayoutElement): LayoutElement {
        val b = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        items.forEach { b.addContent(it) }
        return b.build()
    }

    private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()
    private fun spacerW(w: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(dp(w)).build()

    private fun iconButton(id: String, icon: String, desc: String, bg: Int, fg: Int, clickable: ModifiersBuilders.Clickable) =
        Button.Builder(this, clickable)
            .setIconContent(icon)
            .setContentDescription(desc)
            .setSize(dp(46f))
            .setButtonColors(ButtonColors(bg, fg))
            .build()

    private fun pauseButton() = iconButton("pause", "pause", "Pause listening", SURFACE, WHITE, launch(PauseActivity::class.java.name))
    private fun markButton(on: Boolean) =
        if (on) iconButton(ID_MARK, "mark_on", "Mark this moment again", AMBER, ON_AMBER, load(ID_MARK))
        else iconButton(ID_MARK, "mark", "Mark this moment", ACCENT, ON_ACCENT, load(ID_MARK))
    private fun memoButton() = iconButton("memo", "memo", "Note to self", SURFACE, WHITE, launch(MemoActivity::class.java.name))
    private fun forgetButton() = iconButton("forget", "forget", "Forget the last minutes", SURFACE, LIVE, launch(ForgetActivity::class.java.name))
    private fun forgetChip(device: DeviceParameters) =
        CompactChip.Builder(this, "Forget last…", launch(ForgetActivity::class.java.name), device)
            .setChipColors(ChipColors(SURFACE, LIVE))
            .build()
    private fun resumeButton() = iconButton(ID_RESUME, "play", "Resume listening", ACCENT, ON_ACCENT, load(ID_RESUME))
    private fun extendButton() = Button.Builder(this, load(ID_EXTEND))
        .setTextContent("+15", Typography.TYPOGRAPHY_CAPTION1)
        .setContentDescription("Pause 15 minutes longer")
        .setSize(dp(46f))
        .setButtonColors(ButtonColors(SURFACE, WHITE))
        .build()

    @Suppress("unused") private fun syncChip(device: DeviceParameters, waiting: Int) =
        CompactChip.Builder(this, if (waiting == 0) "✓ All synced" else "$waiting waiting · Sync", load(ID_SYNC), device)
            .setChipColors(ChipColors(SURFACE, if (waiting == 0) GREEN else WHITE))
            .build()

    private fun ring(progress: Float, color: Int, track: Int) = CircularProgressIndicator.Builder()
        .setProgress(progress.coerceIn(0f, 1f))
        .setCircularProgressIndicatorColors(ProgressIndicatorColors(color, track))
        .build()

    /** Text whose value the watch updates by itself (with a static fallback for older renderers). */
    private fun dynamicText(fallback: String, value: DynamicBuilders.DynamicString, widest: String, suffix: String): LayoutElement {
        val full = if (suffix.isEmpty()) value else value.concat(DynamicBuilders.DynamicString.constant(suffix))
        return LayoutElementBuilders.Text.Builder()
            .setText(TypeBuilders.StringProp.Builder(fallback + suffix).setDynamicValue(full).build())
            .setLayoutConstraintsForDynamicText(TypeBuilders.StringLayoutConstraint.Builder(widest + suffix).build())
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(18f))
                .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                .setColor(argb(WHITE))
                .build())
            .build()
    }

    /** Progress ring along the edge that fills by itself as the chunk grows. */
    private fun liveRing(fallback: Float, progress: DynamicBuilders.DynamicFloat) = CircularProgressIndicator.Builder()
        .setProgress(TypeBuilders.FloatProp.Builder(fallback.coerceIn(0f, 1f)).setDynamicValue(progress).build())
        .setCircularProgressIndicatorColors(ProgressIndicatorColors(LIVE, LIVE_TRACK))
        .build()

    @Suppress("unused") private fun ctx(): Context = this
}
