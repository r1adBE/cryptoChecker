import XCTest
@testable import CryptoChecker

/// Binance-Sammelabfrage mit Halbieren bei HTTP 400 (`BinanceBatchSplit`, `PortfolioPriceSource.binanceBatch`) und die
/// Einteilung «Kurs ungeprüft» / «Kein Markt» (`PortfolioPriceIssue.nameUnchecked`) — Fixture `binance_batch_split.json`.
/// Android: `BinanceBatchSplitTest.kt`, `PortfolioPriceSourceTest.kt`.
final class BinanceBatchSplitTests: XCTestCase {
    /// Gefragte Teile der Attrappe, in Reihenfolge.
    private actor Asked {
        var parts: [[String]] = []
        func add(_ part: [String]) { parts.append(part) }
    }

    /// Attrappe: Teil mit `failing` scheitert, sonst Teil mit `unknown` → 400, sonst die Kurse aus `tickers`.
    private func reply(_ part: [String], tickers: [String: Double], unknown: Set<String>,
                       failing: Set<String>) -> BinanceBatchSplit.Reply {
        if part.contains(where: failing.contains) { return .failed }
        if part.contains(where: unknown.contains) { return .rejected }
        var prices: [String: Double] = [:]
        for coin in part { if let p = tickers[coin] { prices[coin] = p } }
        return .prices(prices)
    }

    func testParityCases() async throws {
        let data = try TestSupport.fixture("binance_batch_split")
        let tickers = try XCTUnwrap(data["tickers"] as? [String: Double])
        for c in TestSupport.list(data["cases"]) {
            let name = c["case"] as? String ?? ""
            let unknown = Set(c["unknown"] as? [String] ?? [])
            let failing = Set(c["failing"] as? [String] ?? [])
            let asked = Asked()
            let got = await BinanceBatchSplit.fetch(c["coins"] as? [String] ?? [],
                                                    maxSymbols: c["maxSymbols"] as? Int ?? 0,
                                                    budget: c["budget"] as? Int ?? 0) { part in
                await asked.add(part)
                return self.reply(part, tickers: tickers, unknown: unknown, failing: failing)
            }
            XCTAssertEqual(got.prices, c["prices"] as? [String: Double] ?? [:], name)
            XCTAssertEqual(got.failed, c["failed"] as? Bool, name)
            XCTAssertEqual(got.unlisted, Set(c["unlisted"] as? [String] ?? []), name)
            XCTAssertEqual(got.requests, c["requests"] as? Int, name)
            let count = await asked.parts.count
            XCTAssertEqual(count, got.requests, name)
        }
    }

    /// Erste Aktualisierung (frisch installiert, Erfassen-Blatt nie offen, keine Coin-Liste): IOTA und BTC bekommen ihren
    /// Binance-Kurs, obwohl ICX und WTC Binance unbekannt sind — nicht gescheitert, also nicht «nicht abrufbar».
    func testFirstRefreshWithoutListPricesKnownCoins() async throws {
        try XCTSkipIf(BlockedSources.isBlocked(PortfolioPriceSource.binanceHost), "Binance-Spiegel gesperrt")
        // Frischer Prozess: kein gemerktes «Binance führt ihn nicht»
        PortfolioPriceSource.state.withLock { $0.binanceUnlisted = [:] }
        let asked = Asked()
        let tickers = ["BTC": 60000.0, "IOTA": 0.21]
        let got = await PortfolioPriceSource.binanceBatch(["ICX", "IOTA", "WTC", "BTC"], known: nil) { part in
            await asked.add(part)
            return self.reply(part, tickers: tickers, unknown: ["ICX", "WTC"], failing: [])
        }
        XCTAssertEqual(got.prices, ["IOTA": 0.21, "BTC": 60000.0])
        XCTAssertFalse(got.failed)
        XCTAssertEqual(got.unlisted, ["ICX", "WTC"])
        // Erste Abfrage mit allen Coins (kein Filter), danach nur Hälften
        let parts = await asked.parts
        XCTAssertEqual(parts.first, ["ICX", "IOTA", "WTC", "BTC"])
        XCTAssertTrue(parts.dropFirst().allSatisfy { $0.count < 4 })
        // Kein Beleg für «nicht abrufbar»: die Kerzen-Kette ohne Kurs heisst «kein Kurs» (bzw. «Kein Markt»)
        XCTAssertEqual(PortfolioPriceIssue.of(failed: false, allSourcesBlocked: false, networkFailed: got.failed), .noPrice)
    }

    /// Weg (b) ohne jeden Kurs → «Kein Markt»; Weg (a) und gescheiterte Abfrage → «Kurs ungeprüft»; verworfen → keiner.
    func testNameUncheckedSplitsNoMarket() {
        let got = PortfolioPriceIssue.nameUnchecked(["ICX", "WTC", "NOREF", "DOWN", "OTHER"], rejected: ["OTHER"],
                                                    noPrice: ["ICX", "WTC", "PLAIN"])
        XCTAssertEqual(got.noMarket, ["ICX", "WTC"])
        XCTAssertEqual(got.unchecked, ["NOREF", "DOWN"])
    }
}
