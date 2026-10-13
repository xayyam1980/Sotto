package com.example.watchscribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Requests from the phone app:
 *  /sync          send everything now (closes the chunk being recorded), then report status
 *  /status        report status
 *  /cmd/start     start listening (resume if stopped/paused; if fully off, ask for one tap on the watch)
 *  /cmd/stop      stop listening: mic off, but stays ready so the phone can start it again
 *  /cmd/pause     body = minutes
 *  /cmd/off       turn Sotto off completely (can then only be started on the watch)
 */
class SyncRequestService : WearableListenerService() {
    /** The phone app came back in reach: send what's waiting straight away. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.any { it.isNearby }) ChunkSync.requestSync(this)
        else RecorderService.updateTile(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val node = event.sourceNodeId
        var note = ""
        when (event.path) {
            "/sync" -> { ChunkSync.replyTo = node; RecorderService.flush(this); return }
            "/cmd/start" -> {
                if (RecorderService.isRunning) RecorderService.resume(this)
                else { askForTap(this); note = "tap" }
            }
            "/cmd/stop" -> RecorderService.pause(this, 0)
            "/cmd/pause" -> RecorderService.pause(this, String(event.data).toIntOrNull() ?: 60)
            "/cmd/resume" -> RecorderService.resume(this)
            "/cmd/off" -> if (RecorderService.isRunning) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            }
            "/status" -> {}
            else -> return
        }
        if (event.path != "/status") Thread.sleep(300) // let the state settle before reporting
        ChunkSync.sendStatus(this, node, note)
    }

    /**
     * Android only lets the watch turn its own microphone on from something the user touches,
     * so when Sotto is fully off we put a "Start listening" notification on the wrist.
     */
    private fun askForTap(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("remote_start", "Start from phone", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            ctx, 7,
            ToggleActivity.intent(ctx).putExtra(ToggleActivity.EXTRA_START_ONLY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, "remote_start")
            .setSmallIcon(R.drawable.ic_stat_sotto)
            .setContentTitle("Start listening?")
            .setContentText("Tap to start Sotto")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_mic, "Start", pi)
            .build()
        nm.notify(9, n)
    }
}
