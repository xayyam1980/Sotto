package com.example.watchscribe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.content.Context

/**
 * Invisible activity that starts or stops recording, then closes.
 * Used by the tile and the Start button: Android only lets a microphone
 * foreground service start while an activity is visible.
 */
class ToggleActivity : Activity() {
    companion object {
        const val EXTRA_START_ONLY = "start_only"
        const val EXTRA_TOKEN = "token"
        /** Intent other parts of Sotto use; carries the secret token. */
        fun intent(ctx: Context) = Intent(ctx, ToggleActivity::class.java).putExtra(EXTRA_TOKEN, Stats.token(ctx))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(EXTRA_TOKEN) != Stats.token(this)) { finish(); return } // not from Sotto
        if (intent.getBooleanExtra(EXTRA_START_ONLY, false) && RecorderService.isRunning) {
            RecorderService.resume(this) // already on: just make sure it's listening
            finish()
            return
        }
        if (RecorderService.isRunning) {
            startService(Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_STOP))
            finish()
            return
        }
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            Toast.makeText(this, "Microphone permission needed", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun start() {
        startForegroundService(Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START))
        finish()
    }
}
