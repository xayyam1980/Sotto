package com.example.watchscribe.mobile.ai

import android.content.Context

/** User choices for on-phone AI. */
object AiSettings {
    private const val PREFS = "ai_settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whisper may only answer in these; the first is used when it guesses something else. */
    val ALL_LANGUAGES = linkedMapOf(
        "ru" to "Russian", "en" to "English", "pl" to "Polish", "uk" to "Ukrainian",
        "de" to "German", "fr" to "French", "az" to "Azerbaijani", "tr" to "Turkish",
    )

    fun languages(ctx: Context): List<String> =
        p(ctx).getString("langs", "ru,en,pl")!!.split(',').filter { it.isNotBlank() }

    fun setLanguages(ctx: Context, langs: List<String>) =
        p(ctx).edit().putString("langs", langs.joinToString(",")).apply()

    /** Languages Parakeet v3 understands (of the ones Sotto offers). */
    val PARAKEET_LANGUAGES = setOf("en", "pl", "ru", "uk", "de", "fr")

    /** Speech model: the one chosen in Settings, else Parakeet when it covers all the user's languages. */
    fun asrModel(ctx: Context): ModelId =
        p(ctx).getString("asr", null)?.let { runCatching { ModelId.valueOf(it) }.getOrNull() }
            ?: if (languages(ctx).all { it in PARAKEET_LANGUAGES }) ModelId.PARAKEET else ModelId.WHISPER

    fun setAsrModel(ctx: Context, m: ModelId) = p(ctx).edit().putString("asr", m.name).apply()

    fun summaryModel(ctx: Context): ModelId =
        runCatching { ModelId.valueOf(p(ctx).getString("llm", ModelId.GEMMA_E2B.name)!!) }.getOrDefault(ModelId.GEMMA_E2B)

    fun setSummaryModel(ctx: Context, m: ModelId) = p(ctx).edit().putString("llm", m.name).apply()

    fun onlyWhileCharging(ctx: Context) = p(ctx).getBoolean("charging", true)
    fun setOnlyWhileCharging(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("charging", v).apply()

    /** "English" or "same" (the language of the recording). */
    fun summaryLanguage(ctx: Context): String = p(ctx).getString("sumlang", "English")!!
    fun setSummaryLanguage(ctx: Context, v: String) = p(ctx).edit().putString("sumlang", v).apply()
}
