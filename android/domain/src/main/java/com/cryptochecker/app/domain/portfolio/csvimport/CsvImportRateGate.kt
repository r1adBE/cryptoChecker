package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.util.TextLimit

/** Was einer importierten Zeile dauerhaft fehlt, weil ihr Tageskurs fehlt ([CsvImportTx.missingRate]). */
enum class CsvMissingRate { PRICE, FEE, PRICE_AND_FEE }

/**
 * Ausdrückliche Bestätigung «Trotzdem importieren» der Vorschau ([accepted]) samt den Vermerken in der Sprache der App
 * («Preis ohne Tageskurs», «Gebühr ohne Tageskurs»), die die betroffenen Transaktionen beim Speichern in die Notiz bekommen.
 */
data class CsvRateConsent(val accepted: Boolean = false, val priceNote: String = "", val feeNote: String = "")

/**
 * Kein stiller, dauerhafter Verlust beim CSV-Import (reines Kotlin; iOS `CsvImportRateGate.swift`, gemeinsame Fälle
 * `csv_import.json` › «rateGate»): Fehlt einer Zeile der Tageskurs, fehlt der Transaktion danach der Preis bzw. die
 * Gebühr — ein zweiter Import holt nichts nach (Schlüssel «bereits importiert»). Darum ist «Importieren» gesperrt,
 * solange solche Zeilen in der Vorschau stehen ([canImport]): erst «Erneut laden» mit Kurs oder die ausdrückliche
 * Bestätigung; dann trägt jede betroffene Transaktion einen bleibenden Vermerk in der Notiz ([mark]).
 */
object CsvImportRateGate {

    /** Höchstlänge der Notiz (wie [com.cryptochecker.app.domain.model.NOTE_MAX]). */
    private const val NOTE_MAX = 120

    /** Zwischen Vermerken und der Notiz aus der Datei. */
    const val SEPARATOR = " · "

    /** «Importieren» erlaubt? Mit Zeilen ohne Tageskurs ([missingFx] = Anzahl) nur nach ausdrücklicher Bestätigung. */
    fun canImport(missingFx: Int, accepted: Boolean): Boolean = missingFx <= 0 || accepted

    /**
     * Was [p] ohne Tageskurs fehlt; null = nichts. Gebühr-Zeilen und Staking-Umbuchungen führen keinen Preis
     * ([CsvImportMapping.toTx]): bei ihnen zählt nur die Gebühr.
     */
    fun missing(p: CsvImportPriced): CsvMissingRate? {
        val price = p.priceNeedsFx && p.row.kind != CsvImportKind.FEE && p.row.kind != CsvImportKind.TRANSFER
        return when {
            price && p.feeNeedsFx -> CsvMissingRate.PRICE_AND_FEE
            price -> CsvMissingRate.PRICE
            p.feeNeedsFx -> CsvMissingRate.FEE
            else -> null
        }
    }

    /**
     * Notiz mit Vermerk: zuerst die Vermerke ([consent]), dann die bisherige [note] (Art aus der Datei), getrennt mit
     * [SEPARATOR], auf [NOTE_MAX] Zeichen gekürzt — der Vermerk steht vorn und bleibt so immer lesbar. [missing] null =
     * [note] unverändert.
     */
    fun note(note: String?, missing: CsvMissingRate?, consent: CsvRateConsent): String? {
        val marks = when (missing) {
            null -> return note
            CsvMissingRate.PRICE -> listOf(consent.priceNote)
            CsvMissingRate.FEE -> listOf(consent.feeNote)
            CsvMissingRate.PRICE_AND_FEE -> listOf(consent.priceNote, consent.feeNote)
        }
        val parts = (marks + listOfNotNull(note)).map { it.trim() }.filter { it.isNotEmpty() }
        return TextLimit.takeGraphemes(parts.joinToString(SEPARATOR), NOTE_MAX).trim().ifEmpty { null }
    }

    /** Vermerk an jeder betroffenen Transaktion ([CsvImportTx.missingRate]); die übrigen unverändert. */
    fun mark(txs: List<CsvImportTx>, consent: CsvRateConsent): List<CsvImportTx> =
        txs.map { tx -> if (tx.missingRate == null) tx else tx.copy(note = note(tx.note, tx.missingRate, consent)) }
}
