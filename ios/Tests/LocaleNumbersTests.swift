import XCTest
@testable import CryptoChecker

/// Wie `LocaleNumbersTest.kt`: Anzeige in den Ziffern der Sprache, Eingabe nimmt sie zurück.
final class LocaleNumbersTests: XCTestCase {

    // Ziffern ausdrücklich gewählt: so hängt der Test nicht davon ab, welche Ziffern «ar»
    // ohne Region auf diesem System als Standard hat
    private let arabic = Locale(identifier: "ar_EG@numbers=arab")
    private let persian = Locale(identifier: "fa_IR@numbers=arabext")
    private let posix = Locale(identifier: "en_US_POSIX")

    func testIntegerUsesLocaleDigits() {
        XCTAssertEqual(LocaleNumbers.integer(72, locale: arabic), "٧٢")
        XCTAssertEqual(LocaleNumbers.integer(72, locale: persian), "۷۲")
        XCTAssertEqual(LocaleNumbers.integer(72, locale: posix), "72")
        // Jahre ohne Tausendertrennung
        XCTAssertEqual(LocaleNumbers.integer(2024, locale: arabic), "٢٠٢٤")
        XCTAssertEqual(LocaleNumbers.integer(2024, locale: Locale(identifier: "de_CH")), "2024")
    }

    func testDecimalRoundsLikeFormatAndUsesLocaleDigits() {
        XCTAssertEqual(LocaleNumbers.decimal(58.44, maxDecimals: 1, locale: posix), "58.4")
        XCTAssertEqual(LocaleNumbers.decimal(0.25, maxDecimals: 1, locale: posix), "0.3")
        XCTAssertEqual(LocaleNumbers.decimal(1.5, maxDecimals: 3, minDecimals: 0, locale: posix), "1.5")
        XCTAssertEqual(LocaleNumbers.decimal(58.44, maxDecimals: 1, locale: arabic), "٥٨٫٤")
        let fa = LocaleNumbers.decimal(58.44, maxDecimals: 1, locale: persian)
        XCTAssertTrue(fa.hasPrefix("۵۸"), fa)
        XCTAssertTrue(fa.hasSuffix("۴"), fa)
    }

    func testDigitsMapsOnlyAsciiDigits() {
        XCTAssertEqual(LocaleNumbers.digits("3 / 10", locale: arabic), "٣ / ١٠")
        XCTAssertEqual(LocaleNumbers.digits("12s", locale: persian), "۱۲s")
        XCTAssertEqual(LocaleNumbers.digits("3 / 10", locale: posix), "3 / 10")
    }

    func testLocalizedOutputParsesBack() {
        // Eingaben nehmen lokale Ziffern an: Anzeige → Eingabe ergibt denselben Wert
        XCTAssertEqual(ThresholdParser.latinDigits(LocaleNumbers.decimal(58.4, maxDecimals: 1, locale: arabic)), "58.4")
        XCTAssertEqual(ThresholdParser.latinDigits(LocaleNumbers.integer(1234, locale: persian)), "1234")
    }

    func testGasFeesFollowLocale() {
        XCTAssertEqual(GasFees.formatGwei(1.44, locale: posix), "1.4")
        XCTAssertEqual(GasFees.formatGwei(1.44, locale: arabic), "١٫٤")
        XCTAssertEqual(GasFees.formatGwei(23.2, locale: arabic), "٢٣")
        XCTAssertEqual(GasFees.formatGwei(0.0123, locale: posix), "0.012")
        XCTAssertEqual(GasFees.formatUsd(0.63, locale: posix), "$0.63")
        XCTAssertEqual(GasFees.formatUsd(0.004, locale: posix), "<$0.01")
    }

    /// Wie `LocaleNumbersTest.displayUsesSameLocaleAsInput` (Android): Anzeige und Eingabe mit
    /// derselben Locale (Sprache + Region des Geräts).
    func testDisplayUsesSameLocaleAsInput() {
        let deCH = LocaleNumbers.formatLocale(language: "de", region: "CH")
        XCTAssertEqual(deCH.identifier, "de_CH")
        // Tausendertrenner de_CH je nach ICU-Stand ’ (U+2019, neueres CLDR) oder ' (Apple auf dem CI-Mac)
        XCTAssertTrue(["90\u{2019}000.00", "90'000.00"]
            .contains(LocaleNumbers.decimal(90_000, maxDecimals: 2, locale: deCH, grouping: true)))
        XCTAssertEqual(LocaleNumbers.decimal(90_000, maxDecimals: 2,
                                             locale: LocaleNumbers.formatLocale(language: "de", region: "DE"), grouping: true),
                       "90.000,00")
        XCTAssertEqual(LocaleNumbers.decimal(90_000, maxDecimals: 2,
                                             locale: LocaleNumbers.formatLocale(language: "en-US", region: "US"), grouping: true),
                       "90,000.00")
        // Ungültige Region: nur die Sprache
        XCTAssertEqual(LocaleNumbers.formatLocale(language: "de", region: "not a region").identifier, "de")
        let cases: [(String, String)] = [("de", "CH"), ("de", "DE"), ("fr", "CH"), ("en-US", "US"), ("it", "CH")]
        for (language, region) in cases {
            let locale = LocaleNumbers.formatLocale(language: language, region: region)
            let separator = ThresholdParser.decimalSeparator(locale)
            let shown = LocaleNumbers.decimal(1234.5, maxDecimals: 2, locale: locale, grouping: true)
            XCTAssertEqual(shown[shown.index(shown.endIndex, offsetBy: -3)], separator, "\(language)-\(region)")
            XCTAssertEqual(ThresholdParser.parse(shown, decimalSeparator: separator) ?? 0, 1234.5, accuracy: 1e-9,
                           "\(language)-\(region)")
        }
    }
}
