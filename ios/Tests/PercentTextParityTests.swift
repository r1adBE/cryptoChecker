import XCTest
@testable import CryptoChecker

/// Prozent in der Schreibweise der Sprache (`Parity/percent_text.json`): Abstand vor «%» (de, fr …, nicht de_CH/fr_CH),
/// Türkisch «%2,4», Arabisch/Persisch «٪» mit eigenen Ziffern; dazu der kompakte Abstand der Kursmitteilung.
/// Gegenstück: `PercentTextParityTest.kt` und `PercentTextTest.kt`.
final class PercentTextParityTests: XCTestCase {
    private typealias S = TestSupport

    func testFaelleWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("percent_text")["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let id = c["locale"] as? String ?? ""
            let locale = Locale(identifier: id)
            let value = try XCTUnwrap(S.number(c["value"]))
            let decimals = Int(try XCTUnwrap(S.number(c["decimals"])))
            let actual = (c["kind"] as? String) == "signed"
                ? PercentText.signed(value, decimals: decimals, locale: locale)
                : PercentText.unsigned(value, decimals: decimals, locale: locale)
            XCTAssertEqual(actual, c["expected"] as? String, "\(id) \(value)")
        }
        // de, fr, tr, ar, fa müssen dabei sein (die Fälle mit eigener Schreibweise)
        let languages = Set(cases.compactMap { ($0["locale"] as? String)?.split(separator: "_").first.map(String.init) })
        XCTAssertTrue(languages.isSuperset(of: ["de", "fr", "tr", "ar", "fa"]))
    }

    func testAbstandGroessteEinheit() {
        let now: Int64 = 10_000_000_000
        XCTAssertEqual(PriceFormat.ageParts(since: now - 7 * 86_400_000 - 5_000, now: now)?.value, 7)
        XCTAssertEqual(PriceFormat.ageParts(since: now - 7 * 86_400_000, now: now)?.unit, .day)
        XCTAssertEqual(PriceFormat.ageParts(since: now - 3 * 3_600_000, now: now)?.unit, .hour)
        XCTAssertEqual(PriceFormat.ageParts(since: now - 12 * 60_000, now: now)?.unit, .minute)
        XCTAssertEqual(PriceFormat.ageParts(since: now - 45_000, now: now)?.unit, .second)
        XCTAssertNil(PriceFormat.ageParts(since: 0, now: now))
        XCTAssertNil(PriceFormat.ageParts(since: now + 1_000, now: now))
    }

    func testAbstandInDerSprache() throws {
        let now: Int64 = 10_000_000_000
        // Englisch wie bisher «7d»; Deutsch nicht mehr englisch (z. B. «7 T.»)
        XCTAssertEqual(PriceFormat.age(since: now - 7 * 86_400_000, now: now, locale: Locale(identifier: "en_US")), "7d")
        let german = try XCTUnwrap(PriceFormat.age(since: now - 7 * 86_400_000, now: now, locale: Locale(identifier: "de_DE")))
        XCTAssertTrue(german.contains("7"))
        XCTAssertNotEqual(german, "7d")
    }
}
