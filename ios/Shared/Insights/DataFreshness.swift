import Foundation

/// Ein geholter Wert und welcher Anbieter ihn geliefert hat (`provider` nil = unbekannt) —
/// wie `Sourced` (Android, `DataFreshness.kt`).
struct Sourced<Value: Sendable>: Sendable {
    let value: Value
    let provider: String?
}

/// Herkunft und Stand eines gezeigten Werts im Markt-Tab: Anbieter (z. B. «CoinGecko»),
/// Zeitpunkt (Epoch-ms) und Gültigkeit des Bereichs (`CycleCachePolicy`) — wie `DataStamp` (Android).
struct DataStamp: Equatable, Sendable {
    let provider: String?
    let savedAt: Int64
    let ttl: Int64
}

/// Herkunft und Alter der Werte im Markt-Tab (rein, testbar): welche Altersstufe
/// («gerade eben», «vor 3 Min.», «heute 02:00», Datum) gezeigt wird, ab wann ein Wert als
/// veraltet gilt, und die Anbieternamen. Wie `DataFreshness` (Android).
enum DataFreshness {

    /// Veraltet = älter als so viele Gültigkeitsdauern.
    static let staleFactor: Int64 = 3

    static let alternativeMe = "alternative.me"
    static let coinGecko = "CoinGecko"
    static let coinMetrics = "Coin Metrics"
    static let mempool = "mempool.space"
    static let binance = "Binance"
    static let binanceUS = "Binance.US"
    static let coinbase = "Coinbase"
    static let bybit = "Bybit"

    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute

    /// Altersstufe eines Werts für die Nebenzeile.
    enum Age: Equatable {
        /// Jünger als eine Minute (auch: Zeitpunkt in der Zukunft, Uhr verstellt).
        case justNow
        /// «vor N Min.», 1–59.
        case minutes(Int)
        /// Heute, mindestens eine Stunde her: «heute HH:MM».
        case today(Int64)
        /// Früherer Tag: Datum.
        case date(Int64)
    }

    /// Altersstufe von `savedAt` zum Zeitpunkt `now`; «heute» nach dem Kalender `calendar`.
    static func age(savedAt: Int64, now: Int64, calendar: Calendar = .current) -> Age {
        let diff = now - savedAt
        if diff < minute { return .justNow }
        if diff < hour { return .minutes(Int(diff / minute)) }
        let saved = Date(timeIntervalSince1970: Double(savedAt) / 1000)
        let current = Date(timeIntervalSince1970: Double(now) / 1000)
        return calendar.isDate(saved, inSameDayAs: current) ? .today(savedAt) : .date(savedAt)
    }

    /// Veraltet: älter als `staleFactor` × `ttl`. Zukunft oder TTL ≤ 0: nie.
    static func isStale(savedAt: Int64, now: Int64, ttl: Int64) -> Bool {
        ttl > 0 && now - savedAt > staleFactor * ttl
    }

    /// Mehrere Anbieter zu einem Namen, z. B. «Binance, Coin Metrics»; nil ohne Namen.
    static func providers(_ names: String?...) -> String? {
        var out: [String] = []
        for name in names {
            guard let trimmed = name?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty,
                  !out.contains(trimmed) else { continue }
            out.append(trimmed)
        }
        return out.isEmpty ? nil : out.joined(separator: ", ")
    }

    /// Anzeigename eines Kerzen-Hosts der Ausweich-Kette: alle Binance-Hosts «Binance»,
    /// api.binance.us «Binance.US», Coinbase «Coinbase»; sonst `siteName`.
    static func candleProvider(host: String) -> String {
        let h = host.lowercased()
        if h == "api.binance.us" || h.hasSuffix(".binance.us") { return binanceUS }
        if h.hasSuffix("binance.com") || h.hasSuffix("binance.vision") { return binance }
        if h.hasSuffix("coinbase.com") { return coinbase }
        return siteName(host)
    }

    /// Kurzer Name einer Adresse: die letzten zwei Teile des Hosts, z. B.
    /// «https://ethereum-rpc.publicnode.com» → «publicnode.com».
    static func siteName(_ url: String) -> String {
        var host = url
        if let range = host.range(of: "://") { host = String(host[range.upperBound...]) }
        host = String(host.prefix { $0 != "/" && $0 != ":" }).lowercased()
        let parts = host.split(separator: ".").map(String.init).filter { !$0.isEmpty }
        return parts.suffix(2).joined(separator: ".")
    }
}
