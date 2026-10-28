import XCTest

/// Bildschirmfotos aller Hauptseiten, hell und dunkel — für den Workflow «Screenshots», der sie auf den Zweig
/// `screenshots` legt (DEVELOPMENT.md). Gleiche Seiten und Namen wie `ScreenshotTourTest.kt` (Android): Merkliste,
/// Aktionsblatt eines Paars, Alarme des Paars, Alarm-Editor, Alle Alarme, Paar hinzufügen, Coin-Suche, Markt,
/// Portfolio, Einstellungen mit «Light / Dark», «Alerts», «Backup & restore» und «About the app»; dazu der
/// Bericht «Last refresh» aus dem Menü der Merkliste (`06_refresh_report`).
///
/// Daten: die Demo-Sicherung der Store-Bilder (`android/docs/store/screenshots/demo-backup.json`, gelesen neben dieser
/// Quelle wie `fixture`), über «Wiederherstellen» eingespielt (`UITEST_BACKUP`). Darin gesetzt: Hell/Dunkel als
/// Einstellung der App (`darkMode`), Währung USD ohne Umrechnung (keine Wechselkurse ohne Netz) und je ein Alarm für
/// ETH und SOL, damit «Alarm» im Aktionsblatt die Alarm-Liste öffnet. Kurse aus `UITestExchange` (kein Netz).
///
/// Jedes Bild ist ein eigener Schritt (`step`) mit eigenem Weg über die Tab-Leiste: Scheitert einer, laufen die
/// übrigen weiter, am Ende schlägt der Test fehl und nennt die fehlenden Bilder. Bedienung und Wege zurück in
/// `ScreenshotTourSteps.swift`. Kein `app.open(URL)`: im ersten Lauf startete das die App neu (leerer Speicher,
/// `UITesting.prepareIfRequested`), «All alerts» zeigte dann 0 Alarme im hellen Modus.
///
/// Die Bilder sind Anhänge `NN_name_light` bzw. `NN_name_dark` (immer behalten); der Workflow holt sie mit
/// `xcresulttool export attachments` aus dem Result-Bundle. Läuft nur mit `TEST_RUNNER_SCREENSHOT_TOUR=1`
/// (xcodebuild gibt es als `SCREENSHOT_TOUR` an den Test weiter), sonst übersprungen — der normale UI-Testlauf
/// nimmt alle Klassen des Ziels.
final class ScreenshotTourTests: XCTestCase {

    /// «light» bzw. «dark»: Endung der Anhänge.
    var mode = "light"
    /// Bilder, die fehlen, mit Grund — am Ende der Tour gemeldet.
    var missing: [String] = []

    override func setUpWithError() throws {
        // Ein Fehler (auch eine Meldung von XCUITest selbst) beendet die Tour nicht; `step` merkt das Bild als fehlend
        continueAfterFailure = true
        guard ProcessInfo.processInfo.environment["SCREENSHOT_TOUR"] == "1" else {
            throw XCTSkip("Nur im Workflow «Screenshots» (TEST_RUNNER_SCREENSHOT_TOUR=1)")
        }
    }

    @MainActor
    func testTourLight() throws {
        try tour(dark: false)
    }

    @MainActor
    func testTourDark() throws {
        try tour(dark: true)
    }

    // MARK: Ablauf

    @MainActor
    private func tour(dark: Bool) throws {
        mode = dark ? "dark" : "light"
        missing = []
        let app = XCUIApplication()
        // Startargument zuletzt: ohne Wert, damit es kein «-AppleLocale»-Wert verschluckt
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", UiTestTags.launchArgument]
        app.launchEnvironment[UiTestTags.backupEnvironment] = try demoBackup(dark: dark)
        app.launch()
        closeStorageNotice(app)
        do {
            try restoreDemoBackup(app)
        } catch {
            XCTFail("Demo backup not restored: \(error)")
            return
        }

        // Einstellungen (nach dem Wiederherstellen offen): Sichern, Hauptseite, Unterseiten
        step("14_settings_backup") {
            try self.require(app.tagged(UiTestTags.backupExport).exists, "backup page left")
        }
        step("11_settings") {
            try self.toSettings(app)
            self.scrollToTop(app)
        }
        settingsPage(app, "Light / Dark", "12_settings_display")
        settingsPage(app, "Alerts", "13_settings_alarms")
        settingsPage(app, "About the app", "15_about")

        // Tabs Markt und Portfolio
        step("09_market") {
            try self.openTab(app, UiTestTags.tabCycle)
            // Quellen ausser den Fake-Börsen antworten 404: Fehlerzustände abwarten
            self.settle(4)
        }
        step("10_portfolio") {
            try self.openTab(app, UiTestTags.tabPortfolio)
            try self.require(app.tagged(UiTestTags.portfolioCoin("BTC")).waitForExistence(timeout: uiListTimeout),
                             "portfolio empty")
        }

        // Merkliste, Aktionsblatt → Alarme des Paars → Editor, Alle Alarme (Glocke), Bericht
        step("01_watchlist") { try self.toWatchlist(app) }
        step("02_pair_actions") { try self.openPairActions(app) }
        step("03_pair_alarms") { try self.openPairAlarms(app) }
        step("04_alarm_editor") {
            try self.openPairAlarms(app)
            try self.tap(app, UiTestTags.alarmCard)
            try self.requireTag(app, UiTestTags.alarmSave)
        }
        step("05_alarms_overview") { try self.openAlarmsOverview(app) }
        step("06_refresh_report") { try self.openRefreshReport(app) }

        // Paar hinzufügen und Coin-Suche «btc» (Paarlisten der Fake-Börsen laden, falls nötig)
        step("07_explorer") { try self.openExplorer(app) }
        step("08_coin_search") {
            try self.openExplorer(app)
            try self.tap(app, UiTestTags.explorerSearch)
            // «\n» = Suchen: Tastatur zu, damit nichts die Treffer verdeckt
            app.tagged(UiTestTags.explorerSearch).typeText("btc\n")
            let coin = app.tagged(UiTestTags.explorerCoin("BTC"))
            let load = app.tagged(UiTestTags.explorerListsLoad)
            try self.require(self.waitForAny([load, coin]), "no search result")
            if load.exists { load.tap() }
            try self.require(coin.waitForExistence(timeout: uiListTimeout), "BTC not found")
        }

        if !missing.isEmpty {
            XCTFail("Missing screenshots (\(mode)): " + missing.joined(separator: "; "))
        }
    }

    /// Ein Bild: `open` führt zur Seite, dann das Foto `name`. Wirft `open` oder meldet XCUITest dabei einen Fehler,
    /// wird das Bild als fehlend gemerkt (mit Grund) und die Tour läuft weiter; der nächste Schritt sucht seinen
    /// Ausgangspunkt selbst.
    @MainActor
    func step(_ name: String, _ open: () throws -> Void) {
        let failuresBefore = testRun?.totalFailureCount ?? 0
        do {
            try open()
            if (testRun?.totalFailureCount ?? 0) > failuresBefore {
                missing.append("\(name) (XCUITest failure, see log)")
                return
            }
            shot(name)
        } catch {
            missing.append("\(name) (\(error))")
        }
    }

    /// Unterseite der Einstellungen über ihre Beschriftung öffnen und fotografieren (eigener Schritt).
    @MainActor
    private func settingsPage(_ app: XCUIApplication, _ label: String, _ name: String) {
        step(name) {
            try self.toSettings(app)
            let row = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
            try self.require(row.waitForExistence(timeout: uiTimeout), "settings row \(label) missing")
            self.tapRow(app, row)
            // Unterseite offen: die Zeile «Sichern» der Hauptseite ist weg (nur gewartet, nicht verlangt)
            _ = self.wait(app.tagged(UiTestTags.settingsBackup), "exists == false", timeout: 5)
        }
    }

    /// Demo-Sicherung mit Hell/Dunkel (`darkMode`), USD ohne Umrechnung und je einem Alarm für alle Paare.
    private func demoBackup(dark: Bool, file: StaticString = #filePath) throws -> String {
        let root = URL(fileURLWithPath: "\(file)").deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let url = root.appendingPathComponent("android/docs/store/screenshots/demo-backup.json")
        var backup = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        var settings = backup["settings"] as? [String: Any] ?? [:]
        settings["darkMode"] = dark
        settings["portfolioCurrency"] = "USD"
        settings["showConverted"] = false
        backup["settings"] = settings
        // Alarmwährung CHF der Vorlage entfernen (null = Währung des Paars): ohne Netz keine Wechselkurse
        var alarms = (backup["alarms"] as? [[String: Any]] ?? []).map { alarm -> [String: Any] in
            var copy = alarm
            copy["currency"] = NSNull()
            return copy
        }
        if let template = alarms.first {
            let taken = Set(alarms.compactMap { $0["watchId"] as? Int })
            let watchIds = (backup["watches"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? Int }
            for (offset, id) in watchIds.filter({ !taken.contains($0) }).enumerated() {
                var extra = template
                extra["id"] = 100 + offset
                extra["watchId"] = id
                extra["condition"] = "PRICE_ABOVE"
                extra["threshold"] = id == 2 ? 4_000.0 : 250.0
                alarms.append(extra)
            }
        }
        backup["alarms"] = alarms
        let data = try JSONSerialization.data(withJSONObject: backup)
        return try XCTUnwrap(String(data: data, encoding: .utf8))
    }

    /// Einstellungen › Sichern › Wiederherstellen (Datei aus `UITEST_BACKUP`) → «Import»; Ergebnis-Dialog schliessen,
    /// Mitteilungs-Abfrage beantworten. Endet auf der Seite «Backup & restore».
    @MainActor
    private func restoreDemoBackup(_ app: XCUIApplication) throws {
        try openTab(app, UiTestTags.tabSettings)
        let row = try requireTag(app, UiTestTags.settingsBackup)
        tapRow(app, row)
        try tap(app, UiTestTags.backupImport)
        let confirm = app.tagged(UiTestTags.backupRestoreConfirm)
        if confirm.waitForExistence(timeout: uiTimeout) {
            confirm.tap()
        } else {
            // Kennungen erreichen nicht in jeder iOS-Fassung die Knöpfe einer Rückfrage: über die Beschriftung
            let byLabel = [app.alerts.firstMatch, app.sheets.firstMatch].map { $0.buttons["Import"] }
                .first { $0.exists }
            guard let byLabel else { throw TourStepError("restore confirmation missing") }
            byLabel.tap()
        }
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 5) {
            for label in ["Close", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                break
            }
        }
        allowNotificationsIfAsked(timeout: 6)
        try require(app.tagged(UiTestTags.backupExport).waitForExistence(timeout: uiTimeout), "backup page left")
        settle(2)
    }

    // MARK: Bildschirmfoto

    /// Ganzer Bildschirm als Anhang `name_mode`, auch bei Erfolg behalten (sonst löscht Xcode ihn).
    @MainActor
    private func shot(_ name: String) {
        settle(1.5)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "\(name)_\(mode)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
