package com.example.watchscribe

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication: whether Sotto is listening, paused or off, with today's minutes.
 * Tapping it opens Sotto.
 */
class SottoComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        /** Asks watch faces to show the new state (cheap; called with every tile update). */
        fun update(ctx: Context) {
            try {
                ComplicationDataSourceUpdateRequester
                    .create(ctx, ComponentName(ctx, SottoComplicationService::class.java))
                    .requestUpdateAll()
            } catch (t: Throwable) {
                Log.w(RecorderService.TAG, "Complication update failed", t)
            }
        }

        /** (title, icon) for the current state. */
        internal fun state(): Pair<String, Int> = when {
            !RecorderService.isRunning -> "Off" to R.drawable.ic_stop
            RecorderService.isPaused -> "Paused" to R.drawable.ic_pause
            RecorderService.chunkSeconds > 0 -> "Rec" to R.drawable.ic_mic
            else -> "Live" to R.drawable.ic_mic
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val (title, icon) = state()
        return build(title, Stats.shortDuration(Stats.today(this).first), icon)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build("Live", "42m", R.drawable.ic_mic) else null

    private fun build(title: String, text: String, icon: Int): ComplicationData {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder("Sotto $title, $text today").build(),
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, icon)).build())
            .setTapAction(open)
            .build()
    }
}
