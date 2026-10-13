package com.example.watchscribe.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import com.example.watchscribe.mobile.ui.Consent
import com.example.watchscribe.mobile.ui.WatchScribeTheme
import com.example.watchscribe.mobile.ui.rememberConsentGate

/**
 * The widget's Record button. Starting the microphone needs a visible screen, so this
 * see-through screen shows the consent reminder (if on), starts or stops recording, and closes.
 */
class WidgetRecordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PhoneRecorder.live.value.running) {
            PhoneRecorder.stop(this); finish(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow the microphone in Sotto first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        if (!Consent.remind(this)) { start(); finish(); return }
        setContent {
            WatchScribeTheme {
                val gate = rememberConsentGate(onCancel = { finish() })
                LaunchedEffect(Unit) { gate.ask { start(); finish() } }
            }
        }
    }

    private fun start() {
        // Same microphone as last chosen on the Notes screen
        val wanted = getSharedPreferences("phone_mic", Context.MODE_PRIVATE).getString("mic", null)
        val id = PhoneRecorder.inputs(this).firstOrNull { it.label == wanted }?.id ?: 0
        PhoneRecorder.start(this, id)
        Toast.makeText(this, "Sotto is recording", Toast.LENGTH_SHORT).show()
    }
}
