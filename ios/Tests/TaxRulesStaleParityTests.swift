import XCTest
@testable import CryptoChecker

/// Regelstand der Länder-Vorlagen als gemeinsame Testfälle (`tax_rules_stale.json`, Quelle `android/testdata/parity`) —
/// wie `TaxRulesStaleParityTest.kt`: Lesen von «JJJJ-MM» und Hinweis «Regeln womöglich veraltet» für Steuerjahre nach
/// dem Jahr des Regelstands; dazu die Anzeige als Monat und Jahr (`TaxRulesDate.label`).
final class TaxRulesStaleParityTests: XCTestCase {
    private typealias S = TestSupport

    func testMonth() throws {
        let cases = S.list(try S.fixture("tax_rules_stale")["month"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let rulesDate = c["rulesDate"] as? String ?? ""
            let want = (c["month"] as? [Any]).map { [Int(S.number($0[0]) ?? 0), Int(S.number($0[1]) ?? 0)] }
            let got = TaxRulesDate.month(rulesDate).map { [$0.year, $0.month] }
            XCTAssertEqual(got, want, "month «\(rulesDate)»")
        }
    }

    func testRulesStale() throws {
        let cases = S.list(try S.fixture("tax_rules_stale")["stale"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let taxYear = Int(S.number(c["taxYear"]) ?? 0)
            let rulesDate = c["rulesDate"] as? String ?? ""
            XCTAssertEqual(TaxRulesDate.rulesStale(taxYear: taxYear, rulesDate: rulesDate), c["stale"] as? Bool, name)
        }
    }

    /// Der hinterlegte Regelstand ist lesbar — sonst bliebe der Hinweis still aus.
    func testStoredRulesDateIsReadable() throws {
        let month = try XCTUnwrap(TaxRulesDate.month(TaxTemplate.rulesDate), "rulesDate nicht lesbar")
        XCTAssertTrue(TaxRulesDate.rulesStale(taxYear: month.year + 1))
        XCTAssertFalse(TaxRulesDate.rulesStale(taxYear: month.year))
    }

    /// Monat und Jahr statt «2026-10»; Arabisch mit landeseigenen Ziffern; unlesbar: Rohwert.
    func testLabel() {
        let german = TaxRulesDate.label("2026-10", locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(german.contains("2026"), german)
        XCTAssertNotEqual(german, "2026-10")
        let arabic = TaxRulesDate.label("2026-10", locale: Locale(identifier: "ar"))
        XCTAssertTrue(arabic.contains("٢٠٢٦"), arabic)
        let thai = TaxRulesDate.label("2026-10", locale: Locale(identifier: "th_TH"))
        XCTAssertTrue(thai.contains("2026"), "gregorianisch wie Android: \(thai)")
        XCTAssertEqual(TaxRulesDate.label("2026-13", locale: Locale(identifier: "en_US")), "2026-13")
    }
}
