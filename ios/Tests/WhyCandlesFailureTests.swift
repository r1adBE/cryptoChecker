import XCTest
import os
@testable import CryptoChecker

/// V4-02: «Was bewegt sich mit?» ohne Kerzen – offline bzw. Zeitgrenze ist «nicht abrufbar» (mit «Erneut versuchen»),
/// nur sonst «keine Stundenkerzen für dieses Paar». Android: WhyCandlesFailureTest.
final class WhyCandlesFailureTests: XCTestCase {
    func testOfflineGehtVor() {
        XCTAssertEqual(WhyCandlesFailure.of(online: false, timedOut: false), .offline)
        XCTAssertEqual(WhyCandlesFailure.of(online: false, timedOut: true), .offline)
    }

    func testZeitgrenzeIstNichtAbrufbar() {
        XCTAssertEqual(WhyCandlesFailure.of(online: true, timedOut: true), .timeout)
    }

    func testGeantwortetOhneKerzenIstKeinFehler() {
        XCTAssertNil(WhyCandlesFailure.of(online: true, timedOut: false))
        XCTAssertNil(ActivityAnalyzer.notLive(now: 1).candlesFailure)
    }

    /// «Erneut versuchen» (`refresh`) fragt neu statt den gemerkten Leerstand zu liefern (Audit A-01, früher
    /// Quelltext-Wächter): echter `ActivityMonitor` über `ExchangeHTTP.testTransport`, ein unbekannter Coin hat keine
    /// Kerzen. Android: `WhyEmptyStateDeviceTest` (dort auch der Leerzustand mit der Börse des Paars).
    func testRetryAsksAgainInsteadOfTheRememberedEmptyState() async throws {
        let coin = "ZZQW\(Int.random(in: 1000...9999))"
        let requests = OSAllocatedUnfairLock(initialState: 0)
        ExchangeHTTP.testTransport.withLock {
            $0 = { url in
                if url.uppercased().contains(coin) { requests.withLock { $0 += 1 } }
                throw HttpExchangeError(httpCode: 404, body: "test")
            }
        }
        defer { ExchangeHTTP.testTransport.withLock { $0 = nil } }
        let watch = Watch(id: 1, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: coin, quoteAsset: "USDT",
                          lastPrice: 1.0)
        _ = await ActivityMonitor.explain(watch)
        let first = requests.withLock { $0 }
        XCTAssertGreaterThan(first, 0, "erste Abfrage ging nicht ans Netz")
        _ = await ActivityMonitor.explain(watch)
        XCTAssertEqual(requests.withLock { $0 }, first, "ohne «Erneut versuchen» gemerkter Leerstand")
        _ = await ActivityMonitor.explain(watch, refresh: true)
        XCTAssertGreaterThan(requests.withLock { $0 }, first, "«Erneut versuchen» fragte nicht neu")
    }
}
