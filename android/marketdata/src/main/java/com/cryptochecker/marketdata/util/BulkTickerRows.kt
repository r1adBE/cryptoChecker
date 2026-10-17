package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.Ticker
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ausbeute einer Sammelabfrage: [tickers] je Paar-Kennung, dazu [failed] — Paare, deren Zeile zwar in der Antwort
 * stand, sich aber nicht lesen liess (Feld fehlt oder heisst anders, kein letzter Kurs). iOS: `BulkTickerParse`.
 */
class BulkTickerParse(val tickers: Map<String, Ticker>, val failed: Set<String>)

/**
 * Regeln für einzelne Zeilen einer Sammelabfrage (reines Kotlin, iOS in `Market.swift`). Eine unlesbare Zeile
 * meldet ihr Paar als Fehler ([ROW_ERROR]), statt den alten Kurs als «unverändert» stehen zu lassen. Ist die
 * Mehrheit unlesbar, hat die Börse wohl ihr Format geändert: Die ganze Antwort gilt als Formatfehler
 * ([FORMAT_ERROR]), und Einzelabfragen mit demselben Leser sind zwecklos.
 */
object BulkTickerRows {
    /** Beginn des Fehlertexts, wenn die Zeilen mehrheitlich unlesbar sind. */
    const val FORMAT_ERROR = "Bulk format error"

    /** Fehlertext für ein Paar mit unlesbarer Zeile in einer sonst lesbaren Antwort. */
    const val ROW_ERROR = "Bulk row unreadable"

    /** Der letzte Kurs ist brauchbar: endlich und grösser als «kein Wert» — dieselbe Hürde wie bei Einzelabfragen. */
    fun usable(ticker: Ticker): Boolean {
        val price = ticker.last
        return price.isFinite() && price > Ticker.MISSING
    }

    /**
     * Das Kursfeld ist da, aber leer (null, "", 0): ein inaktives oder nie gehandeltes Paar. Solche Zeilen
     * werden übersprungen statt als unlesbar gezählt, sonst fiele eine Börse mit vielen davon in den
     * Formatfehler. Fehlt das Feld ganz oder ist der Wert Unsinn, bleibt es ein Fehler.
     */
    fun inactivePrice(json: JSONObject, key: String): Boolean =
        json.has(key) && blankPrice(json.opt(key))

    /** Dasselbe für Zeilen als Liste; ist die Zeile zu kurz, ist sie unlesbar, nicht leer. */
    fun inactivePrice(array: JSONArray, index: Int): Boolean =
        index < array.length() && blankPrice(array.opt(index))

    private fun blankPrice(raw: Any?): Boolean = when (raw) {
        null, JSONObject.NULL -> true
        is String -> raw.isBlank() || raw.trim().toDoubleOrNull() == 0.0
        is Number -> raw.toDouble() == 0.0
        else -> false
    }

    /** Formatfehler, sobald es unlesbare Zeilen gibt und sie die lesbaren übertreffen. */
    fun isFormatFailure(parsed: Int, failed: Int): Boolean = failed > 0 && failed > parsed

    fun formatErrorText(parsed: Int, failed: Int): String =
        "$FORMAT_ERROR: $failed of ${parsed + failed} rows unreadable"

    /** Ist [error] der Fehlertext einer Sammelabfrage mit Formatfehler? */
    fun isFormatError(error: String?): Boolean = error != null && FORMAT_ERROR in error
}
