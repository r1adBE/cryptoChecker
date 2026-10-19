import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/historic_close_chain.json` — wie `HistoricCloseChainParityTest.kt`.
final class HistoricCloseChainParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("historic_close_chain") }

    private func optionalString(_ value: Any?) -> String? { value as? String }

    func testOrder() throws {
        let d = try data()
        let cases = TestSupport.list(d["order"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            XCTAssertEqual(HistoricCloseChain.order(optionalString(c["own"])), c["expected"] as? [String], "\(c)")
        }
        XCTAssertEqual(HistoricCloseChain.seriesChain, d["seriesChain"] as? [String])
    }

    func testProvider() throws {
        for c in TestSupport.list(try data()["provider"]) {
            XCTAssertEqual(HistoricCloseChain.provider(c["market"] as? String ?? ""), c["expected"] as? String, "\(c)")
        }
    }

    func testPairs() throws {
        for c in TestSupport.list(try data()["pairs"]) {
            let expected = (c["expected"] as? [[String]] ?? []).map { HistoricCloseChain.ChainPair(quote: $0[0], pairId: $0[1]) }
            XCTAssertEqual(HistoricCloseChain.pairs(c["market"] as? String ?? "", base: c["base"] as? String ?? ""), expected, "\(c)")
        }
    }

    /// Gleiches Kürzel, anderer Token: Name entscheidet vor dem Kursband.
    func testNames() throws {
        for c in TestSupport.list(try data()["names"]) {
            let allowed = HistoricCloseChain.nameAllowed(base: c["base"] as? String ?? "", own: c["own"] as? Bool ?? false,
                                                         marketKey: c["market"] as? String ?? "",
                                                         ownName: optionalString(c["ownName"]),
                                                         binanceName: optionalString(c["binanceName"]),
                                                         providerName: optionalString(c["providerName"]))
            XCTAssertEqual(allowed, c["expected"] as? Bool, "\(c)")
        }
    }

    func testDecide() throws {
        let cases = TestSupport.list(try data()["decide"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let verdict = HistoricCloseChain.decide(close: TestSupport.number(c["close"]), latest: TestSupport.number(c["latest"]),
                                                    current: TestSupport.number(c["current"]),
                                                    primary: c["primary"] as? Bool ?? false, nameOk: c["nameOk"] as? Bool ?? false)
            XCTAssertEqual(verdict.rawValue, c["expected"] as? String, "\(c)")
        }
    }

    /// Reihenfolge, Abbruch beim ersten Treffer, Anbieter und «ungeprüft».
    func testResolve() async throws {
        for c in TestSupport.list(try data()["resolve"]) {
            let outcomes = c["outcomes"] as? [String: [String: Any]] ?? [:]
            var asked: [String] = []
            let r = await HistoricCloseChain.resolve(c["order"] as? [String] ?? []) { source in
                asked.append(source)
                let o = outcomes[source]
                return HistoricCloseChain.Outcome(price: TestSupport.number(o?["price"]), rejected: o?["rejected"] as? Bool ?? false,
                                                  provider: o?["provider"] as? String)
            }
            let e = c["expected"] as? [String: Any] ?? [:]
            let name = c["name"] as? String ?? ""
            TestSupport.assertNumber(r.price, TestSupport.number(e["price"]), accuracy: 1e-12, "price \(name)")
            XCTAssertEqual(r.source, optionalString(e["source"]), "source \(name)")
            XCTAssertEqual(r.provider, optionalString(e["provider"]), "provider \(name)")
            XCTAssertEqual(r.unchecked, e["unchecked"] as? Bool, "unchecked \(name)")
            XCTAssertEqual(asked, e["asked"] as? [String], "asked \(name)")
        }
    }

    func testAcceptSeries() throws {
        for c in TestSupport.list(try data()["acceptSeries"]) {
            let accepted = HistoricCloseChain.acceptSeries(latest: TestSupport.number(c["latest"]),
                                                           current: TestSupport.number(c["current"]),
                                                           nameOk: c["nameOk"] as? Bool ?? false)
            XCTAssertEqual(accepted, c["expected"] as? Bool, "\(c)")
        }
    }

    func testFillUsdtUsd() throws {
        func days(_ v: Any?) -> [Int: Double] {
            var out: [Int: Double] = [:]
            for (k, x) in v as? [String: Any] ?? [:] {
                if let day = Int(k), let value = TestSupport.number(x) { out[day] = value }
            }
            return out
        }
        for c in TestSupport.list(try data()["fillUsdtUsd"]) {
            XCTAssertEqual(HistoricCloseChain.fillUsdtUsd(days(c["primary"]), days(c["secondary"])), days(c["expected"]), "\(c)")
        }
    }
}
