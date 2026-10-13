package com.example.watchscribe.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.watchscribe.mobile.data.Recording
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class GroupBy(val label: String) { DAY("Day"), PLACE("Place"), NONE("None") }
enum class SortBy(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first") }

sealed interface Screen {
    data object Library : Screen
    data object Places : Screen
    data object Ask : Screen
    data object Watch : Screen
    data class Detail(val id: String) : Screen
}

/** View options shared by the library screens (kept while the app runs). */
class ViewOptions {
    var groupBy by mutableStateOf(GroupBy.DAY)
    var sortBy by mutableStateOf(SortBy.NEWEST)
    var starredOnly by mutableStateOf(false)
    var longOnly by mutableStateOf(false)      // > 5 min
    var hideShort by mutableStateOf(false)     // < 30 s (off: short notes show too)
    var lastDays by mutableStateOf<Int?>(null) // null = all time
    var place by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    fun reset() {
        groupBy = GroupBy.DAY; sortBy = SortBy.NEWEST; starredOnly = false
        longOnly = false; hideShort = false; lastDays = null; place = null
    }

    val summary: String get() = "${sortBy.label.substringBefore(' ')} · By ${groupBy.label.lowercase()}"

    fun apply(all: List<Recording>): List<Recording> {
        val q = query.trim().lowercase()
        val since = lastDays?.let { System.currentTimeMillis() - it * 86_400_000L }
        val list = all.filter { r ->
            (!starredOnly || r.starred) &&
                (!longOnly || r.seconds > 300) &&
                (!hideShort || r.seconds >= 30) &&
                (since == null || r.start >= since) &&
                (place == null || r.placeOrUnknown == place) &&
                (q.isEmpty() || r.title.lowercase().contains(q) || r.placeOrUnknown.lowercase().contains(q) ||
                    r.transcriptText.lowercase().contains(q))
        }
        return when (sortBy) {
            SortBy.NEWEST -> list.sortedByDescending { it.start }
            SortBy.OLDEST -> list.sortedBy { it.start }
            SortBy.LONGEST -> list.sortedByDescending { it.seconds }
        }
    }

    fun group(list: List<Recording>): List<Pair<String, List<Recording>>> = when (groupBy) {
        GroupBy.NONE -> listOf("All notes" to list)
        GroupBy.PLACE -> list.groupBy { it.placeOrUnknown }.toList()
        GroupBy.DAY -> list.groupBy { dayLabel(it.start) }.toList()
    }
}

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

fun dayLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val d = dayFmt.format(t)
    return when {
        same(c, today) -> "Today · $d"
        same(c, yesterday) -> "Yesterday · $d"
        else -> d
    }
}

fun totalLabel(list: List<Recording>): String {
    val sec = list.sumOf { it.seconds }.toInt()
    val time = if (sec >= 3600) "${sec / 3600} h ${sec / 60 % 60} min" else "${(sec + 59) / 60} min"
    return "${list.size} ${if (list.size == 1) "note" else "notes"} · $time"
}
