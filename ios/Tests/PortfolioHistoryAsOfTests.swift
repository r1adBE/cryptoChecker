import XCTest
@testable import CryptoChecker

/// Älterer Stand im Wertverlauf (eigene Börse nicht erreichbar); Gegenstück `PortfolioHistoryAsOfTest.kt`.
final class PortfolioHistoryAsOfTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private let ttl: Int64 = 12 * 60 * 60_000

    func testOldestStaleTimeAmongDrawnCoins() {
        let stale: [String: Int64] = ["BTC": now - 3 * ttl, "ETH": now - 2 * ttl, "SOL": now - 5 * ttl]
        // SOL ist nicht gezeichnet (ohne Verlauf) und zählt nicht
        XCTAssertEqual(PortfolioHistoryAsOf.of(ownStale: stale, drawn: ["BTC", "ETH", "ADA"]), now - 3 * ttl)
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: stale, drawn: ["ADA"]))
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: [:], drawn: ["BTC"]))
        XCTAssertNil(PortfolioHistoryAsOf.of(ownStale: ["BTC": 0], drawn: ["BTC"]))
    }

    func testFallbackIsStaleOnlyWhenOlderThanTheCacheTime() {
        // Jünger als 12 h: heutiger Verlauf (nur evtl. kürzer), kein «Stand …»
        XCTAssertNil(PortfolioHistoryAsOf.staleSince(savedAt: now - 60_000, now: now, ttl: ttl))
        // Älter: mit seiner Zeit markiert
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: now - ttl, now: now, ttl: ttl), now - ttl)
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: now - 3 * ttl, now: now, ttl: ttl), now - 3 * ttl)
        // Unbekannte Zeit gilt nicht als frisch; künftige (Uhr zurückgestellt) wie frisch, nie eine künftige Anzeige
        XCTAssertEqual(PortfolioHistoryAsOf.staleSince(savedAt: 0, now: now, ttl: ttl), 0)
        XCTAssertNil(PortfolioHistoryAsOf.staleSince(savedAt: now + 60_000, now: now, ttl: ttl))
    }
}
