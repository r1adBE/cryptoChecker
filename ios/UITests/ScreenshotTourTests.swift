import XCTest

/// Bildschirmfotos aller Hauptseiten, hell und dunkel — für den Workflow «Screenshots», der sie auf den Zweig
/// `screenshots` legt (DEVELOPMENT.md). Gleiche Seiten und Namen wie `ScreenshotTourTest.kt` (Android): Merkliste,
/// Aktionsblatt eines Paars, Alarme des Paars, Alarm-Editor, Alle Alarme, Paar hinzufügen, Coin-Suche, Markt,
/// Portfolio, Einstellungen mit «Light / Dark», «Alerts», «Backup & restore» und «About the app». Den Bericht
/// «Letzte Aktualisierung» (Android `06_refresh_report`) gibt es hier nicht als eigenes Bild.
///
/// Daten: die Demo-Sicherung der Store-Bilder (`android/docs/store/screenshots/demo-backup.json`, gelesen neben dieser
/// Quelle wie `fixture`), über «Wiederherstellen» eingespielt (`UITEST_BACKUP`). Darin gesetzt: Hell/Dunkel als
/// Einstellung der App (`darkMode`), Währung USD ohne Umrechnung (keine Wechselkurse ohne Netz) und je ein Alarm für
/// ETH und SOL, damit «Alarm» im Aktionsblatt die Alarm-Liste öffnet. Kurse aus `UITestExchange` (kein Netz).
///
/// Jedes Bild ist ein Anhang `NN_name_light` bzw. `NN_name_dark` (immer behalten); der Workflow holt sie mit
/// `xcresulttool export attachments` aus dem Result-Bundle. Läuft nur mit `TEST_RUNNER_SCREENSHOT_TOUR=1`
/// (xcodebuild gibt es als `SCREENSHOT_TOUR` an den Test weiter), sonst übersprungen — der normale UI-Testlauf
/// nimmt alle Klassen des Ziels.
final class ScreenshotTourTests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
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
        let mode = dark ? "dark" : "light"
        let app = XCUIApplication()
        // Startargument zuletzt: ohne Wert, damit es kein «-AppleLocale»-Wert verschluckt
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", UiTestTags.launchArgument]
        app.launchEnvironment[UiTestTags.backupEnvironment] = try demoBackup(dark: dark)
        app.launch()
        closeStorageNotice(app)
        restoreDemoBackup(app)

        // Einstellungen (nach dem Wiederherstellen offen): Sichern, Hauptseite, Unterseiten
        shot("14_settings_backup", mode)
        goBack(app)
        XCTAssertTrue(app.tagged(UiTestTags.settingsBackup).waitForExistence(timeout: uiTimeout), "settings missing")
        scrollToTop(app)
        shot("11_settings", mode)
        settingsPage(app, "Light / Dark", "12_settings_display", mode)
        settingsPage(app, "Alerts", "13_settings_alarms", mode)
        settingsPage(app, "About the app", "15_about", mode)

        // Tabs Markt und Portfolio
        openTab(app, UiTestTags.tabCycle)
        // Quellen ausser den Fake-Börsen antworten 404: Fehlerzustände abwarten
        settle(4)
        shot("09_market", mode)
        openTab(app, UiTestTags.tabPortfolio)
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("BTC")).waitForExistence(timeout: uiListTimeout), "portfolio empty")
        shot("10_portfolio", mode)

        // Merkliste, Alle Alarme (Link wie die App-Verknüpfung)
        openTab(app, UiTestTags.tabWatchlist)
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiListTimeout), "watchlist empty")
        shot("01_watchlist", mode)
        app.open(URL(string: "cryptochecker://alarms")!)
        // Die Übersicht hat keine eigene Kennung: warten, bis sie die Merkliste verdeckt
        XCTAssertTrue(wait(app.tagged(UiTestTags.watchRow), "isHittable == false"), "alarms overview not opened")
        shot("05_alarms_overview", mode)
        backToWatchlist(app)

        // Paar hinzufügen und Coin-Suche «btc» (Paarlisten der Fake-Börsen laden, falls nötig)
        // «+» im Kopf der Merkliste; fehlt er, der Link der App-Verknüpfung
        if app.tagged(UiTestTags.watchlistAddPair).waitForExistence(timeout: 5) {
            tap(app, UiTestTags.watchlistAddPair)
        } else {
            app.open(URL(string: "cryptochecker://add")!)
        }
        XCTAssertTrue(app.tagged(UiTestTags.explorerSearch).waitForExistence(timeout: uiTimeout), "explorer missing")
        shot("07_explorer", mode)
        tap(app, UiTestTags.explorerSearch)
        // «\n» = Suchen: Tastatur zu, damit nichts die Treffer verdeckt
        app.tagged(UiTestTags.explorerSearch).typeText("btc\n")
        let coin = app.tagged(UiTestTags.explorerCoin("BTC"))
        let load = app.tagged(UiTestTags.explorerListsLoad)
        XCTAssertTrue(waitForAny([load, coin]), "no search result")
        if load.exists { load.tap() }
        XCTAssertTrue(coin.waitForExistence(timeout: uiListTimeout), "BTC not found")
        shot("08_coin_search", mode)
        backToWatchlist(app)

        // Aktionsblatt → Alarme des Paars → Editor (zuletzt: das Blatt bleibt offen, der Test endet)
        tap(app, UiTestTags.watchRow)
        XCTAssertTrue(app.tagged(UiTestTags.watchActionAlarm).waitForExistence(timeout: uiTimeout), "action sheet missing")
        shot("02_pair_actions", mode)
        tap(app, UiTestTags.watchActionAlarm)
        XCTAssertTrue(app.tagged(UiTestTags.alarmCard).waitForExistence(timeout: uiTimeout), "pair alarms missing")
        shot("03_pair_alarms", mode)
        tap(app, UiTestTags.alarmCard)
        XCTAssertTrue(app.tagged(UiTestTags.alarmSave).waitForExistence(timeout: uiTimeout), "alarm editor missing")
        shot("04_alarm_editor", mode)
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
    private func restoreDemoBackup(_ app: XCUIApplication) {
        openTab(app, UiTestTags.tabSettings)
        let row = app.tagged(UiTestTags.settingsBackup)
        XCTAssertTrue(row.waitForExistence(timeout: uiTimeout), "\(UiTestTags.settingsBackup) missing")
        tapRow(app, row)
        tap(app, UiTestTags.backupImport)
        let confirm = app.tagged(UiTestTags.backupRestoreConfirm)
        if confirm.waitForExistence(timeout: uiTimeout) {
            confirm.tap()
        } else {
            // Kennungen erreichen nicht in jeder iOS-Fassung die Knöpfe einer Rückfrage: über die Beschriftung
            let byLabel = [app.alerts.firstMatch, app.sheets.firstMatch].map { $0.buttons["Import"] }
                .first { $0.exists }
            XCTAssertNotNil(byLabel, "restore confirmation missing")
            byLabel?.tap()
        }
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 5) {
            for label in ["Close", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                break
            }
        }
        allowNotificationsIfAsked(timeout: 6)
        XCTAssertTrue(app.tagged(UiTestTags.backupExport).waitForExistence(timeout: uiTimeout), "backup page left")
        settle(2)
    }

    // MARK: Bildschirmfoto

    /// Ganzer Bildschirm als Anhang `name_mode`, auch bei Erfolg behalten (sonst löscht Xcode ihn).
    @MainActor
    private func shot(_ name: String, _ mode: String) {
        settle(1.5)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "\(name)_\(mode)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    // MARK: Navigation

    /// Unterseite der Einstellungen über ihre Beschriftung öffnen, fotografieren, zurück zur Hauptseite.
    @MainActor
    private func settingsPage(_ app: XCUIApplication, _ label: String, _ name: String, _ mode: String) {
        let row = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: uiTimeout), "settings row \(label) missing")
        tapRow(app, row)
        // Unterseite offen: die Zeile «Sichern» der Hauptseite ist weg (nur gewartet, nicht verlangt)
        _ = wait(app.tagged(UiTestTags.settingsBackup), "exists == false", timeout: 5)
        shot(name, mode)
        goBack(app)
        XCTAssertTrue(app.tagged(UiTestTags.settingsBackup).waitForExistence(timeout: uiTimeout), "settings missing")
    }

    /// Zurück-Knopf der Navigationsleiste (erster Knopf links).
    @MainActor
    private func goBack(_ app: XCUIApplication) {
        let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: uiTimeout), "back button missing")
        back.tap()
        settle(1)
    }

    /// Zurück, bis die Merkliste wieder oben liegt (höchstens dreimal).
    @MainActor
    private func backToWatchlist(_ app: XCUIApplication) {
        for _ in 0..<3 {
            if app.tagged(UiTestTags.watchRow).isHittable { return }
            goBack(app)
        }
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout), "watchlist not reached")
    }

    @MainActor
    private func openTab(_ app: XCUIApplication, _ tag: String) {
        tap(app, tag)
        // Langsamer CI-Mac: kam der Tipp nicht an (Tab nicht gewählt), einmal erneut tippen
        if !wait(app.tagged(tag), "isSelected == true", timeout: 5) { tap(app, tag) }
        settle(1)
    }

    /// Hinweis zum Speicher (nur im unsignierten Simulator) schliessen, falls er erscheint.
    @MainActor
    private func closeStorageNotice(_ app: XCUIApplication) {
        let close = app.tagged(UiTestTags.storageNoticeClose)
        guard close.waitForExistence(timeout: 3) else { return }
        close.tap()
        if !wait(close, "exists == false"), close.exists { close.tap() }
    }

    /// Wartet, bis das Element da und bedienbar ist, und tippt — bei mehreren Treffern (Tab-Leisten verdeckter
    /// Tabs) den sichtbaren.
    @MainActor
    private func tap(_ app: XCUIApplication, _ tag: String, timeout: TimeInterval = uiTimeout) {
        let element = app.tagged(tag)
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(tag) missing")
        XCTAssertTrue(wait(element, "isEnabled == true", timeout: timeout), "\(tag) disabled")
        let matches = app.descendants(matching: .any).matching(identifier: tag).allElementsBoundByIndex
        if matches.count > 1, let visible = matches.first(where: { $0.isHittable }) {
            visible.tap()
        } else {
            element.tap()
        }
    }

    /// Zeile so weit scrollen, dass sie ganz über der eigenen Tab-Leiste liegt (ein Tipp darunter träfe die Leiste,
    /// siehe `CryptoCheckerUITests.openBackupPage`), dann antippen.
    @MainActor
    private func tapRow(_ app: XCUIApplication, _ row: XCUIElement) {
        let bars = app.descendants(matching: .any).matching(identifier: UiTestTags.tabSettings).allElementsBoundByIndex
        let tabTop = bars.first(where: { $0.isHittable })?.frame.minY ?? app.frame.maxY
        var swipes = 0
        while swipes < 8 && (!row.isHittable || row.frame.maxY > tabTop - 8) {
            drag(app, from: 0.7, to: 0.35)
            swipes += 1
        }
        row.tap()
        settle(1)
    }

    /// Liste ganz nach oben (für das Bild der Hauptseite).
    @MainActor
    private func scrollToTop(_ app: XCUIApplication) {
        for _ in 0..<4 { drag(app, from: 0.3, to: 0.8) }
    }

    @MainActor
    private func drag(_ app: XCUIApplication, from: CGFloat, to: CGFloat) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
    }

    /// Ein- und Ausblendungen abwarten (keine Bedingung, auf die sich warten liesse).
    private func settle(_ seconds: TimeInterval) {
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "settle")], timeout: seconds)
    }
}
