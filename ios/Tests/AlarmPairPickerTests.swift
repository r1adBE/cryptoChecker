import XCTest
@testable import CryptoChecker

/// Seit Build 103 (Review UX-02): Paarwahl für einen neuen Alarm — Suche wie in der Merkliste, Favoriten zuerst
/// (`testdata/parity/alarm_pair_picker.json`). Gegenstück: `AlarmPairPickerParityTest.kt`. Seit Build 104 über die
/// vorberechneten Zeilen (`AlarmPairPicker.makeRows`) mit gefalteten Coin-Namen.
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
        let rows = AlarmPairPicker.makeRows(watches)
        let folded = AlarmPairPicker.foldedNames(names)
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let expected = (c["expected"] as? [NSNumber] ?? []).map(\.int64Value)
            let actual = AlarmPairPicker.choices(rows, query: c["query"] as? String ?? "", foldedNames: folded).map(\.id)
            XCTAssertEqual(actual, expected, name)
        }
        // Seit Build 104: ein neuer Kurs ändert die Zeilen-Quelle nicht, ein Favorit schon
        let priced = watches.map { w -> Watch in var copy = w; copy.lastPrice = 42; copy.lastUpdate = 1; return copy }
        XCTAssertEqual(watches.map(AlarmPairPicker.Source.init), priced.map(AlarmPairPicker.Source.init))
        let flipped = watches.map { w -> Watch in var copy = w; copy.favorite.toggle(); return copy }
        XCTAssertNotEqual(watches.map(AlarmPairPicker.Source.init), flipped.map(AlarmPairPicker.Source.init))
        // Seit Build 105 (PERF3-R2): Schlüssel für `.task(id:)` ebenso
        XCTAssertEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(priced))
        XCTAssertNotEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(flipped))
        XCTAssertNotEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(Array(watches.reversed())))
    }

    /// Seit Build 105 (PERF3-R2): das Struct neu zu erzeugen (Elternbildschirm zeichnet neu) faltet keine Zeilen.
    func testErzeugenRechnetKeineZeilen() {
        let watches = (1...50).map { Watch(id: Int64($0), marketKey: "binance", marketName: "Binance", baseAsset: "C\($0)",
                                           quoteAsset: "USDT") }
        let before = AlarmPairPicker.makeRowsCalls
        for _ in 0..<5 { _ = AlarmPairPicker(watches: watches) { _ in } }
        XCTAssertEqual(AlarmPairPicker.makeRowsCalls, before)
    }
}
