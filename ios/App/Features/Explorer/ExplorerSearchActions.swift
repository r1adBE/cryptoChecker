import Foundation
import Accessibility
import SwiftUI
import UIKit

// Coin-Suche von «Paar hinzufügen» (Teil von `ExplorerViewModel`): Suchbegriff, Treffer über alle
// gespeicherten Paarlisten, Coin wählen, fehlende Listen laden, Namensverzeichnis.
// Eigene Datei, weil die Suche über alle Börsen eine eigene Aufgabe neben der Auswahl nach Börse ist
// (wie `ExplorerSearchActions.kt`).

extension ExplorerViewModel {
    func addSearchHit(_ hit: ExplorerSearchHit) {
        // Begriff, mit dem ein Paar aufgenommen wurde, landet unter «Zuletzt gesucht»
        rememberSearch(searchQuery)
        add(exchange: hit.exchange, pair: hit.pair)
    }

    // MARK: Coin-Suche

    func setSearchQuery(_ query: String) {
        if query != searchQuery { pickedCoin = nil }
        searchQuery = query
        if !Self.isBlank(query) {
            readStoredLists()
            ensureNameIndex()
        }
        runSearch()
    }

    /// Taste «Suchen»: Begriff unter «Zuletzt gesucht» ablegen.
    func confirmSearch() {
        rememberSearch(searchQuery)
    }

    func clearRecentSearches() {
        recentSearches = []
        ExplorerRecentSearches.save([])
    }

    /// Coin antippen: seine Paare über alle geladenen Listen zeigen (Vorschlag zuerst); nil = zurück zu den Treffern.
    func pickCoin(_ hit: ExplorerCoinHit?) {
        pickedCoin = hit
        if hit == nil { pickedCoinPairs = [] }
        runSearch()
    }

    /// Fehlende Paarlisten laden — nur auf ausdrücklichen Wunsch: je Börse die vollständige öffentliche
    /// Liste (für alle gleich), vier Börsen zugleich. Treffer erscheinen nach und nach.
    func loadMissingLists() {
        guard missingListsTask == nil, !unloadedExchanges.isEmpty else { return }
        pendingListExchanges = unloadedExchanges
        listsLoadedCount = 0
        failedListKeys = []
        let targets = unloadedExchanges
        let total = pendingListExchanges.count
        searchLists = ExplorerSearchLists(missing: total, progress: ExplorerSearchProgress(done: 0, total: total))
        missingListsTask = Task { @MainActor [weak self] in
            var workers: [Task<Void, Never>] = []
            for _ in 0..<4 {
                workers.append(Task { @MainActor [weak self] in
                    while let next = self?.nextPendingListExchange() {
                        let (key, info) = await ExplorerViewModel.syncForSearch(next)
                        self?.listLoaded(key: key, info: info, total: total)
                    }
                })
            }
            for worker in workers { await worker.value }
            guard let self else { return }
            self.missingListsTask = nil
            self.updateUnloaded()
            // Börsen ohne Antwort (Wartung, Zeitüberschreitung, kein Netz) mit Namen melden statt wortlos
            // dieselbe Karte zu zeigen
            let stillMissing = Set(self.unloadedExchanges.map(\.key))
            self.searchLists.failed = targets
                .filter { self.failedListKeys.contains($0.key) && stillMissing.contains($0.key) }
                .map(\.name)
            self.runSearch()
        }
    }

    private func rememberSearch(_ query: String) {
        let next = ExplorerRecentSearches.add(recentSearches, query)
        if next == recentSearches { return }
        recentSearches = next
        ExplorerRecentSearches.save(next)
    }

    private static func isBlank(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Namensverzeichnis einmalig im Hintergrund bauen, danach neu suchen (Treffer über Namen).
    private func ensureNameIndex() {
        if nameIndexTask != nil { return }
        nameIndexTask = Task { @MainActor [weak self] in
            let built = await Task.detached(priority: .userInitiated) {
                ExplorerNameIndex(names: CoinLogoStore.names, stockNames: CoinLogoStore.stockNames,
                                  tradFiBases: CoinLogoStore.tradFiBases)
            }.value
            guard let self else { return }
            self.nameIndex = built
            self.runSearch()
        }
    }

    /// Coins, Paar-Treffer und — bei angetipptem Coin — seine Paare neu berechnen (entprellt).
    private func runSearch() {
        searchTask?.cancel()
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        if query.isEmpty {
            coinHits = []
            pairHits = []
            pickedCoinPairs = []
            return
        }
        // Alles, was die Suche braucht, jetzt festhalten (läuft danach abseits des Hauptthreads)
        let scope = assetScope
        let hidden = hiddenAlpha
        let candidates = searchExchanges.filter { scope.allowsExchange($0.key) }
        let lists = searchCache
        let names = nameIndex
        let picked = pickedCoin
        let includeUnlisted = !unloadedExchanges.isEmpty
        let preference = pairPreference(candidates)
        // «α»-Kennzeichen je Treffer und Namen gegen falsche Alpha-Zuordnung
        let alphaSymbols = CoinLogoStore.alphaSymbols
        let alphaNames = CoinLogoStore.alphaNames
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 150_000_000)
            guard !Task.isCancelled else { return }
            let found = await Task.detached(priority: .userInitiated) {
                var namesByExchange: [String: [String: String]] = [:]
                for m in candidates { namesByExchange[m.key] = TokenIdentityStore.shared.exchangeNames(m.key) }
                let coins = ExplorerCoinSearch.coins(query, exchanges: candidates, cache: lists, names: names, scope: scope,
                                                     hiddenAlpha: hidden, alphaSymbols: alphaSymbols,
                                                     alphaNames: alphaNames, pairNames: namesByExchange,
                                                     includeUnlisted: includeUnlisted)
                let showPairs = ExplorerCoinSearch.showPairHits(query, coinsFound: !coins.isEmpty)
                let pairs = showPairs
                    ? ExplorerPairSearch.find(query, exchanges: candidates, cache: lists, scope: scope, names: names,
                                              hiddenAlpha: hidden, alphaSymbols: alphaSymbols,
                                              alphaNames: alphaNames, pairNames: namesByExchange)
                    : []
                let coinPairs = picked.map {
                    ExplorerCoinSearch.pairs(for: $0, exchanges: candidates, cache: lists, preference: preference,
                                             scope: scope, hiddenAlpha: hidden, alphaSymbols: alphaSymbols,
                                             alphaNames: alphaNames, pairNames: namesByExchange)
                } ?? []
                return (coins, showPairs, pairs, coinPairs)
            }.value
            guard !Task.isCancelled, let self else { return }
            self.coinHits = found.0
            self.showPairHits = found.1
            self.pairHits = found.2
            self.pickedCoinPairs = found.3
        }
    }

    /// Vorschlag der Coin-Suche: Start-Börse der Region (bei Standort-Sperre Binance.US, wie die
    /// Start-Merkliste), Lieblingsbörsen (gemerkt als Anzeigename) und die Umrechnungswährung.
    private func pairPreference(_ exchanges: [Exchange]) -> ExplorerCoinPairPreference {
        let favoriteNames = data.favorites[.EXCHANGE] ?? []
        return ExplorerCoinPairPreference(
            defaultExchange: StarterCoins.exchangeKey(),
            favoriteExchanges: Set(exchanges.filter { favoriteNames.contains($0.name) }.map(\.key)),
            displayCurrency: data.settings.portfolioCurrency,
            exchangeRank: ExchangeOrder.ranks(ExchangeRankingStore.volumes())
        )
    }

    /// Gespeicherte Paarlisten aller Börsen in den Speicher der Suche — nur vom Gerät, kein Abruf. Einmal je
    /// Sitzung (später geladene Listen kommen über `loadMissingLists` bzw. den Abgleich der gewählten Börse).
    private func readStoredLists() {
        if storedListsTask != nil { return }
        let exchanges = allExchanges.filter { $0.key != Self.dexKey }
        // Gleich «suche…» zeigen statt kurz «keine Treffer»
        searchLists = ExplorerSearchLists(reading: true)
        storedListsTask = Task { @MainActor [weak self] in
            var stored: [String: ExchangePairsInfo] = [:]
            for exchange in exchanges {
                // Binance inkl. Alpha – «inkl. Alpha-Tokens» filtert erst beim Suchen (`AssetScope.allows`)
                let info = await PairCache.shared.selectionPairs(for: exchange.key)
                if !info.pairs.isEmpty { stored[exchange.key] = info }
            }
            guard let self else { return }
            // Inzwischen frisch abgeglichene Listen nicht mit der gespeicherten Fassung überschreiben
            self.searchCache.merge(stored) { current, _ in current }
            self.searchExchanges = exchanges
            self.updateUnloaded()
            self.runSearch()
        }
    }

    /// Welche Börsen noch keine Liste haben, aber eine laden können.
    func updateUnloaded() {
        unloadedExchanges = searchExchanges.filter { searchCache[$0.key] == nil && ExchangeService.supportsPairSync($0) }
        searchLists = ExplorerSearchLists(missing: unloadedExchanges.count)
    }

    private func nextPendingListExchange() -> Exchange? {
        guard !pendingListExchanges.isEmpty else { return nil }
        return pendingListExchanges.removeFirst()
    }

    private func listLoaded(key: String, info: ExchangePairsInfo?, total: Int) {
        if let info { searchCache[key] = info } else { failedListKeys.insert(key) }
        listsLoadedCount += 1
        searchLists.progress = ExplorerSearchProgress(done: listsLoadedCount, total: total)
        runSearch()
    }

    /// Vollständige öffentliche Liste der Börse holen (Binance inkl. gespeicherter Alpha-Liste);
    /// nil = Fehler oder leer.
    nonisolated private static func syncForSearch(_ exchange: Exchange) async -> (String, ExchangePairsInfo?) {
        let key = exchange.key
        guard (try? await PairCache.shared.sync(key)) != nil else { return (key, nil) }
        let fresh = await PairCache.shared.selectionPairs(for: key)
        return (key, fresh.pairs.isEmpty ? nil : fresh)
    }
}
