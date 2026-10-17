import Foundation

/// Kurs von der Börse, die der Nutzer an seinen Transaktionen einträgt — wie `PortfolioExchangeRoute.kt`
/// (gleiche Regeln, dort und in `PortfolioExchangeRouteTests` getestet):
///  - Ein Coin wird mit der eingetragenen Börse bewertet, wenn ALLE seine Transaktionen mit Börse dieselbe
///    nennen; mehrere Börsen bei einem Coin → Binance und ein Hinweis.
///  - Nur Spot-Börsen der App; Binance ist ohnehin die Standardquelle.
///  - Paar: USDT, dann USD (über den Marktkurs USDT/USD, ohne Kurs 1 als Näherung), dann Fiat über den
///    Devisenkurs und denselben USDT/USD-Kurs (`toUsdt`). Andere Stablecoins (USDC …) nicht — sie gelten im
///    Portfolio nicht automatisch als 1.
///  - Klappt es nicht, bleibt Binance und der Coin steht in `PortfolioPrices.exchangeFailed`.
///  - Wertverlauf, «7 T» und «1 J» (auch Widget und Alarme): Tageskerzen der eigenen Börse in USDT
///    (`ownDailyUsdt`, davor Binance angeglichen: `stitch`); nur ohne sie Binance auf den Börsenkurs skaliert
///    (`scaleCloses`) — und nur mit Binance-Bezugskurs jetzt, sonst fehlt der Coin (`historyCloses`).
///
/// Die Paarliste einer Börse liegt in der App (`PairCache`); die App löst die Wege auf (`resolved`) und legt
/// sie in der App Group ab (`PortfolioExchangeRouteStore`) — so nutzt auch das Widget dieselben Kurse.
enum PortfolioExchangeRoute {
    /// Märkte ohne Spot-Kurse für Coins (Futures, Optionen, DEX-Pools).
    private static let notSpot: Set<String> = ["Hyperliquid", "Deribit", "DexScreener"]

    /// Standardquelle des Portfolios.
    static let defaultMarket = "Binance"

    /// Gegenwährungen in dieser Reihenfolge; USD über USDT/USD, Fiat über den Devisenkurs (`toUsdt`).
    static let quotes = [
        "USDT", "USD", "EUR", "CHF", "GBP", "JPY", "KRW", "TRY", "BRL", "MXN", "INR", "IDR", "AUD", "CAD",
        "SGD", "HKD", "PLN", "SEK", "NOK", "DKK", "CZK", "HUF", "RON", "NZD", "ZAR", "THB",
    ]

    /// Spot-Börse der App?
    static func isSpot(_ marketKey: String) -> Bool { !marketKey.hasSuffix("Futures") && !notSpot.contains(marketKey) }

    /// Börse je Coin (nur wenn alle Einträge mit Börse dieselbe nennen) und die Coins mit mehreren Börsen.
    static func exchangeByCoin(_ entries: [(coin: String, exchange: String?)]) -> (single: [String: String], mixed: Set<String>) {
        var names: [String: Set<String>] = [:]
        var display: [String: String] = [:]
        for e in entries {
            guard let name = e.exchange?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { continue }
            let coin = PortfolioCalculator.normalizeCoin(e.coin)
            names[coin, default: []].insert(name.lowercased())
            if display[coin] == nil { display[coin] = name }
        }
        var single: [String: String] = [:]
        var mixed = Set<String>()
        for (coin, set) in names {
            if set.count == 1, let name = display[coin] { single[coin] = name } else { mixed.insert(coin) }
        }
        return (single, mixed)
    }

    /// Eintrag für `exchangeByLedger`: Börse der Transaktion, bei einer Umbuchung ihr Ziel (`transferTo`).
    struct RouteEntry: Sendable {
        var coin: String
        var exchange: String?
        var type: PortfolioTxType = .BUY
        var transferTo: String? = nil
        var time: Int64 = 0
        var id: Int64 = 0
    }

    /// Wie `exchangeByCoin`, aber mit Umbuchungen: je Coin zählen ab der LETZTEN Umbuchung mit Ziel (Zeit, dann
    /// Id) nur deren Ziel und die Börsen der späteren Transaktionen — dort liegt der Bestand. Ohne solche
    /// Umbuchung alle Einträge. Ziel = Wallet oder freier Text → kein eigener Weg. Wie Android.
    static func exchangeByLedger(_ entries: [RouteEntry]) -> (single: [String: String], mixed: Set<String>) {
        var order: [String] = []
        var byCoin: [String: [RouteEntry]] = [:]
        for e in entries {
            let coin = PortfolioCalculator.normalizeCoin(e.coin)
            if byCoin[coin] == nil { order.append(coin) }
            byCoin[coin, default: []].append(e)
        }
        var pairs: [(coin: String, exchange: String?)] = []
        for coin in order {
            // Stabil wie Android (`sortedWith`): Zeit, dann Id, sonst Reihenfolge der Eingabe
            let sorted = (byCoin[coin] ?? []).enumerated().sorted { a, b in
                if a.element.time != b.element.time { return a.element.time < b.element.time }
                if a.element.id != b.element.id { return a.element.id < b.element.id }
                return a.offset < b.offset
            }.map(\.element)
            let last = sorted.lastIndex {
                $0.type == .TRANSFER && !($0.transferTo?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            }
            if let last {
                pairs.append((coin: coin, exchange: sorted[last].transferTo))
                for e in sorted[(last + 1)...] where e.type != .TRANSFER { pairs.append((coin: coin, exchange: e.exchange)) }
            } else {
                for e in sorted { pairs.append((coin: coin, exchange: e.type == .TRANSFER ? nil : e.exchange)) }
            }
        }
        return exchangeByCoin(pairs)
    }

    /// Spot-Markt zu einem eingetragenen Namen (`markets`: Schlüssel → Anzeigename); Binance und freier Text → nil.
    static func market(_ name: String, markets: [String: String]) -> (key: String, name: String)? {
        let wanted = name.trimmingCharacters(in: .whitespaces).lowercased()
        guard !wanted.isEmpty else { return nil }
        guard let hit = markets.sorted(by: { $0.key < $1.key }).first(where: { key, label in
            isSpot(key) && (label.lowercased() == wanted || key.lowercased() == wanted)
        }), hit.key != defaultMarket else { return nil }
        return (hit.key, hit.value)
    }

    /// Bestes Paar für `coin` nach `quotes`; nil = keines.
    static func pickPair(_ coin: String, pairs: [MarketPair]) -> MarketPair? {
        let base = PortfolioCalculator.normalizeCoin(coin)
        let own = pairs.filter { PortfolioCalculator.normalizeCoin($0.base) == base && $0.contractType == .none }
        for quote in quotes {
            if let hit = own.first(where: { $0.quote.uppercased() == quote }) { return hit }
        }
        return nil
    }

    /// Kurs in USDT: USDT unverändert; USD geteilt durch den Marktkurs USDT/USD `usdtUsd` (ungültig oder Standard = 1,
    /// USDT = USD wie bis Build 58); Fiat geteilt durch den Kurs USD → Fiat und durch `usdtUsd`; sonst nil. Wie Android.
    static func toUsdt(_ price: Double, quote: String, usdtUsd: Double = 1, usdTo: (String) -> Double?) -> Double? {
        guard price > 0, price.isFinite else { return nil }
        let usdt = usdtUsd > 0 && usdtUsd.isFinite ? usdtUsd : 1
        let q = quote.uppercased()
        if q == "USDT" { return price }
        if q == "USD" { return price / usdt }
        guard quotes.contains(q), let rate = usdTo(q), rate > 0, rate.isFinite else { return nil }
        return price / rate / usdt
    }

    /// Binance-Tageskurse auf den Kurs der eigenen Börse skaliert (Faktor = Kurs der Börse ÷ Binance-Kurs).
    static func scaleCloses(_ closes: [String: [Int: Double]], prices: [String: Double],
                            binance: [String: Double]) -> [String: [Int: Double]] {
        scale(closes, prices: prices, binance: binance) { $0 * $1 }
    }

    /// Eröffnung/Hoch/Tief mit demselben Faktor wie `scaleCloses` — wie Android `scaleRanges`.
    static func scaleRanges(_ ranges: [String: [Int: PortfolioDayRange]], prices: [String: Double],
                            binance: [String: Double]) -> [String: [Int: PortfolioDayRange]] {
        scale(ranges, prices: prices, binance: binance) { r, f in
            PortfolioDayRange(open: r.open * f, high: r.high * f, low: r.low * f)
        }
    }

    /// ZUERST die Identität: Verhältnis eigener Kurs ÷ Binance ausserhalb 1/3 … 3 (`PricePlausibility.sameToken`)
    /// = wohl ein anderer Token unter dem Kürzel → Reihe des Coins verwerfen statt skalieren (wie Android).
    private static func scale<T>(_ series: [String: [Int: T]], prices: [String: Double], binance: [String: Double],
                                 apply: (T, Double) -> T) -> [String: [Int: T]] {
        guard !binance.isEmpty else { return series }
        var out: [String: [Int: T]] = [:]
        for (coin, values) in series {
            guard let own = prices[coin], let ref = binance[coin], own > 0, ref > 0 else {
                // Kein Bezug zum Skalieren: Reihe unverändert — die strenge Prüfung danach
                // (`PricePlausibility.closesMatchLive`) wirft sie ohne passenden aktuellen Kurs hinaus
                out[coin] = values
                continue
            }
            guard PricePlausibility.sameToken(own, reference: ref) else { continue }
            let factor = own / ref
            out[coin] = values.mapValues { apply($0, factor) }
        }
        return out
    }

    /// Tagesverlauf für Wertverlauf, «7 T»/«1 J», Widget und Alarme — wie Android `historyCloses`:
    /// ohne eigene Börse (`routed`) oder mit eigener Reihe (`own`) unverändert; eingetragene Börse ging nicht
    /// (`failed`, Binance-Kurs): Binance unverändert; sonst Binance skaliert — NUR mit eigenem Kurs und
    /// Binance-Bezug jetzt, sonst fehlt die Reihe (Coin unter «ohne …»), nie die unskalierte Binance-Reihe.
    static func historyCloses(_ closes: [String: [Int: Double]], ranges: [String: [Int: PortfolioDayRange]],
                              own: Set<String>, routed: Set<String>, failed: Set<String>,
                              prices: [String: Double], binance: [String: Double]) -> RoutedHistory {
        let fallback = Set(closes.keys.filter { routed.contains($0) && !own.contains($0) && !failed.contains($0) })
        guard !fallback.isEmpty else { return RoutedHistory(closes: closes, ranges: ranges, scaled: []) }
        // Nur Coins mit beiden Kursen jetzt — sonst liesse `scale` die Reihe unskaliert durch
        let withRef = fallback.filter { coin in
            guard let p = prices[coin], let b = binance[coin] else { return false }
            return p > 0 && b > 0 && p.isFinite && b.isFinite
        }
        let refs = binance.filter { withRef.contains($0.key) }
        let scaled = scaleCloses(closes.filter { withRef.contains($0.key) }, prices: prices, binance: refs)
        let scaledRanges = scaleRanges(ranges.filter { scaled[$0.key] != nil }, prices: prices, binance: refs)
        var outCloses = closes.filter { !fallback.contains($0.key) }
        for (coin, values) in scaled { outCloses[coin] = values }
        var outRanges = ranges.filter { !fallback.contains($0.key) }
        for (coin, values) in scaledRanges { outRanges[coin] = values }
        return RoutedHistory(closes: outCloses, ranges: outRanges, scaled: Set(scaled.keys))
    }

    /// Tageskerzen der eigenen Börse (Gegenwährung `quote`) in USDT — wie Android `ownDailyUsdt`: USDT/USD
    /// unverändert, Fiat je Tag geteilt durch den Devisenkurs dieses Tags (sonst der letzte davor,
    /// `PortfolioHistoryFx.rateOn`); Tage ohne Kurs fallen weg. nil = nicht umrechenbar oder nichts übrig. Mit
    /// `usdtUsdByDay` (USDT/USD-Tagesschluss): USD- und Fiat-Kerzen zusätzlich geteilt durch USDT/USD DES Tags (ohne
    /// Schluss 1, `PortfolioStables.usdtUsd`); USDT-Kerzen unverändert.
    static func ownDailyUsdt(_ daily: PortfolioCoinDaily, quote: String, usdToByDay: [Int: Double]?,
                             usdtUsdByDay: [Int: Double]? = nil) -> PortfolioCoinDaily? {
        let q = quote.uppercased()
        guard quotes.contains(q) else { return nil }
        let direct = q == "USDT" || q == "USD"
        var sorted: [(day: Int, rate: Double)] = []
        if !direct {
            sorted = (usdToByDay ?? [:]).filter { $0.value > 0 && $0.value.isFinite }
                .sorted { $0.key < $1.key }.map { (day: $0.key, rate: $0.value) }
            if sorted.isEmpty { return nil }
        }
        var closes: [Int: Double] = [:]
        var ranges: [Int: PortfolioDayRange] = [:]
        for (day, close) in daily.closes {
            guard close > 0, close.isFinite else { continue }
            let fx: Double? = direct ? 1.0 : PortfolioHistoryFx.rateOn(sorted, day: day)
            let usdt: Double = q == "USDT" || usdtUsdByDay == nil ? 1.0 : PortfolioStables.usdtUsd(usdtUsdByDay?[day]).rate
            guard let r = fx.map({ $0 * usdt }) else { continue }
            closes[day] = close / r
            if let range = daily.ranges[day] {
                let converted = PortfolioDayRange(open: range.open / r, high: range.high / r, low: range.low / r)
                if converted.valid { ranges[day] = converted }
            }
        }
        return closes.isEmpty ? nil : PortfolioCoinDaily(closes: closes, ranges: ranges)
    }

    /// Eigene Reihe vorn mit Binance ergänzt, wenn die Börse nicht so weit zurückreicht — wie Android `stitch`:
    /// Binance-Tage vor dem ersten eigenen Tag, mit dem Verhältnis eigener ÷ Binance-Schluss AN diesem Tag
    /// angeglichen. Ohne Binance-Schluss an dem Tag oder bei anderem Token bleibt die eigene Reihe allein.
    static func stitch(_ own: PortfolioCoinDaily, binance: PortfolioCoinDaily?) -> StitchedDaily {
        guard let first = own.closes.keys.min(), let ownFirst = own.closes[first],
              let binance, let anchor = binance.closes[first], anchor > 0, anchor.isFinite,
              PricePlausibility.sameToken(ownFirst, reference: anchor) else {
            return StitchedDaily(daily: own, binanceBefore: nil)
        }
        let earlier = binance.closes.filter { $0.key < first && $0.value > 0 && $0.value.isFinite }
        guard !earlier.isEmpty else { return StitchedDaily(daily: own, binanceBefore: nil) }
        let factor = ownFirst / anchor
        var closes = earlier.mapValues { $0 * factor }
        for (day, value) in own.closes { closes[day] = value }
        var ranges: [Int: PortfolioDayRange] = [:]
        for (day, r) in binance.ranges where day < first {
            let scaled = PortfolioDayRange(open: r.open * factor, high: r.high * factor, low: r.low * factor)
            if scaled.valid { ranges[day] = scaled }
        }
        for (day, r) in own.ranges { ranges[day] = r }
        return StitchedDaily(daily: PortfolioCoinDaily(closes: closes, ranges: ranges), binanceBefore: first)
    }

    /// Coins für den Hinweis «Binance-Verlauf skaliert» ab `fromDay`: ganz skaliert oder mit Binance vor
    /// einem ersten eigenen Tag nach `fromDay`; nur `coins`, alphabetisch — wie Android `scaledHint`.
    static func scaledHint(scaled: Set<String>, binanceBefore: [String: Int], fromDay: Int, coins: [String]) -> [String] {
        Array(Set(coins.map { PortfolioCalculator.normalizeCoin($0) }))
            .filter { coin in scaled.contains(coin) || (binanceBefore[coin].map { $0 > fromDay } ?? false) }
            .sorted()
    }

    /// «Kraken (BTC, ETH); Bitstamp (SOL)» — Börsen und Coins alphabetisch.
    static func sourcesText(_ sources: [String: String]) -> String {
        Dictionary(grouping: sources, by: { $0.value })
            .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { exchange, entries in "\(exchange) (\(entries.map(\.key).sorted().joined(separator: ", ")))" }
            .joined(separator: "; ")
    }

    /// Hängen die Werte der Gesamtkarte am Kurs USDT/USD? Ja bei Umrechnung in eine Anzeigewährung,
    /// bei USD oder wenn Kurse anderer Börsen in USD mit Binance-USDT gemischt werden. Mit USDT zum
    /// Marktkurs zählt das nur noch ohne Kurs (Ersatz 1, `DepegAlarm.cardNote`); bis dahin steht
    /// «USDT = 1» auf der Karte selbst. Wie Android `usdtParityShown`.
    static func usdtParityShown(displayCurrency: String, converted: Bool, sources: [String: String]) -> Bool {
        let currency = displayCurrency.trimmingCharacters(in: .whitespaces).uppercased()
        return converted || currency == "USD" || !sources.isEmpty
    }
}

/// Tageskerzen eines Coins: Schluss und Eröffnung/Hoch/Tief je UTC-Tag — wie Android `PortfolioCoinDaily`.
struct PortfolioCoinDaily: Equatable, Sendable {
    var closes: [Int: Double]
    var ranges: [Int: PortfolioDayRange] = [:]
}

/// Ergebnis von `PortfolioExchangeRoute.stitch`; `binanceBefore` = erster eigener Tag, davor Binance (nil = nur eigene).
struct StitchedDaily: Equatable, Sendable {
    var daily: PortfolioCoinDaily
    var binanceBefore: Int?
}

/// Ergebnis von `PortfolioExchangeRoute.historyCloses`; `scaled` = Coins mit skaliertem Binance-Verlauf.
struct RoutedHistory: Sendable {
    var closes: [String: [Int: Double]]
    var ranges: [String: [Int: PortfolioDayRange]]
    var scaled: Set<String>
}

/// Ein aufgelöster Weg: Coin → Paar an einer Spot-Börse.
struct PortfolioRoute: Codable, Equatable, Sendable {
    var marketKey: String
    var marketName: String
    var base: String
    var quote: String
    var pairId: String?
}

/// Aufgelöste Wege in der App Group (die App schreibt sie, Widget und Hintergrund lesen sie).
enum PortfolioExchangeRouteStore {
    private struct Stored: Codable {
        var routes: [String: PortfolioRoute]
        /// Coin → eingetragene Börse, für die die App kein Paar fand (dann Binance mit Hinweis).
        var unresolved: [String: String]
        var mixed: [String]
    }

    private static let key = "portfolio_exchange_routes_v1"

    static func save(routes: [String: PortfolioRoute], unresolved: [String: String], mixed: Set<String>) {
        let value = Stored(routes: routes, unresolved: unresolved, mixed: mixed.sorted())
        if let data = try? JSONEncoder().encode(value) { SharedStorage.defaults.set(data, forKey: key) }
    }

    static func load() -> (routes: [String: PortfolioRoute], unresolved: [String: String], mixed: Set<String>) {
        guard let data = SharedStorage.defaults.data(forKey: key),
              let value = try? JSONDecoder().decode(Stored.self, from: data) else { return ([:], [:], []) }
        return (value.routes, value.unresolved, Set(value.mixed))
    }

    static func clear() { SharedStorage.defaults.removeObject(forKey: key) }
}
