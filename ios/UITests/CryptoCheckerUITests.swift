import XCTest

/// Die wichtigsten Abläufe über die Oberfläche — wie `UiFlowsTest.kt` (Android): gleiche Schritte, gleiche
/// Kennungen (`UiTestTags`), gleiche Testdatei (`Fixtures/uitest_portfolio.csv` = android/testdata/uitest):
///  1. Coin hinzufügen: Coin-Suche «btc» → Bitcoin → vorgeschlagenes Paar → steht in der Merkliste.
///  2. Alarm setzen: Aktionsblatt → Alarm → «über 200’000» → gespeichert und in der Liste.
///  3. CSV-Import: Datei → Vorschau → Importieren → Positionen sichtbar; «Importe › Rückgängig» entfernt sie.
///  4. Sichern: mit Passwort sichern → Neustart mit leerem Speicher → falsches Passwort abgelehnt →
///     richtiges stellt Merkliste und Portfolio wieder her.
///
/// Jeder Start beginnt mit leerem Speicher und festen Börsen-Antworten (Startargument `-uiTesting`, nur im
/// Debug-Build, siehe `UITesting` in der App); die Dateiauswahl ersetzen Testdateien. Kein Netz.
/// Ausführen: `xcodebuild test -scheme CryptoChecker -only-testing:CryptoCheckerUITests …` (DEVELOPMENT.md).
final class CryptoCheckerUITests: XCTestCase {
    private static let csvFixture = "uitest_portfolio.csv"
    private static let alarmThreshold = "200000"
    private static let testPassphrase = "Kiwi-Harbor-4821!"
    private static let wrongPassphrase = "Lemon-Valley-9034?"

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
        // Allererster Alarm: Bestätigung (OK = Abbrechen-Rolle, links); danach ggf. «Wann geprüft wird»
        dismissAppAlerts(app)
        XCTAssertTrue(app.tagged(UiTestTags.alarmCard).waitForExistence(timeout: uiTimeout))
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
        XCTAssertTrue(app.tagged(UiTestTags.toast).waitForExistence(timeout: uiTimeout), "no export feedback")

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
        XCTAssertTrue(app.tagged(UiTestTags.hint(UiTestTags.backupRestorePassword)).waitForExistence(timeout: uiTimeout),
                      "wrong password not rejected")

        // Richtiges Passwort → Rückfrage «Ersetzen?» → wiederhergestellt
        enterText(app, UiTestTags.backupRestorePassword, Self.testPassphrase)
        tap(app, UiTestTags.backupRestoreSubmit)
        tapConfirmation(app, UiTestTags.backupRestoreConfirm, label: "Import")
        allowNotificationsIfAsked()

        tap(app, UiTestTags.tabWatchlist)
        XCTAssertTrue(app.tagged(UiTestTags.watchRow).waitForExistence(timeout: uiTimeout), "watchlist not restored")
        tap(app, UiTestTags.tabPortfolio)
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("BTC")).waitForExistence(timeout: uiTimeout), "portfolio not restored")
        XCTAssertTrue(app.tagged(UiTestTags.portfolioCoin("ETH")).exists)
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
        XCTAssertTrue(wait(app.tagged(UiTestTags.explorerSuggestedAdd), "isEnabled == false"), "pair not added")
        // Erstes Paar: die App fragt nach Mitteilungen (nur beim ersten Mal je Installation)
        allowNotificationsIfAsked()
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
        tap(app, UiTestTags.tabSettings)
        // Die Zeile «Sicherung» steht weit unten: erst sichtbar scrollen, sonst trifft der Tipp nichts
        // (das Element existiert, ist aber nicht antippbar) – auf GitHub fehlte danach «backup_export»
        let row = app.tagged(UiTestTags.settingsBackup)
        XCTAssertTrue(row.waitForExistence(timeout: uiTimeout), "\(UiTestTags.settingsBackup) missing")
        var swipes = 0
        while !row.isHittable && swipes < 6 {
            app.swipeUp()
            swipes += 1
        }
        row.tap()
        if !app.tagged(UiTestTags.backupExport).waitForExistence(timeout: 5) {
            // Ein zweiter Tipp, falls der erste noch in eine Scroll-Bewegung fiel
            if row.exists && row.isHittable { row.tap() }
        }
    }

    // MARK: Hilfen

    /// Wartet, bis das Element da und bedienbar ist, und tippt.
    @MainActor
    private func tap(_ app: XCUIApplication, _ tag: String, timeout: TimeInterval = uiTimeout,
                     file: StaticString = #filePath, line: UInt = #line) {
        let element = app.tagged(tag)
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(tag) missing", file: file, line: line)
        XCTAssertTrue(wait(element, "isEnabled == true", timeout: timeout), "\(tag) disabled", file: file, line: line)
        element.tap()
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

    /// Hinweise der App mit einem «OK» (Abbrechen-Rolle, steht links) schliessen; ohne Hinweis nichts.
    @MainActor
    private func dismissAppAlerts(_ app: XCUIApplication) {
        for _ in 0..<2 {
            let alert = app.alerts.firstMatch
            guard alert.waitForExistence(timeout: 3) else { return }
            alert.buttons.element(boundBy: 0).tap()
        }
    }
}
