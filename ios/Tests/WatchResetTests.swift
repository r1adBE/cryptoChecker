import XCTest
@testable import CryptoChecker

/// «App zurücksetzen» räumt auch die Uhr auf: `PhoneWatchLink.reset` setzt den Stand im Speicher wie nach der
/// Installation (Schalter «Portfolio-Wert auf der Uhr» aus) und sendet der Uhr Löschen, leere iPhone-Liste und leere
/// Kurse. Wie Android `PhoneWatchSync.clearWatch`. Reihenfolge und Inhalt von `reset` prüft die Regel `ios-wiring` in
/// `.github/scripts/source_rules.py`.
final class WatchResetTests: XCTestCase {

    /// Leere Kurse vom iPhone heissen auf der Uhr: keine Coins mehr vom iPhone; das leere Portfolio bleibt leer.
    func testEmptyQuotesAndPortfolioClearTheWatch() {
        XCTAssertEqual(WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson([])), [])
        XCTAssertEqual(WatchPortfolio.fromJson(WatchPortfolio.toJson(.clear)), .clear)
    }
}
