package com.example.watchscribe.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.RecordingStore
import com.example.watchscribe.mobile.ui.relative
import kotlinx.coroutines.flow.MutableStateFlow

/** Home-screen widget: one-tap Record with phone, and the three latest notes. */
class SottoWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val app = ctx.applicationContext
        val done = goAsync()
        Thread {
            try {
                if (RecordingStore.items.value.isEmpty()) RecordingStore.refresh(app) // refresh() redraws the widget
                else update(app)
            } finally { done.finish() }
        }.start()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        /** A note the widget asked to open; the app shows it. */
        val openRequest = MutableStateFlow<String?>(null)

        private val rows = listOf(
            Triple(R.id.note1, R.id.note1_title, R.id.note1_meta),
            Triple(R.id.note2, R.id.note2_title, R.id.note2_meta),
            Triple(R.id.note3, R.id.note3_title, R.id.note3_meta),
        )

        /** Redraws all Sotto widgets (cheap; call after notes or recording state change). */
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SottoWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_sotto)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            val running = PhoneRecorder.live.value.running
            v.setTextViewText(R.id.widget_record, if (running) "■ Stop" else "● Record")
            v.setOnClickPendingIntent(
                R.id.widget_record,
                PendingIntent.getActivity(ctx, 30, Intent(ctx, WidgetRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags),
            )
            v.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(ctx, 31, Intent(ctx, MainActivity::class.java), flags))

            // With App lock on, note titles stay off the home screen
            val locked = AppLock.enabled(ctx)
            val notes = if (locked) emptyList() else RecordingStore.items.value.sortedByDescending { it.start }.take(3)
            v.setViewVisibility(R.id.widget_empty, if (notes.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_empty, if (locked) "Notes are hidden while App lock is on" else "No notes yet")
            rows.forEachIndexed { i, (row, title, meta) ->
                val r = notes.getOrNull(i)
                if (r == null) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
                v.setViewVisibility(row, View.VISIBLE)
                v.setTextViewText(title, r.title)
                v.setTextViewText(meta, "${relative(r.start)} · ${Exporter.length(r.seconds)}" + (r.summary?.let { " · $it" } ?: ""))
                v.setOnClickPendingIntent(
                    row,
                    PendingIntent.getActivity(
                        ctx, 40 + i,
                        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, r.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        flags,
                    ),
                )
            }
            mgr.updateAppWidget(ids, v)
        }
    }
}
