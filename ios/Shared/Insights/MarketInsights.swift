import Foundation

/// Fear & Greed Index (0 = extreme Angst, 100 = extreme Gier).
struct FearGreed: Equatable, Sendable, Codable {
    let value: Int
    let yesterday: Int?
    let weekAgo: Int?
    let monthAgo: Int?
}

/// Letzter Fear-&-Greed-Wert im App-Group-Speicher — das Widget «Was gerade auffällt» zeigt
/// ihn ohne eigenen Abruf (wie in Android aus dem Zwischenspeicher des Markt-Tabs).
/// Geschrieben bei jedem erfolgreichen Abruf (`InsightsDataSource.fearGreed`).
enum FearGreedShared {
    /// Schlüssel; Version im Namen (anderes Format = nichts gespeichert).
    static let storeKey = "fear_greed_shared_v1"
    /// Älter zeigt das Widget nicht (24 h).
    static let maxAgeMillis: Int64 = 24 * 3_600_000

    struct Stored: Codable, Equatable, Sendable {
        let value: Int
        let time: Int64
    }

    static func store(_ value: Int, time: Int64 = TimeUtils.nowMillis) {
        guard let raw = try? JSONEncoder().encode(Stored(value: value, time: time)) else { return }
        SharedStorage.defaults.set(raw, forKey: storeKey)
    }

    static func stored() -> Stored? {
        guard let raw = SharedStorage.defaults.data(forKey: storeKey) else { return nil }
        return try? JSONDecoder().decode(Stored.self, from: raw)
    }

    /// Wert fürs Widget: 0–100 und höchstens `maxAgeMillis` alt (Zukunft oder ≤ 0: nie); sonst nil.
    static func showable(_ stored: Stored?, now: Int64) -> Int? {
        guard let stored, (0...100).contains(stored.value), stored.time > 0, stored.time <= now,
              now - stored.time <= maxAgeMillis else { return nil }
        return stored.value
    }

    /// «Fear & Greed 72 · Gier» (Stufen wie die Karte im Markt-Tab).
    static func line(_ value: Int) -> String {
        let key: String
        switch ActivityAnalyzer.fearGreedLevel(value) {
        case .extremeFear: key = "fng_extreme_fear"
        case .fear: key = "fng_fear"
        case .neutral: key = "fng_neutral"
        case .greed: key = "fng_greed"
        case .extremeGreed: key = "fng_extreme_greed"
        }
        return L("factor_fear_greed") + " " + LocaleNumbers.integer(value) + " · " + L(key)
    }
}

/// Marktanteile nach Marktkapitalisierung, in Prozent.
struct Dominance: Equatable, Sendable, Codable {
    let btc: Double
    let eth: Double?
}

/// Gesamter Krypto-Markt (CoinGecko `/global`): Marktkapitalisierung und 24-h-Volumen
/// je Währung (Schlüssel in Kleinbuchstaben, z. B. "usd", "chf") und die 24-h-Veränderung
/// der Marktkapitalisierung in USD (Prozent).
struct GlobalMarket: Equatable, Sendable, Codable {
    let totalMarketCap: [String: Double]
    let totalVolume: [String: Double]
    let change24hPercent: Double?

    /// Werte in `currency`, wenn CoinGecko sie dafür liefert, sonst in USD; nil, wenn beides fehlt.
    func values(currency: String) -> (code: String, marketCap: Double, volume: Double)? {
        for code in [currency.lowercased(), "usd"] {
            if let cap = totalMarketCap[code], let volume = totalVolume[code], cap > 0, volume > 0 {
                return (code.uppercased(), cap, volume)
            }
        }
        return nil
    }
}

/// Ergebnis der einen Abfrage `api.coingecko.com/api/v3/global`: Dominanz und Gesamtmarkt.
struct CoinGeckoGlobal: Equatable, Sendable, Codable {
    let dominance: Dominance?
    let market: GlobalMarket?
}

/// Altcoin-Saison: Wie viele grosse Altcoins haben Bitcoin in 90 Tagen
/// geschlagen? Ab 75 % gilt es als Altcoin-, bis 25 % als Bitcoin-Saison.
struct AltSeason: Equatable, Sendable, Codable {
    let outperformers: Int
    let total: Int

    var index: Int { total == 0 ? 0 : outperformers * 100 / total }
}

/// Ein Punkt im Zyklus-Vergleich: Tage seit dem Halving und Kurs-Vielfaches.
struct CyclePoint: Equatable, Sendable, Codable {
    let day: Int
    let multiple: Double
}

/// Markanter Punkt eines Zyklus (Hoch oder Tief danach).
/// `change` als Anteil: beim Hoch gegenüber dem Halving-Tag, beim Tief gegenüber dem Hoch.
struct CycleMarker: Equatable, Sendable, Codable {
    let day: Int
    let date: LocalDay
    let priceUsd: Double
    let multiple: Double
    let change: Double
}

/// Kurs als Vielfaches des Halving-Tageskurses, je Zyklus.
/// `top` = höchster Kurs der ersten 1000 Tage, `bottom` = tiefster Kurs danach,
/// nur wenn er mindestens 30 % unter dem Hoch liegt.
struct CycleSeries: Equatable, Sendable, Codable {
    let halving: LocalDay
    let points: [CyclePoint]
    var top: CycleMarker? = nil
    var bottom: CycleMarker? = nil
    /// Doppel-Top/-Bottom: zweites Hoch bzw. Tief nahe am ersten (siehe `CycleExtremes`).
    var secondTop: CycleMarker? = nil
    var secondBottom: CycleMarker? = nil
}

struct CycleHistory: Equatable, Sendable, Codable {
    let series: [CycleSeries]
}
