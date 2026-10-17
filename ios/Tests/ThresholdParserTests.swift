import XCTest
@testable import CryptoChecker

/// Wie `ThresholdParserTest.kt` (alle Fälle zusätzlich in `ParityFixtureTests`).
final class ThresholdParserTests: XCTestCase {

    private func p(_ text: String, _ decimal: Character = ".", hint: Double? = nil) -> Double? {
        ThresholdParser.parse(text, decimalSeparator: decimal, priceHint: hint)
    }

    private func eq(_ expected: Double, _ actual: Double?, file: StaticString = #filePath, line: UInt = #line) {
        guard let actual else { return XCTFail("nil statt \(expected)", file: file, line: line) }
        XCTAssertEqual(actual, expected, accuracy: expected * 1e-12, file: file, line: line)
    }

    func testAmbiguousSeparatorFollowsTheCurrentPrice() {
        eq(60_000, p("60,000", ",", hint: 65_000))
        eq(60, p("60,000", ",", hint: 58))
        eq(60_000, p("60.000", ".", hint: 65_000))
        eq(60, p("60.000", ".", hint: 58))
    }

    func testAmbiguousWithoutPriceFollowsTheRegion() {
        eq(60, p("60,000", ","))
        eq(60_000, p("60,000", "."))
        eq(60, p("60.000", "."))
        eq(60_000, p("60.000", ","))
    }

    func testGroupingCharactersAndBothSeparators() {
        eq(60_000, p("60\u{2019}000"))
        eq(60_000, p("60\u{00A0}000"))
        eq(60_000.5, p(" 60 000,5 ", ","))
        eq(1_234.56, p("1.234,56"))
        eq(1_234_567.89, p("1\u{2019}234\u{2019}567.89"))
        eq(1_234_567, p("1.234.567"))
        eq(0.00012, p("0,00012", ".", hint: 60_000))
        eq(0.123, p("0,123", ".", hint: 60_000))
    }

    func testInvalidInput() {
        for text in ["", "   ", "60k", "1e5", "-5", "0", "0,000", "1,2,3", "1.234,5,6", "12,34.5", ".", "abc"] {
            XCTAssertNil(p(text), "'\(text)'")
        }
    }

    func testArabicIndicAndPersianDigitsReadLikeLatinDigits() {
        // ٦٠٠٠٠ / ۶۰۰۰۰ = 60000; «٫» Dezimalzeichen, «٬» Tausendertrennung
        eq(60_000, p("\u{0666}\u{0660}\u{0660}\u{0660}\u{0660}"))
        eq(60_000, p("\u{06F6}\u{06F0}\u{06F0}\u{06F0}\u{06F0}"))
        eq(1_234.5, p("\u{0661}\u{066C}\u{0662}\u{0663}\u{0664}\u{066B}\u{0665}"))
        eq(0.5, p("\u{0660}\u{066B}\u{0665}"))
        // Richtungszeichen aus eingefügtem Text stören nicht
        eq(42, p("\u{200E}42\u{200F}"))
        eq(42, p("\u{2066}42\u{2069}"))
        XCTAssertEqual(ThresholdParser.latinDigits("\u{0661}\u{0662}\u{0663}\u{0664}\u{066B}\u{0665}"), "1234.5")
        XCTAssertEqual(ThresholdParser.latinDigits("abc"), "abc")
        XCTAssertNil(p("\u{0660}"))
    }

    /// Wie `zero is allowed for amounts, everything else like parse` (ThresholdParserTest.kt).
    func testZeroIsAllowedForAmountsEverythingElseLikeParse() {
        XCTAssertEqual(ThresholdParser.parseAllowingZero("0", decimalSeparator: "."), 0)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("0,00", decimalSeparator: ","), 0)
        XCTAssertEqual(ThresholdParser.parseAllowingZero(".0", decimalSeparator: "."), 0)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("\u{0660}", decimalSeparator: "."), 0)
        // Mehrdeutig: Region bzw. Kurs entscheidet wie bei den Schwellwerten
        XCTAssertEqual(ThresholdParser.parseAllowingZero("60.000", decimalSeparator: ","), 60_000)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("60.000", decimalSeparator: "."), 60)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("60.000", decimalSeparator: ",", priceHint: 58), 60)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("1,234", decimalSeparator: "."), 1_234)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("1.234,56", decimalSeparator: "."), 1_234.56)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("1\u{00A0}234,5", decimalSeparator: ","), 1_234.5)
        XCTAssertEqual(ThresholdParser.parseAllowingZero("1\u{202F}234.5", decimalSeparator: "."), 1_234.5)
        for bad in ["1.5f", "2d", "1e3", "-5", "NaN", "Infinity", "", " ", "0,0,0"] {
            XCTAssertNil(ThresholdParser.parseAllowingZero(bad, decimalSeparator: "."), bad)
        }
    }

    /// Melde-Schwelle in den Einstellungen: «5%», «5 %», «٥٪» = 5 (wie `parsePercent` in Android).
    func testSettingsPercentParse() {
        XCTAssertEqual(SettingsPercentOption.parse("5%"), 5)
        XCTAssertEqual(SettingsPercentOption.parse("5\u{00A0}%"), 5)
        XCTAssertEqual(SettingsPercentOption.parse("\u{0665}\u{066A}"), 5)
        XCTAssertEqual(SettingsPercentOption.parse("2,5"), 2.5)
        XCTAssertEqual(SettingsPercentOption.parse(".5"), 0.5)
        for bad in ["1e1", "nan", "5f", "101", "-1", "", "%", "1.2.3"] {
            XCTAssertNil(SettingsPercentOption.parse(bad), bad)
        }
    }

    /// Gespeicherter Wert fürs Eingabefeld ohne Stellenbegrenzung (wie `toPlainString` in Android).
    func testAmountForInputKeepsTinyValuesAndZero() {
        XCTAssertEqual(PriceFormat.amountForInput(0), "0")
        XCTAssertEqual(PriceFormat.amountForInput(nil), "")
        XCTAssertEqual(PriceFormat.amountForInput(1.2345e-10), "0.00000000012345")
        XCTAssertEqual(PriceFormat.amountForInput(1234.5, decimalSeparator: ","), "1234,5")
        XCTAssertEqual(DecimalText.rounded(1.005, 2), 1.01)
        XCTAssertEqual(PriceFormat.changePercent(1.005).map(BidiText.strip)?.hasSuffix("01%"), true)
    }

    func testBidiTextOnlyForRightToLeftLanguages() {
        XCTAssertEqual(BidiText.ltr("+1.20%", language: "en"), "+1.20%")
        XCTAssertEqual(BidiText.ltr("+1.20%", language: "ar"), "\u{2066}+1.20%\u{2069}")
        XCTAssertEqual(BidiText.isolate("Binance", language: "he"), "\u{2068}Binance\u{2069}")
        XCTAssertEqual(BidiText.isolate("Binance", language: "fa"), "\u{2068}Binance\u{2069}")
        XCTAssertEqual(BidiText.isolate("Binance", language: "de"), "Binance")
        XCTAssertEqual(BidiText.ltr("", language: "ar"), "")
        XCTAssertEqual(BidiText.language(of: "ar-EG"), "ar")
        XCTAssertEqual(BidiText.language(of: "he_IL"), "he")
        XCTAssertTrue(BidiText.isRtl("iw"))
        XCTAssertFalse(BidiText.isRtl("en"))
        XCTAssertEqual(BidiText.strip(BidiText.isolate("BTC/USDT", language: "ar")), "BTC/USDT")
    }
}
