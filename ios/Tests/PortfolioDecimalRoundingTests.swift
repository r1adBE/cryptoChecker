import XCTest
@testable import CryptoChecker

/// Eine Rundungsregel (halbe auf gerade) für Rechnen (`PortfolioDecimal.amount`) und Speichern
/// (`PortfolioDecimal.text`); eine Menge unter 10⁻¹⁸ ist nach dem Runden 0 und damit ungültig. Wie
/// `PortfolioTxCleanupTest.kt` (Android).
final class PortfolioDecimalRoundingTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    func testEineRundungsregelFuerRechnenUndSpeichern() {
        for v in ["0.0000000000000000015", "0.0000000000000000025", "2.0000000000000000035", "0.0000000000000000005"] {
            XCTAssertEqual(PortfolioDecimal.amount(dec(v)), dec(PortfolioDecimal.text(dec(v))), v)
        }
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000015")), "0.000000000000000002")
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000025")), "0.000000000000000002")
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000005")), "0")
    }

    func testMengeUnterEinemWeiIstNachDemRundenNull() {
        // Das Erfassen-Blatt prüft die gerundete Menge (> 0): unter 1 Wei ist ungültig statt als 0 gespeichert
        XCTAssertEqual(PortfolioDecimal.amount(dec("0.0000000000000000004")), 0)
        XCTAssertEqual(PortfolioDecimal.amount(dec("1.0000000000000000005")), 1)
    }
}
