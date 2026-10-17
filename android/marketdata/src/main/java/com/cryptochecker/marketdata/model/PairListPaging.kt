package com.cryptochecker.marketdata.model

/**
 * Blätterzustand der Paarliste für genau eine Anfrage einer Synchronisierung (erste Seite samt Folgeseiten).
 *
 * [Market]-Instanzen gibt es je Börse nur einmal, gleichzeitige Abfragen teilen sie. Ein Cursor in der Börse selbst
 * würde zwei gleichzeitige Synchronisierungen derselben Börse vermischen — darum holt der Abgleich für jede Anfrage
 * einen frischen Zustand ([Market.pairListPaging]) und fragt nur ihn nach der nächsten Seite.
 * iOS: `PairListPaging.swift`.
 */
fun interface PairListPaging {
    /**
     * Adresse der Seite nach [body] (Antwort auf Anfrage [call]); null = keine weitere Seite. Wirft bei einer
     * unlesbaren Antwort, damit die Anfrage scheitert statt Paare unbemerkt wegzulassen (wie iOS).
     */
    fun nextPage(call: Int, body: String): String?
}

/**
 * Für Börsen, deren nächste Seite nicht allein aus der letzten Antwort folgt (Gemini: Warteschlange der mehrdeutigen
 * Symbole aus der Liste). Sie liefern je Anfrage einen eigenen Zustand.
 */
interface PagedPairList {
    fun newPairListPaging(): PairListPaging
}

/**
 * Frischer Blätterzustand für eine Anfrage der Paarliste: der eigene der Börse ([PagedPairList]), sonst zustandslos
 * über [Market.pairListNextPage] (der Cursor steht in der Antwort, etwa Bybit `nextPageCursor`).
 */
fun Market.pairListPaging(): PairListPaging =
    (this as? PagedPairList)?.newPairListPaging() ?: PairListPaging { call, body -> pairListNextPage(call, body) }
