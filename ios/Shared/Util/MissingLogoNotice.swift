import Foundation

/// Hinweis «2 Coins ohne Logo» an der Stelle von «Logos werden geladen …» (`LogoLoadingHint`): Coins der Merkliste, die
/// ein Logo haben sollten, aber weder ein mitgeliefertes noch ein geladenes haben (Initialen).
///
/// Welche Coins zählen (`key`): die Basis-Coins aller Paare der Merkliste (unabhängig von Gruppe und Filter), nach
/// derselben Regel wie die Plakette — DEX-Pools nie (`CoinLogos.allowed(forExchange:)`: dort immer Initialen), Kürzel
/// ohne Dateinamen nie (`CoinLogos.fileName`: immer Initialen); TradFi-Paare im eigenen Namensraum
/// (`CoinLogos.tradFiKey`), Krypto wie beim Laden (`CoinLogos.normalize`: «1000PEPE» und «PEPE» sind ein Coin).
///
/// Sichtbar (`state`) nur mit Schalter «Coin-Logos › In der App», nie während Logos laden (erstes Laden noch nicht
/// erledigt oder Hinweis «Logos werden geladen» steht) und nur, wenn mindestens ein Coin ohne Logo NICHT im
/// geschlossenen Satz (`dismissed`, ✕) liegt. Die Zahl nennt alle Coins ohne Logo. Kommen Logos, sinkt sie von selbst.
/// Wie Android `MissingLogoNotice.kt`, gemeinsame Testfälle `Parity/missing_logos.json`.
enum MissingLogoNotice {
    /// Ergebnis: Zeile zeigen und mit welcher Zahl (0, wenn unsichtbar).
    struct State: Equatable, Sendable {
        let visible: Bool
        let count: Int
        static let hidden = State(visible: false, count: 0)
    }

    /// Ein Paar der Merkliste: Börse, Basis-Coin, TradFi.
    struct WatchedCoin: Equatable, Sendable {
        let exchangeKey: String
        let base: String
        let tradFi: Bool
    }

    /// Schlüssel eines Coins der Merkliste, der ein Logo haben sollte; nil = zeigt immer Initialen (gewollt).
    static func key(exchangeKey: String, base: String, tradFi: Bool) -> String? {
        guard CoinLogos.allowed(forExchange: exchangeKey) else { return nil }
        let key = tradFi ? CoinLogos.tradFiKey(base) : CoinLogos.normalize(base)
        return CoinLogos.fileName(key) == nil ? nil : key
    }

    /// Schlüssel der Coins aus `pairs` (`key`), ohne doppelte, in der Reihenfolge der Merkliste.
    static func candidates(_ pairs: [WatchedCoin]) -> [String] {
        var seen = Set<String>()
        return pairs.compactMap { pair in
            guard let key = Self.key(exchangeKey: pair.exchangeKey, base: pair.base, tradFi: pair.tradFi),
                  seen.insert(key).inserted else { return nil }
            return key
        }
    }

    /// Zeile zeigen? `missing`: Coins ohne Logo; `dismissed`: beim letzten ✕ gemerkte Coins; `loading`: Logos laden
    /// noch; `logosEnabled`: Schalter «Coin-Logos › In der App».
    static func state(missing: [String], dismissed: Set<String>, loading: Bool, logosEnabled: Bool) -> State {
        guard logosEnabled, !loading, !missing.isEmpty else { return .hidden }
        let distinct = Set(missing)
        guard !distinct.isSubset(of: dismissed) else { return .hidden }
        return State(visible: true, count: distinct.count)
    }

    /// Symbol zum Anzeigen (ohne `CoinLogos.tradFiPrefix`).
    static func displaySymbol(_ key: String) -> String { CoinLogos.displaySymbol(key) }

    /// Gemerkter Satz als Text (eine Zeile je Schlüssel, sortiert).
    static func encode(_ keys: Set<String>) -> String { keys.sorted().joined(separator: "\n") }

    /// Gemerkter Satz aus `text`; leer bei nil oder leer.
    static func decode(_ text: String?) -> Set<String> {
        guard let text else { return [] }
        return Set(text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    }
}
