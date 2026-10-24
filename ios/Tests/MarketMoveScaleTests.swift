import XCTest
@testable import CryptoChecker

/// Schlagzeile des Pulses und gemeinsame Skala: Fälle `market_move_scale.json` (Android: MarketMoveScaleTest).
final class MarketMoveScaleTests: XCTestCase {

    private typealias S = TestSupport

    private func changes(up: Int, down: Int, total: Int) -> [Double] {
        Array(repeating: 1.0, count: up) + Array(repeating: -1.0, count: down)
            + Array(repeating: 0.0, count: total - up - down)
    }

    private func makeReport(btc: Double, eth: Double, sol: Double, market: Double?, top: [Double],
                        ratio: Double?) throws -> PulseReport {
        let input = PulseInput(btc: btc, eth: eth, sol: sol, volumeRatio: ratio, fearGreed: nil, fundingPercent: nil,
                               ethGasGwei: nil, topChanges: top, marketCapUsd: market == nil ? nil : 3.0e12,
                               marketCap24h: market)
        return try XCTUnwrap(CryptoPulse.evaluate(input))
    }

    /// Namen wie in den Testfällen (Android-Enum).
    private static func summaryName(_ summary: PulseSummary) -> String {
        switch summary {
        case .broadUp: "BROAD_UP"
        case .broadUpVolume: "BROAD_UP_VOLUME"
        case .broadDown: "BROAD_DOWN"
        case .broadDownVolume: "BROAD_DOWN_VOLUME"
        case .mixed: "MIXED"
        case .calm: "CALM"
        }
    }

    private static func leadName(_ kind: PulseLeadKind) -> String {
        // «pulse_lead_btc_leads» → «BTC_LEADS»
        String(kind.key.dropFirst("pulse_lead_".count)).uppercased()
    }

    func testSharedCases() throws {
        let fixture = try S.fixture("market_move_scale")
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let input = try XCTUnwrap(c["input"] as? [String: Any], name)
            let expected = try XCTUnwrap(c["expected"] as? [String: Any], name)
            let btc = try XCTUnwrap(S.number(input["btc24h"]), name)
            let eth = try XCTUnwrap(S.number(input["eth24h"]), name)
            let sol = try XCTUnwrap(S.number(input["sol24h"]), name)
            let market = S.number(input["marketChange24h"])
            let breadth = (input["breadth"] as? [String: Any]).map {
                PulseBreadth(up: Int(S.number($0["up"]) ?? 0), down: Int(S.number($0["down"]) ?? 0),
                             total: Int(S.number($0["total"]) ?? 0))
            }

            let move = MarketMoveScale.decide(btc: btc, eth: eth, sol: sol, marketChange: market, breadth: breadth)
            XCTAssertEqual(move.direction.rawValue, expected["direction"] as? String, "direction: \(name)")
            XCTAssertEqual(move.level?.rawValue, expected["level"] as? String, "level: \(name)")

            let top = breadth.map { changes(up: $0.up, down: $0.down, total: $0.total) } ?? []
            let report = try makeReport(btc: btc, eth: eth, sol: sol, market: market, top: top,
                                    ratio: S.number(input["btcVolumeRatio"]))
            XCTAssertEqual(report.breadth, breadth, "breadth: \(name)")
            XCTAssertEqual(Self.summaryName(report.summary), expected["summary"] as? String, "summary: \(name)")
            XCTAssertEqual(Self.leadName(CryptoPulse.leadSentence(report).kind), expected["lead"] as? String,
                           "lead: \(name)")
            XCTAssertEqual(market.map { MarketPlainSummary.directionKind($0).rawValue },
                           expected["plainDirection"] as? String, "plain: \(name)")
        }
    }

    /// Raster: gleichgerichtete Grosswerte → deren Richtung, ausser Gesamtmarkt (ab 1 %) oder Breite
    /// stehen dagegen, dann «gemischt» (ruhige Fälle ausgenommen); nie «leicht» neben «deutlich».
    func testHeadlineNeverContradictsTheNumbers() throws {
        let steps = [-4.0, -3.0, -2.0, -1.2, -0.5, 0.0, 0.5, 1.2, 2.0, 3.0, 4.0]
        let markets: [Double?] = [nil, -3.5, -3.0, -1.5, -1.0, -0.5, 0.5, 1.0, 1.5, 3.0, 3.5]
        // Breite: 26 von 30 fallend (−1) bzw. steigend (+1)
        let breadths = [(-1, changes(up: 4, down: 26, total: 30)), (1, changes(up: 26, down: 4, total: 30))]
        let noise = MarketMoveScale.noisePercent
        let flat = MarketMoveScale.flatBelowPercent
        for btc in steps { for eth in steps { for sol in steps { for market in markets { for (lean, top) in breadths {
            let big = [btc, eth, sol]
            let r = try makeReport(btc: btc, eth: eth, sol: sol, market: market, top: top, ratio: nil)
            let lead = CryptoPulse.leadSentence(r).kind
            let label = "btc=\(btc) eth=\(eth) sol=\(sol) market=\(String(describing: market)) lean=\(lean)"
            var bigSign = 0
            if big.allSatisfy({ $0 > noise }) { bigSign = 1 } else if big.allSatisfy({ $0 < -noise }) { bigSign = -1 }
            if bigSign != 0 && r.summary != .calm {
                let marketAgainst = market.map { $0 * Double(bigSign) <= -flat } ?? false
                let expected: PulseSummary = marketAgainst || lean == -bigSign
                    ? .mixed : (bigSign > 0 ? .broadUp : .broadDown)
                XCTAssertEqual(r.summary, expected, label)
            }
            let plain = market.map { MarketPlainSummary.directionKind($0) }
            if plain == .downClear { XCTAssertNotEqual(lead, .driftDown, label) }
            if plain == .upClear { XCTAssertNotEqual(lead, .driftUp, label) }
            // Gesamtwert zeigt in eine Richtung (ab 1 %) → Schlagzeile nie in die Gegenrichtung
            if let market, market >= flat { XCTAssertNotEqual(r.summary, .broadDown, label) }
            if let market, market <= -flat { XCTAssertNotEqual(r.summary, .broadUp, label) }
        } } } } }
    }

    func testLevelThresholds() {
        XCTAssertEqual(MarketMoveScale.level(0.99), .flat)
        XCTAssertEqual(MarketMoveScale.level(-0.99), .flat)
        XCTAssertEqual(MarketMoveScale.level(1.0), .slight)
        XCTAssertEqual(MarketMoveScale.level(-2.99), .slight)
        XCTAssertEqual(MarketMoveScale.level(3.0), .clear)
        XCTAssertEqual(MarketMoveScale.level(-3.0), .clear)
    }
}
