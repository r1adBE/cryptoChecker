package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Kandidaten-Paare des Umbuchungs-Abgleichs ([CsvTransferMatch.candidates]) ohne «jede Auszahlung mit jeder
 * Einzahlung»: Einzahlungen je Coin nach Zeit sortiert, das Zeitfenster per Binärsuche — O((A + E) · log E) plus die
 * Einzahlungen im Fenster statt A × E. Wie `CsvTransferWindow` (iOS).
 */
internal object CsvTransferWindow {

    /**
     * Alle Paare (Index in [outs], Index in [ins]) mit [fits], in derselben Reihenfolge wie die doppelte Schleife über
     * [outs] und [ins] (Auszahlung, dann Einzahlung nach Index). [fits] muss für einen anderen Coin ([coin]) und eine
     * Einzahlung ausserhalb von [early] vor bis [window] nach der Auszahlung ([time]) falsch sein.
     */
    fun <T> pairs(
        outs: List<T>,
        ins: List<T>,
        coin: (T) -> String,
        time: (T) -> Long,
        early: Long,
        window: Long,
        fits: (T, T) -> Boolean,
    ): List<Pair<Int, Int>> {
        // Je Coin die Einzahlungen nach Zeit (stabil: bei gleicher Zeit nach Index)
        val byCoin = ins.indices.groupBy { coin(ins[it]) }.mapValues { (_, list) -> list.sortedBy { time(ins[it]) } }
        val out = ArrayList<Pair<Int, Int>>()
        val hits = ArrayList<Int>()
        for ((wi, w) in outs.withIndex()) {
            val list = byCoin[coin(w)] ?: continue
            val from = time(w) - early
            val to = time(w) + window
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (time(ins[list[mid]]) < from) lo = mid + 1 else hi = mid
            }
            hits.clear()
            while (lo < list.size && time(ins[list[lo]]) <= to) {
                if (fits(w, ins[list[lo]])) hits += list[lo]
                lo++
            }
            // Reihenfolge der doppelten Schleife: Einzahlungen nach Index
            hits.sort()
            hits.forEach { out += wi to it }
        }
        return out
    }
}
