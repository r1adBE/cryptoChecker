package com.cryptochecker.marketdata.util

/**
 * Wie weit eine Sammelabfrage aus mehreren Teilen als «vollständig» gilt. Teilanfragen und Paare gehören je zu
 * einer Gruppe (`Exchange.bulkTickersRequestGroup` / `bulkTickersPairGroup`; Binance: Spot 0, Alpha 1). Scheitert
 * ein Teil, verliert nur seine Gruppe die Aussage «nicht mehr gehandelt» — scheitert Alpha, werden gestrichene
 * Spot-Paare trotzdem erkannt (BulkDelisted). Dazu die unbekannten Paare einer abgelehnten gefilterten Anfrage
 * (Bithumb). Reines Kotlin; iOS: `BulkCompleteness` in `Exchange.swift`.
 */
object BulkCompleteness {

    /** Die angefragten Paare, deren Gruppe gescheitert oder ausgelassen ist. */
    fun incomplete(pairIds: Collection<String>, failedGroups: Set<Int>, groupOf: (String) -> Int): Set<String> {
        if (failedGroups.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (id in pairIds) if (groupOf(id) in failedGroups) result.add(id)
        return result
    }

    /**
     * Gibt die Liste über wenigstens ein angefragtes Paar Auskunft? Ohne gescheiterte Gruppe immer; sonst nur,
     * wenn nicht alle (verschiedenen) Paare unvollständig sind. Ohne Paare: nein.
     */
    fun anyComplete(pairIds: Collection<String>, failedGroups: Set<Int>, incomplete: Set<String>): Boolean {
        if (failedGroups.isEmpty()) return true
        return pairIds.toSet().size > incomplete.size
    }

    /**
     * Angefragte Paare, die in der Paarliste der Börse ([known]) fehlen. null, wenn keines fehlt oder [known]
     * leer ist — eine leere Liste sagt nichts aus, es bleibt beim ursprünglichen Fehler.
     */
    fun unknownPairs(pairIds: Collection<String>, known: Set<String>): Set<String>? {
        if (known.isEmpty()) return null
        val missing = pairIds.filterNotTo(LinkedHashSet()) { it in known }
        return missing.ifEmpty { null }
    }
}
