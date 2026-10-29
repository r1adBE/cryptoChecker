import XCTest
import os
@testable import CryptoChecker

/// Steuer-Export: die Tagesschlüsse je Coin auf einmal (`HistoricPriceSource.dayClosesUsdt`, Kerzen je Quelle in Stücken)
/// sind für dieselben Daten genau die der Einzelabfrage je (Coin, Tag) (`dailyCloseUsdt`, bisheriger Weg): gleicher
/// Schluss, gleiche Quelle, gleiches «ungeprüft» — über einen gespielten Markt, der beide Abfragearten aus denselben
/// Kerzen beantwortet (Binance `startTime`/`endTime`/`limit`, Coinbase `start`/`end`). Spiegel: HistoricDayClosesBatchTest.kt.
final class HistoricDayClosesBatchTests: XCTestCase {
    private static let dayMillis: Int64 = 86_400_000
    private let first = LocalDay(2021, 1, 1)
    private let last = LocalDay(2024, 6, 30)

    /// Gespielter Markt: Binance-Reihen je «Host|Symbol», Coinbase je Produkt; was fehlt, ist unbekannt (keine Antwort).
    private final class Market: Sendable {
        let binance: [String: [Int64: Double]]
        let coinbase: [String: [Int64: Double]]
        let requested = OSAllocatedUnfairLock(initialState: [String]())

        init(binance: [String: [Int64: Double]], coinbase: [String: [Int64: Double]]) {
            self.binance = binance
            self.coinbase = coinbase
        }

        func count(_ part: String) -> Int { requested.withLock { $0.filter { $0.contains(part) }.count } }
        var total: Int { requested.withLock { $0.count } }

        func reply(_ url: String) -> String? {
            requested.withLock { $0.append(url) }
            guard let c = URLComponents(string: url), let host = c.host else { return nil }
            func param(_ name: String) -> String? { c.queryItems?.first { $0.name == name }?.value }
            if host == "api.exchange.coinbase.com" {
                let parts = c.path.split(separator: "/")
                guard parts.count >= 2, let all = coinbase[String(parts[1])] else { return nil }
                var picked = all.sorted { $0.key < $1.key }
                if let start = param("start").flatMap(Self.iso), let end = param("end").flatMap(Self.iso) {
                    picked = picked.filter { $0.key >= start && $0.key <= end }
                } else {
                    picked = Array(picked.suffix(300))
                }
                // NEUESTE zuerst wie Coinbase
                return "[" + picked.reversed().map { "[\($0.key / 1000),\($0.value),\($0.value),\($0.value),\($0.value),2.0]" }
                    .joined(separator: ",") + "]"
            }
            guard host.contains("binance"), c.path.hasSuffix("/klines"),
                  let all = binance["\(host)|\(param("symbol") ?? "")"] else { return nil }
            let limit = param("limit").flatMap { Int($0) } ?? 500
            var picked = all.sorted { $0.key < $1.key }
            if let start = param("startTime").flatMap({ Int64($0) }) {
                let end = param("endTime").flatMap { Int64($0) }
                picked = Array(picked.filter { $0.key >= start && (end == nil || $0.key <= (end ?? 0)) }.prefix(limit))
            } else {
                picked = Array(picked.suffix(limit))
            }
            return "[" + picked.map { "[\($0.key),\"\($0.value)\",\"\($0.value)\",\"\($0.value)\",\"\($0.value)\",\"10.0\"," +
                "\($0.key + HistoricDayClosesBatchTests.dayMillis - 1),\"1\"]" }.joined(separator: ",") + "]"
        }

        private static func iso(_ text: String) -> Int64? {
            guard let date = ISO8601DateFormatter().date(from: text) else { return nil }
            return Int64(date.timeIntervalSince1970 * 1000)
        }
    }

    /// Tage von `from` bis `to` ohne die, die `skip` ausnimmt; Schluss nach `close`.
    private func series(_ from: LocalDay, _ to: LocalDay, skip: (Int) -> Bool = { _ in false },
                        close: (Int) -> Double) -> [Int64: Double] {
        var out: [Int64: Double] = [:]
        for (i, day) in (from.epochDay...to.epochDay).enumerated() where !skip(i) {
            out[LocalDay(epochDay: day).epochMillisUTC] = close(i)
        }
        return out
    }

    private func market() -> Market {
        let eth = series(first, last) { 1000.0 + Double($0) * 1.5 }
        let newSpot = series(LocalDay(2023, 3, 1), last) { 8.0 + Double($0) * 0.01 }
        let gapSpot = series(first, last, skip: { $0 % 7 == 3 }) { 2.0 + Double($0 % 5) * 0.01 }
        return Market(
            binance: [
                "data-api.binance.vision|ETHUSDT": eth, "api.binance.com|ETHUSDT": eth,
                "data-api.binance.vision|NEWUSDT": newSpot, "api.binance.com|NEWUSDT": newSpot,
                // FOO: Binance-Spot führt unter dem Kürzel einen anderen Token (jüngster Schluss 0.5 gegen 50)
                "data-api.binance.vision|FOOUSDT": series(first, last) { _ in 0.5 },
                "api.binance.us|FOOUSD": series(LocalDay(2022, 1, 1), last) { 40.0 + Double($0) * 0.01 },
                "data-api.binance.vision|GAPUSDT": gapSpot, "api.binance.com|GAPUSDT": gapSpot,
                "fapi.binance.com|GAPUSDT": series(first, last) { 2.05 + Double($0 % 3) * 0.01 },
                "data-api.binance.vision|BARUSDT": series(first.plusDays(-40), last) { _ in 0.01 },
            ],
            coinbase: [
                "NEW-USD": series(LocalDay(2021, 6, 1), last) { 7.5 + Double($0) * 0.003 },
                "FOO-USDC": series(first, last) { 45.0 + Double($0) * 0.005 },
                // BAR über alle verlangten Tage (auch die vor `first`): überall ein anderer Token
                "BAR-USD": series(first.plusDays(-40), last) { _ in 0.02 },
            ])
    }

    /// Verlangte Tage: jeder 9. über die ganze Spanne (mehrere Stücke je Quelle) und ein Block um den Jahreswechsel.
    private func days() -> Set<Int> {
        var out = Set(stride(from: first.epochDay - 20, through: last.epochDay, by: 9))
        out.formUnion(LocalDay(2022, 12, 25).epochDay...LocalDay(2023, 1, 5).epochDay)
        return out
    }

    private func fetch(_ m: Market) -> HistoricDayFetch {
        HistoricDayFetch(get: { _, url in m.reply(url) }, series: { _, _ in nil }, fx: { _, _ in nil })
    }

    func testBatchedClosesEqualPerDayCloses() async throws {
        for host in ["data-api.binance.vision", "api.binance.com", "fapi.binance.com", "api.binance.us", "api.exchange.coinbase.com"] {
            try XCTSkipIf(BlockedSources.isBlocked(host), "\(host) gesperrt")
        }
        let coins = ["ETH", "eth", "NEW", "FOO", "GAP", "BAR", "NOREF", "USDT", "BAD COIN"]
        let wanted = Dictionary(uniqueKeysWithValues: coins.map { ($0, days()) })
        // Aktuelle Kurse wie CutoffExport.referencePrices: Schlüssel wie verlangt; NOREF ohne Bezug
        let current: [String: Double] = ["ETH": 3100, "eth": 3100, "NEW": 10.5, "FOO": 50, "GAP": 2, "BAR": 5, "BAD COIN": 1]

        let perDayMarket = market()
        var perDay: [String: [Int: HistoricPriceSource.CloseResult]] = [:]
        for coin in coins {
            for epochDay in wanted[coin] ?? [] {
                let d = LocalDay(epochDay: epochDay)
                perDay[coin, default: [:]][epochDay] = await HistoricPriceSource.dailyCloseUsdt(
                    coin, day: CutoffDay(year: d.year, month: d.month, day: d.day), current: current[coin],
                    fetch: fetch(perDayMarket))
            }
        }

        let batchMarket = market()
        let batched = await HistoricPriceSource.dayClosesUsdt(wanted, current: current, fetch: fetch(batchMarket))

        XCTAssertEqual(perDay, batched)
        // Alle Wege kommen vor: Binance-Spot, Futures, Binance.US, Coinbase, keiner, «ungeprüft», USDT = 1
        let sources = Set(perDay.values.flatMap { $0.values }.compactMap(\.source))
        XCTAssertTrue(sources.isSuperset(of: [DataFreshness.binance, DataFreshness.binanceFutures, DataFreshness.binanceUS,
                                              DataFreshness.coinbase]), "\(sources)")
        XCTAssertTrue(perDay["NEW"]?.values.contains { $0.price == nil } ?? false)
        XCTAssertTrue(perDay["BAR"]?.values.allSatisfy { $0.price == nil && $0.unchecked } ?? false)
        XCTAssertTrue(perDay["NOREF"]?.values.allSatisfy(\.unchecked) ?? false)
        XCTAssertTrue(perDay["USDT"]?.values.allSatisfy { $0.price == 1 } ?? false)
        // ETH und eth: je zwei Stücke (mehr als 1000 Tage) und ein jüngster Schluss; insgesamt ein Bruchteil der Abfragen
        XCTAssertEqual(batchMarket.count("data-api.binance.vision/api/v3/klines?symbol=ETHUSDT"), 6)
        XCTAssertLessThan(batchMarket.total * 10, perDayMarket.total)
    }
}
