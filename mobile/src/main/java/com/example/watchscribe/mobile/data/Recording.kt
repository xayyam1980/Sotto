package com.example.watchscribe.mobile.data

import java.io.File

/** One recording received from the watch, plus the metadata the app keeps for it. */
data class Recording(
    val id: String,              // file name, e.g. chunk_20261011_104200.wav
    val clean: File,             // noise-reduced copy, used for listening
    val raw: File?,              // original from the watch, kept for transcription
    val start: Long,             // epoch millis when recording started
    val seconds: Double,
    val sizeBytes: Long,
    val title: String,
    val customTitle: Boolean,
    val starred: Boolean,
    val place: String?,
    val lat: Double?,
    val lon: Double?,
    val peaks: List<Float>,      // 0..1 waveform overview
    val segments: List<Seg>?,    // transcript from the Mac, null until ready
    val lang: String?,
    val sync: String?,           // null | "uploaded" | "failed"
    val marks: List<Double>,     // seconds bookmarked on the watch
    val summary: String?,        // AI summary (on-phone Gemma)
    val actions: List<String>,   // AI action items
    val speakers: Int,           // number of distinct speakers found (0 = unknown)
) {
    val placeOrUnknown: String get() = place ?: "Unknown place"
    val transcriptText: String get() = segments?.joinToString(" ") { it.text } ?: ""
}

data class Seg(val t0: Double, val t1: Double, val text: String, val speaker: Int? = null)
