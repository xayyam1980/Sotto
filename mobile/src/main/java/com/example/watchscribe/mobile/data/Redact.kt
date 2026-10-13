package com.example.watchscribe.mobile.data

import android.content.Context

/**
 * Hides personal details in what leaves the phone (exports, shares, "Open in Claude/Gemini"):
 * e-mail addresses, IBANs and long numbers such as phone, card or ID numbers.
 * Dates, times and ordinary amounts are left alone. Notes on the phone are never changed.
 */
object Redact {
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?\b""")
    // 9+ digits, optionally with spaces, dashes, dots, brackets: phone, card, PESEL… (not dates/times)
    private val NUMBER = Regex("""(?<![\w])(?:\+|00|\()?\d(?:[ \-.()/]{0,2}\d){8,}(?![\w:])""")

    fun text(s: String): String = NUMBER.replace(IBAN.replace(EMAIL.replace(s, "[email]"), "[IBAN]"), "[number]")

    fun recording(r: Recording): Recording = r.copy(
        title = text(r.title),
        summary = r.summary?.let(::text),
        actions = r.actions.map(::text),
        segments = r.segments?.map { it.copy(text = text(it.text)) },
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("export", Context.MODE_PRIVATE)
    /** The user's choice in the Export sheet, also used for Share and "Open in Claude/Gemini". */
    fun on(ctx: Context) = prefs(ctx).getBoolean("hidePersonal", false)
    fun setOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("hidePersonal", v).apply()
}
