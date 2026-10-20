import Foundation
import Accessibility
import SwiftUI
import UIKit

// DEX-Pools suchen und hinzufügen (Teil von `ExplorerViewModel`).
// Eigene Datei, weil DexScreener ohne Paarliste anders funktioniert als die Börsen (wie `ExplorerDexActions.kt`).

extension ExplorerViewModel {
    // MARK: DEX

    /// Pools zu Token-Name, Symbol oder Vertragsadresse suchen.
    func searchDex(_ query: String) {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if term.isEmpty { return }
        dexTask?.cancel()
        dexSearching = true
        dexNoResults = false
        dexTask = Task { [weak self] in
            do {
                let body = try await MarketHTTP.call(DexScreener.searchURL(term))
                let pools = try DexScreener.parseSearch(body, query: term)
                guard let self, !Task.isCancelled else { return }
                self.dexResults = Self.distinctPools(pools)
                self.dexNoResults = pools.isEmpty
                self.dexSearching = false
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.dexResults = []
                self.dexSearching = false
                self.show(ExplorerMessage(text: L("something_went_wrong"), showView: false))
            }
        }
    }

    /// Jede Pool-Kennung einmal, höchstens 25.
    private static func distinctPools(_ pools: [DexPool]) -> [DexPool] {
        var known = Set<String>()
        var out: [DexPool] = []
        for pool in pools where known.insert(pool.pairId).inserted {
            out.append(pool)
            if out.count == 25 { break }
        }
        return out
    }

    func addDexPool(_ pool: DexPool) {
        guard let market = exchange else { return }
        // Liquidität aus der Suche gleich merken: Alarm-Editor und Alarme richten sich bis zur ersten Abfrage danach
        DexLiquidityStore.put(pool.pairId, pool.liquidityUsd)
        insert(market: market, pair: MarketPair(pool.baseSymbol, pool.watchQuote, pool.pairId),
               addedText: L("dex_added", pool.baseSymbol),
               duplicateText: L("dex_already", pool.baseSymbol))
    }
}
