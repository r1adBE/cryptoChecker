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
        let outcome = data.addWatches(market: market, pairs: bulkPairs, group: data.addTargetGroup)
        let anyAdded = outcome.added > 0
        show(ExplorerMessage(text: L("explorer_bulk_added", count: outcome.added, outcome.added, outcome.skipped), long: true))
        haptic(anyAdded ? .success : .warning)
        if anyAdded { requestNotifications() }
    }
}
