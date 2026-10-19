import Foundation

/// Ausweich-Kette für historische TAGESSCHLÜSSE (Stichtag, Steuer-Zeilen, Vorschlag beim Erfassen, Wertverlauf): nur
/// Börsen, mit denen die App ohnehin spricht — keine neuen Hosts. Reihenfolge: eigene Börse des Coins (falls eingetragen)
/// → Binance (Spot, Futures, Binance.US) → Coinbase → Kraken → OKX → Bybit. Jede Quelle zählt nur mit bestandener
/// Identitätsprüfung (`decide`: Name + `PricePlausibility.acceptSourceClose`); ein gleiches Kürzel eines anderen Tokens
/// wird nie übernommen. Stablecoin-Regeln bleiben bei `PortfolioStables`. Wie `HistoricCloseChain.kt`; gemeinsame
/// Testfälle `Parity/historic_close_chain.json`.
enum HistoricCloseChain {
    static let binance = "Binance"
    static let coinbase = "Coinbase"
    static let kraken = "Kraken"
    static let okx = "Okex"
    static let bybit = "Bybit"

    /// Kette ohne eigene Börse (Börsen-Schlüssel wie `MarketsConfig`).
    static let chain = [binance, coinbase, kraken, okx, bybit]

    /// Ausweich für den Wertverlauf, wenn Binance den Coin nicht führt oder nicht erreichbar ist: dieselbe Kette ohne
    /// Binance (dessen Reihe hat die Quelle schon versucht).
    static let seriesChain = [coinbase, kraken, okx, bybit]

    /// Reihenfolge der Quellen: eigene Börse `ownMarketKey` zuerst (auch eine Börse ausserhalb der Kette, z. B.
    /// Bitstamp), danach die übrigen Glieder von `chain` ohne Doppel. Gross/Klein egal; leer = nur `chain`.
    static func order(_ ownMarketKey: String?) -> [String] {
        guard let own = ownMarketKey?.trimmingCharacters(in: .whitespaces), !own.isEmpty else { return chain }
        let canonical = chain.first { $0.caseInsensitiveCompare(own) == .orderedSame } ?? own
        return [canonical] + chain.filter { $0 != canonical }
    }

    /// Anzeigename der Quelle («Kurs: Kraken»); OKX heisst in der App «OKX», der Schlüssel «Okex».
    static func provider(_ marketKey: String) -> String { marketKey == okx ? "OKX" : marketKey }

    /// Ein abzufragendes Paar einer Kettenquelle: Gegenwährung und Kennung der Börse.
    struct ChainPair: Equatable, Sendable {
        let quote: String
        let pairId: String
    }

    /// Kürzel, die Kraken anders führt.
    private static let krakenAssets = ["BTC": "XBT", "DOGE": "XDG"]

    /// Paare einer Kettenquelle für `base`, in dieser Reihenfolge versucht; leer = Quelle nicht in der Kette oder
    /// Kürzel unbrauchbar. Gegenwährung USDT bzw. USD/USDC (≈ USDT wie bisher bei Binance.US und Coinbase).
    /// Kraken führt BTC als «XBT» und DOGE als «XDG».
    static func pairs(_ marketKey: String, base: String) -> [ChainPair] {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        guard !b.isEmpty, b.count <= 15, b.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return [] }
        switch marketKey {
        case coinbase: return [ChainPair(quote: "USD", pairId: "\(b)-USD"), ChainPair(quote: "USDC", pairId: "\(b)-USDC")]
        case kraken:
            let k = krakenAssets[b] ?? b
            return [ChainPair(quote: "USD", pairId: "\(k)USD"), ChainPair(quote: "USDT", pairId: "\(k)USDT")]
        case okx: return [ChainPair(quote: "USDT", pairId: "\(b)-USDT"), ChainPair(quote: "USDC", pairId: "\(b)-USDC")]
        case bybit: return [ChainPair(quote: "USDT", pairId: "\(b)USDT"), ChainPair(quote: "USDC", pairId: "\(b)USDC")]
        default: return []
        }
    }

    /// Name passt? Eigene Börse (`own`): ihr Name (`providerName`) darf dem eigenen (`ownName`, Merkliste) nicht klar
    /// widersprechen. Kettenquelle: wie die Ausweich-Reihen (`TokenIdentity.fallbackSeriesAllowed`) — widerspricht der
    /// eigene Name dem Binance-Namen, fällt die ganze Kette aus. Unbekannte Namen: ja (Kursband entscheidet).
    static func nameAllowed(base: String, own: Bool, marketKey: String, ownName: String?, binanceName: String?,
                            providerName: String?) -> Bool {
        if own { return TokenIdentity.compatible(symbol: base, ownName, providerName) }
        return TokenIdentity.fallbackSeriesAllowed(symbol: base, ownName: ownName, provider: provider(marketKey),
                                                   binanceName: binanceName, providerName: providerName)
    }

    /// Urteil über den Schluss einer Quelle: übernommen, verworfen (anderer Token/ungeprüft) oder keiner da.
    enum Verdict: String, Sendable {
        case accept = "ACCEPT"
        case reject = "REJECT"
        case missing = "MISSING"
    }

    /// Schluss `close` einer Quelle übernehmen? Ohne gültigen Schluss `.missing` (nächste Quelle, kein Hinweis); Name
    /// widerspricht (`nameOk` false) oder Identitätsprüfung nicht bestanden (`PricePlausibility.acceptSourceClose`:
    /// jüngster Schluss derselben Quelle `latest` gegen den aktuellen Kurs `current`; `primary` = 1/3…3, sonst ±25 %)
    /// → `.reject`.
    static func decide(close: Double?, latest: Double?, current: Double?, primary: Bool, nameOk: Bool) -> Verdict {
        guard let close, close > 0, close.isFinite else { return .missing }
        if !nameOk { return .reject }
        return PricePlausibility.acceptSourceClose(close, primary: primary, sourceLatest: latest, current: current)
            ? .accept : .reject
    }

    /// Ergebnis einer Quelle: übernommener Schluss oder keiner; `rejected` = es gab einen, der die Prüfung nicht
    /// bestand. `provider` = genauerer Anbieter des Treffers (z. B. «Binance.US»); nil = `provider(_:)` der Quelle.
    struct Outcome: Sendable {
        var price: Double?
        var rejected = false
        var provider: String?
    }

    /// Ergebnis der Kette: Schluss und Quelle (`source` = Börsen-Schlüssel, `provider` = Anzeigename) oder keiner;
    /// `unchecked` = nur verworfene Schlüsse.
    struct Resolved: Sendable {
        var price: Double?
        var source: String?
        var unchecked: Bool
        var provider: String?
    }

    /// Quellen in `order` der Reihe nach fragen (`outcome`, z. B. Abfrage), bis eine einen Schluss liefert. Ohne
    /// Treffer: `unchecked`, wenn irgendeine Quelle einen Schluss verworfen hat.
    static func resolve(_ order: [String], outcome: (String) async -> Outcome) async -> Resolved {
        var rejected = false
        for source in order {
            let r = await outcome(source)
            if let price = r.price, price > 0, price.isFinite {
                return Resolved(price: price, source: source, unchecked: false, provider: r.provider ?? provider(source))
            }
            rejected = rejected || r.rejected
        }
        return Resolved(price: nil, source: nil, unchecked: rejected, provider: nil)
    }

    /// Ausweich-Reihe für den Wertverlauf übernehmen? Jüngster Schluss `latest` der Reihe gegen den aktuellen Kurs
    /// `current` mit ±25 % (Ausweich-Quelle) und passender Name (`nameOk`). Ohne aktuellen Kurs nicht.
    static func acceptSeries(latest: Double?, current: Double?, nameOk: Bool) -> Bool {
        nameOk && PricePlausibility.acceptSourceClose(latest, primary: false, sourceLatest: latest, current: current)
    }

    /// USDT/USD-Tagesschlüsse: `primary` (Kraken) gilt; Tage ohne Schluss dort füllt `secondary` (z. B. Coinbase), aber
    /// nur plausible Werte (`PortfolioStables.usdtUsd` ohne Näherung). Erst danach gilt «USDT = 1 angenommen».
    static func fillUsdtUsd(_ primary: [Int: Double], _ secondary: [Int: Double]) -> [Int: Double] {
        if secondary.isEmpty { return primary }
        var out: [Int: Double] = [:]
        for (day, v) in secondary where !PortfolioStables.usdtUsd(v).approximate { out[day] = v }
        for (day, v) in primary where !PortfolioStables.usdtUsd(v).approximate || out[day] == nil { out[day] = v }
        return out
    }
}
