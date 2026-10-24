import Foundation
import XCTest
@testable import CryptoChecker

/// Deterministischer Zufall (SplitMix64): gleiche Folge wie `SplitMix64` in `PerformanceData.kt` (Android).
struct PerformanceRandom {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// Gleichverteilt in [0, 1).
    mutating func nextDouble() -> Double { Double(next() >> 11) / 9_007_199_254_740_992.0 }

    /// Gleichverteilt in 0..<bound.
    mutating func nextInt(_ bound: Int) -> Int { Int((next() >> 33) % UInt64(bound)) }
}

/// Testdaten der Leistungstests: bis zu 2000 Paare (Obergrenze der Merkliste) aus 100 echten Coins × 20
/// Quote-Währungen, je Börse eindeutig, mit festem Seed. Paar `i` hat immer dieselbe Basis/Quote; in der gemischten
/// Merkliste steht es an der Börse `i % 5` (`markets`). Gleich aufgebaut wie `PerformanceData.kt` (Android).
enum PerformanceData {
    static let seed: UInt64 = 20_261_024

    /// Zeitpunkt des gespeicherten Kurses der Testpaare.
    static let updatedAt: Int64 = 1_792_100_000_000

    static let bases = [
        "BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "TON", "AVAX",
        "SHIB", "DOT", "LINK", "BCH", "NEAR", "POL", "LTC", "ICP", "UNI", "APT",
        "ETC", "XLM", "SUI", "FIL", "HBAR", "ATOM", "ARB", "OP", "IMX", "VET",
        "INJ", "RENDER", "GRT", "STX", "MKR", "AAVE", "ALGO", "THETA", "FTM", "RUNE",
        "EGLD", "SAND", "MANA", "AXS", "FLOW", "XTZ", "CHZ", "EOS", "NEO", "KAVA",
        "IOTA", "ZEC", "DASH", "CRV", "SNX", "LDO", "CAKE", "GALA", "ENJ", "1INCH",
        "COMP", "ZIL", "BAT", "QNT", "KSM", "WAVES", "CELO", "ROSE", "MINA", "GMX",
        "DYDX", "PEPE", "FLOKI", "BONK", "WIF", "JUP", "PYTH", "TIA", "SEI", "WLD",
        "ORDI", "ENA", "ETHFI", "JTO", "STRK", "ZRO", "NOT", "BOME", "PENDLE", "ONDO",
        "FET", "AR", "KAS", "OM", "TAO", "JASMY", "HOT", "ANKR", "CFX", "IOTX",
    ]

    static let quotes = [
        "USDT", "USDC", "FDUSD", "TUSD", "EUR", "TRY", "BRL", "JPY", "AUD", "GBP",
        "PLN", "RON", "ZAR", "UAH", "ARS", "MXN", "IDR", "COP", "CZK", "CHF",
    ]

    /// Ungefährer Wert einer Einheit der Quote-Währung in CHF (für die Kurs-Spalte, wie die Umrechnung der App).
    private static let chfPerQuote: [String: Double] = [
        "USDT": 0.80, "USDC": 0.80, "FDUSD": 0.80, "TUSD": 0.80, "EUR": 0.93, "TRY": 0.024,
        "BRL": 0.15, "JPY": 0.0053, "AUD": 0.52, "GBP": 1.07, "PLN": 0.22, "RON": 0.19,
        "ZAR": 0.045, "UAH": 0.019, "ARS": 0.0008, "MXN": 0.043, "IDR": 0.00005, "COP": 0.0002,
        "CZK": 0.037, "CHF": 1.0,
    ]

    /// Börsen der gemischten Merkliste: Schlüssel und Anzeigename.
    static let markets: [(key: String, name: String)] = [
        ("Binance", "Binance"), ("Kraken", "Kraken"), ("Bybit", "Bybit"), ("Okex", "OKX"), ("Coinbase", "Coinbase"),
    ]

    static let binance = markets[0]

    /// Gruppen (nil = keine; doppelt, damit etwa ein Viertel ohne Gruppe bleibt).
    static let groups: [String?] = [nil, nil, "Layer 1", "DeFi", "Memes", "Gaming", AutoGroup.tradFi, AutoGroup.dated]

    /// Gespeicherte Coin-Namen für die Suche («bitcoin» findet BTC).
    static let coinNames = [
        "BTC": "Bitcoin", "ETH": "Ethereum", "SOL": "Solana", "XRP": "XRP", "ADA": "Cardano",
        "DOGE": "Dogecoin", "DOT": "Polkadot", "LINK": "Chainlink", "PEPE": "Pepe", "AVAX": "Avalanche",
    ]

    static func base(_ index: Int) -> String { bases[index % bases.count] }

    static func quote(_ index: Int) -> String { quotes[(index / bases.count) % quotes.count] }

    /// Kennung wie bei Binance, z. B. «BTCUSDT».
    static func symbol(_ index: Int) -> String { base(index) + quote(index) }

    static func chf(perQuote quote: String) -> Double { chfPerQuote[quote] ?? 1 }

    /// Die ersten `count` Paare als Binance-Paarliste (Auswahl der Sammelaufnahme).
    static func binancePairs(_ count: Int) -> [MarketPair] {
        (0..<count).map { MarketPair(base($0), quote($0), symbol($0)) }
    }

    /// Merkliste mit `count` Paaren (höchstens 2000). `market` nil = gemischt über `markets`; sonst alle an dieser
    /// Börse. Etwa 5 % Favoriten, 5 % ohne 24-h-Wert, 2 % deaktiviert, 10 % mit Notiz.
    static func watches(_ count: Int, market: (key: String, name: String)? = nil) -> [Watch] {
        precondition(count <= bases.count * quotes.count, "höchstens \(bases.count * quotes.count) Paare")
        var rng = PerformanceRandom(seed: seed)
        return (0..<count).map { i -> Watch in
            let place = market ?? markets[i % markets.count]
            let price = pow(10.0, rng.nextDouble() * 8.0 - 4.0)
            let previous = price * (0.99 + rng.nextDouble() * 0.02)
            let change = (rng.nextDouble() - 0.5) * 16.0
            let noChange = rng.nextInt(20) == 0
            let favorite = rng.nextInt(20) == 0
            let group = groups[rng.nextInt(groups.count)]
            let note = rng.nextInt(10) == 0 ? String(format: "Einstieg bei %.4f", previous) : nil
            let disabled = rng.nextInt(50) == 0
            return Watch(id: Int64(i + 1), marketKey: place.key, marketName: place.name, baseAsset: base(i),
                         quoteAsset: quote(i), pairId: symbol(i), sortOrder: i, notificationEnabled: false,
                         lastPrice: price, previousPrice: previous, lastUpdate: updatedAt, favorite: favorite,
                         groupName: group, note: note, change24h: noChange ? nil : change, disabled: disabled)
        }
    }

    /// Neuer Kurs je Kennung für eine Aktualisierung: gespeicherter Kurs ±3 % (Reihenfolge der Merkliste).
    static func newPrices(_ watches: [Watch]) -> [(pairId: String, price: Double)] {
        var rng = PerformanceRandom(seed: seed + 2)
        return watches.compactMap { w -> (pairId: String, price: Double)? in
            guard let price = w.lastPrice, let pairId = w.pairId else { return nil }
            return (pairId, price * (0.97 + rng.nextDouble() * 0.06))
        }
    }

    /// Alarme: je Paar 1–3 (`perPair` nil) bzw. genau `perPair` — Kursmarke über/unter (Alarmwährung `currency`)
    /// und Prozent seit Bezug (jeder dritte ohne Bezug, bekommt also einen). Etwa jedes hundertste Paar hat eine
    /// schon überschrittene, scharfe Kursmarke (löst aus).
    static func alarms(_ watches: [Watch], currency: String? = nil, perPair: Int? = nil) -> [Alarm] {
        var rng = PerformanceRandom(seed: seed + 1)
        var out: [Alarm] = []
        out.reserveCapacity(watches.count * 3)
        var id: Int64 = 0
        for (i, w) in watches.enumerated() {
            guard let price = w.lastPrice else { continue }
            let count = perPair ?? (1 + rng.nextInt(3))
            for k in 0..<count {
                let u = rng.nextDouble()
                id += 1
                switch (i + k) % 4 {
                case 0:
                    out.append(Alarm(id: id, watchId: w.id, condition: .PRICE_ABOVE,
                                     threshold: i % 25 == 0 ? price * 0.5 : price * (1.1 + u), currency: currency))
                case 1:
                    out.append(Alarm(id: id, watchId: w.id, condition: .PRICE_BELOW, threshold: price * (0.9 - 0.5 * u),
                                     currency: currency))
                case 2:
                    out.append(Alarm(id: id, watchId: w.id, condition: .CHANGE_PERCENT_UP, threshold: 5 + 10 * u,
                                     referencePrice: i % 3 == 0 ? nil : price))
                default:
                    out.append(Alarm(id: id, watchId: w.id, condition: .CHANGE_PERCENT_DOWN, threshold: 5 + 10 * u,
                                     referencePrice: i % 3 == 0 ? nil : price))
                }
            }
        }
        return out
    }

    /// Ungefilterte Sammelantwort von Binance (`GET /api/v3/ticker/24hr`) mit einer Zeile je Eintrag von `prices`
    /// (Kennung → Kurs) — Felder und Zahlenformat wie die Aufzeichnung `recorded_binance.json`.
    static func binanceBulkBody(_ prices: [(pairId: String, price: Double)]) -> String {
        var rows: [String] = []
        rows.reserveCapacity(prices.count)
        for (index, entry) in prices.enumerated() {
            rows.append(binanceRow(entry.pairId, last: entry.price, index: index))
        }
        return "[" + rows.joined(separator: ",") + "]"
    }

    private static func binanceRow(_ symbol: String, last: Double, index: Int) -> String {
        let open = last * 1.0073
        let openTime: Int64 = 1_792_106_885_009 + Int64(index)
        let percent = String(format: "%.3f", (last / open - 1) * 100)
        return "{\"symbol\":\"\(symbol)\",\"priceChange\":\"\(decimal(last - open))\","
            + "\"priceChangePercent\":\"\(percent)\",\"weightedAvgPrice\":\"\(decimal(last * 1.0033))\","
            + "\"prevClosePrice\":\"\(decimal(open))\",\"lastPrice\":\"\(decimal(last))\",\"lastQty\":\"0.00009000\","
            + "\"bidPrice\":\"\(decimal(last * 0.9999))\",\"bidQty\":\"3.21350000\","
            + "\"askPrice\":\"\(decimal(last * 1.0001))\",\"askQty\":\"2.64886000\","
            + "\"openPrice\":\"\(decimal(open))\",\"highPrice\":\"\(decimal(last * 1.0159))\","
            + "\"lowPrice\":\"\(decimal(last * 0.9898))\",\"volume\":\"14757.89016000\","
            + "\"quoteVolume\":\"1208844990.00894550\",\"openTime\":\(openTime),\"closeTime\":\(openTime + 86_400_000),"
            + "\"firstId\":6766948266,\"lastId\":6770117321,\"count\":3169056}"
    }

    private static func decimal(_ value: Double) -> String { String(format: "%.8f", value) }
}

/// Zeitmessung der Leistungstests ohne XCTest-`measure` (das bräuchte Baseline-Dateien von Xcode): je Messung zuerst
/// `warmupRuns` Aufwärmläufe, dann der Median aus `measuredRuns` Läufen mit `ContinuousClock`; Eingaben je Lauf
/// frisch und ungemessen (`setup`).
///
/// Geprüft werden eine feste, grosszügige Obergrenze für `large` Paare (CI-Runner sind langsam und schwanken, die
/// Tests laufen als Debug-Build ohne Optimierung) und die Skalierung gegenüber `small` Paaren — höchstens
/// `scaleFactor`-mal so lang plus `scaleSlackMillis`. Linear wäre das 4-Fache, n·log n knapp das 5-Fache,
/// quadratisch das 16-Fache. Alle Werte stehen mit «[Performance]» im Testprotokoll. Wie `PerformanceTiming.kt`.
enum PerformanceTiming {
    /// Obergrenze der Merkliste.
    static let large = WatchlistLimits.maxPairs
    /// Vergleichsgrösse für die Skalierung.
    static let small = WatchlistLimits.maxPairs / 4
    static let warmupRuns = 2
    static let measuredRuns = 5
    static let scaleFactor = 5.0
    /// Feste Zugabe zur Skalierungsgrenze: kleine Messwerte schwanken absolut um einige Millisekunden.
    static let scaleSlackMillis = 20.0

    /// Median der Laufzeit von `block` in Millisekunden; `setup` liefert je Lauf frische Eingaben (nicht gemessen).
    static func medianMillis<S, R>(setup: () throws -> S, _ block: (S) throws -> R) rethrows -> Double {
        for _ in 0..<warmupRuns {
            let input = try setup()
            let result = try block(input)
            withExtendedLifetime(result) {}
        }
        let clock = ContinuousClock()
        var times: [Double] = []
        for _ in 0..<measuredRuns {
            let input = try setup()
            let started = clock.now
            let result = try block(input)
            let elapsed = started.duration(to: clock.now)
            withExtendedLifetime(result) {}
            times.append(millis(elapsed))
        }
        return times.sorted()[measuredRuns / 2]
    }

    /// Wie oben, ohne eigene Eingaben je Lauf.
    static func medianMillis<R>(_ block: () throws -> R) rethrows -> Double {
        try medianMillis(setup: {}, { _ in try block() })
    }

    /// Misst `measure` (Anzahl Paare → Median in ms) für `small` und `large` Paare, schreibt beide Werte ins
    /// Testprotokoll und prüft `limitMillis` für `large` sowie die Skalierung.
    static func assertScales(_ name: String, limitMillis: Double, file: StaticString = #filePath, line: UInt = #line,
                             _ measure: (Int) throws -> Double) rethrows {
        let smallMillis = try measure(small)
        let largeMillis = try measure(large)
        let scaleLimit = scaleFactor * smallMillis + scaleSlackMillis
        report(name, smallMillis, largeMillis, limitMillis, scale: "Skalierung ≤ \(format(scaleLimit)) ms")
        XCTAssertLessThanOrEqual(largeMillis, limitMillis,
                                 "\(name): \(format(largeMillis)) ms für \(large) Paare", file: file, line: line)
        XCTAssertLessThanOrEqual(largeMillis, scaleLimit,
                                 "\(name): \(format(largeMillis)) ms für \(large) Paare gegenüber "
                                     + "\(format(smallMillis)) ms für \(small) — wächst schneller als n·log n",
                                 file: file, line: line)
    }

    /// Nur die feste Grenze; das Verhältnis `large` zu `small` steht bloss im Protokoll. Für Abläufe, die
    /// bekanntlich schneller als linear wachsen (im jeweiligen Test begründet).
    static func assertLimitOnly(_ name: String, limitMillis: Double, file: StaticString = #filePath, line: UInt = #line,
                                _ measure: (Int) throws -> Double) rethrows {
        let smallMillis = try measure(small)
        let largeMillis = try measure(large)
        report(name, smallMillis, largeMillis, limitMillis, scale: "Skalierung nicht geprüft")
        XCTAssertLessThanOrEqual(largeMillis, limitMillis,
                                 "\(name): \(format(largeMillis)) ms für \(large) Paare", file: file, line: line)
    }

    /// Grösse (z. B. einer Sicherung) ins Testprotokoll.
    static func reportSize(_ name: String, pairs: Int, bytes: Int, limitBytes: Int) {
        print("[Performance] \(name): \(pairs) Paare \(bytes) Bytes (\(bytes / max(pairs, 1)) je Paar) · "
              + "Grenze \(limitBytes) Bytes")
    }

    private static func report(_ name: String, _ smallMillis: Double, _ largeMillis: Double, _ limitMillis: Double,
                               scale: String) {
        let ratio = smallMillis > 0 ? largeMillis / smallMillis : .nan
        print("[Performance] \(name): \(small) Paare \(format(smallMillis)) ms · \(large) Paare "
              + "\(format(largeMillis)) ms (×\(String(format: "%.1f", ratio))) · Grenze \(format(limitMillis)) ms · "
              + scale)
    }

    private static func millis(_ duration: Duration) -> Double {
        let parts = duration.components
        return Double(parts.seconds) * 1_000 + Double(parts.attoseconds) / 1e15
    }

    private static func format(_ millis: Double) -> String { String(format: "%.2f", millis) }
}
