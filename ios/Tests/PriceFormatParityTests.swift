import XCTest
@testable import CryptoChecker

/// Kurs der Merkliste (`Parity/price_format.json`): unter 10 höchstens vier gültige Stellen (auch kleinste Kurse), ab 10
/// zwei Nachkommastellen mit der Tausendertrennung der Region. Gegenstück: Android `PriceFormatParityTest.kt` (ab 10,
/// JVM) und `PriceFormatIcuTest.kt` (unter 10, Gerät).
final class PriceFormatParityTests: XCTestCase {
    private typealias S = TestSupport

    /// Tausender-Apostroph der Schweiz: Java/CLDR liefert U+2019 (’), Foundation das ASCII-Zeichen (') — verglichen
    /// wird ohne diesen einen Zeichenunterschied (wie `WatchPriceFormatParityTests`).
    private func same(_ text: String?) -> String? { text?.replacingOccurrences(of: "\u{2019}", with: "'") }

    func testKursWieGemeinsameFaelle() throws {
        let fixture = try S.fixture("price_format")
        let below = try XCTUnwrap(S.number(fixture["smallBelow"]))
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 60)
        var small = 0, large = 0
        for c in cases {
            let id = c["locale"] as? String ?? ""
            let value = try XCTUnwrap(S.number(c["value"]))
            let text = PriceFormat.formatDouble(value, locale: Locale(identifier: id))
            XCTAssertEqual(same(text), same(c["expected"] as? String), "\(id) \(value)")
            if value < below { small += 1 } else { large += 1 }
        }
        // Beide Zweige und die drei Regionen sind vertreten
        XCTAssertGreaterThanOrEqual(small, 30)
        XCTAssertGreaterThanOrEqual(large, 30)
        XCTAssertEqual(Set(cases.compactMap { $0["locale"] as? String }), ["en_US", "de_CH", "de_DE"])
    }

    /// Ohne Region: das Format des Geräts — dieselbe Regel (Grenze bei 10), nur mit dessen Zeichen.
    func testOhneRegionGleicheRegel() {
        XCTAssertEqual(PriceFormat.formatDouble(83_142), PriceFormat.formatDouble(83_142, locale: .current))
        XCTAssertEqual(PriceFormat.formatDouble(0.00000712), PriceFormat.formatDouble(0.00000712, locale: .current))
        XCTAssertEqual(PriceFormat.price(0.00000712), PriceFormat.formatDouble(0.00000712))
        XCTAssertEqual(PriceFormat.price(0), "—")
    }
}
