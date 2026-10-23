import XCTest
@testable import CryptoChecker

/// Begrenzter Zwischenspeicher (`BoundedCache`) — wie `BoundedCacheTest.kt` (Android).
final class BoundedCacheTests: XCTestCase {

    func testHoechstensMaxEntriesDerAmLaengstenNichtBenutzteFaelltHeraus() {
        var cache = BoundedCache<String, Int>(maxEntries: 3)
        cache.set("A", 1)
        cache.set("B", 2)
        cache.set("C", 3)
        // A benutzt: B ist jetzt der älteste
        XCTAssertEqual(cache.get("A"), 1)
        cache.set("D", 4)
        XCTAssertEqual(cache.count, 3)
        XCTAssertNil(cache.get("B"))
        XCTAssertEqual(cache.get("A"), 1)
        XCTAssertEqual(cache.get("D"), 4)
    }

    func testVielePaareBleibenBegrenzt() {
        var cache = BoundedCache<String, Int>()
        XCTAssertEqual(cache.maxEntries, 512)
        for i in 0..<2_000 { cache.set("P\(i)", i) }
        XCTAssertEqual(cache.count, 512)
        XCTAssertEqual(cache.get("P1999"), 1_999)
        XCTAssertNil(cache.get("P0"))
    }

    func testEntfernen() {
        var cache = BoundedCache<String, Int>(maxEntries: 2)
        cache.set("A", 1)
        XCTAssertEqual(cache.removeValue(forKey: "A"), 1)
        XCTAssertNil(cache.get("A"))
        cache.set("B", 2)
        cache.removeAll()
        XCTAssertEqual(cache.count, 0)
    }

    /// Eigene Kerzen (`OwnCandleDataSource.invalidate`): alle Einträge eines Paars entfernen.
    func testRemoveKeysWhere() {
        var cache = BoundedCache<String, Int>(maxEntries: 4)
        cache.set("A", 1)
        cache.set("BTC|1", 3)
        cache.set("BTC|2", 4)
        cache.removeKeys { $0.hasPrefix("BTC|") }
        XCTAssertEqual(cache.count, 1)
        XCTAssertEqual(cache.get("A"), 1)
    }
}
