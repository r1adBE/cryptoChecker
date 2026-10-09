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
