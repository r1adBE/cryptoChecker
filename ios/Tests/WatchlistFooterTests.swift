import XCTest
@testable import CryptoChecker

/// Fusszeile am Ende der Merkliste (`Parity/watchlist_footer.json`) — gleiche Fälle wie `WatchlistFooterTest.kt`.
final class WatchlistFooterTests: XCTestCase {
    private typealias S = TestSupport

    func testGrenzeWieInDerApp() throws {
        let fixture = try S.fixture("watchlist_footer")
        XCTAssertEqual(S.number(fixture["maxPairs"]).map { Int($0) }, WatchlistLimits.maxPairs)
    }

    func testFusszeileWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("watchlist_footer")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let shown = Int(try XCTUnwrap(S.number(c["shown"]), name))
            let total = Int(try XCTUnwrap(S.number(c["total"]), name))
            let selecting = try XCTUnwrap(c["selecting"] as? Bool, name)
            let actual = S.number(c["max"]).map {
                WatchlistFooter.of(shown: shown, total: total, selecting: selecting, max: Int($0))
            } ?? WatchlistFooter.of(shown: shown, total: total, selecting: selecting)
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(actual, name)
                continue
            }
            let expected = WatchlistFooter(shown: shown, total: total,
                                           filtered: try XCTUnwrap(e["filtered"] as? Bool, name),
                                           remaining: S.number(e["remaining"]).map { Int($0) })
            XCTAssertEqual(actual, expected, name)
        }
    }

    func testNaheDerGrenzeWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("watchlist_footer")["nearLimit"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let free = Int(try XCTUnwrap(S.number(c["free"])))
            let max = Int(try XCTUnwrap(S.number(c["max"])))
            XCTAssertEqual(WatchlistFooter.isNearLimit(free: free, max: max), c["expected"] as? Bool,
                           "\(free) frei von \(max)")
        }
    }

    func testZehnPlaetzeBeiDerGrenzeDerApp() {
        XCTAssertTrue(WatchlistFooter.isNearLimit(free: 10, max: WatchlistLimits.maxPairs))
        XCTAssertFalse(WatchlistFooter.isNearLimit(free: 11, max: WatchlistLimits.maxPairs))
    }
}
