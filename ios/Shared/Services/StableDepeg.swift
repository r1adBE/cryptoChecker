import Foundation

/// Depeg-Alarm für weitere Stablecoins neben USDT (`DepegAlarm`): derselbe Schalter, dieselbe Schwelle und Hysterese
/// (`DepegAlarm.evaluate`), je Stablecoin ein eigener Zustand. Reine Logik — wie `StableDepeg.kt` (Android).
///  - USDC wird mit eingeschaltetem Alarm immer geprüft: Kraken USDC/USD (direkt) und Binance USDC/USDT × USDT/USD
///    (Kreuzkurs aus dem schon abgefragten USDC/USDT der USDT-Messung). Ein Kreuzkurs allein löst nie aus.
///    Coinbase führt kein Paar USDC-USD (USDC zählt dort 1:1 als USD) — darum keine Coinbase-Quelle für USDC.
///  - Die übrigen Stablecoins (`coins`) nur, wenn sie in der Merkliste oder im Portfolio stehen (`watched`), über ein
///    Spot-Paar der Paarliste von Kraken, Coinbase oder Binance (`exchanges`, `pickPair`) gegen USD, USDC oder USDT;
///    gemeldet wird nur mit mindestens zwei unabhängigen Quellen (verschiedene Börsen, `measure`).
enum StableDepeg {
    static let usdc = "USDC"

    /// Weitere Stablecoins in fester Reihenfolge (`CurrencyConversion.usdStables` ohne USD und USDT): Reihenfolge der
    /// Einstellungs-Zeile und Kennung der Mitteilung (`notificationIndex`).
    static let coins = ["USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1", "RLUSD", "PYUSD", "USDP", "GUSD"]

    /// Börsen für die übrigen Stablecoins (bestehende Ticker-Abrufe, Reihenfolge = Reihenfolge der Quellen).
    static let exchanges = [DepegAlarm.krakenKey, DepegAlarm.coinbaseKey, DepegAlarm.binanceKey]

    /// Gegenwährungen in Vorrang-Reihenfolge (`pickPair`).
    static let quotes = ["USD", "USDC", "USDT"]

    /// Kraken USDC/USD (Paar-Kennung wie in der Paarliste der Börse).
    static let krakenUsdcPairId = "USDCUSD"

    /// Geprüfte Stablecoins: USDC immer, die übrigen nur, wenn ihr Kürzel als Basis einer Merklisten-Zeile
    /// (`watchBases`) oder als Coin des Portfolios (`holdings`) vorkommt. Reihenfolge `coins`. Wie Android.
    static func watched(watchBases: [String], holdings: [String]) -> [String] {
        let held = Set((watchBases + holdings).map(PortfolioCalculator.normalizeCoin))
        return coins.filter { $0 == usdc || held.contains($0) }
    }

    /// Spot-Paar von `coin` in `pairs` mit Gegenwährung nach `quotes` (nie gegen sich selbst); nil = keines.
    static func pickPair(_ coin: String, pairs: [ExchangePair]) -> ExchangePair? {
        let base = PortfolioCalculator.normalizeCoin(coin)
        let own = pairs.filter { PortfolioCalculator.normalizeCoin($0.base) == base && $0.contractType == .none }
        for quote in quotes where quote != base {
            if let hit = own.first(where: { $0.quote.uppercased() == quote }) { return hit }
        }
        return nil
    }

    /// Kurs `price` in der Gegenwährung `quote` in USD: USD direkt; USDT × `usdtUsd`; USDC × `usdcUsd` (Messungen dieses
    /// Laufs). Fehlt der nötige Kurs oder ist `price` ungültig: nil — nie still 1 angenommen. Wie Android.
    static func toUsd(_ price: Double?, quote: String, usdtUsd: Double?, usdcUsd: Double?) -> Double? {
        guard let p = price, p.isFinite, p > 0 else { return nil }
        let factor: Double?
        switch quote.uppercased() {
        case "USD": factor = 1
        case "USDT": factor = usdtUsd
        case "USDC": factor = usdcUsd
        default: factor = nil
        }
        guard let f = factor, f.isFinite, f > 0 else { return nil }
        return p * f
    }

    /// Eine Quelle in USD (`usd` nil = fehlt); `cross` = über einen anderen Stablecoin umgerechnet.
    struct Source: Equatable {
        let usd: Double?
        let cross: Bool
    }

    /// Messung eines Stablecoins in USD; `alarmable` = darf den Alarm auslösen (`measure`).
    struct Measurement: Equatable {
        let price: Double
        let alarmable: Bool
    }

    /// Zwei unabhängige Quellen nötig? USDC nicht (wie USDT: eine direkte genügt), alle übrigen ja.
    static func requiresTwo(_ coin: String) -> Bool { PortfolioCalculator.normalizeCoin(coin) != usdc }

    /// Messung aus `sources` (je Börse höchstens eine) nach den Regeln von `DepegAlarm.measure`: ausserhalb 0.05…3
    /// Börsenfehler; im Normalbereich zählt ein Kurs, im Einbruch-Bereich nur bestätigt (5 %); es gilt der Wert näher
    /// bei 1. `alarmable`: mit `requireTwo` mindestens zwei gültige Quellen, sonst mindestens eine direkte. Wie Android.
    static func measure(_ sources: [Source], requireTwo: Bool) -> Measurement? {
        let raw: [Double?] = sources.map { s in
            guard let v = s.usd, inBreakRange(v) else { return nil }
            return v
        }
        let accepted = raw.indices.filter { i in
            guard let v = raw[i] else { return false }
            if normal(v) { return true }
            return raw.indices.contains { j in
                guard j != i, let o = raw[j] else { return false }
                return agree(v, o)
            }
        }
        guard let first = accepted.first, let start = raw[first] else { return nil }
        // Gleichstand: der erste (wie Android minBy)
        var price = start
        for i in accepted.dropFirst() { if let v = raw[i], abs(v - 1) < abs(price - 1) { price = v } }
        let alarmable = requireTwo ? accepted.count >= 2 : accepted.contains { !sources[$0].cross }
        return Measurement(price: price, alarmable: alarmable)
    }

    private static func normal(_ p: Double) -> Bool {
        p.isFinite && p >= PortfolioStables.usdtUsdMin && p <= PortfolioStables.usdtUsdMax
    }

    private static func inBreakRange(_ p: Double) -> Bool {
        p.isFinite && p >= PortfolioStables.usdtUsdBreakMin && p <= PortfolioStables.usdtUsdBreakMax
    }

    private static func agree(_ a: Double, _ b: Double) -> Bool { abs(a - b) <= DepegAlarm.breakAgreement * max(a, b) }

    /// Ordnungszahl für Kennung der Mitteilung (je Stablecoin eine eigene); -1 = unbekannt.
    static func notificationIndex(_ coin: String) -> Int {
        coins.firstIndex(of: PortfolioCalculator.normalizeCoin(coin)) ?? -1
    }

    /// Einträge der Zeile «Überwacht»: USDT, dann `watched` (Reihenfolge `coins`), je mit dem letzten Kurs, solange er
    /// höchstens `DepegAlarm.liveMaxAgeMillis` alt ist (`DepegAlarm.liveUsdtUsd`); sonst ohne Kurs (nil). Wie Android.
    static func lineItems(watched: [String], usdt: DepegAlarm.Reading?, readings: [String: DepegAlarm.Reading],
                          now: Int64) -> [(coin: String, price: Double?)] {
        [(PortfolioStables.valuation, DepegAlarm.liveUsdtUsd(usdt, now: now))]
            + coins.filter(watched.contains).map { ($0, DepegAlarm.liveUsdtUsd(readings[$0], now: now)) }
    }
}
