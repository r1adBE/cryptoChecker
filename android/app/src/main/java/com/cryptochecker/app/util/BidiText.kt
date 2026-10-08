package com.cryptochecker.app.util

import java.util.Locale

/**
 * Text in Rechts-nach-links-Sprachen (Arabisch, Hebräisch, Persisch …) richtig ordnen.
 * Reines Kotlin, als Unit-Test prüfbar (Spiegel: Shared/Util/BidiText.swift).
 *
 * - [ltr]: Zahl mit Vorzeichen («+1.20%», «−12.00 USD») als links-nach-rechts-Insel
 *   (Unicode LRI … PDI). Ohne Insel stellt der Bidi-Algorithmus in einem RTL-Absatz das
 *   Vorzeichen hinter die Zahl («1.20%+») — allein (Pille) wie mitten im Satz.
 * - [isolate]: Name aus fremder Schrift («Binance», «BTC/USDT») als Insel mit eigener
 *   Richtung (FSI … PDI), damit «Binance · vor 5 Min.» in RTL in Lesereihenfolge steht.
 *
 * Nur bei einer RTL-Sprache; sonst bleibt der Text unverändert (keine unsichtbaren Zeichen
 * in Tests, Exporten und LTR-Oberflächen).
 */
object BidiText {

    const val LRI = '\u2066'
    const val FSI = '\u2068'
    const val PDI = '\u2069'

    /** Sprachen, die von rechts nach links geschrieben werden (auch alte Android-Codes iw, ji). */
    private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ps", "yi", "ji", "ckb", "sd", "ug", "dv")

    fun isRtl(locale: Locale = Locale.getDefault()): Boolean = locale.language in RTL_LANGUAGES

    /** Zahl/Betrag immer links nach rechts; nur bei RTL-Sprache. */
    fun ltr(text: String, locale: Locale = Locale.getDefault()): String =
        if (text.isEmpty() || !isRtl(locale)) text else "$LRI$text$PDI"

    /** Eingebetteter Name mit eigener Richtung; nur bei RTL-Sprache. */
    fun isolate(text: String, locale: Locale = Locale.getDefault()): String =
        if (text.isEmpty() || !isRtl(locale)) text else "$FSI$text$PDI"

    /** Unsichtbares Richtungszeichen (LRM, RLM, ALM, Einbettung, Insel)? */
    fun isMark(c: Char): Boolean = c in MARKS

    /** Richtungszeichen entfernen — z. B. aus eingefügtem Text. */
    fun strip(text: String): String = text.filterNot { it in MARKS }

    private val MARKS = setOf(
        '\u200E', '\u200F', '\u061C',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
