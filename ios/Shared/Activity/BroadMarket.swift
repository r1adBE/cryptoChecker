import Foundation

/// Breiter Markt für «Warum?»: Median der 24-h-Veränderung der Top-Coins (wie Android).
struct BroadMarketSnapshot: Equatable, Sendable {
    /// Median in %.
    let change24h: Double
    /// So viele Coins flossen ein.
    let coins: Int
    /// Stand der Daten (Median von «last_updated», Epoch-ms).
    let time: Int64
}

/// Ein Coin der Rangliste mit 24-h-Veränderung (CoinGecko `/coins/markets`).
struct BroadCoin: Equatable, Sendable {
    let symbol: String
    let change24h: Double?
    let lastUpdated: Int64?
}

/// Zweite Markt-Referenz neben BTC/ETH — wie `BroadMarket.kt`: die Rangliste der Top-40-Coins aus
/// CoinGecko (dieselbe Abfrage wie der Spot-Tab, `SpotMarket.topURL`, für alle Nutzer gleich)
/// — keine Anfrage, die einen Coin verrät.
///
/// Gezählt werden nur «Altcoins»: ohne Stablecoins, ohne BTC und ETH (die eigene Referenzen sind),
/// ohne deren verpackte/gestakte Abbilder und ohne Gold-Token — und ohne den betrachteten Coin
/// selbst. Der Median ist robust gegen einzelne Ausreisser. Zu wenige Coins oder ein zu alter
/// Stand: kein breiter Markt.
enum BroadMarket {
    /// Mindestens so viele Coins, sonst kein Median.
    static let minCoins = 10
    /// Höchstens so alt (Median von «last_updated») — CoinGecko aktualisiert laufend.
    static let maxAgeMillis: Int64 = 3 * ActivityAnalyzer.hourMillis
    /// Anbieter der Rangliste (für die Quellenangabe).
    static let provider = "CoinGecko"

    /// Kein eigener Markt: Abbilder von BTC/ETH (verpackt, gestakt) und Gold-Token.
    static let excluded: Set<String> = [
        "BTC", "ETH", "WBTC", "CBBTC", "LBTC", "BTCB", "TBTC", "SOLVBTC",
        "WETH", "STETH", "WSTETH", "WEETH", "RETH", "CBETH", "METH", "EZETH", "RSETH", "BETH", "WBETH",
        "PAXG", "XAUT",
    ]

    /// Weitere Stablecoins der Rangliste, die nicht in der Umrechnungsliste stehen.
    private static let extraStables: Set<String> = ["USDS", "USDTB", "BUIDL", "USDX", "SUSDE", "SUSDS", "EURC", "USDG", "USDF"]

    private static let futureToleranceMillis: Int64 = 5 * 60_000

    /// Antwort von `/coins/markets` lesen; Einträge ohne Kürzel fallen weg.
    static func parse(_ text: String?) -> [BroadCoin] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any] else { return [] }
        return root.compactMap { item in
            guard let o = item as? [String: Any],
                  let raw = o["symbol"] as? String else { return nil }
            let symbol = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard !symbol.isEmpty else { return nil }
            return BroadCoin(
                symbol: symbol,
                change24h: finite(o["price_change_percentage_24h"]),
                lastUpdated: SpotMarket.isoMillis(o["last_updated"] as? String)
            )
        }
    }

    /// Zählt der Coin zum breiten Markt (ohne `excludeBase`)?
    static func counts(_ symbol: String, excludeBase: String?) -> Bool {
        let s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if s.isEmpty || excluded.contains(s) || CurrencyConversion.usdStables.contains(s) || extraStables.contains(s) {
            return false
        }
        guard let excludeBase else { return true }
        return s != excludeBase.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Median der 24-h-Veränderung der zählenden Coins; nil bei weniger als `minCoins` Werten,
    /// ohne Zeitstempel oder wenn der Stand älter als `maxAgeMillis` ist (bzw. über 5 Min. in der Zukunft).
    static func snapshot(_ coins: [BroadCoin], excludeBase: String?, now: Int64) -> BroadMarketSnapshot? {
        // Je Kürzel der erste Eintrag (wie `distinctBy` in Kotlin)
        var seen = Set<String>()
        var used: [BroadCoin] = []
        for coin in coins where counts(coin.symbol, excludeBase: excludeBase) && coin.change24h != nil {
            if seen.insert(coin.symbol).inserted { used.append(coin) }
        }
        guard used.count >= minCoins else { return nil }
        let times = used.compactMap(\.lastUpdated)
        guard times.count >= minCoins else { return nil }
        let time = Int64(median(times.map { Double($0) }))
        if now - time > maxAgeMillis || time - now > futureToleranceMillis { return nil }
        let change = median(used.compactMap(\.change24h))
        return BroadMarketSnapshot(change24h: change, coins: used.count, time: time)
    }

    /// Median (bei gerader Anzahl Mittel der beiden mittleren).
    static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let n = sorted.count
        guard n > 0 else { return .nan }
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /// Zahl aus JSON (NSNumber, nicht Bool); NSNull, Text und nicht endliche Werte → nil.
    private static func finite(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        return d.isFinite ? d : nil
    }
}
