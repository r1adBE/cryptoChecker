import Foundation

// Übersicht von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Markt-Chip, Börse öffnen und schliessen, «Beliebt».
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

    /// «Beliebt» neu bestimmen: Coins der Start-Börse (`ExplorerPopularPairs`) als …/USDT-Paare. Liegt ihre Paarliste
    /// schon auf dem Gerät, nur Coins mit Spot-Paar in USDT (mit der Kennung aus der Liste); sonst wie die
    /// Start-Merkliste. Nur gespeicherte Daten, kein Abruf. Ohne Start-Börse in der Auswahl oder bei «Nur TradFi»: leer.
    /// Wie Android `refreshPopular`.
    func refreshPopular() {
        let region = StarterPairs.region()
        let startKey = StarterPairs.exchangeKey(region)
        guard appliesAssetScope, assetScope.allowsTokens,
              let start = exchanges.first(where: { $0.key == startKey }) else {
            popularPairs = []
            return
        }
        Task { @MainActor [weak self] in
            let stored = await PairCache.shared.pairs(for: startKey)
            guard let self else { return }
            let quote = StarterPairs.startQuoteValue
            let list = stored.pairs.isEmpty ? nil : stored
            let available = list.map { info in
                Set(info.pairs.filter { $0.quote.uppercased() == quote && $0.contractType == .none }.map { $0.base.uppercased() })
            }
            let symbols = ExplorerPopularPairs.pick(
                starter: (StarterCoins.cached(exchangeKey: startKey) ?? []).map(\.symbol),
                fallback: StarterCoins.fallback(exchangeKey: startKey).map(\.symbol),
                available: available
            )
            self.popularPairs = symbols.map { base in
                let pair = list?.pair(base: base, quote: quote, contractType: .none)
                    ?? StarterPairs.pair(for: base, region: region).pair
                return ExplorerSearchHit(exchange: start, pair: pair, alpha: false)
            }
        }
    }
}
