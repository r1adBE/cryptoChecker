import XCTest
@testable import CryptoChecker

/// Widget-Erweiterung (~30 MB) lädt keine grossen ungefilterten Sammellisten (`LowMemoryBulk`).
final class LowMemoryBulkTests: XCTestCase {

    func testPlan() {
        // Gefiltert möglich: Sammelabfrage bleibt
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "Binance", pairs: 30, unfilteredOnly: false), .bulk)
        // Ungefiltert, wenige Paare: einzeln
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "GateIo", pairs: LowMemoryBulk.maxSingles, unfilteredOnly: true), .singles)
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "Bitstamp", pairs: 3, unfilteredOnly: true), .singles)
        // Ungefiltert, viele Paare: grosse Liste → auslassen (gespeicherter Kurs), kleine Liste → Sammelabfrage
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "GateIo", pairs: LowMemoryBulk.maxSingles + 1, unfilteredOnly: true), .skip)
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "Kucoin", pairs: 40, unfilteredOnly: true), .skip)
        XCTAssertEqual(LowMemoryBulk.plan(exchangeKey: "Bitstamp", pairs: 40, unfilteredOnly: true), .bulk)
    }

    func testUnfilteredDetection() throws {
        let gate = try XCTUnwrap(ExchangesConfig.exchange("GateIo"))
        let kucoin = try XCTUnwrap(ExchangesConfig.exchange("Kucoin"))
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        XCTAssertTrue(LowMemoryBulk.isUnfiltered(gate, pairIds: ["BTC_USDT", "ETH_USDT", "SOL_USDT"]))
        XCTAssertTrue(LowMemoryBulk.isUnfiltered(kucoin, pairIds: ["BTC-USDT", "ETH-USDT", "SOL-USDT"]))
        // Binance filtert bis 100 Symbole, darüber gilt die ungefilterte Liste
        XCTAssertFalse(LowMemoryBulk.isUnfiltered(binance, pairIds: ["BTCUSDT", "ETHUSDT", "SOLUSDT"]))
        let many = (0..<101).map { "C\($0)USDT" }
        XCTAssertTrue(LowMemoryBulk.isUnfiltered(binance, pairIds: many))
    }

    func testSkipsAlphaTokenList() {
        XCTAssertTrue(LowMemoryBulk.skipsRequest(url: BinanceAlpha.tokenListURL))
        XCTAssertFalse(LowMemoryBulk.skipsRequest(url: "https://api.binance.com/api/v3/ticker/24hr?symbols=%5B%22BTCUSDT%22%5D"))
    }
}
