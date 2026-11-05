import XCTest
@testable import CryptoChecker

/// Referenzkurse von NBU und NBP (`ReferenceRates`, `ReferenceSource`, `BankReference`) als gemeinsame Testfälle
/// (`reference_rates.json`, Quelle `android/testdata/parity`) — wie `ReferenceRatesParityTest.kt`: Quelle je Währung,
/// Ausschlüsse, Lesen der Antworten, Kreuzkurse, Veränderung gegen die vorige Veröffentlichung, Gültigkeit der
/// behaltenen Antworten und der Hinweis «umgerechnet».
final class ReferenceRatesParityTests: XCTestCase {
    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("reference_rates") }

    private func close(_ name: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTAssertNil(actual, name) }
        guard let actual else { return XCTFail("\(name): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), abs(expected) * 1e-9, "\(name): expected \(expected), got \(actual)")
    }

    private func exchange(_ source: String) throws -> BankReference {
        let key = try XCTUnwrap(ReferenceSource(rawValue: source)).exchangeKey
        return try XCTUnwrap(ExchangesConfig.exchange(key) as? BankReference)
    }

    private func joined(_ c: [String: Any]) throws -> [String] {
        let bodies = try data()["bodies"] as? [String: String] ?? [:]
        return (c["bodies"] as? [String] ?? []).compactMap { bodies[$0] }
    }

    func testTickersFromJoinedResponses() throws {
        for c in S.list(try data()["tickers"]) {
            let exchange = try exchange(c["source"] as? String ?? "")
            let name = "\(c["source"] ?? "") \(c["bodies"] ?? "")"
            let tickers = (try? exchange.parseBulkTickersMain(call: 0, body: MultiResponse.join(try joined(c)))) ?? [:]
            for (id, raw) in c["expected"] as? [String: [String: Any]] ?? [:] {
                guard let ticker = tickers[id] else { XCTFail("\(name): missing \(id)"); continue }
                close("\(name) \(id) last", S.number(raw["last"]), ticker.last)
                close("\(name) \(id) change", S.number(raw["change"]), ticker.change24hPercent)
            }
            for id in c["absent"] as? [String] ?? [] { XCTAssertNil(tickers[id], "\(name): \(id) must be absent") }
        }
    }

    func testNbuRangeURL() throws {
        let c = try XCTUnwrap(try data()["nbuRangeUrl"] as? [String: Any])
        let today = try XCTUnwrap(LocalDay(iso: c["today"] as? String ?? ""))
        XCTAssertEqual(ReferenceRates.nbuRangeURL(today: today), c["expected"] as? String)
    }

    func testAssignPrefersEcbThenNbuThenNbp() throws {
        let c = try XCTUnwrap(try data()["assign"] as? [String: Any])
        func codes(_ key: String) -> Set<String> { Set(c[key] as? [String] ?? []) }
        let assigned = ReferenceRates.assign(ecb: codes("ecb"), nbu: codes("nbu"), nbp: codes("nbp"))
        let expected = (c["expected"] as? [String: String] ?? [:]).compactMapValues { ReferenceSource(rawValue: $0) }
        XCTAssertEqual(assigned, expected)
        for code in c["absent"] as? [String] ?? [] { XCTAssertNil(assigned[code], "\(code) must be excluded") }
        XCTAssertEqual(ReferenceRates.pairs(assigned, source: .nbu).compactMap(\.pairId), c["nbuPairs"] as? [String])
        let nbp = ReferenceRates.pairs(assigned, source: .nbp).compactMap(\.pairId)
        XCTAssertEqual(nbp.count, Int(S.number(c["nbpPairsCount"]) ?? -1))
        XCTAssertTrue(Set(nbp).isSuperset(of: c["nbpPairsSample"] as? [String] ?? []))
    }

    func testExpectedWednesdayAndValidity() throws {
        for c in S.list(try data()["expectedWednesday"]) {
            let now = S.millis(c["now"])
            XCTAssertEqual(ReferenceRates.nbpExpectedWednesday(now: now).description, c["expected"] as? String,
                           c["utc"] as? String ?? "")
        }
        for c in S.list(try data()["validUntil"]) {
            let actual = try exchange(c["source"] as? String ?? "").validUntil(bodies: try joined(c), now: S.millis(c["now"]))
            XCTAssertEqual(actual, S.millis(c["expected"]), "\(c["source"] ?? "") \(c["utc"] ?? "") \(c["bodies"] ?? "")")
        }
    }

    func testSourcesAndConvertedNotice() throws {
        for c in S.list(try data()["sources"]) {
            let key = c["exchangeKey"] as? String ?? ""
            let source = ReferenceSource.of(key)
            XCTAssertEqual(source?.rawValue, c["source"] as? String, key)
            guard let source else { continue }
            XCTAssertEqual(source.weekly, c["weekly"] as? Bool)
            XCTAssertEqual(source.home, c["home"] as? String)
            let exchange = try XCTUnwrap(ExchangesConfig.exchange(key))
            XCTAssertFalse(exchange.allowsAlarms, key)
            XCTAssertEqual(exchange.priceIsDailyReference, !source.weekly, key)
        }
        for c in S.list(try data()["converted"]) {
            let source = try XCTUnwrap(ReferenceSource(rawValue: c["source"] as? String ?? ""))
            XCTAssertEqual(source.converted(base: c["base"] as? String ?? "", hasChange: c["hasChange"] as? Bool ?? false),
                           c["expected"] as? Bool, "\(c)")
        }
    }
}
