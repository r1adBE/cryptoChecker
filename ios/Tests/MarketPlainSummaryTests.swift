import XCTest
@testable import CryptoChecker

/// «Kurz gesagt» im Markt-Tab: gemeinsame Fälle `market_plain_summary.json` (Android: MarketPlainSummaryTest).
final class MarketPlainSummaryTests: XCTestCase {

    private typealias S = TestSupport

    /// Namen der Tatsachen wie in den Testfällen (Android-Enum).
    private static let factKinds: [String: UnusualFactKind] = [
        "STRONGER_THAN_BTC": .strongerThanBtc, "WEAKER_THAN_BTC": .weakerThanBtc, "AGAINST_MARKET": .againstMarket,
        "VOLUME": .volume, "FUNDING_HIGH": .fundingHigh, "FUNDING_NEGATIVE": .fundingNegative,
    ]

    func testSharedCases() throws {
        let fixture = try S.fixture("market_plain_summary")
        let now = S.millis(fixture["now"])
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 40)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let inputSpec = try XCTUnwrap(c["input"] as? [String: Any], name)
            let actual = MarketPlainSummary.evaluate(try input(inputSpec, now: now))
            let expected = try XCTUnwrap(c["expected"] as? [String: Any], name)

            let direction = expected["direction"] as? [String: Any]
            XCTAssertEqual(actual.direction?.kind.rawValue, direction?["kind"] as? String, "direction: \(name)")
            if let direction {
                XCTAssertEqual(actual.direction?.changePercent ?? .nan, S.number(direction["changePercent"]) ?? 0,
                               accuracy: 1e-9, "direction %: \(name)")
            }

            let mood = expected["mood"] as? [String: Any]
            XCTAssertEqual(actual.mood?.kind.rawValue, mood?["kind"] as? String, "mood: \(name)")
            if let mood {
                XCTAssertEqual(actual.mood?.value, (S.number(mood["value"])).map { Int($0) }, "mood value: \(name)")
            }

            let standout = expected["standout"] as? [String: Any]
            XCTAssertEqual(actual.standout?.kind.rawValue, standout?["kind"] as? String, "standout: \(name)")
            if let standout, let s = actual.standout {
                XCTAssertEqual(s.coin, standout["coin"] as? String, "coin: \(name)")
                XCTAssertEqual(s.change24h, S.number(standout["change24h"]), "change: \(name)")
                XCTAssertEqual(s.btc24h, S.number(standout["btc24h"]), "btc: \(name)")
                XCTAssertEqual(s.volumeRatio, S.number(standout["volumeRatio"]), "ratio: \(name)")
            }
            XCTAssertEqual(actual.isEmpty, direction == nil && mood == nil && standout == nil, "isEmpty: \(name)")
        }
    }

    func testMoodBandsMatchFearGreedCard() {
        // Satz und Farbstufe der Karte dürfen nie auseinanderlaufen
        let order: [PlainMoodKind] = [.extremeFear, .fear, .neutral, .greed, .extremeGreed]
        for value in 0...100 {
            XCTAssertEqual(order.firstIndex(of: MarketPlainSummary.moodKind(value)), MarketScalePalette.level(value),
                           "value \(value)")
        }
    }

    func testStaleStampOrMissingStampHidesSentence() {
        let now: Int64 = 1_760_700_000_000
        XCTAssertNil(MarketPlainSummary.direction(PlainSummaryInput(now: now, pulseMarketChange24h: 2)))
        let old = DataStamp(provider: nil, savedAt: now - 16 * 60_000, ttl: 5 * 60_000)
        XCTAssertNil(MarketPlainSummary.direction(PlainSummaryInput(now: now, pulseMarketChange24h: 2, pulseStamp: old)))
    }

    // MARK: Testfälle lesen

    private func stamp(_ raw: Any?, now: Int64) -> DataStamp? {
        guard let spec = raw as? [String: Any] else { return nil }
        return DataStamp(provider: nil, savedAt: now - S.millis(spec["ageMillis"]), ttl: S.millis(spec["ttlMillis"]))
    }

    private func input(_ i: [String: Any], now: Int64) throws -> PlainSummaryInput {
        PlainSummaryInput(
            now: now,
            pulseMarketChange24h: S.number(i["pulseMarketChange24h"]),
            pulseStamp: stamp(i["pulseStamp"], now: now),
            globalMarketChange24h: S.number(i["globalMarketChange24h"]),
            globalStamp: stamp(i["globalStamp"], now: now),
            fearGreed: S.number(i["fearGreed"]).map { Int($0) },
            fearGreedTime: S.number(i["fearGreedTimeAgeMillis"]).map { now - Int64($0) },
            fearGreedStamp: stamp(i["fearGreedStamp"], now: now),
            unusual: try (i["unusual"] as? [String: Any]).map { try report($0, now: now) },
            unusualStamp: stamp(i["unusualStamp"], now: now)
        )
    }

    private func report(_ r: [String: Any], now: Int64) throws -> UnusualReport {
        let rows = try S.list(r["rows"]).map { row in
            UnusualRow(
                symbol: row["symbol"] as? String ?? "",
                name: row["name"] as? String ?? "",
                change24h: S.number(row["change24h"]) ?? .nan,
                facts: try S.list(row["facts"]).map { fact in
                    UnusualFact(
                        kind: try XCTUnwrap(Self.factKinds[fact["kind"] as? String ?? ""]),
                        strength: S.number(fact["strength"]) ?? 0,
                        volumeRatio: S.number(fact["volumeRatio"]),
                        fundingPercent: S.number(fact["fundingPercent"])
                    )
                },
                score: 1
            )
        }
        return UnusualReport(rows: rows, btc24h: S.number(r["btc24h"]), time: now)
    }
}
