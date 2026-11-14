import XCTest
@testable import CryptoChecker

/// Eigene Melde-Schwelle: Eingabefilter und Prüfung — gleich wie Android `PercentInputTest`.
/// «Sichern» im `SettingsPercentSheet` ist genau dann aktiv, wenn `parse` einen Wert liefert.
final class SettingsPercentInputTests: XCTestCase {

    private func typed(_ input: String) -> Double? {
        SettingsPercentOption.parse(SettingsPercentOption.filterInput(input))
    }

    func testArabischesUndPersischesDezimalzeichenBleibt() {
        XCTAssertEqual(SettingsPercentOption.filterInput("٢٫٥"), "٢٫٥")
        XCTAssertEqual(typed("٢٫٥"), 2.5)
        XCTAssertEqual(typed("۲٫۵"), 2.5)
        XCTAssertEqual(typed("2,5"), 2.5)
        XCTAssertEqual(typed("2.5"), 2.5)
    }

    func testTausendertrennungWirdAbgelehntStattVerschluckt() {
        XCTAssertEqual(SettingsPercentOption.filterInput("١٬٥"), "١٬٥")
        XCTAssertNil(typed("١٬٥"))
        XCTAssertTrue(SettingsPercentOption.inputError(SettingsPercentOption.filterInput("١٬٥")))
    }

    func testFilterEntferntNichtsStillUndKuerztAufSechsZeichen() {
        // Eingefügt: Buchstabe, Exponent, Vorzeichen bleiben stehen und gelten als Fehler (nie still «25», «13», «0»)
        XCTAssertEqual(SettingsPercentOption.filterInput("2a5 %"), "2a5%")
        XCTAssertNil(typed("2a5 %"))
        XCTAssertNil(typed("1e3"))
        XCTAssertNil(typed("-0"))
        XCTAssertTrue(SettingsPercentOption.inputError(SettingsPercentOption.filterInput("1e3")))
        XCTAssertEqual(typed("5 %"), 5)
        XCTAssertEqual(SettingsPercentOption.filterInput("1234567"), "123456")
    }

    func testFehlerErstBeiUngueltigerEingabe() {
        XCTAssertFalse(SettingsPercentOption.inputError(""))
        XCTAssertFalse(SettingsPercentOption.inputError("0"))
        XCTAssertFalse(SettingsPercentOption.inputError("100"))
        XCTAssertTrue(SettingsPercentOption.inputError("150"))
        XCTAssertTrue(SettingsPercentOption.inputError("1.2.3"))
        XCTAssertTrue(SettingsPercentOption.inputError(","))
    }
}
