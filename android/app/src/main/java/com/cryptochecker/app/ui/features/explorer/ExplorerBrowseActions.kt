// Übersicht von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Markt-Chip, Börse öffnen und schliessen.
// Eigene Datei wie `ExplorerBrowseActions.swift`; der Zustand bleibt in der Klasse ([ExplorerSearchHits]).
package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.ExchangeInfo

/** Markt-Chip wählen: Börsenliste und Treffer der Suche zeigen nur noch diese Art. */
fun ExplorerViewModel.setMarketFilter(filter: MarketFilter) {
    if (hits.market.value == filter) return
    hits.market.value = filter
    runSearch()
}

/**
 * Börse aus der Übersicht oder der Suche öffnen: ihre Paare (Auswahl wie bisher) bzw. bei DexScreener die DEX-Suche.
 * Merkt sie unter «Zuletzt genutzte Börsen» (nur auf dem Gerät); ein Suchbegriff wird geleert.
 */
fun ExplorerViewModel.openExchange(target: ExchangeInfo) {
    recentSearchStore.addExchange(target.key)
    if (query.value.isNotEmpty()) setSearchQuery("")
    selectExchange(target)
}

/** Zurück von den Paaren einer Börse zur Übersicht. */
fun ExplorerViewModel.closeExchange() = selectExchange(null)
