import Foundation
import Accessibility
import SwiftUI
import UIKit

// «Mehrere Paare auf einmal» (Teil von `ExplorerViewModel`): Gegenwährung, Liste sammeln, Kennzahlen für
// die Schnellfilter, Paare an-/abwählen und gemeinsam hinzufügen.
// Eigene Datei, weil die Sammelaufnahme ein eigener Modus ist (wie `ExplorerBulkActions.kt`).

extension ExplorerViewModel {
    func selectBulkPair(_ pair: MarketPair) {
        coin = pair.base
        quote = pair.quote
        contract = pair.contractType
        reconcile()
    }

    // MARK: Sammelaufnahme

    func setBulkQuote(_ quote: String) {
        // Eine gesammelte Liste passt nur zur Gegenwährung, mit der sie entstand
        if quote != bulkQuote { dropBulkList() }
        bulkQuote = quote
    }

    func dropBulkList() {
        bulkPairs = []
        bulkEmpty = false
    }

    /// Alle Paare der gewählten Gegenwährung (für den aktuellen Kontrakttyp) zusammenstellen.
    func collectBulkPairs() {
        guard let info = pairList, let counter = bulkQuote else {
            bulkPairs = []
            bulkEmpty = true
            return
        }
        let collected = info.pairs(withQuote: counter, contractType: contract)
        bulkPairs = collected
        bulkEmpty = collected.isEmpty
        guard let lead = collected.first else { return }
        quote = lead.quote
        coin = lead.base
        reconcile()
    }

    /// Schnellfilter-Werte der Liste «Alle …-Paare» holen – nur über die Sammelabfrage der Börse
    /// (dieselbe wie beim Aktualisieren der Merkliste), nie Paar für Paar. Für dieselbe Liste
    /// höchstens einmal je Minute. Gegenstück zu `loadBulkStats` (Android).
    func loadBulkStats() {
        guard let market = exchange, market.key != Self.dexKey, market.bulkTickersNumOfRequests > 0 else { return }
        var unique = Set<String>()
        var ids: [String] = []
        for case let id? in bulkPairs.map(\.pairId) where unique.insert(id).inserted { ids.append(id) }
        if ids.isEmpty { return }
        let key = "\(market.key)|\(ids.sorted().joined(separator: ","))"
        let now = Date()
        if key == bulkStatsKey, now.timeIntervalSince(bulkStatsAt) < 60 { return }
        bulkStatsKey = key
        bulkStatsAt = now
        bulkStatsTask?.cancel()
        let marketKey = market.key
        bulkStatsTask = Task { [weak self] in
            let answer = await MarketService.fetchBulkTickers(market: market, pairIds: ids)
            guard let self, !Task.isCancelled, self.isStill(marketKey) else { return }
            self.bulkStats = answer.tickers.mapValues { ExplorerQuickFilters.stats(of: $0) }
        }
    }

    func addBulkPairs() {
        guard let market = exchange, !bulkPairs.isEmpty else {
            show(ExplorerMessage(text: L("explorer_no_pair_selected"), showView: false))
            return
        }
        let pairs = bulkPairs
        // EINE Rückfrage F-08 für alle Paare, vorher; «Abbrechen» = nichts angelegt, keine Meldung
        data.withWatchlistConfirm { [weak self] in self?.addBulkPairsNow(market, pairs) }
    }

    private func addBulkPairsNow(_ market: Market, _ bulkPairs: [MarketPair]) {
        let outcome = data.addWatches(market: market, pairs: bulkPairs, group: data.addTargetGroup)
        // Merkliste gesperrt: keine Zählung «0 hinzugefügt», Hinweis und Haptik kommen von `.watchlistLockedHint`
        if outcome.locked { return }
        let anyAdded = outcome.added > 0
        var text = L("explorer_bulk_added", count: outcome.added, outcome.added, outcome.skipped)
        // Merkliste voll: nur bis zur Grenze angelegt, die Rückmeldung nennt die übrigen — wie Android
        if outcome.refused > 0 {
            text += " " + L("explorer_bulk_not_added_limit", count: outcome.refused, outcome.refused,
                            LocaleNumbers.integer(WatchlistLimits.maxPairs, grouping: true))
        }
        // Neue Paare angelegt: «Rückgängig» entfernt genau diese wieder — wie Android
        show(ExplorerMessage(text: text, long: true, undoIds: anyAdded ? outcome.addedIds : nil))
        haptic(anyAdded ? .success : .warning)
        if anyAdded { requestNotifications() }
    }

    /// «Rückgängig» nach dem Hinzufügen: die eben neu angelegten Paare wieder entfernen (vorhandene bleiben).
    func undoBulkAdd(_ ids: [Int64]) {
        guard !ids.isEmpty else { return }
        data.withWatchlistConfirm { [weak self] in _ = self?.data.deleteForUndo(ids: Set(ids)) }
    }
}

/// Wie viele Paare von «Alle …-Paare» sind noch nicht in der Merkliste? Gleiche Identität wie beim Anlegen
/// (`AppData.insertOutcome`, Kontrakttyp angeglichen) — wie Android `bulkNewCount`.
enum ExplorerBulkCount {
    static func newCount(marketKey: String?, pairs: [MarketPair], watches: [Watch]) -> Int {
        guard let marketKey else { return 0 }
        let present = Set(watches.map(\.identity))
        return pairs.filter { p in
            let contract = FuturesContractType.normalized(marketKey: marketKey, pairId: p.pairId, p.contractType)
            let key = WatchIdentity.key(marketKey: marketKey, base: p.base, quote: p.quote,
                                        contract: String(contract.rawValue), pairId: p.pairId)
            return !present.contains(key)
        }.count
    }
}
