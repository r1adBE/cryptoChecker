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

    func testZeichenFuerEingabefelder() {
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "en_US")), "%")
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "de_DE")), "%")
        // Türkisch: im Feld ebenfalls hinten, nur das Zeichen (wie Android)
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "tr_TR")), "%")
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "ar_EG@numbers=arab")), "\u{066A}")
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "fa_IR@numbers=arabext")), "\u{066A}")
        XCTAssertEqual(PercentText.symbol(locale: Locale(identifier: "ar_EG@numbers=latn")), "%")
    }

    func testAbstandNachSpracheUndRegion() {
        // Spanisch (Mexiko) ohne Abstand (CLDR es_MX), die übrigen spanischen Regionen mit
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "es_MX")), "2.4%")
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "es_US")), "2.4\u{00A0}%")
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "es_419")), "2.4\u{00A0}%")
        // Englisch in Deutschland, Österreich, Schweden, Finnland, Dänemark mit Abstand; in der Schweiz und den USA ohne
        XCTAssertEqual(PercentText.wrap("2,4", locale: Locale(identifier: "en_DE")), "2,4\u{00A0}%")
        XCTAssertEqual(PercentText.wrap("2,4", locale: Locale(identifier: "en_SE")), "2,4\u{00A0}%")
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "en_CH")), "2.4%")
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "en_US")), "2.4%")
        XCTAssertEqual(PercentText.wrap("2.4", locale: Locale(identifier: "de_LI")), "2.4%")
        XCTAssertEqual(PercentText.wrap("2,4", locale: Locale(identifier: "fr_CH")), "2,4%")
    }

    func testMitteilungsprozentOhneVorzeichenBeiGerundetNull() {
        let us = Locale(identifier: "en_US")
        XCTAssertEqual(PriceFormat.changePercentDetailed(1.2346, locale: us), BidiText.ltr("+1.235%"))
        XCTAssertEqual(PriceFormat.changePercentDetailed(-0.5, locale: us), BidiText.ltr("\u{2212}0.500%"))
        // Gerundet 0: weder «+0.000%» noch «−0.000%»
        XCTAssertEqual(PriceFormat.changePercentDetailed(0.0004, locale: us), BidiText.ltr("0.000%"))
        XCTAssertEqual(PriceFormat.changePercentDetailed(-0.0004, locale: us), BidiText.ltr("0.000%"))
        XCTAssertEqual(PriceFormat.changePercentDetailed(0, locale: us), BidiText.ltr("0.000%"))
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
        // Englisch «7d». Die Einheit in anderen Sprachen kommt aus den Plattformdaten: im Test-Simulator (iOS 26)
        // liefert Foundation für de ebenfalls «7d», darum hier nur Zahl und Länge geprüft (CI).
        XCTAssertEqual(PriceFormat.age(since: now - 7 * 86_400_000, now: now, locale: Locale(identifier: "en_US")), "7d")
        let german = try XCTUnwrap(PriceFormat.age(since: now - 7 * 86_400_000, now: now, locale: Locale(identifier: "de_DE")))
        XCTAssertTrue(german.contains("7"))
        XCTAssertLessThan(german.count, 8)
    }
}
