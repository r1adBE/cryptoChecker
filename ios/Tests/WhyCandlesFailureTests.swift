import XCTest
import os
@testable import CryptoChecker

/// «Was bewegt sich mit?» ohne Kerzen – offline bzw. Zeitgrenze ist «nicht abrufbar» (mit «Erneut versuchen»),
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

    /// «Erneut versuchen» (`refresh`) fragt neu statt den gemerkten Leerstand zu liefern: echter `ActivityMonitor` über
    /// `ExchangeHTTP.testTransport`, ein unbekannter Coin hat keine Kerzen. Android: `WhyEmptyStateDeviceTest` (dort
    /// auch der Leerzustand mit der Börse des Paars).
    func testRetryAsksAgainInsteadOfTheRememberedEmptyState() async throws {
        let coin = "ZZQW\(Int.random(in: 1000...9999))"
        let requests = OSAllocatedUnfairLock(initialState: [String]())
        ExchangeHTTP.testTransport.withLock {
            $0 = { url in
                if url.uppercased().contains(coin) { requests.withLock { $0.append(url) } }
                throw HttpExchangeError(httpCode: 404, body: "test")
            }
        }
        defer { ExchangeHTTP.testTransport.withLock { $0 = nil } }
        let watch = Watch(id: 1, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: coin, quoteAsset: "USDT",
                          lastPrice: 1.0)
        func asked(_ refresh: Bool) async -> Set<String> {
            let before = requests.withLock { $0.count }
            _ = await ActivityMonitor.explain(watch, refresh: refresh)
            return Set(requests.withLock { Array($0[before...]) })
        }
        let first = await asked(false)
        XCTAssertFalse(first.isEmpty, "erste Abfrage ging nicht ans Netz")
        // Ohne «Erneut versuchen»: was gemerkt ist (mindestens die Kerzen), wird nicht neu gefragt
        let again = await asked(false)
        let remembered = first.subtracting(again)
        XCTAssertFalse(remembered.isEmpty, "nichts gemerkt")
        // «Erneut versuchen» fragt das Gemerkte neu
        let retry = await asked(true)
        XCTAssertFalse(remembered.isDisjoint(with: retry), "«Erneut versuchen» fragte nicht neu")
    }
}
