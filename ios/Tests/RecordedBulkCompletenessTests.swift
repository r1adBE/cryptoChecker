import XCTest
@testable import CryptoChecker

/// Börsen, deren `bulkTickersComplete` sich auf eine Aufzeichnung stützt — wie `RecordedBulkCompletenessTest.kt`:
/// In `recorded_*.json` (nach `Tests/Exchanges/Recorded` kopiert) muss jedes Paar der aufgezeichneten Paarliste
/// auch in der aufgezeichneten Sammelabfrage stehen (gelesen oder als unlesbar gemeldet) — sonst bestätigte die App
/// ein gehandeltes Paar als «nicht mehr gehandelt».
final class RecordedBulkCompletenessTests: XCTestCase {

    private typealias S = TestSupport

    /// Börse → Aufzeichnung, die die Vollständigkeit belegt.
    private let evidence: [(key: String, file: String)] = [
        ("CoinDcx", "recorded_coindcx"),
        ("Luno", "recorded_luno"),
        ("Icrypex", "recorded_icrypex"),
        ("HashKey", "recorded_hashkey"),
    ]

    /// Paare, die die Sammelabfrage zwar führt, aber mit Kurs 0 (nie gehandelt) — sie fallen dort weg wie bei Lighter
    /// und gelten damit als nicht gehandelt (HashKey «SPICEUSDC», Aufzeichnung 19.10.2026: c/h/l/o/v alle «0»).
    private let listedWithoutPrice: [String: Set<String>] = ["HashKey": ["SPICEUSDC"]]

    func testRecordedBulkListsEveryRecordedPair() throws {
        let bundle = Bundle(for: RecordedBulkCompletenessTests.self)
        var failures: [String] = []
        for (key, file) in evidence {
            let exchange = try XCTUnwrap(ExchangesConfig.exchange(key), key)
            if !exchange.bulkTickersComplete { failures.append("\(key): bulkTickersComplete is false") }
            let url = try XCTUnwrap(bundle.url(forResource: file, withExtension: "json"), file)
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            let data = try XCTUnwrap(object as? [String: Any], file)
            var pairs: [ExchangePair] = []
            var listed = Set<String>()
            for c in S.list(data["cases"]) {
                let call = Int(S.number(c["requestId"]) ?? 0)
                let body = c["body"] as? String ?? ""
                switch c["kind"] as? String {
                case "pairs": pairs += try exchange.readPairList(call: call, body: body)
                case "bulk":
                    let parsed = try exchange.parseBulkTickersDetailed(call: call, body: body)
                    listed.formUnion(parsed.tickers.keys)
                    listed.formUnion(parsed.failed)
                default: break
                }
            }
            if pairs.isEmpty || listed.isEmpty { failures.append("\(key): recording lacks pair list or bulk response") }
            let zero = listedWithoutPrice[key] ?? []
            let missing = pairs.compactMap(\.pairId).filter { !listed.contains($0) && !zero.contains($0) }
            if !missing.isEmpty {
                failures.append("\(key): \(missing.count) recorded pair(s) missing in bulk: \(missing.prefix(10))")
            }
        }
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }
}
