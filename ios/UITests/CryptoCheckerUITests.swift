import XCTest

/// Die wichtigsten Abläufe über die Oberfläche — wie `UiFlowsTest.kt` (Android): gleiche Schritte, gleiche
/// Kennungen (`UiTestTags`), gleiche Testdatei (`Fixtures/uitest_portfolio.csv` = android/testdata/uitest):
///  1. Coin hinzufügen: Coin-Suche «btc» → Bitcoin → vorgeschlagenes Paar → steht in der Merkliste.
///  2. Alarm setzen: Aktionsblatt → Alarm → «über 200’000» → gespeichert und in der Liste.
///  3. CSV-Import: Datei → Vorschau → Importieren → Positionen sichtbar; «Importe › Rückgängig» entfernt sie.
///  4. Sichern: mit Passwort sichern → Neustart mit leerem Speicher → falsches Passwort abgelehnt →
///     richtiges stellt Merkliste und Portfolio wieder her.
///  5. Ergebnis als Dialog (nur iOS): Sicherung ohne Passwort mit eigenem Bitcoin-Server → Rückfrage → Ergebnis mit
///     Hinweis erscheint als Dialog, nachdem die Rückfrage zu ist; ebenso «Sicherung nicht bestätigt» nach dem
///     Blatt «Sichern» (Prüfung erzwungen fehlgeschlagen).
///  6. Steuer-Arbeitsunterlage: eigene Seite → Vorlage wählen → Export → Dateiablage erscheint.
///
/// Jeder Start beginnt mit leerem Speicher und festen Börsen-Antworten (Startargument `-uiTesting`, nur im
/// Debug-Build, siehe `UITesting` in der App); die Dateiauswahl ersetzen Testdateien. Kein Netz.
/// Ausführen: `xcodebuild test -scheme CryptoChecker -only-testing:CryptoCheckerUITests …` (DEVELOPMENT.md).
final class CryptoCheckerUITests: XCTestCase {
    private static let csvFixture = "uitest_portfolio.csv"
    private static let alarmThreshold = "200000"
    private static let testPassphrase = "Kiwi-Harbor-4821!"
    private static let wrongPassphrase = "Lemon-Valley-9034?"
    /// Sicherung ohne Passwort: ein Paar und ein eigener Bitcoin-Server, den sie nicht setzen darf (Hinweis «nicht
    /// übernommen» → Ergebnis als Dialog, `BackupResultPresentation`).
    private static let serverNoteBackup = """
        {"format":"cryptochecker-backup","version":1,"createdAt":0,\
        "watches":[{"id":1,"exchangeKey":"Binance","exchangeName":"Binance","baseAsset":"BTC","quoteAsset":"USDT",\
        "contractType":"NONE","pairId":"BTCUSDT","sortOrder":0,"notificationEnabled":false,"ttsEnabled":false,\
        "favorite":false,"group":null,"note":null,"disabled":false}],"alarms":[],"favorites":{"EXCHANGE":[],"COIN":[],"QUOTE":[]},"settings":{\
        "updateMode":"SPARSAM","backgroundIntervalMinutes":15,"priceNotifications":true,\
        "ongoingNotifications":false,"notificationChangePercent":5.0,"ttsEnabled":false,\
        "ttsAlarmsOnly":true,"ttsSpeechRate":1.0,"alarmCooldownMinutes":0,"includeRollingFutures":false,\
        "assetScope":"TOKENS","includeAlphaTokens":false,"accentColor":"ORANGE","darkMode":null,\
        "fearGreedBelow":0,"indicatorAlerts":{},"fearGreedAbove":0,"gasAlertEthTenths":0,"gasAlertBtc":0,\
        "depegAlarm":false,"depegThresholdTenths":5,"activityAlerts":false,"activitySensitivity":"NORMAL",\
        "macroNotifications":false,"portfolioEnabled":false,"portfolioCurrency":"USD","showConverted":true,\
        "priceColorScheme":"CALM","watchlistSparkline":false,"watchlistNames":true,"appIconBadge":true,\
        "watchlistActivityCard":true,"coinLogos":true,"widgetCoinLogos":false,"portfolioCoinLogos":true,\
        "changeBasis":"ROLLING_24H","highContrast":false,"priceColorsInverted":false,"alarmSignal":"SYSTEM",\
        "quietHoursEnabled":false,"quietHoursStart":1380,"quietHoursEnd":420,"appLock":false,\
        "hidePortfolioAmounts":false,"portfolioSystemBackup":false,"costMethod":"FIFO",\
        "taxTemplate":"NEUTRAL","watchlistColumnSort":null,"addressServerBtc":"https://node.example.org"},\
        "portfolio":[],"portfolioAlarms":[],"watchedAddresses":[]}
        """

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: 1. Coin hinzufügen

    @MainActor
    func testAddCoinFromSearch() throws {
        let app = launchApp()
        addBitcoinFromSearch(app)
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout))
    }

    // MARK: 2. Alarm setzen

    @MainActor
    func testSetPriceAlarm() throws {
        let app = launchApp()
        addBitcoinFromSearch(app)

        tap(app, UiTestTags.watchRow)
        tap(app, UiTestTags.watchActionAlarm)
        // Paar ohne Alarm: der Editor öffnet sich gleich; neuer Alarm = «über»
        enterText(app, UiTestTags.alarmThreshold, Self.alarmThreshold)
        tap(app, UiTestTags.alarmSave)

        allowNotificationsIfAsked()
        // Allererster Alarm (leerer Speicher: immer): die Bestätigung muss kommen (Pflicht).
        // Hauptknopf öffnet «Damit Alarme ankommen» als Blatt, «OK» schliesst es
        let check = app.alerts.firstMatch.buttons["Check that alerts arrive"]
        XCTAssertTrue(check.waitForExistence(timeout: uiTimeout), "first-alert explanation not shown")
        check.tap()
        let checklist = app.navigationBars["Make sure alerts arrive"]
        XCTAssertTrue(checklist.waitForExistence(timeout: uiTimeout), "alert checklist not shown")
        checklist.buttons["OK"].tap()
        // Danach ggf. «Wann geprüft wird»
        dismissAppAlerts(app)
        // Gespeicherte Schwelle auf der Karte (wie Android im Repository): Ziffern der Beschriftung, gleich welche
        // Tausendertrennung
        let card = app.tagged(UiTestTags.alarmCard)
        XCTAssertTrue(card.waitForExistence(timeout: uiTimeout), "alarm card missing")
        XCTAssertTrue(card.label.filter(\.isNumber).contains(Self.alarmThreshold),
                      "threshold \(Self.alarmThreshold) not on the alarm card: \(card.label)")
    }

    // MARK: 3. CSV-Import mit Rückgängig

    @MainActor
    func testImportCsvAndUndo() throws {
        let app = launchApp(csv: try fixture(Self.csvFixture))
        importFixtureCsv(app)

        tap(app, UiTestTags.portfolioMenu)
        tapMenuItem(app, UiTestTags.portfolioImports, label: "Imports")
        tap(app, UiTestTags.importUndo)
        tapConfirmation(app, UiTestTags.importUndoConfirm, label: "Undo")

        XCTAssertTrue(wait(app.tagged(UiTestTags.portfolioCoin("BTC")), "exists == false"), "BTC still in the portfolio")
        XCTAssertFalse(app.tagged(UiTestTags.portfolioCoin("ETH")).exists)
    }

    // MARK: 4. Sichern und Wiederherstellen

    @MainActor
    func testBackupRoundTripWithPassword() throws {
        var app = launchApp(csv: try fixture(Self.csvFixture))
        addBitcoinFromSearch(app)
        importFixtureCsv(app)

        // Sichern: Portfolio-Daten dabei → «Mit Passwort schützen» ist vorgewählt
        openBackupPage(app)
        tap(app, UiTestTags.backupExport)
        enterText(app, UiTestTags.backupPassword, Self.testPassphrase)
        enterText(app, UiTestTags.backupPasswordRepeat, Self.testPassphrase)
        tap(app, UiTestTags.backupExportConfirm)
        // Rückmeldung «Gesichert» = Datei geschrieben (verschlüsseln dauert etwa eine Sekunde)
        XCTAssertTrue(app.tagged(UiTestTags.toast).waitForExistence(timeout: 90), "no export feedback")

        // Neustart = leerer Speicher (Merkliste und Portfolio weg), die Sicherung bleibt
        app.terminate()
        app = launchApp(keepBackup: true)
        XCTAssertTrue(app.tagged(UiTestTags.watchlistAddPair).waitForExistence(timeout: uiTimeout))
        XCTAssertFalse(app.tagged(UiTestTags.watchRow).exists)

        // Falsches Passwort: abgelehnt, Hinweis unter dem Feld
        openBackupPage(app)
        tap(app, UiTestTags.backupImport)
        enterText(app, UiTestTags.backupRestorePassword, Self.wrongPassphrase)
        tap(app, UiTestTags.backupRestoreSubmit)
        XCTAssertTrue(app.tagged(UiTestTags.hint(UiTestTags.backupRestorePassword)).waitForExistence(timeout: 90),
                      "wrong password not rejected")

        // Richtiges Passwort → Rückfrage «Ersetzen?» → wiederhergestellt
        enterText(app, UiTestTags.backupRestorePassword, Self.testPassphrase)
        tap(app, UiTestTags.backupRestoreSubmit)
        // Argon2id (64 MiB) braucht im Debug-Build des Simulators deutlich länger als im Release: auf die Rückfrage warten
        let confirmShown = app.tagged(UiTestTags.backupRestoreConfirm).waitForExistence(timeout: 90)
            || app.alerts.firstMatch.waitForExistence(timeout: 5) || app.sheets.firstMatch.exists
        XCTAssertTrue(confirmShown, "restore confirmation not shown")
        tapConfirmation(app, UiTestTags.backupRestoreConfirm, label: "Import")
        allowNotificationsIfAsked()

        tap(app, UiTestTags.tabWatchlist)
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout), "watchlist not restored")
        tap(app, UiTestTags.tabPortfolio)
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("BTC")).waitForExistence(timeout: uiTimeout), "portfolio not restored")
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("ETH")).exists)
    }

    // MARK: 5. Ergebnis als Dialog

    /// iOS zeigt keinen Dialog, solange die Rückfrage noch zugeht: das Ergebnis wartet darauf und muss trotzdem
    /// erscheinen. Danach würde die App nach Mitteilungen fragen (Paar eingespielt) — nicht prüfbar, weil die
    /// Erlaubnis auf dem Simulator meist schon aus einem früheren Test geklärt ist; geprüft wird nur, dass die
    /// Erklärung nicht über dem offenen Dialog liegt.
    @MainActor
    func testRestoreResultDialogAfterConfirmation() throws {
        let app = launchApp(backup: Self.serverNoteBackup)
        openBackupPage(app)
        tap(app, UiTestTags.backupImport)
        tapConfirmation(app, UiTestTags.backupRestoreConfirm, label: "Import")

        // Titel «Backup & restore»; Text mit dem Hinweis zur Sicherung ohne Passwort
        let dialog = app.alerts.containing(NSPredicate(format: "label == %@", "Backup & restore")).firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 5), "restore result dialog not shown")
        let note = dialog.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "unencrypted backup")).firstMatch
        XCTAssertTrue(note.exists, "server note missing in the result dialog")
        XCTAssertFalse(app.tagged(UiTestTags.notificationPrepromptContinue).exists, "notification explanation over the result")

        let close = dialog.buttons["Close"]
        XCTAssertTrue(close.exists, "close button missing")
        close.tap()
        XCTAssertTrue(wait(dialog, "exists == false", timeout: uiTimeout), "result dialog not closed")
        allowNotificationsIfAsked(timeout: 6)
        tap(app, UiTestTags.tabWatchlist)
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout), "pair not restored")
    }

    /// Gleiche Zeitannahme beim Sichern: Das Ergebnis kommt, während sich das Blatt «Sichern» noch schliesst.
    /// Die Prüfung nach dem Schreiben scheitert hier absichtlich (`UITEST_FAIL_VERIFY`) → «nicht bestätigt» als Dialog.
    @MainActor
    func testExportResultDialogAfterSheet() throws {
        let app = launchApp(failVerify: true)
        // Ohne Portfolio-Daten ist «Mit Passwort schützen» aus: Sichern → Rückfrage «Ohne Passwort speichern?»
        openBackupPage(app)
        tap(app, UiTestTags.backupExport)
        tap(app, UiTestTags.backupExportConfirm)
        let plain = app.alerts.firstMatch.buttons["Save without password"]
        XCTAssertTrue(plain.waitForExistence(timeout: uiTimeout), "plain backup confirmation not shown")
        plain.tap()

        let dialog = app.alerts.containing(NSPredicate(format: "label == %@", "Backup & restore")).firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 5), "export result dialog not shown")
        let message = dialog.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "could not be verified")).firstMatch
        XCTAssertTrue(message.exists, "not-verified message missing in the result dialog")
        XCTAssertFalse(app.tagged(UiTestTags.toast).exists, "export reported as success")

        let close = dialog.buttons["Close"]
        XCTAssertTrue(close.waitForExistence(timeout: uiTimeout), "close button missing")
        close.tap()
        XCTAssertTrue(wait(dialog, "exists == false", timeout: uiTimeout), "result dialog not closed")
        XCTAssertTrue(app.tagged(UiTestTags.backupExport).waitForExistence(timeout: uiTimeout), "backup page left")
    }

    // MARK: 6. Steuer-Arbeitsunterlage

    /// Portfolio aus der Testdatei → ⋯ › «Steuer-Arbeitsunterlage…» → eigene Seite → Vorlage «Germany» (Beta-Hinweis
    /// mit OK) → «Income» → die Dateiablage des Systems erscheint (eigener Prozess: nur das Erscheinen, dann zu).
    @MainActor
    func testTaxExportWorksheet() throws {
        let app = launchApp(csv: try fixture(Self.csvFixture))
        importFixtureCsv(app)
        tap(app, UiTestTags.portfolioMenu)
        tapMenuItem(app, UiTestTags.portfolioTaxExport, label: "Worksheet…")
        let page = app.tagged(UiTestTags.taxWorksheetScreen)
        XCTAssertTrue(page.waitForExistence(timeout: uiTimeout), "worksheet missing")
        tap(app, UiTestTags.taxWorksheetTemplate)
        tapMenuItem(app, UiTestTags.taxTemplateOption("DE"), label: "Germany")
        dismissAppAlerts(app)
        tap(app, UiTestTags.taxWorksheetIncome)
        let running = app.tagged(UiTestTags.exportCancel)
        XCTAssertTrue(wait(running, "exists == false", timeout: uiListTimeout), "export still running")
        XCTAssertFalse(app.tagged(UiTestTags.exportRetry).exists, "export failed")
        let close = app.navigationBars.buttons["Cancel"]
        XCTAssertTrue(waitForAny([app.buttons["Move"], app.buttons["Save"], close]), "file exporter not shown")
        // Das Systemblatt nicht selbst schliessen (es baut sich beim Antippen um); jeder Test startet die App neu
    }

    // MARK: Gemeinsame Schritte

    /// Ablauf 1: über die Coin-Suche Bitcoin hinzufügen und zurück zur Merkliste.
    @MainActor
    private func addBitcoinFromSearch(_ app: XCUIApplication) {
        tap(app, UiTestTags.watchlistAddPair)
        // «\n» = Suchen: Tastatur zu, damit nichts die Treffer verdeckt
        enterText(app, UiTestTags.explorerSearch, "btc\n")
        // Paarlisten liegen noch nicht auf dem Gerät: «Listen laden» (Antworten der Fake-Börsen)
        let coin = app.tagged(UiTestTags.explorerCoin("BTC"))
        let load = app.tagged(UiTestTags.explorerListsLoad)
        XCTAssertTrue(waitForAny([load, coin]), "no search result")
        if load.exists { load.tap() }
        tap(app, UiTestTags.explorerCoin("BTC"))
        // Vorschlag erscheint, sobald eine Liste mit BTC geladen ist
        tap(app, UiTestTags.explorerSuggestedAdd, timeout: uiListTimeout)
        // Erstes Paar: die App erklärt kurz und fragt dann nach Mitteilungen (nur beim ersten Mal je Installation).
        // Zuerst wegtippen: Die Erklärung liegt in einem eigenen Fenster über allem und verdeckt den Knopf
        allowNotificationsIfAsked(timeout: 6)
        XCTAssertTrue(wait(app.tagged(UiTestTags.explorerSuggestedAdd), "isEnabled == false"), "pair not added")
        // Zurück zur Merkliste (Zurück-Knopf der Navigationsleiste)
        app.navigationBars.firstMatch.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout), "pair missing in the watchlist")
    }

    /// Ablauf 3 bis zur Anzeige: Portfolio (leer) › «CSV importieren» → Vorschau → Importieren.
    @MainActor
    private func importFixtureCsv(_ app: XCUIApplication) {
        tap(app, UiTestTags.tabPortfolio)
        tap(app, UiTestTags.portfolioImportCsv)
        tap(app, UiTestTags.csvImportConfirm, timeout: uiListTimeout)
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("BTC")).waitForExistence(timeout: uiTimeout), "BTC not imported")
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("ETH")).waitForExistence(timeout: uiTimeout), "ETH not imported")
    }

    @MainActor
    private func openBackupPage(_ app: XCUIApplication) {
        // Nach dem Import liegt kurz eine Rückmeldung (Toast mit «Rückgängig») über der Tab-Leiste und fängt
        // den Tipp ab – auf GitHub blieb die App danach im Portfolio. Erst warten, bis sie weg ist, dann den Tab
        // wechseln und prüfen, dass die Einstellungen wirklich offen sind.
        _ = wait(app.tagged(UiTestTags.toast), "exists == false", timeout: 12)
        // Jeder Tab trägt seine eigene Leiste (safeAreaInset), auch die gerade nicht sichtbaren stehen im
        // Baum: «firstMatch» kann die Leiste eines verdeckten Tabs treffen, ein Tipp darauf bewirkt nichts.
        // Darum die sichtbare (antippbare) Taste nehmen.
        var attempts = 0
        repeat {
            let candidates = app.descendants(matching: .any).matching(identifier: UiTestTags.tabSettings).allElementsBoundByIndex
            if let visible = candidates.first(where: { $0.isHittable }) { visible.tap() } else { tap(app, UiTestTags.tabSettings) }
            attempts += 1
        } while !app.tagged(UiTestTags.settingsBackup).waitForExistence(timeout: 4) && attempts < 3
        // Die Zeile «Sicherung» steht weit unten, anfangs hinter der eigenen Tab-Leiste. XCUITest hält sie dort
        // schon für «antippbar», der Tipp auf ihre Mitte trifft aber den Tab «Portfolio» (Video der CI: die
        // Einstellungen gingen auf und sprangen zurück). Darum scrollen, bis sie ganz über der Leiste steht.
        let row = app.tagged(UiTestTags.settingsBackup)
        XCTAssertTrue(row.waitForExistence(timeout: uiTimeout), "\(UiTestTags.settingsBackup) missing")
        scrollAboveTabBar(app, row)
        row.tap()
        if !app.tagged(UiTestTags.backupExport).waitForExistence(timeout: 5) {
            // Ein zweiter Tipp, falls der erste noch in eine Scroll-Bewegung fiel
            if !row.exists {
                let visible = app.descendants(matching: .any).matching(identifier: UiTestTags.tabSettings)
                    .allElementsBoundByIndex.first(where: { $0.isHittable })
                visible?.tap()
                _ = row.waitForExistence(timeout: 4)
            }
            if row.exists { scrollAboveTabBar(app, row); row.tap() }
        }
        if !app.tagged(UiTestTags.backupExport).waitForExistence(timeout: uiTimeout) {
            // Hinweis für die CI-Meldung: was ist stattdessen zu sehen? (Kennungen und Titel, gekürzt)
            let ids = app.descendants(matching: .any).allElementsBoundByIndex.prefix(400)
                .map(\.identifier).filter { !$0.isEmpty }
            let bars = app.navigationBars.allElementsBoundByIndex.map(\.identifier)
            let tabs = app.descendants(matching: .any).matching(identifier: UiTestTags.tabSettings).allElementsBoundByIndex
                .map { "hit=\($0.isHittable) frame=\(Int($0.frame.minX)),\(Int($0.frame.minY)),\(Int($0.frame.width))x\(Int($0.frame.height))" }
            XCTFail("backup page not shown; bars=\(bars) keyboards=\(app.keyboards.count) tabSettings=\(tabs) hittable=\(row.isHittable) ids=\(Array(Set(ids)).sorted().prefix(60))")
        }
    }

    // MARK: Hilfen

    /// Element so weit hochscrollen, dass es ganz zwischen Navigationsleiste und eigener Tab-Leiste liegt.
    @MainActor
    private func scrollAboveTabBar(_ app: XCUIApplication, _ element: XCUIElement) {
        let bars = app.descendants(matching: .any).matching(identifier: UiTestTags.tabSettings).allElementsBoundByIndex
        let tabTop = bars.first(where: { $0.isHittable })?.frame.minY ?? app.frame.maxY
        var swipes = 0
        while swipes < 8 && (!element.isHittable || element.frame.maxY > tabTop - 8) {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
            swipes += 1
        }
    }

    /// Wartet, bis das Element da und bedienbar ist, und tippt.
    @MainActor
    private func tap(_ app: XCUIApplication, _ tag: String, timeout: TimeInterval = uiTimeout,
                     file: StaticString = #filePath, line: UInt = #line) {
        let element = app.tagged(tag)
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(tag) missing", file: file, line: line)
        XCTAssertTrue(wait(element, "isEnabled == true", timeout: timeout), "\(tag) disabled", file: file, line: line)
        // Mehrere Treffer (z. B. Tab-Leisten verdeckter Tabs): den sichtbaren antippen
        let matches = app.descendants(matching: .any).matching(identifier: tag).allElementsBoundByIndex
        if matches.count > 1, let visible = matches.first(where: { $0.isHittable }) {
            visible.tap()
        } else {
            element.tap()
        }
    }

    /// Tippt ins Feld (Fokus zuerst) und gibt den Text ein.
    @MainActor
    private func enterText(_ app: XCUIApplication, _ tag: String, _ text: String,
                           file: StaticString = #filePath, line: UInt = #line) {
        tap(app, tag, file: file, line: line)
        app.tagged(tag).typeText(text)
    }

    /// Eintrag eines Menüs: über die Kennung, sonst über die (englische) Beschriftung — Kennungen erreichen nicht
    /// in jeder iOS-Fassung die Menü-Einträge.
    @MainActor
    private func tapMenuItem(_ app: XCUIApplication, _ tag: String, label: String) {
        let byTag = app.tagged(tag)
        if byTag.waitForExistence(timeout: 3) {
            byTag.tap()
            return
        }
        let byLabel = app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
        XCTAssertTrue(byLabel.waitForExistence(timeout: uiTimeout), "menu item \(label) missing")
        byLabel.tap()
    }

    /// Knopf einer Rückfrage (Alert bzw. Aktionsblatt): über die Kennung, sonst über die (englische) Beschriftung
    /// in der Rückfrage — nicht der gleich beschriftete Knopf dahinter.
    @MainActor
    private func tapConfirmation(_ app: XCUIApplication, _ tag: String, label: String) {
        let byTag = app.tagged(tag)
        if byTag.waitForExistence(timeout: 3) {
            byTag.tap()
            return
        }
        for container in [app.alerts.firstMatch, app.sheets.firstMatch] where container.exists {
            let button = container.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
            if button.exists {
                button.tap()
                return
            }
        }
        XCTFail("confirmation \(label) missing")
    }

    /// Hinweise der App mit «OK» schliessen; ohne Hinweis nichts. Über die Beschriftung, nicht die Stelle: mit
    /// drei Knöpfen stehen sie untereinander und «OK» (Abbrechen-Rolle) unten. Nur «OK»: ein
    /// unerwarteter Hinweis ohne «OK» lässt den Test scheitern (statt irgendeinen Knopf zu tippen).
    @MainActor
    private func dismissAppAlerts(_ app: XCUIApplication) {
        for _ in 0..<2 {
            let alert = app.alerts.firstMatch
            guard alert.waitForExistence(timeout: 3) else { return }
            let ok = alert.buttons["OK"]
            guard ok.exists else {
                let buttons = alert.buttons.allElementsBoundByIndex.map(\.label)
                return XCTFail("unexpected alert «\(alert.label)» without OK: \(buttons)")
            }
            ok.tap()
            // Erst weiter, wenn er zu ist (sonst träfe die nächste Runde denselben Hinweis)
            _ = wait(ok, "exists == false", timeout: 5)
        }
    }
}
