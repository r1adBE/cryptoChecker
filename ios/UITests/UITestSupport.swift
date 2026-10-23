import XCTest

/// Normales Warten auf ein Element bzw. einen Zustand (Sekunden).
let uiTimeout: TimeInterval = 15
/// Paarlisten aller Börsen laden (die meisten antworten 404) bzw. Datei lesen und Kurse holen.
let uiListTimeout: TimeInterval = 60

/// Hilfen der UI-Tests: Start mit den Test-Nahtstellen der App (`UITesting`, nur Debug-Build), Elemente über
/// `UiTestTags`, Warten statt fester Pausen und die Mitteilungs-Abfrage des Systems.
extension XCTestCase {
    /// App frisch starten: Speicher leer (wie eine Neuinstallation), feste Börsen-Antworten, Englisch.
    /// `csv`: Text, den «CSV importieren» statt der Dateiauswahl bekommt; `keepBackup`: Sicherung des
    /// vorigen Starts behalten (Wiederherstellen nach «Daten weg»); `backup`: Sicherungsdatei, die «Wiederherstellen»
    /// statt der Dateiauswahl bekommt.
    @MainActor
    func launchApp(csv: String? = nil, keepBackup: Bool = false, backup: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        // Startargument zuletzt: ohne Wert, damit es kein «-AppleLocale»-Wert verschluckt
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", UiTestTags.launchArgument]
        if let csv { app.launchEnvironment[UiTestTags.csvEnvironment] = csv }
        if keepBackup { app.launchEnvironment[UiTestTags.keepBackupEnvironment] = "1" }
        if let backup { app.launchEnvironment[UiTestTags.backupEnvironment] = backup }
        // Rückfallebene, falls die Mitteilungs-Abfrage eine Eingabe blockiert (sonst `allowNotificationsIfAsked`)
        addUIInterruptionMonitor(withDescription: "Notification permission") { alert in
            let allow = alert.buttons["Allow"]
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
        app.launch()
        return app
    }

    /// Gemeinsame Testdatei (Kopie von android/testdata/uitest, tools/gen_xcodeproj.py) — gelesen neben dieser
    /// Quelle wie `Tests/Csv`: der Test läuft im Simulator auf dem Mac.
    func fixture(_ name: String, file: StaticString = #filePath) throws -> String {
        let url = URL(fileURLWithPath: "\(file)").deletingLastPathComponent().appendingPathComponent("Fixtures/\(name)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Wartet, bis die Bedingung (NSPredicate) für das Element gilt.
    @MainActor
    @discardableResult
    func wait(_ element: XCUIElement, _ format: String, timeout: TimeInterval = uiTimeout) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: format), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// Wartet, bis eines der Elemente da ist.
    @MainActor
    func waitForAny(_ elements: [XCUIElement], timeout: TimeInterval = uiTimeout) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if elements.contains(where: { $0.exists }) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return false
    }

    /// Mitteilungs-Erlaubnis erteilen, falls gefragt wird (neue Paare, Alarm, Wiederherstellen): zuerst «Weiter»
    /// der Erklärung der App (`NotificationPrePrompt`; erscheint nicht, wenn das System nicht mehr fragen würde),
    /// dann die Abfrage des Systems. Das System spricht seine eigene Sprache: «Allow», sonst der rechte Knopf.
    @MainActor
    func allowNotificationsIfAsked(timeout: TimeInterval = 3) {
        // Die laufende App (ohne Neustart); Kennung, sonst die Beschriftung im Abfrage-Fenster
        let app = XCUIApplication()
        let proceed = app.tagged(UiTestTags.notificationPrepromptContinue)
        let proceedLabel = app.alerts.firstMatch.buttons["Continue"]
        if waitForAny([proceed, proceedLabel], timeout: timeout) {
            if proceed.exists { proceed.tap() } else { proceedLabel.tap() }
        }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        guard alert.waitForExistence(timeout: timeout) else { return }
        let allow = alert.buttons["Allow"]
        if allow.exists {
            allow.tap()
        } else if alert.buttons.count > 1 {
            alert.buttons.element(boundBy: 1).tap()
        }
    }
}

extension XCUIApplication {
    /// Erstes Element mit der Kennung (`UiTestTags`), gleich welcher Art (Knopf, Feld, Text).
    func tagged(_ tag: String) -> XCUIElement {
        descendants(matching: .any).matching(identifier: tag).firstMatch
    }
}
