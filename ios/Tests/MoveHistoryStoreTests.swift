import XCTest
@testable import CryptoChecker

/// Verlauf des Bewegungs-Alarms mit einem Schlüssel je Paar (seit Build 102, wie `MoveHistoryCoreTest.kt`): append für
/// N Paare bleibt O(N).
final class MoveHistoryStoreTests: XCTestCase {
    private final class FakeStorage: MoveHistoryStorage {
        var map: [String: String]
        var writes = 0
        init(_ initial: [String: String] = [:]) { map = initial }
        func read(_ key: String) -> String? { map[key] }
        func write(_ changes: [String: String?]) {
            writes += 1
            for (key, value) in changes { map[key] = value }
        }
        func keys() -> [String] { Array(map.keys) }
    }

    private let hour: Int64 = 3_600_000

    func testAppendForNPairsDecodesOnlyOwnHistory() {
        let core = MoveHistoryCore(storage: FakeStorage())
        let n = 200
        for id in 1...n { core.append(Int64(id), MoveWindow.PricePoint(price: 100, time: 1_000), now: 1_000) }
        let afterFirst = core.decodes
        for id in 1...n { core.append(Int64(id), MoveWindow.PricePoint(price: 101, time: 301_000), now: 301_000) }
        XCTAssertEqual(core.decodes - afterFirst, n)
        XCTAssertEqual(core.history(7).count, 2)
    }

    func testLegacyKeyIsSplitOnce() {
        let legacy = #"{"1":[[1000,100.0],[200000,101.0]],"2":[[1000,50.0]],"x":[[1,1]]}"#
        let storage = FakeStorage([MoveHistoryCore.legacyKey: legacy])
        let core = MoveHistoryCore(storage: storage)
        XCTAssertEqual(core.history(1).map(\.price), [100, 101])
        XCTAssertEqual(core.history(2).count, 1)
        XCTAssertNil(storage.map[MoveHistoryCore.legacyKey])
        XCTAssertNil(storage.map[MoveHistoryCore.prefix + "x"])
    }

    func testExpiredForeignHistoriesAreCleanedUp() {
        let now = 40 * hour
        let storage = FakeStorage([
            MoveHistoryCore.key(5): "[[\(now - 32 * hour),10.0]]",
            MoveHistoryCore.key(6): "[[\(now - hour),10.0]]",
            "anders": "bleibt",
        ])
        let core = MoveHistoryCore(storage: storage)
        core.append(1, MoveWindow.PricePoint(price: 100, time: now), now: now)
        XCTAssertNil(storage.map[MoveHistoryCore.key(5)])
        XCTAssertNotNil(storage.map[MoveHistoryCore.key(6)])
        XCTAssertEqual(storage.map["anders"], "bleibt")
        XCTAssertNotNil(storage.map[MoveHistoryCore.key(1)])
    }

    func testNoWriteWithoutNewPoint() {
        let storage = FakeStorage()
        let core = MoveHistoryCore(storage: storage)
        core.append(1, MoveWindow.PricePoint(price: 100, time: 1_000), now: 1_000)
        let writes = storage.writes
        core.append(1, MoveWindow.PricePoint(price: 100.5, time: 11_000), now: 11_000)
        XCTAssertEqual(storage.writes, writes)
    }
}
