import Foundation

/// Wann zwei Einträge der Merkliste dasselbe Paar sind: Börse, Basis, Quote und Kontrakt — bei DexScreener
/// zusätzlich der Pool (`chainId/pairAddress`, gespeichert als `pairId`). Zwei Pools mit gleichem Symbol auf
/// derselben Chain sind verschiedene Märkte mit eigenem Kurs und eigener Liquidität, also zwei Zeilen. Bei den
/// Börsen bleibt der Schlüssel ohne Kennung: Dort benennt die Kennung nur das Paar, und ältere Einträge haben
/// keine (oder eine anders geschriebene). Gilt für Hinzufügen, Bearbeiten, «Rückgängig» und Sicherungen.
/// Wie `WatchIdentity.kt` (testdata/parity/watch_identity.json).
enum WatchIdentity {
    /// Börse, deren Paar-Kennung zum Schlüssel gehört (`DexScreener.key`).
    static let poolMarket = "DexScreener"

    /// Eindeutiger Schlüssel eines Eintrags; `pool` nur bei `poolMarket`, sonst nil.
    struct Key: Hashable, Sendable {
        let marketKey: String
        let base: String
        let quote: String
        let contract: String
        let pool: String?
    }

    /// Pool-Kennung, die zum Schlüssel gehört: die Paar-Kennung bei DexScreener, sonst nil.
    static func pool(marketKey: String, pairId: String?) -> String? {
        guard marketKey == poolMarket, let pairId, !pairId.isEmpty else { return nil }
        return pairId
    }

    static func key(marketKey: String, base: String, quote: String, contract: String, pairId: String?) -> Key {
        Key(marketKey: marketKey, base: base, quote: quote, contract: contract,
            pool: pool(marketKey: marketKey, pairId: pairId))
    }
}
