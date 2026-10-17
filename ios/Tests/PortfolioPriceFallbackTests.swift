import XCTest
@testable import CryptoChecker

/// Wie `PortfolioPriceFallbackTest.kt`.
final class PortfolioPriceFallbackTests: XCTestCase {

    private let minute: Int64 = 60_000
    private let now: Int64 = 1_800_000_000_000

    func testMergeKeepsNewerPerCoinAndDropsInvalid() {
        let current: [String: StoredPrice] = [
            "BTC": StoredPrice(price: 65_000, at: now - minute),
            "ETH": StoredPrice(price: 3_000, at: now - 90 * minute),
            "BAD": StoredPrice(price: .nan, at: now),
        ]
        let stored: [String: StoredPrice] = [
            "BTC": StoredPrice(price: 60_000, at: now - 60 * minute),
            "ETH": StoredPrice(price: 3_100, at: now - 30 * minute),
            "SOL": StoredPrice(price: 150, at: now - 10 * minute),
            "ZERO": StoredPrice(price: 0, at: now),
        ]
        let merged = PortfolioPriceFallback.merge(current: current, stored: stored)
        XCTAssertEqual(Set(merged.keys), ["BTC", "ETH", "SOL"])
        XCTAssertEqual(merged["BTC"]?.price, 65_000)
        // Gemerkter Kurs ist neuer als der im Speicher
        XCTAssertEqual(merged["ETH"]?.price, 3_100)
    }

    func testPruneDropsOldAndCapsEntries() {
        let old = now - PortfolioPriceFallback.maxAgeMillis - 1
        let pruned = PortfolioPriceFallback.prune(
            ["BTC": StoredPrice(price: 1, at: now), "OLD": StoredPrice(price: 1, at: old), "": StoredPrice(price: 1, at: now)],
            now: now
        )
        XCTAssertEqual(Set(pruned.keys), ["BTC"])

        var many: [String: StoredPrice] = [:]
        for i in 0..<(PortfolioPriceFallback.maxEntries + 5) { many["C\(i)"] = StoredPrice(price: 1, at: now - Int64(i)) }
        let capped = PortfolioPriceFallback.prune(many, now: now)
        XCTAssertEqual(capped.count, PortfolioPriceFallback.maxEntries)
        // Die neuesten bleiben
        XCTAssertNotNil(capped["C0"])
        XCTAssertNil(capped["C\(PortfolioPriceFallback.maxEntries + 4)"])
    }

    func testEncodeDecodeRoundTrip() {
        let entries: [String: StoredPrice] = [
            "BTC": StoredPrice(price: 65_432.1, at: now),
            "PEPE": StoredPrice(price: 0.00000123, at: now - minute),
        ]
        XCTAssertEqual(PortfolioPriceFallback.decode(PortfolioPriceFallback.encode(entries)), entries)
    }

    func testDecodeRejectsForeignOrBrokenContent() {
        XCTAssertTrue(PortfolioPriceFallback.decode(nil).isEmpty)
        XCTAssertTrue(PortfolioPriceFallback.decode("").isEmpty)
        XCTAssertTrue(PortfolioPriceFallback.decode("other-v9\nBTC;1.0;1").isEmpty)
        let text = PortfolioPriceFallback.formatHeader + "\nBTC;abc;1\nETH;3000.0;\(now)\nSOL;-1;\(now)\nXRP;0.5\n"
        XCTAssertEqual(PortfolioPriceFallback.decode(text), ["ETH": StoredPrice(price: 3000, at: now)])
    }

    func testStateFollowsWatchlistRules() {
        let after = 45 * minute
        func state(_ updatedAt: Int64, online: Bool = true, open: Bool = true) -> PortfolioPriceState {
            PortfolioPriceFallback.state(updatedAt: updatedAt, now: now, staleAfterMillis: after,
                                         online: online, hasOpenCoins: open)
        }
        XCTAssertEqual(state(now, open: false), .none)
        XCTAssertEqual(state(0), .none)
        XCTAssertEqual(state(now - 5 * minute), .fresh)
        XCTAssertEqual(state(now - 46 * minute), .stale)
        // Offline hat Vorrang (auch ohne Kurs: «Offline» ohne Stand)
        XCTAssertEqual(state(now - 5 * minute, online: false), .offline)
        XCTAssertEqual(state(0, online: false), .offline)
        // Uhr zurückgestellt: Zeit in der Zukunft gilt als frisch
        XCTAssertEqual(state(now + 10 * minute), .fresh)
    }

    func testRecordOnlyWithFreshPrices() {
        let after = 45 * minute
        XCTAssertFalse(PortfolioPriceFallback.mayRecord(0, now: now, staleAfterMillis: after))
        XCTAssertTrue(PortfolioPriceFallback.mayRecord(now - minute, now: now, staleAfterMillis: after))
        XCTAssertFalse(PortfolioPriceFallback.mayRecord(now - 3 * 60 * minute, now: now, staleAfterMillis: after))
    }

    func testStaleThresholdIgnoresLiveMode() {
        // Mindestens 15 Minuten, sonst dreimal das Hintergrund-Intervall
        XCTAssertEqual(PortfolioPriceFallback.staleAfterMillis(backgroundIntervalMinutes: 1), 15 * minute)
        XCTAssertEqual(PortfolioPriceFallback.staleAfterMillis(backgroundIntervalMinutes: 15), 45 * minute)
        XCTAssertEqual(PortfolioPriceFallback.staleAfterMillis(backgroundIntervalMinutes: 60), 180 * minute)
    }

    func testStaleCoinsListsEveryPricedCoinWithOldPrice() {
        let after = 45 * minute
        let values: [String: Double] = ["BTC": 900, "ETH": 50, "USDT": 30, "SOL": 20, "DOGE": 0]
        let times: [String: Int64] = ["BTC": now - minute, "ETH": now - 3 * 60 * minute, "DOGE": now - 3 * 60 * minute]
        // ETH alt, SOL ohne Zeit; USDT immer frisch, DOGE ohne Wert zählt nicht
        XCTAssertEqual(PortfolioPriceFallback.staleCoins(values: values, times: times, now: now, staleAfterMillis: after),
                       ["ETH", "SOL"])
        // Aufnahme ist trotzdem erlaubt (≥ 80 % frisch) — die Alarme prüfen aber nicht
        XCTAssertTrue(PortfolioPriceFallback.mayRecordShare(values: values, times: times, now: now,
                                                            staleAfterMillis: after, anyPrice: true))
        var allFresh = times
        allFresh["ETH"] = now - minute
        allFresh["SOL"] = now
        XCTAssertTrue(PortfolioPriceFallback.staleCoins(values: values, times: allFresh, now: now,
                                                        staleAfterMillis: after).isEmpty)
    }
}
