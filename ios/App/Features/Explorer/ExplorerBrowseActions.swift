import Foundation

// Übersicht von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Markt-Chip, Börse öffnen und schliessen.
// Eigene Datei wie `ExplorerBrowseActions.kt`; der Zustand bleibt in der Klasse.

extension ExplorerViewModel {
    /// Markt-Chip wählen: Börsenliste und Treffer der Suche zeigen nur noch diese Art.
    func setMarketFilter(_ filter: ExplorerMarketFilter) {
        guard filter != marketFilter else { return }
        marketFilter = filter
        runSearch()
    }

    /// Börse aus der Übersicht oder der Suche öffnen: ihre Paare (Auswahl wie bisher) bzw. bei DexScreener die
    /// DEX-Suche. Merkt sie unter «Zuletzt genutzte Börsen» (nur lokal); ein Suchbegriff wird geleert.
    func openExchange(_ target: Exchange) {
        rememberExchange(target.key)
        if !searchQuery.isEmpty { setSearchQuery("") }
        selectExchange(target)
    }

    /// Zurück von den Paaren einer Börse zur Übersicht: Auswahl, Liste, Kurs und DEX-Treffer der Börse verwerfen.
    func closeExchange() {
        guard exchange != nil else { return }
        exchangeLoadTask?.cancel()
        clearSelectionResults()
        exchange = nil
        canReloadPairs = false
    }

    /// Unter «Zuletzt genutzte Börsen» ablegen (neueste zuerst).
    func rememberExchange(_ key: String) {
        let next = ExplorerRecentExchanges.add(recentExchanges, key)
        guard next != recentExchanges else { return }
        recentExchanges = next
        ExplorerRecentExchanges.save(next)
    }
}
