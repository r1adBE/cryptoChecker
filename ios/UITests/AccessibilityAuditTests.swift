import XCTest

/// Automatische Barrierefreiheits-Prüfung (`performAccessibilityAudit`, iOS 17) der vier Hauptbereiche Merkliste,
/// Portfolio, Alarme und Einstellungen — wie `AccessibilityAuditTest.kt` (Android). Je ein Lauf in Standardgrösse,
/// mit der grössten Schrift (`AccessibilityXXXL`) und auf Arabisch (Rechts nach links): Die Bildschirme laden und das
/// Audit findet nichts ausser den begründeten Ausnahmen (`Exception`).
///
/// Start mit leerem Speicher und festen Börsen-Antworten (`-uiTesting`, wie `CryptoCheckerUITests`); die
/// Alarm-Übersicht öffnet der Link `cryptochecker://alarms` (wie die App-Verknüpfung).
final class AccessibilityAuditTests: XCTestCase {

    /// Bekannte, unvermeidbare Meldung: Art, Kennung des Elements (Präfix) und Begründung.
    private struct Exception: Sendable {
        let type: XCUIAccessibilityAuditType
        let identifierPrefix: String
        let reason: String
    }

    private static let exceptions: [Exception] = [
        Exception(type: .dynamicType, identifierPrefix: "tab_",
                  reason: "Tableiste: Schrift bewusst bis accessibility1 gedeckelt (AppTabBarMetrics.maxTypeSize); "
                      + "darüber zeigt langes Drücken den Large Content Viewer (HIG für Leisten)"),
    ]

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testMainScreensPassAudit() throws {
        try auditMainScreens(launch(language: "en", locale: "en_US"))
    }

    @MainActor
    func testMainScreensPassAuditWithLargestText() throws {
        try auditMainScreens(launch(language: "en", locale: "en_US",
                                    contentSize: "UICTContentSizeCategoryAccessibilityXXXL"))
    }

    @MainActor
    func testMainScreensPassAuditRightToLeft() throws {
        try auditMainScreens(launch(language: "ar", locale: "ar"))
    }

    // MARK: Ablauf

    /// Startet die App wie `launchApp` (leerer Speicher, feste Antworten), aber in `language` und optional mit der
    /// Schriftgrösse `contentSize`.
    @MainActor
    private func launch(language: String, locale: String, contentSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", locale]
        if let contentSize {
            arguments += ["-UIPreferredContentSizeCategoryName", contentSize]
        }
        // Startargument zuletzt: ohne Wert, damit es kein anderes Argument verschluckt
        app.launchArguments = arguments + [UiTestTags.launchArgument]
        app.launch()
        return app
    }

    /// Merkliste, Portfolio und Einstellungen über die Tableiste, dann die Alarm-Übersicht über den Link.
    @MainActor
    private func auditMainScreens(_ app: XCUIApplication) throws {
        XCTAssertTrue(app.tagged(UiTestTags.tabWatchlist).waitForExistence(timeout: uiTimeout), "watchlist not loaded")
        try audit(app, "watchlist")

        openTab(app, UiTestTags.tabPortfolio)
        try audit(app, "portfolio")

        openTab(app, UiTestTags.tabSettings)
        try audit(app, "settings")

        openTab(app, UiTestTags.tabWatchlist)
        app.open(URL(string: "cryptochecker://alarms")!)
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: uiTimeout), "alarms overview not loaded")
        try audit(app, "alarms")
    }

    @MainActor
    private func openTab(_ app: XCUIApplication, _ tag: String) {
        let tab = app.tagged(tag)
        XCTAssertTrue(tab.waitForExistence(timeout: uiTimeout), "\(tag) missing")
        tab.tap()
        XCTAssertTrue(wait(tab, "isSelected == true"), "\(tag) not selected")
    }

    /// Audit aller Arten; Ausnahmen aus `exceptions` gelten als erledigt (Handler gibt `true` zurück).
    @MainActor
    private func audit(_ app: XCUIApplication, _ screen: String) throws {
        try app.performAccessibilityAudit(for: .all) { issue in
            let identifier = issue.element?.identifier ?? ""
            let known = AccessibilityAuditTests.exceptions.contains { exception in
                issue.auditType == exception.type && identifier.hasPrefix(exception.identifierPrefix)
            }
            if !known {
                let label = issue.element?.label ?? ""
                print("[\(screen)] \(issue.compactDescription) — id «\(identifier)», «\(label)»")
            }
            return known
        }
    }
}
