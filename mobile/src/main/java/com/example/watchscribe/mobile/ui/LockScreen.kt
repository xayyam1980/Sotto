package com.example.watchscribe.mobile.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

/** Shown instead of the app while it is locked. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(Palette.AccentContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(32.dp), tint = Palette.Accent)
            }
            Text("Sotto is locked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Your notes stay on this phone.", fontSize = 14.sp, color = Palette.Muted, textAlign = TextAlign.Center)
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Accent).clickable(onClick = onUnlock).padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Unlock", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}
