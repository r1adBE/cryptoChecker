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
        // Speicher-Hinweis: nur im unsignierten Simulator (kein Schlüsselbund der App Group), auf dem Gerät nur ohne
        // Schlüssel. Schwebt über der Liste; das Audit meldet dort «Contrast failed» für ihn und den verdeckten Titel,
        // obwohl Text #1B1B1B auf der Fläche #E5E2DB rund 13:1 hat — vorher schliessen
        let noticeClose = app.tagged(UiTestTags.storageNoticeClose)
        if noticeClose.waitForExistence(timeout: 3) {
            noticeClose.tap()
            XCTAssertTrue(wait(noticeClose, "exists == false"), "storage notice not closed")
        }
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
        // Langsamer CI-Mac: kam der Tipp nicht an (Tab nicht gewählt), einmal erneut tippen
        if !wait(tab, "isSelected == true", timeout: 5) { tab.tap() }
        XCTAssertTrue(wait(tab, "isSelected == true"), "\(tag) not selected")
    }

    /// Audit aller Arten; Ausnahmen aus `exceptions` und die gedeckelte Tableiste gelten als erledigt, jeder andere
    /// Befund lässt den Test scheitern (und steht zusätzlich als Zeile «A11Y-AUDIT» im Log).
    @MainActor
    private func audit(_ app: XCUIApplication, _ screen: String) throws {
        // Erst wenn Ein- und Ausblendungen fertig sind: mitten in der Blende misst das Audit halbtransparenten Text
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "settle")], timeout: 1.5)
        // Tableiste: ihre Beschriftungen sind bewusst gedeckelt (`AppTabBarMetrics`) und haben keine eigene Kennung —
        // erkannt über die Lage innerhalb einer Taste `tab_*` oder unterhalb der Oberkante der Leiste
        let tabButtons = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "tab_")).allElementsBoundByIndex
        let tabFrames: [CGRect] = tabButtons.map { $0.frame.insetBy(dx: -1, dy: -1) }
        let tabTop = tabFrames.map(\.minY).filter { $0 > 0 }.min() ?? 0
        let window = app.windows.firstMatch.frame
        let navBar = app.navigationBars.firstMatch.exists ? app.navigationBars.firstMatch.frame.insetBy(dx: -1, dy: -1) : .zero
        let handler: (XCUIAccessibilityAuditIssue) throws -> Bool = { issue in
            let identifier = issue.element?.identifier ?? ""
            let frame = issue.element?.frame ?? .zero
            let inTabBar = issue.auditType == .dynamicType && !frame.isEmpty
                && (tabFrames.contains { $0.contains(frame) } || (tabTop > 0 && frame.midY >= tabTop))
            // Kontrast eines Texts, der unter die durchscheinende Tableiste reicht oder aus dem Fenster ragt (grosse
            // Schrift, Arabisch: lange Startseite): gemessen wird dann gegen die Leiste bzw. nichts. Die Farben selbst
            // haben genug Kontrast (Theme.swift, z. B. #474747 auf #FBF9F6 rund 9:1); nur protokolliert
            let underBar = issue.auditType == .contrast && !frame.isEmpty
                && ((tabTop > 0 && frame.maxY > tabTop) || (!window.isEmpty && !window.contains(frame)))
            // Navigationsleiste: UIKit deckelt die Schrift des Titels (wie die Tableiste); langes Drücken zeigt ihn im
            // Large Content Viewer (`TabTitle`). Elementlose «Potentially inaccessible text»-Treffer (Texterkennung im
            // Bild ohne zugehöriges Element, nicht zuzuordnen): nur protokolliert
            let inNavBar = issue.auditType == .dynamicType && !frame.isEmpty && !navBar.isEmpty && navBar.contains(frame)
            let unattributed = issue.auditType == .elementDetection && issue.element == nil
            if inNavBar || unattributed {
                print("A11Y-AUDIT [\(screen)] (Leiste bzw. ohne Element, nicht gewertet) \(issue.compactDescription)")
            }
            if underBar {
                print("A11Y-AUDIT [\(screen)] (unter der Leiste, nicht gewertet) \(issue.compactDescription) — "
                      + "«\(issue.element?.label ?? "")»")
            }
            let known = inTabBar || underBar || inNavBar || unattributed || AccessibilityAuditTests.exceptions.contains { exception in
                issue.auditType == exception.type && identifier.hasPrefix(exception.identifierPrefix)
            }
            if !known {
                let label = issue.element?.label ?? ""
                print("A11Y-AUDIT [\(screen)] \(issue.compactDescription) — id «\(identifier)», «\(label)»")
            }
            return known
        }
        // Langsamer CI-Mac: «Audit failed to complete in time» (Code -56) einmal wiederholen, dann zählt der Fehler
        do {
            try app.performAccessibilityAudit(for: .all, handler)
        } catch let error as NSError where error.code == -56 {
            print("A11Y-AUDIT [\(screen)] Zeitgrenze des Audits, zweiter Versuch")
            try app.performAccessibilityAudit(for: .all, handler)
        }
    }
}
