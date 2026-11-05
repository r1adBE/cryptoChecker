import XCTest
@testable import CryptoChecker

/// Euro-Referenzkurse der EZB (`EcbRates`) als gemeinsame Testfälle (`ecb_rates.json`, Quelle `android/testdata/parity`)
/// — wie `EcbRatesParityTest.kt`: Rundung auf 5 Stellen, Kreuzkurse, Lesen der Dateien (XML) und des Data Portals
/// (SDMX-JSON), Zeitfenster, Adresse und Abdeckung der 90-Tage-Datei.
final class EcbRatesParityTests: XCTestCase {
    private typealias S = TestSupport

    private func close(_ name: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTAssertNil(actual, name) }
        guard let actual else { return XCTFail("\(name): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), abs(expected) * 1e-12, "\(name): expected \(expected), got \(actual)")
    }

    private func days(_ raw: Any?) -> EcbRates.Days? {
        guard let object = raw as? [String: Any] else { return nil }
        var out: EcbRates.Days = [:]
        for (day, rates) in object {
            var parsed: [String: Double] = [:]
            for (code, rate) in rates as? [String: Any] ?? [:] { parsed[code] = S.number(rate) }
            out[day] = parsed
        }
        return out
    }

    private func sameDays(_ name: String, _ expected: EcbRates.Days?, _ actual: EcbRates.Days?) {
        guard let expected else { return XCTAssertNil(actual, name) }
        guard let actual else { return XCTFail("\(name): expected days, got nil") }
        XCTAssertEqual(Set(expected.keys), Set(actual.keys), name)
        for (day, rates) in expected {
            XCTAssertEqual(Set(rates.keys), Set(actual[day].map { Array($0.keys) } ?? []), "\(name) \(day)")
            for (code, rate) in rates { close("\(name) \(day) \(code)", rate, actual[day]?[code]) }
        }
    }

    private func cases(_ key: String) throws -> [[String: Any]] {
        let list = S.list(try S.fixture("ecb_rates")[key])
        XCTAssertFalse(list.isEmpty, "\(key): keine Fälle")
        return list
    }

    func testRound5() throws {
        for c in try cases("round5") {
            close("round5 \(c["value"] ?? "")", S.number(c["expected"]), EcbRates.round5(S.number(c["value"]) ?? .nan))
        }
    }

    func testCrossAndRebase() throws {
        let day = try XCTUnwrap(days(["d": try S.fixture("ecb_rates")["day"] as Any])?["d"])
        for c in try cases("cross") {
            let base = c["base"] as? String ?? ""
            let quote = c["quote"] as? String ?? ""
            close("cross \(base)/\(quote)", S.number(c["expected"]), EcbRates.cross(day, base: base, quote: quote))
        }
        for c in try cases("rebase") {
            let base = c["base"] as? String ?? ""
            var expected: [String: Double] = [:]
            for (code, rate) in c["expected"] as? [String: Any] ?? [:] { expected[code] = S.number(rate) }
            let actual = EcbRates.rebase(day, base: base)
            XCTAssertEqual(Set(expected.keys), Set(actual.keys), "rebase \(base)")
            for (code, rate) in expected { close("rebase \(base) \(code)", rate, actual[code]) }
        }
    }

    func testParseXml() throws {
        for c in try cases("parseXml") {
            let only = (c["only"] as? [String]).map(Set.init)
            sameDays(c["name"] as? String ?? "", days(c["expected"]),
                     EcbRates.parseXML(c["body"] as? String ?? "", only: only))
        }
    }

    func testParseSdmx() throws {
        for c in try cases("parseSdmx") {
            sameDays(c["name"] as? String ?? "", days(c["expected"]), EcbRates.parseSDMX(c["body"] as? String ?? ""))
        }
    }

    func testRecent() throws {
        for c in try cases("recent") {
            let all = days(c["days"]) ?? [:]
            let expected = (c["expected"] as? [String] ?? []).compactMap { all[$0] }
            let window = Int(S.number(c["windowDays"]) ?? 0)
            XCTAssertEqual(EcbRates.recent(all, windowDays: window), expected, c["name"] as? String ?? "")
        }
    }

    func testSeriesUrlAndCoverage() throws {
        for c in try cases("seriesUrl") {
            let from = try XCTUnwrap(LocalDay(iso: c["from"] as? String ?? ""))
            let to = try XCTUnwrap(LocalDay(iso: c["to"] as? String ?? ""))
            XCTAssertEqual(EcbRates.seriesURL(codes: c["codes"] as? [String] ?? [], from: from, to: to),
                           c["expected"] as? String)
        }
        for c in try cases("coveredByHist90d") {
            let from = try XCTUnwrap(LocalDay(iso: c["from"] as? String ?? ""))
            let today = try XCTUnwrap(LocalDay(iso: c["today"] as? String ?? ""))
            XCTAssertEqual(EcbRates.coveredByHist90d(from: from, today: today), c["expected"] as? Bool,
                           "\(from) / \(today)")
        }
    }
}
