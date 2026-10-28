import XCTest
@testable import CryptoChecker

/// `WatchIdentity` an den gemeinsamen Fällen `Parity/watch_identity.json` (wie `WatchIdentityTest`).
@MainActor
final class WatchIdentityTests: XCTestCase {

    private func key(_ spec: [String: Any]) -> WatchIdentity.Key {
        WatchIdentity.key(exchangeKey: spec["exchangeKey"] as? String ?? "",
                          base: spec["base"] as? String ?? "",
                          quote: spec["quote"] as? String ?? "",
                          contract: spec["contract"] as? String ?? "",
                          pairId: spec["pairId"] as? String)
    }

    func testSharedCases() throws {
        let data = try TestSupport.fixture("watch_identity")
        for c in TestSupport.list(data["cases"]) {
            let name = c["name"] as? String ?? ""
            let a = key(c["a"] as? [String: Any] ?? [:])
            let b = key(c["b"] as? [String: Any] ?? [:])
            XCTAssertEqual(a.pool, c["pool"] as? String, name)
            XCTAssertEqual(a == b, c["same"] as? Bool, name)
        }
    }

    private func pool(_ id: Int64, _ pairId: String) -> Watch {
        var watch = Watch(id: id, exchangeKey: "DexScreener", exchangeName: "DexScreener",
                          baseAsset: "PEPE", quoteAsset: "USD (ethereum)")
        watch.pairId = pairId
        return watch
    }

    func testTwoPoolsAreTwoPairsTheSamePoolIsOne() {
        XCTAssertFalse(pool(1, "ethereum/0xaaa").samePair(as: pool(2, "ethereum/0xbbb")))
        XCTAssertTrue(pool(1, "ethereum/0xaaa").samePair(as: pool(3, "ethereum/0xaaa")))
        XCTAssertNotEqual(WatchEdit.Key(pool(1, "ethereum/0xaaa")), WatchEdit.Key(pool(2, "ethereum/0xbbb")))
        XCTAssertNil(WatchIdentity.pool(exchangeKey: "Binance", pairId: "BTCUSDT"))
    }

    func testInsertAddsSecondPoolButNotTheSamePoolTwice() {
        var snapshot = SharedStorage.Snapshot()
        let exchange = DexScreener()
        let first = ExchangePair("PEPE", "USD (ethereum)", "ethereum/0xaaa")
        let second = ExchangePair("PEPE", "USD (ethereum)", "ethereum/0xbbb")
        XCTAssertNotNil(AppData.insert(&snapshot, exchange: exchange, pair: first))
        XCTAssertNotNil(AppData.insert(&snapshot, exchange: exchange, pair: second))
        XCTAssertNil(AppData.insert(&snapshot, exchange: exchange, pair: first))
        XCTAssertEqual(snapshot.watches.count, 2)
    }

    func testRestoreCleanupKeepsTwoPoolsButMergesTheSamePool() {
        let alarm = Alarm(id: 10, watchId: 3, condition: .PRICE_ABOVE, threshold: 1)
        let result = WatchlistRestoreCleanup.clean(
            watches: [pool(1, "ethereum/0xaaa"), pool(2, "ethereum/0xbbb"), pool(3, "ethereum/0xaaa")],
            alarms: [alarm])
        XCTAssertEqual(result.watches.map(\.id), [1, 2])
        // Alarm des weggelassenen Doppels hängt am behaltenen Pool
        XCTAssertEqual(result.alarms.first?.watchId, 1)
    }
}
