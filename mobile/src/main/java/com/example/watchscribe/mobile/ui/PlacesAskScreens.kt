package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watchscribe.mobile.data.Recording

@Composable
fun PlacesScreen(
    all: List<Recording>,
    onOpenPlace: (String) -> Unit,
    onRename: (String, String) -> Unit,
    bottomNav: @Composable () -> Unit,
) {
    val places = all.groupBy { it.placeOrUnknown }.toList().sortedByDescending { it.second.size }
    var renaming by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = Palette.Ground, bottomBar = bottomNav) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Places", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                    Text("${places.size} places · ${all.size} notes", fontSize = 14.sp, color = Palette.Muted)
                }
            }
            item { PlacesMap(pinsFor(places), onPin = onOpenPlace, modifier = Modifier.padding(bottom = 8.dp)) }
            if (places.isEmpty()) item {
                Text("No notes yet.", color = Palette.Muted, modifier = Modifier.padding(4.dp))
            }
            itemsIndexed(places, key = { _, p -> p.first }) { i, (name, list) ->
                val tint = Palette.PlaceTints[i % Palette.PlaceTints.size]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
                        .clickable { onOpenPlace(name) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Place, null, tint = Palette.Ground)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${totalLabel(list)} · ${relative(list.maxOf { it.start })}", fontSize = 12.sp, color = Palette.Muted)
                    }
                    if (name != "Unknown place") IconButton(onClick = { renaming = name }) { Icon(Icons.Rounded.Edit, "Rename $name", tint = Palette.Muted) }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.Muted)
                }
            }
            item {
                Text(
                    "Places come from your phone's location when a note arrives. Rename a place once and every note there follows.",
                    fontSize = 12.sp, lineHeight = 18.sp, color = Palette.Muted, modifier = Modifier.padding(4.dp),
                )
            }
        }
    }

    renaming?.let { from ->
        var text by remember(from) { mutableStateOf(from) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename place") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onRename(from, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}
