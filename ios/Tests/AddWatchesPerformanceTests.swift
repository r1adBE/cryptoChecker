import XCTest
@testable import CryptoChecker

/// Leistung von «Mehrere Paare auf einmal» unter iOS: `AppData.addWatches` legt alle Paare in einem Schritt auf dem
/// Main-Thread an, über `WatchInsertion.insertAll`. Gemessen: bis zu 2000 Paare in eine leere Merkliste. Vorhandene
/// Identitäten, höchste Id und nächster Platz werden einmal vorab berechnet, der Durchlauf ist also linear — darum mit
/// Skalierungsprüfung 2000 gegen 500 Paare (`PerformanceTiming`, Median).
final class AddWatchesPerformanceTests: XCTestCase {
    private typealias T = PerformanceTiming

    func testBulkInsertIntoEmptyWatchlist() throws {
        let market = try XCTUnwrap(MarketsConfig.market(PerformanceData.binance.key))
        // Erwartet wenige 10 ms (Debug); Grenze grosszügig
        T.assertScales("Sammelaufnahme in leere Merkliste", limitMillis: 1_000) { n in
            let pairs = PerformanceData.binancePairs(n)
            return T.medianMillis(setup: { SharedStorage.Snapshot() }) { start -> Int in
                var s = start
                return WatchInsertion.insertAll(&s, market: market, pairs: pairs).added
            }
        }
        // Alle angelegt, Ids und Plätze fortlaufend
        var s = SharedStorage.Snapshot()
        let result = WatchInsertion.insertAll(&s, market: market, pairs: PerformanceData.binancePairs(T.large))
        XCTAssertEqual(result.added, T.large)
        XCTAssertEqual(result.refused, 0)
        XCTAssertEqual(s.watches.map(\.id), (1...Int64(T.large)).map { $0 })
        XCTAssertEqual(s.watches.map(\.sortOrder), Array(0..<T.large))
    }
}
