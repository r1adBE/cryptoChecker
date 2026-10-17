import Foundation

/// Eingaben je Coin für «Heute auffällig» — wie `UnusualCoin` (Android).
/// - `change24h`: 24-h-Veränderung in % (Binance-Spiegel, …USDT)
/// - `quoteVolume`: 24-h-Umsatz in USDT
/// - `marketCap`: Marktkapitalisierung in USD (CoinGecko-Rangliste, bis 24 h alt)
/// - `fundingPercent`: letzte Funding Rate des USDT-Perpetuals in % je Periode; nil = kein Perpetual
struct UnusualCoin: Codable, Equatable, Sendable {
    let symbol: String
    let name: String
    let change24h: Double
    let quoteVolume: Double?
    let marketCap: Double?
    let fundingPercent: Double?
}

/// Alle Eingaben einer Abfrage; `ownTypical` = eigener üblicher Umsatz-Anteil je Coin.
struct UnusualInput: Codable, Equatable, Sendable {
    let coins: [UnusualCoin]
    let ownTypical: [String: Double]
    /// Zeitpunkt der Abfrage (Epoch-ms).
    let time: Int64
}

enum UnusualFactKind: Int, Codable, Sendable {
    case strongerThanBtc, weakerThanBtc, againstMarket, volume, fundingHigh, fundingNegative
}

struct UnusualFact: Equatable, Sendable {
    let kind: UnusualFactKind
    /// Stärke relativ zur Schwelle (1 = genau an der Schwelle).
    let strength: Double
    var volumeRatio: Double? = nil
    var fundingPercent: Double? = nil
}

struct UnusualRow: Equatable, Sendable, Identifiable {
    let symbol: String
    let name: String
    let change24h: Double
    /// Auffälligste zuerst (nie leer).
    let facts: [UnusualFact]
    let score: Double

    var id: String { symbol }
    var fact: UnusualFact { facts[0] }
}

/// Höchstens `MarketUnusual.maxRows` Zeilen; leer = «Heute nichts Auffälliges».
struct UnusualReport: Equatable, Sendable {
    let rows: [UnusualRow]
    let btc24h: Double?
    let time: Int64
}

/// Ein Tageswert des Umsatz-Anteils (24-h-Umsatz ÷ Marktkapitalisierung).
struct TurnoverSample: Codable, Equatable, Sendable {
    let day: Int64
    let value: Double
}

/// «Heute auffällig» — reine Logik, wie `MarketUnusual.kt` (dort mit Unit-Tests):
/// deutlich stärker/schwächer als BTC (≥ 3 Prozentpunkte), gegen den Markt (≥ 2 %, andere
/// Richtung als BTC, BTC bewegt sich mindestens 0.5 %), Volumen ≥ 2× üblich (Umsatz-Anteil gegen
/// den eigenen Median der Vortage, anfangs gegen den Median aller Coins), Funding ≥ 0.05 % bzw.
/// ≤ −0.03 %. Keine Prognose, keine Empfehlung.
enum MarketUnusual {
    static let relativePp = 3.0
    /// Ein 24-h-Ticker gilt als laufend, wenn sein Fenster höchstens so lange zurück endet.
    static let tickerStaleMillis: Int64 = 2 * 60 * 60_000
    static let againstMinPercent = 2.0
    static let btcDirectionMinPercent = 0.5
    static let volumeRatio = 2.0
    static let fundingHighPercent = 0.05
    static let fundingNegativePercent = -0.03
    static let maxRows = 5
    static let minUniverse = 5
    static let minOwnDays = 5
    static let historyDays: Int64 = 14
    private static let extraFactBonus = 0.25
    private static let againstWeight = 1.25

    /// Wird das Paar im Spot noch gehandelt? Ein delistetes Paar (Binance: Status BREAK)
    /// liefert weiter einen Ticker, aber mit eingefrorenen Werten: Fensterende (`closeTime`,
    /// ms) lange vorbei oder keine Abschlüsse (`tradeCount`). Fehlt ein Feld, zählt der Ticker.
    static func isLiveTicker(closeTime: Int64?, tradeCount: Int64?, now: Int64) -> Bool {
        if let tradeCount, tradeCount <= 0 { return false }
        if let closeTime, closeTime > 0, now - closeTime > tickerStaleMillis { return false }
        return true
    }

    static func turnover(_ coin: UnusualCoin) -> Double? {
        guard let volume = coin.quoteVolume, let cap = coin.marketCap,
              volume.isFinite, cap.isFinite, volume > 0, cap > 0 else { return nil }
        return volume / cap
    }

    static func median(_ values: [Double]) -> Double? {
        let sorted = values.filter { $0.isFinite }.sorted()
        guard !sorted.isEmpty else { return nil }
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }

    /// Tatsachen eines Coins, die auffälligste zuerst.
    static func facts(_ coin: UnusualCoin, btc: Double?, typical: Double?) -> [UnusualFact] {
        var out: [UnusualFact] = []
        let change = coin.change24h
        let isBtc = coin.symbol.uppercased() == "BTC"

        if let btc, !isBtc, change.isFinite, btc.isFinite {
            let against = abs(btc) >= btcDirectionMinPercent
                && abs(change) >= againstMinPercent
                && (change > 0) != (btc > 0)
            let diff = change - btc
            if against {
                // Schliesst «stärker/schwächer» ein — nicht doppelt nennen
                out.append(UnusualFact(kind: .againstMarket, strength: abs(change) / againstMinPercent * againstWeight))
            } else if abs(diff) >= relativePp {
                out.append(UnusualFact(kind: diff > 0 ? .strongerThanBtc : .weakerThanBtc, strength: abs(diff) / relativePp))
            }
        }

        if let turnover = turnover(coin), let typical, typical > 0, typical.isFinite {
            let ratio = turnover / typical
            if ratio >= volumeRatio {
                out.append(UnusualFact(kind: .volume, strength: ratio / volumeRatio, volumeRatio: ratio))
            }
        }

        if let funding = coin.fundingPercent, funding.isFinite {
            if funding >= fundingHighPercent {
                out.append(UnusualFact(kind: .fundingHigh, strength: funding / fundingHighPercent, fundingPercent: funding))
            } else if funding <= fundingNegativePercent {
                out.append(UnusualFact(kind: .fundingNegative, strength: funding / fundingNegativePercent,
                                       fundingPercent: funding))
            }
        }
        return out.sorted { a, b in
            a.strength != b.strength ? a.strength > b.strength : a.kind.rawValue < b.kind.rawValue
        }
    }

    /// Zeilen nach Auffälligkeit (stärkste Tatsache plus kleiner Zuschlag je weitere), höchstens
    /// `maxRows`. nil ohne Coins.
    static func evaluate(_ input: UnusualInput) -> UnusualReport? {
        var seen = Set<String>()
        let coins = input.coins.filter { coin in
            coin.change24h.isFinite && !coin.symbol.isEmpty && seen.insert(coin.symbol.uppercased()).inserted
        }
        guard !coins.isEmpty else { return nil }
        let btc = coins.first { $0.symbol.uppercased() == "BTC" }?.change24h
        let turnovers = coins.compactMap { turnover($0) }
        let universeMedian: Double? = turnovers.count >= minUniverse ? median(turnovers) : nil

        let rows: [UnusualRow] = coins.compactMap { coin in
            let own = input.ownTypical[coin.symbol.uppercased()].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let found = facts(coin, btc: btc, typical: own ?? universeMedian)
            guard let first = found.first else { return nil }
            return UnusualRow(symbol: coin.symbol.uppercased(), name: coin.name, change24h: coin.change24h,
                              facts: found, score: first.strength + extraFactBonus * Double(found.count - 1))
        }
        let sorted = rows.sorted { a, b in
            if a.score != b.score { return a.score > b.score }
            if abs(a.change24h) != abs(b.change24h) { return abs(a.change24h) > abs(b.change24h) }
            return a.symbol < b.symbol
        }
        return UnusualReport(rows: Array(sorted.prefix(maxRows)), btc24h: btc, time: input.time)
    }

    // MARK: Eigener «üblicher» Umsatz

    /// Neue Tageswerte eintragen (der jüngste des Tages gilt), nur Coins dieser Abfrage behalten,
    /// je Coin die letzten `historyDays` Tage.
    static func updateHistory(_ history: [String: [TurnoverSample]], coins: [UnusualCoin], day: Int64)
        -> [String: [TurnoverSample]] {
        var out: [String: [TurnoverSample]] = [:]
        for coin in coins {
            let symbol = coin.symbol.uppercased()
            var merged = (history[symbol] ?? []).filter { $0.day < day && $0.value.isFinite }
            if let value = turnover(coin) { merged.append(TurnoverSample(day: day, value: value)) }
            merged = merged.sorted { $0.day < $1.day }.filter { $0.day > day - historyDays }
            if !merged.isEmpty { out[symbol] = merged }
        }
        return out
    }

    /// Median der Vortage (ohne `today`); nil unter `minOwnDays` Tagen.
    static func ownTypical(_ samples: [TurnoverSample], today: Int64) -> Double? {
        let before = samples.filter { $0.day < today && $0.value > 0 }.map(\.value)
        return before.count >= minOwnDays ? median(before) : nil
    }
}
