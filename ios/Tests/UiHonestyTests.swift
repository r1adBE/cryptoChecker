import XCTest
@testable import CryptoChecker

/// Kleine Ehrlichkeits-Regeln der Oberfläche (Audit A-01, früher Quelltext-Wächter). Wie Android `UiHonestyTest.kt`.
final class UiHonestyTests: XCTestCase {
    /// Vergleich: die feste Spalte des gewählten Zeitraums entfällt (nie zweimal «7T» mit zwei Werten).
    func testCompareNeverShowsTheRangeTwice() {
        let columns: [(String, Double?)] = [("7D", 1), ("24h", 2), ("7D", 3), ("30D", 4)]
        let shown = CompareMath.withoutRepeatedRange(columns)
        XCTAssertEqual(shown.map(\.0), ["7D", "24h", "30D"])
        XCTAssertEqual(shown.first?.1, 1)
        XCTAssertEqual(CompareMath.withoutRepeatedRange([(String, Int)]()).count, 0)
    }

    /// Diagnose erst nach der Freischaltung, «Entwickler» nur im Debug-Build.
    func testAboutExtras() {
        XCTAssertEqual(AboutExtras.of(unlocked: false, debugBuild: true), AboutExtras(diagnostics: false, developer: false))
        XCTAssertEqual(AboutExtras.of(unlocked: true, debugBuild: false), AboutExtras(diagnostics: true, developer: false))
        XCTAssertEqual(AboutExtras.of(unlocked: true, debugBuild: true), AboutExtras(diagnostics: true, developer: true))
    }

    /// Sprachausgabe: Einstellungsseite und Meldung «Kurs vorlesen» sagen «nur bei offener App».
    func testSpeechSaysOpenAppOnly() {
        let openOnly = L("alarm_speak_ios_open_only")
        XCTAssertTrue(SpeechHints.settingsSubtitle.hasSuffix(openOnly))
        XCTAssertTrue(SpeechHints.speechOnToast.hasSuffix(openOnly))
        XCTAssertTrue(SpeechHints.speechOnToast.hasPrefix(L("watchlist_tts_speech_on")))
    }
}
