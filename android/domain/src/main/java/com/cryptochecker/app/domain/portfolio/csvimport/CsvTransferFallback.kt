// CSV-Import: Ersatzzeilen für Verknüpfungen mit gespeicherten Seiten (Teil von [CsvTransferMatch]). Eigene Datei, weil
// `CsvTransferMatch.kt` an der Längengrenze ist; iOS `CsvTransferMatch+Fallback.swift`.
package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Zeilen zum Speichern: [plan] samt den neuen Zeilen, die gegen eine gespeicherte Seite aufgehen
 * ([CsvStoredTransfer.absorbed]), in der Reihenfolge der Vorschau [txs]. Gespeichert wird so eine Zeile nur, wenn ihre
 * Verknüpfung beim Speichern nicht mehr geht ([fallbacks]: gespeicherte Seite inzwischen gelöscht oder schon verknüpft) —
 * sie darf nie verloren gehen. Eine Auszahlung darunter ohne Wahl bleibt beim Speichern eine Auszahlung (vorsichtig, wie
 * der Import ohne Wahl).
 */
fun CsvTransferMatch.withFallbacks(txs: List<CsvImportTx>, plan: CsvTransferPlan): List<CsvImportTx> {
    val absorbed = plan.storedUpdates.mapNotNull { it.absorbed }
    if (absorbed.isEmpty()) return plan.transactions
    val order = txs.withIndex().associate { (i, t) -> t.importKey to i }
    return (plan.transactions + absorbed).sortedBy { order[it.importKey] ?: Int.MAX_VALUE }
}

/** Herkunftsschlüssel der Ersatzzeilen ([withFallbacks]) → Id der gespeicherten Seite, in der sie aufgehen. */
fun CsvTransferMatch.fallbacks(plan: CsvTransferPlan): Map<String, Long> =
    plan.storedUpdates.mapNotNull { u -> u.absorbed?.let { it.importKey to u.id } }.toMap()
