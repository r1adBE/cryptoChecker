import XCTest
@testable import CryptoChecker

/// Kurze Zahlen und Sekunden in der Sprache der App (`LocaleNumbers.compact` / `.seconds`) — wie Android
/// `CompactAmount.number` und `seconds` (ICU). Geprüft wird nur das Stabile: Ziffern, Trennzeichen, Kürzel.
final class LocaleCompactTests: XCTestCase {

    func testCompactEnglishUsesShortSuffix() {
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(LocaleNumbers.compact(1_234_567, locale: en), "1.23M")
        XCTAssertEqual(LocaleNumbers.compact(12_300, locale: en), "12.3K")
    }

    func testCompactGermanUsesCommaAndLocalWord() {
        let text = LocaleNumbers.compact(1_200_000, locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(text.contains("1,2"), text)
        XCTAssertFalse(text.contains("M "), text)
    }

    func testCompactNonFiniteIsDash() {
        XCTAssertEqual(LocaleNumbers.compact(.nan), "—")
    }

    func testSecondsOneDecimalLocalized() {
        XCTAssertTrue(LocaleNumbers.seconds(500, locale: Locale(identifier: "en_US")).hasPrefix("0.5"))
        XCTAssertTrue(LocaleNumbers.seconds(1_260, locale: Locale(identifier: "de_DE")).hasPrefix("1,3"))
    }
}
