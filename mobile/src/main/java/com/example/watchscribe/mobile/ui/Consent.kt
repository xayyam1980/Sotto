package com.example.watchscribe.mobile.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reminder to get people's consent before recording them. */
object Consent {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("consent", Context.MODE_PRIVATE)
    fun remind(ctx: Context) = prefs(ctx).getBoolean("remind", true)
    fun setRemind(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("remind", on).apply()
}

/** Runs actions that start recording, after the consent reminder (unless switched off). */
class ConsentGate internal constructor(private val ctx: Context, private val pending: (() -> Unit) -> Unit) {
    fun ask(start: () -> Unit) = if (Consent.remind(ctx)) pending(start) else start()
}

@Composable
fun rememberConsentGate(): ConsentGate {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gate = remember { ConsentGate(ctx) { pending = it } }
    pending?.let { start ->
        var dontRemind by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Did everyone agree?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Before recording, tell the people around you and get their OK. " +
                            "Recording people without their consent may be illegal where you are (in the EU it is covered by GDPR) " +
                            "and it breaks their trust.",
                        fontSize = 14.sp,
                    )
                    Text("Recordings stay on your phone and watch. Delete any note if someone asks.", fontSize = 13.sp, color = Palette.Muted)
                    Row(Modifier.clickable { dontRemind = !dontRemind }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(dontRemind, { dontRemind = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.Accent))
                        Text("Don't remind me again", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontRemind) Consent.setRemind(ctx, false)
                    pending = null
                    start()
                }) { Text("Everyone agreed, start") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
    return gate
}
