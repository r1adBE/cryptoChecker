import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/stable_depeg.json` — wie `StableDepegParityTest.kt`.
final class StableDepegParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("stable_depeg") }
    private let now: Int64 = 1_800_000_000_000

    func testCoins() throws {
        XCTAssertEqual(try data()["coins"] as? [String], StableDepeg.coins)
        // Dieselbe Liste wie die übrigen Stablecoins der App (ohne USD und USDT)
        XCTAssertEqual(Set(StableDepeg.coins), CurrencyConversion.usdStables.subtracting(["USD", "USDT"]))
        for (i, coin) in StableDepeg.coins.enumerated() {
            XCTAssertEqual(StableDepeg.notificationIndex(coin.lowercased()), i)
        }
        XCTAssertEqual(StableDepeg.notificationIndex("USDT"), -1)
    }

    func testRequireTwo() throws {
        for c in TestSupport.list(try data()["requireTwo"]) {
            XCTAssertEqual(StableDepeg.requiresTwo(c["coin"] as? String ?? ""), c["expected"] as? Bool, "\(c)")
        }
    }

    func testWatched() throws {
        for c in TestSupport.list(try data()["watched"]) {
            let got = StableDepeg.watched(watchBases: c["watchBases"] as? [String] ?? [],
                                          holdings: c["holdings"] as? [String] ?? [])
            XCTAssertEqual(got, c["expected"] as? [String], "\(c)")
        }
    }

    func testPickPair() throws {
        for c in TestSupport.list(try data()["pickPair"]) {
            let pairs = (c["pairs"] as? [[Any]] ?? []).map { p in
                ExchangePair(p[0] as? String ?? "", p[1] as? String ?? "", p[2] as? String)
            }
            let got = StableDepeg.pickPair(c["coin"] as? String ?? "", pairs: pairs)
            if let expected = c["expected"] as? [Any] {
                XCTAssertEqual(got?.base, expected[0] as? String, "\(c)")
                XCTAssertEqual(got?.quote, expected[1] as? String, "\(c)")
                XCTAssertEqual(got?.pairId, expected[2] as? String, "\(c)")
            } else {
                XCTAssertNil(got, "\(c)")
            }
        }
    }

    func testToUsd() throws {
        for c in TestSupport.list(try data()["toUsd"]) {
            let got = StableDepeg.toUsd(TestSupport.number(c["price"]), quote: c["quote"] as? String ?? "",
                                        usdtUsd: TestSupport.number(c["usdtUsd"]), usdcUsd: TestSupport.number(c["usdcUsd"]))
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(got ?? .nan, expected, accuracy: 1e-12, "\(c)")
            } else {
                XCTAssertNil(got, "\(c)")
            }
        }
    }

    /// Je Börse eine Quelle; Kreuzkurs allein bzw. (ausser USDC) weniger als zwei Quellen nie alarmfähig.
    func testMeasure() throws {
        let cases = TestSupport.list(try data()["measure"])
        XCTAssertGreaterThanOrEqual(cases.count, 12)
        for c in cases {
            let sources = TestSupport.list(c["sources"]).map {
                StableDepeg.Source(usd: TestSupport.number($0["usd"]), cross: $0["cross"] as? Bool ?? false)
            }
            let m = StableDepeg.measure(sources, requireTwo: c["requireTwo"] as? Bool ?? false)
            let name = c["name"] as? String ?? ""
            if let expected = TestSupport.number(c["expected"]) {
                XCTAssertEqual(m?.price ?? .nan, expected, accuracy: 1e-9, name)
                XCTAssertEqual(m?.alarmable, c["alarmable"] as? Bool, "alarmable \(name)")
            } else {
                XCTAssertNil(m, name)
            }
        }
    }

    func testLineItems() throws {
        func reading(_ v: Any?) -> DepegAlarm.Reading? {
            guard let o = v as? [String: Any], let price = TestSupport.number(o["price"]) else { return nil }
            return DepegAlarm.Reading(price: price, atMillis: now - Int64(TestSupport.number(o["ageMillis"]) ?? 0))
        }
        for c in TestSupport.list(try data()["lineItems"]) {
            var readings: [String: DepegAlarm.Reading] = [:]
            for (coin, v) in c["readings"] as? [String: Any] ?? [:] { readings[coin] = reading(v) }
            let got = StableDepeg.lineItems(watched: c["watched"] as? [String] ?? [], usdt: reading(c["usdt"]),
                                            readings: readings, now: now)
            let expected = (c["expected"] as? [[Any]] ?? []).map { ($0[0] as? String ?? "", TestSupport.number($0[1])) }
            XCTAssertEqual(got.map(\.coin), expected.map(\.0), "\(c)")
            XCTAssertEqual(got.map(\.price), expected.map(\.1), "\(c)")
        }
    }
}
