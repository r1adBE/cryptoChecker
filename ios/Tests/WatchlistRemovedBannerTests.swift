import XCTest
@testable import CryptoChecker

/// Zweites Wisch-Löschen, solange der Banner steht: ein Sammel-Banner, «Rückgängig» holt beide zurück —
/// wie `RemovedBatchTest.kt`.
final class WatchlistRemovedBannerTests: XCTestCase {

    private func deleted(_ id: Int64, _ base: String) -> DeletedWatch {
        DeletedWatch(
            watch: Watch(id: id, marketKey: "binance", marketName: "Binance", baseAsset: base, quoteAsset: "USDT"),
            alarms: [], activity: nil, selectedGroup: nil
        )
    }

    func testErstesLoeschenZeigtEinzelBannerMitRueckgaengig() {
        let message = WatchlistBannerMessage.removed(deleted(1, "BTC"), after: nil) { _ in }
        XCTAssertEqual(message.removed.map(\.watch.id), [1])
        XCTAssertEqual(message.undo?.watch.id, 1)
        XCTAssertNil(message.action)
    }

    func testZweitesLoeschenFasstBeideZusammen() {
        var restored: [Int64] = []
        let first = WatchlistBannerMessage.removed(deleted(1, "BTC"), after: nil) { _ in }
        let second = WatchlistBannerMessage.removed(deleted(2, "ETH"), after: first) { all in
            restored = all.map(\.watch.id)
        }
        XCTAssertEqual(second.removed.map(\.watch.id), [1, 2])
        XCTAssertNil(second.undo)
        XCTAssertEqual(second.text, L("watchlist_removed_count", count: 2))
        second.action?.perform()
        XCTAssertEqual(restored, [1, 2])
    }

    func testAnderesBannerDavorWirdNichtMitgezaehlt() {
        let other = WatchlistBannerMessage(text: "Favorit")
        let message = WatchlistBannerMessage.removed(deleted(3, "SOL"), after: other) { _ in }
        XCTAssertEqual(message.removed.map(\.watch.id), [3])
        XCTAssertNotNil(message.undo)
    }
}
