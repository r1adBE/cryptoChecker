import XCTest

/// Grund, warum ein Schritt der Bildschirmfoto-Tour sein Ziel nicht erreicht hat (`ScreenshotTourTests.step`).
struct TourStepError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// Bedienung der App für `ScreenshotTourTests`: Wege zu den Seiten und zurück zum Ausgangspunkt, ohne Abbruch bei
/// einem Fehler (werfen statt `XCTAssert`). Jeder Schritt beginnt mit `toWatchlist` bzw. `toSettings` — so findet er
/// seinen Ausgangspunkt auch, wenn der Schritt davor mittendrin stehen geblieben ist.
///
/// Zurück zur Startseite eines Tabs über die eigene Tab-Leiste: der gewählte Tab noch einmal getippt führt von
/// einer Seite darüber zurück zur Startseite (`RootView.reselect`). Blätter (ohne Tab-Leiste) zuerst schliessen:
/// «Close» bzw. «Cancel» der Navigationsleiste, sonst nach unten ziehen. Seiten ohne Tab-Leiste («Paar
/// hinzufügen»): der erste Knopf der Navigationsleiste (Zurück) — nur wenn es eine gibt.
extension ScreenshotTourTests {

    // MARK: Ausgangspunkte

    /// Merkliste oben, nichts darüber (eine Seite oder ein Blatt darüber macht die Zeilen unbedienbar).
    @MainActor
    func watchlistReady(_ app: XCUIApplication) -> Bool {
        let row = app.tagged(UiTestTags.watchRow)
        return row.exists && row.isHittable
    }

    @MainActor
    func toWatchlist(_ app: XCUIApplication) throws {
        for attempt in 0..<6 {
            if watchlistReady(app) { return }
            if let close = sheetCloseButton(app) {
                close.tap()
            } else if let tab = visible(app, UiTestTags.tabWatchlist) {
                tab.tap()
                settle(1)
                // Zweiter Tipp auf den gewählten Tab: zurück zur Startseite (war eine Seite darüber offen)
                if !watchlistReady(app) { visible(app, UiTestTags.tabWatchlist)?.tap() }
            } else if let back = navigationBack(app) {
                back.tap()
            } else {
                // Blatt ohne Navigationsleiste (Aktionsblatt, halbe Höhe): nach unten ziehen
                drag(app, from: attempt.isMultiple(of: 2) ? 0.55 : 0.12, to: 0.98)
            }
            settle(1)
        }
        try require(watchlistReady(app), "watchlist not reached")
    }

    /// Hauptseite der Einstellungen: Tab tippen (wählt ihn), noch einmal tippen (Seite darüber → Startseite, sonst
    /// nach oben scrollen).
    @MainActor
    func toSettings(_ app: XCUIApplication) throws {
        for _ in 0..<3 {
            if let close = sheetCloseButton(app) {
                close.tap()
                settle(1)
            }
            try openTab(app, UiTestTags.tabSettings)
            visible(app, UiTestTags.tabSettings)?.tap()
            settle(1)
            if app.tagged(UiTestTags.settingsBackup).exists && !app.tagged(UiTestTags.backupExport).exists { return }
        }
        throw TourStepError("settings not reached")
    }

    // MARK: Seiten

    /// Merkliste › erste Zeile (BTC): Aktionsblatt.
    @MainActor
    func openPairActions(_ app: XCUIApplication) throws {
        try toWatchlist(app)
        try tap(app, UiTestTags.watchRow)
        try requireTag(app, UiTestTags.watchActionAlarm)
    }

    /// Aktionsblatt › «Alarm»: Alarme des Paars (je ein Alarm, darum die Liste statt des Editors).
    @MainActor
    func openPairAlarms(_ app: XCUIApplication) throws {
        try openPairActions(app)
        try tap(app, UiTestTags.watchActionAlarm)
        try requireTag(app, UiTestTags.alarmCard)
    }

    /// Glocke im Kopf der Merkliste («Alerts (3)», mit Alarmen die Übersicht), sonst Menü › «All alerts».
    @MainActor
    func openAlarmsOverview(_ app: XCUIApplication) throws {
        try toWatchlist(app)
        let bells = app.buttons.matching(NSPredicate(format: "label MATCHES %@", #"Alerts? \(\d+\)"#))
        if bells.firstMatch.waitForExistence(timeout: 5), let bell = hittable(bells) {
            bell.tap()
        } else {
            try openMenuItem(app, "All alerts")
        }
        try require(waitForAny([app.navigationBars["All alerts"], app.staticTexts["All alerts"]]),
                    "alarms overview not opened")
        try require(wait(app.tagged(UiTestTags.watchRow), "isHittable == false"), "alarms overview not on top")
    }

    /// Menü der Merkliste › «Last refresh» (Blatt mit Navigationsleiste).
    @MainActor
    func openRefreshReport(_ app: XCUIApplication) throws {
        try toWatchlist(app)
        try openMenuItem(app, "Last refresh")
        try require(app.navigationBars["Last refresh"].waitForExistence(timeout: uiTimeout), "refresh report missing")
    }

    /// Merkliste › «+» (Paar hinzufügen).
    @MainActor
    func openExplorer(_ app: XCUIApplication) throws {
        try toWatchlist(app)
        try tap(app, UiTestTags.watchlistAddPair)
        try requireTag(app, UiTestTags.explorerSearch)
    }

    /// Menü «More options» der Merkliste öffnen und den Eintrag `label` wählen.
    @MainActor
    private func openMenuItem(_ app: XCUIApplication, _ label: String) throws {
        let menus = app.buttons.matching(NSPredicate(format: "label == %@", "More options"))
        try require(menus.firstMatch.waitForExistence(timeout: uiTimeout), "menu missing")
        try require(hittable(menus) != nil, "menu not tappable")
        hittable(menus)?.tap()
        let item = app.buttons[label].firstMatch
        try require(item.waitForExistence(timeout: uiTimeout), "menu item \(label) missing")
        item.tap()
    }

    // MARK: Bedienung

    @MainActor
    func openTab(_ app: XCUIApplication, _ tag: String) throws {
        try tap(app, tag)
        // Langsamer CI-Mac: kam der Tipp nicht an (Tab nicht gewählt), einmal erneut tippen
        if !wait(app.tagged(tag), "isSelected == true", timeout: 5) { try tap(app, tag) }
        settle(1)
    }

    /// Wartet, bis das Element da und bedienbar ist, und tippt — bei mehreren Treffern (Tab-Leisten verdeckter
    /// Tabs) den sichtbaren.
    @MainActor
    func tap(_ app: XCUIApplication, _ tag: String, timeout: TimeInterval = uiTimeout) throws {
        let element = try requireTag(app, tag, timeout: timeout)
        try require(wait(element, "isEnabled == true", timeout: timeout), "\(tag) disabled")
        (visible(app, tag) ?? element).tap()
    }

    @MainActor
    @discardableResult
    func requireTag(_ app: XCUIApplication, _ tag: String, timeout: TimeInterval = uiTimeout) throws -> XCUIElement {
        let element = app.tagged(tag)
        try require(element.waitForExistence(timeout: timeout), "\(tag) missing")
        return element
    }

    func require(_ condition: Bool, _ reason: String) throws {
        if !condition { throw TourStepError(reason) }
    }

    /// Antippbares Element mit der Kennung (die Tab-Leisten verdeckter Tabs stehen auch im Baum).
    @MainActor
    func visible(_ app: XCUIApplication, _ tag: String) -> XCUIElement? {
        hittable(app.descendants(matching: .any).matching(identifier: tag))
    }

    @MainActor
    func hittable(_ query: XCUIElementQuery) -> XCUIElement? {
        query.allElementsBoundByIndex.first { $0.exists && $0.isHittable }
    }

    /// «Close» bzw. «Cancel» in einer Navigationsleiste (Bericht, Alarm-Editor), falls antippbar.
    @MainActor
    private func sheetCloseButton(_ app: XCUIApplication) -> XCUIElement? {
        ["Close", "Cancel"].lazy.map { app.navigationBars.buttons[$0].firstMatch }
            .first { $0.exists && $0.isHittable }
    }

    /// Erster Knopf der Navigationsleiste (Zurück) — nur wenn eine Leiste da ist.
    @MainActor
    private func navigationBack(_ app: XCUIApplication) -> XCUIElement? {
        let bar = app.navigationBars.firstMatch
        guard bar.exists, bar.buttons.count > 0 else { return nil }
        let back = bar.buttons.element(boundBy: 0)
        return back.exists && back.isHittable ? back : nil
    }

    /// Hinweis zum Speicher (nur im unsignierten Simulator) schliessen, falls er erscheint.
    @MainActor
    func closeStorageNotice(_ app: XCUIApplication) {
        let close = app.tagged(UiTestTags.storageNoticeClose)
        guard close.waitForExistence(timeout: 3) else { return }
        close.tap()
        if !wait(close, "exists == false"), close.exists { close.tap() }
    }

    /// Zeile so weit scrollen, dass sie ganz über der eigenen Tab-Leiste liegt (ein Tipp darunter träfe die Leiste,
    /// siehe `CryptoCheckerUITests.openBackupPage`), dann antippen.
    @MainActor
    func tapRow(_ app: XCUIApplication, _ row: XCUIElement) {
        let tabTop = visible(app, UiTestTags.tabSettings)?.frame.minY ?? app.frame.maxY
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
    func scrollToTop(_ app: XCUIApplication) {
        for _ in 0..<4 { drag(app, from: 0.3, to: 0.8) }
    }

    @MainActor
    func drag(_ app: XCUIApplication, from: CGFloat, to: CGFloat) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
    }

    /// Ein- und Ausblendungen abwarten (keine Bedingung, auf die sich warten liesse).
    func settle(_ seconds: TimeInterval) {
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: "settle")], timeout: seconds)
    }
}
