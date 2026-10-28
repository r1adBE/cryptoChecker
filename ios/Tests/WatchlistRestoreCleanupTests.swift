import XCTest
@testable import CryptoChecker

/// Bereinigung der Merkliste vor dem Wiederherstellen (`BackupManager.restore`) —
/// wie `WatchlistRestoreCleanupTest.kt`.
final class WatchlistRestoreCleanupTests: XCTestCase {

    private struct W: Equatable {
        let id: Int64
        let pair: String
        var tag: String = ""
    }

    private struct A: Equatable {
        let id: Int64
        var watchId: Int64
        var tag: String = ""
    }

    private func clean(_ watches: [W], _ alarms: [A]) -> (watches: [W], alarms: [A]) {
        WatchlistRestoreCleanup.clean(
            watches: watches, alarms: alarms,
            watchId: { $0.id }, pairKey: { $0.pair },
            alarmId: { $0.id }, alarmWatchId: { $0.watchId },
            withWatchId: { a, id in
                var moved = a
                moved.watchId = id
                return moved
            })
    }

    func testCleanBackupStaysUnchanged() {
        let watches = [W(id: 1, pair: "BTC"), W(id: 2, pair: "ETH")]
        let alarms = [A(id: 10, watchId: 1), A(id: 11, watchId: 2)]
        let result = clean(watches, alarms)
        XCTAssertEqual(result.watches, watches)
        XCTAssertEqual(result.alarms, alarms)
    }

    func testSameIdTwiceLastWinsAtFirstPosition() {
        let result = clean([W(id: 1, pair: "BTC", tag: "alt"), W(id: 2, pair: "ETH"), W(id: 1, pair: "BTC", tag: "neu")], [])
        XCTAssertEqual(result.watches, [W(id: 1, pair: "BTC", tag: "neu"), W(id: 2, pair: "ETH")])
    }

    func testSamePairUnderTwoIdsKeepsFirstAndMovesAlarms() {
        let result = clean([W(id: 1, pair: "BTC"), W(id: 2, pair: "BTC")],
                           [A(id: 10, watchId: 1), A(id: 11, watchId: 2)])
        XCTAssertEqual(result.watches, [W(id: 1, pair: "BTC")])
        XCTAssertEqual(result.alarms, [A(id: 10, watchId: 1), A(id: 11, watchId: 1)])
    }

    func testOrphanAlarmsAreDroppedAndSameAlarmIdLastWins() {
        let result = clean([W(id: 1, pair: "BTC")],
                           [A(id: 10, watchId: 1, tag: "alt"), A(id: 11, watchId: 99), A(id: 10, watchId: 1, tag: "neu")])
        XCTAssertEqual(result.alarms, [A(id: 10, watchId: 1, tag: "neu")])
    }

    /// Anders als Android: Id 0 heisst auf iOS nicht «neu vergeben» — gleiche Id 0 wird wie jede
    /// andere zusammengelegt (sonst stünden zwei Paare mit derselben Id in der Merkliste).
    func testIdZeroIsAnOrdinaryId() {
        let result = clean([W(id: 0, pair: "BTC", tag: "alt"), W(id: 0, pair: "BTC", tag: "neu"), W(id: 3, pair: "SOL")],
                           [A(id: 12, watchId: 0), A(id: 13, watchId: 3)])
        XCTAssertEqual(result.watches, [W(id: 0, pair: "BTC", tag: "neu"), W(id: 3, pair: "SOL")])
        XCTAssertEqual(result.alarms, [A(id: 12, watchId: 0), A(id: 13, watchId: 3)])
    }

    /// Echte Paare: Börse, Basis, Quote und Kontrakt bilden den Schlüssel (wie `Watch.samePair`).
    func testWatchPairKeyIncludesContractType() {
        let spot = Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        let spotAgain = Watch(id: 2, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        let perp = Watch(id: 3, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
                         contractType: .perpetual)
        let otherExchange = Watch(id: 4, exchangeKey: "kraken", exchangeName: "Kraken", baseAsset: "BTC", quoteAsset: "USDT")
        let alarm = Alarm(id: 20, watchId: 2, condition: .PRICE_ABOVE, threshold: 100_000)

        let result = WatchlistRestoreCleanup.clean(watches: [spot, spotAgain, perp, otherExchange], alarms: [alarm])
        XCTAssertEqual(result.watches.map(\.id), [1, 3, 4])
        XCTAssertEqual(result.alarms.map(\.id), [20])
        XCTAssertEqual(result.alarms.first?.watchId, 1)
    }
}
