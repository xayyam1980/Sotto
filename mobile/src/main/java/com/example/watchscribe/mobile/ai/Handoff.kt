package com.example.watchscribe.mobile.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.watchscribe.mobile.data.Exporter
import com.example.watchscribe.mobile.data.Recording

/**
 * Sends a note to the official Claude or Gemini app with a ready-made prompt,
 * so your own subscription does the work there. You just press send.
 */
object Handoff {
    enum class App(val label: String, val pkg: String) {
        CLAUDE("Claude", "com.anthropic.claude"),
        GEMINI("Gemini", "com.google.android.apps.bard"),
    }

    fun prompt(r: Recording): String = buildString {
        appendLine("Below is an automatic transcript of a conversation I recorded (it may contain recognition mistakes).")
        appendLine("Please give me: 1) a short summary, 2) decisions made, 3) action items with owners, 4) anything unclear I should follow up on.")
        appendLine()
        appendLine("Recorded: ${r.title} · ${Exporter.length(r.seconds)} · ${r.placeOrUnknown}")
        appendLine()
        val segs = r.segments
        if (segs.isNullOrEmpty()) appendLine("(No transcript yet.)")
        else segs.forEach { s ->
            append("[${Exporter.stamp(s.t0)}] ")
            s.speaker?.let { append("Speaker ${it + 1}: ") }
            appendLine(s.text)
        }
    }

    fun send(ctx: Context, app: App, r: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt(r))
            putExtra(Intent.EXTRA_SUBJECT, r.title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(Intent(intent).setPackage(app.pkg))
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent.createChooser(intent, "Send to…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
