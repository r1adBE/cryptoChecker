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
                let body = try await ExchangeHTTP.call(DexScreener.searchURL(term))
                let pools = try DexScreener.parseSearch(body, query: term)
                guard let self, !Task.isCancelled else { return }
                self.dexResults = Self.distinctPools(pools)
                self.dexNoResults = pools.isEmpty
                self.dexSearching = false
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.dexResults = []
                self.dexSearching = false
                // Wie die Merkliste einordnen (kein Netz / Dienst nicht erreichbar), mit «Erneut versuchen»
                let cause = RefreshReportLogic.classify(ExchangeFailure.of(error), ConnectionErrors.describe(error))
                self.show(ExplorerMessage(text: L(DexFailure.of(cause).messageKey), showView: false, retryDexQuery: term,
                                          kind: .error))
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
        guard let exchange = exchange else { return }
        // Liquidität aus der Suche gleich merken: Alarm-Editor und Alarme richten sich bis zur ersten Abfrage danach
        DexLiquidityStore.put(pool.pairId, pool.liquidityUsd)
        // Token-Adresse fürs Paar-Blatt (gleichnamige Token unterscheiden); nicht Teil der Paar-Identität
        DexTokenAddressStore.put(pool.pairId, pool.baseAddress)
        insert(exchange: exchange, pair: ExchangePair(pool.baseSymbol, pool.watchQuote, pool.pairId),
               addedText: L("dex_added", pool.baseSymbol),
               duplicateText: L("dex_already", pool.baseSymbol))
    }
}

/// Art einer gescheiterten DEX-Suche — gleiche Texte wie die Merkliste: kein Netz (auch Zeitüberschreitung, wie
/// `ConnectionErrors.describe`), Dienst gerade nicht erreichbar (HTTP-Fehler, Bremse), sonst «Etwas ist
/// schiefgelaufen». Gleich wie `DexFailure` (Android).
enum DexFailure: Equatable {
    case offline, unreachable, other

    static func of(_ cause: RefreshFailure) -> DexFailure {
        switch cause {
        case .OFFLINE, .TIMEOUT: return .offline
        case .RATE_LIMIT, .SERVER: return .unreachable
        case .NO_DATA, .UNAVAILABLE, .OTHER: return .other
        }
    }

    var messageKey: String {
        switch self {
        case .offline: return "watch_error_offline"
        case .unreachable: return "error_exchange_unreachable_short"
        case .other: return "something_went_wrong"
        }
    }
}
