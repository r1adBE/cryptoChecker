import XCTest
@testable import CryptoChecker

/// Seit Build 103 (Review UX-02): Paarwahl für einen neuen Alarm — Suche wie in der Merkliste, Favoriten zuerst
/// (`testdata/parity/alarm_pair_picker.json`). Gegenstück: `AlarmPairPickerParityTest.kt`.
@MainActor
final class AlarmPairPickerTests: XCTestCase {
    private typealias S = TestSupport

    func testAuswahlWieGemeinsameTestfaelle() throws {
        let fixture = try S.fixture("alarm_pair_picker")
        let watches: [Watch] = S.list(fixture["watches"]).map { w in
            let market = w["marketName"] as? String ?? ""
            var watch = Watch(id: Int64(S.number(w["id"]) ?? 0), marketKey: market.lowercased(), marketName: market,
                              baseAsset: w["base"] as? String ?? "", quoteAsset: w["quote"] as? String ?? "")
            watch.favorite = w["favorite"] as? Bool ?? false
            watch.note = w["note"] as? String
            return watch
        }
        var names: [Int64: String] = [:]
        for (id, name) in fixture["names"] as? [String: String] ?? [:] {
            if let key = Int64(id) { names[key] = name }
        }
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let expected = (c["expected"] as? [NSNumber] ?? []).map(\.int64Value)
            let actual = AlarmPairPicker.choices(watches, query: c["query"] as? String ?? "", names: names).map(\.id)
            XCTAssertEqual(actual, expected, name)
        }
    }
}
