package com.example.watchscribe.mobile.ai

/** Quick checks on transcribed text: which language it is, and signs the model went wrong. */
object TextChecks {
    private val SPACES = Regex("\\s+")

    /** Number of words in [text]. */
    fun wordCount(text: String) = text.split(SPACES).count { it.isNotBlank() }

    /** True when a line mixes Latin and Cyrillic words, a sign the model was unsure of the language. */
    fun mixedScripts(text: String): Boolean {
        val words = text.split(SPACES)
        val latin = words.count { w -> w.any { it in 'a'..'z' || it in 'A'..'Z' } }
        val cyr = words.count { w -> w.any { it in 'Ѐ'..'ӿ' } }
        return latin >= 2 && cyr >= 2
    }

    /** Names the language of a transcribed line from its letters, among the user's languages. */
    fun guessLanguage(text: String, allowed: List<String>): String {
        val t = text.lowercase()
        fun ok(l: String) = l in allowed
        return when {
            t.any { it in "іїєґ" } && ok("uk") -> "uk"
            t.any { it in 'а'..'я' || it == 'ё' } -> if (ok("ru")) "ru" else if (ok("uk")) "uk" else allowed.firstOrNull() ?: "ru"
            t.any { it in "ąćęłńśźż" } && ok("pl") -> "pl"
            t.any { it in "äöüß" } && ok("de") -> "de"
            t.any { it in "àâçèéêëîïôœùûÿ" } && ok("fr") -> "fr"
            ok("en") -> "en"
            else -> allowed.firstOrNull() ?: "en"
        }
    }

    /** True when the model got stuck repeating itself ("что я не знаю, что я не знаю…"). */
    fun looksLikeLoop(text: String): Boolean {
        val w = text.split(SPACES).filter { it.isNotBlank() }
        return w.size >= 12 && w.toSet().size.toDouble() / w.size < 0.35
    }
}
