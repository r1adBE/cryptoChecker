import XCTest
@testable import CryptoChecker

/// Sperre der Merkliste (Review F-08), wie Android `WatchRepositoryLockTest`: Ist die gespeicherte
/// Merkliste gerade nicht lesbar (`watchlistLocked`), lehnt `AppData` jeden Schreibweg des Nutzers ab, Hintergrund nicht;
/// der Hinweis ist gedrosselt und erscheint nur auf dem zuletzt erschienenen Bildschirm. Das Verhalten prüfen
/// `WatchlistLockBehaviorTests` (jeder Schreibweg gesperrt) und `WatchlistConsentBehaviorTests` (Rückfrage F-08 am
/// Schreibweg); dieser Test sichert an der Quelle zusätzlich, dass jeder Weg dieselbe Sicherung (`watchlistWriteRefused`)
/// nutzt — nur so gilt auch F-08 für jeden — und dass Bildschirme vorher fragen.
@MainActor
final class WatchlistWriteLockTests: XCTestCase {

    // MARK: Regeln

    func testOnlyLockedWatchlistBlocksWrites() {
        XCTAssertTrue(WatchlistStorageLock.blocksWrites(locked: true))
        // Verzögertes Speichern (`watchlistSavePending`, wie Android NOT_SAVED) sperrt nicht: dort ist der Speicher massgebend
        XCTAssertFalse(WatchlistStorageLock.blocksWrites(locked: false))
        XCTAssertFalse(WatchlistStorageLock.blocksWrites(locked: WatchlistStorageLock.isLocked(.missing)))
        XCTAssertTrue(WatchlistStorageLock.blocksWrites(locked: WatchlistStorageLock.isLocked(.inaccessible)))
    }

    func testRefusalHintIsThrottled() {
        let gap = WatchlistWriteRefusal.minGapMillis
        XCTAssertEqual(gap, 2_000)
        XCTAssertTrue(WatchlistWriteRefusal.shouldNotify(lastAt: nil, now: 5))
        XCTAssertFalse(WatchlistWriteRefusal.shouldNotify(lastAt: 1_000, now: 1_000 + gap - 1))
        XCTAssertTrue(WatchlistWriteRefusal.shouldNotify(lastAt: 1_000, now: 1_000 + gap))
        // Uhr rückwärts: nicht als «zu früh» schlucken
        XCTAssertTrue(WatchlistWriteRefusal.shouldNotify(lastAt: 10_000, now: 5_000))
    }

    func testOnlyTopmostHostShowsHint() {
        let list = UUID(), explorer = UUID()
        WatchlistLockedHintHosts.appeared(list)
        WatchlistLockedHintHosts.appeared(explorer)
        // «Paar hinzufügen» über der Merkliste: nur er meldet (keine doppelte Haptik)
        XCTAssertTrue(WatchlistLockedHintHosts.isTop(explorer))
        XCTAssertFalse(WatchlistLockedHintHosts.isTop(list))
        WatchlistLockedHintHosts.disappeared(explorer)
        XCTAssertTrue(WatchlistLockedHintHosts.isTop(list))
        WatchlistLockedHintHosts.disappeared(list)
        XCTAssertFalse(WatchlistLockedHintHosts.isTop(list))
    }

    // MARK: Quelle

    private func source(_ name: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/\(name)")
        return try String(contentsOf: file, encoding: .utf8)
    }

    /// Anfang des Rumpfs von `signature` (bis zur nächsten Funktion); die Sperre steht jeweils zuoberst.
    private func body(_ source: String, _ signature: String) -> String {
        guard let start = source.range(of: signature) else { XCTFail("\(signature) fehlt"); return "" }
        let rest = source[start.upperBound...]
        let ends = ["\n    func ", "\n    private func ", "\n    static func ", "\n    @discardableResult"]
            .compactMap { rest.range(of: $0)?.lowerBound }
        return String(rest[..<(ends.min() ?? rest.endIndex)])
    }

    func testEveryUserWriteAsksTheLock() throws {
        let checks: [String: [String]] = [
            "AppData+Watchlist.swift": [
                "func addStarterCoins(", "func changePair(", "func delete(_ watch: Watch)", "func deleteForUndo(_ watch:",
                "func restore(_ deleted: DeletedWatch)", "func deleteForUndo(ids wanted:", "func restore(_ deleted: [DeletedWatch])",
                "func deleteAllForUndo(",
            ],
            "AppData+WatchEdits.swift": [
                "func tryAddWatch(", "func addWatches(", "func setNotificationEnabled(", "func setAllNotifications(",
                "func setTtsEnabled(", "func setNote(", "func setGroup(_ watch:", "func setGroup(ids:", "func setFavorite(ids:",
                "func setDisabled(", "func toggleFavorite(_ watch:", "func move(", "func reorder(",
            ],
            "AppData+Reading.swift": ["func applyGroupEdit(", "func deleteGroup("],
            "AppData+Alarms.swift": ["func deleteAlarm(", "func setAlarmEnabled("],
        ]
        for (file, signatures) in checks {
            let text = try source(file)
            for signature in signatures {
                XCTAssertTrue(body(text, signature).contains("watchlistWriteRefused("), "\(file): \(signature) prüft die Sperre nicht")
            }
        }
        // Neue Alarme sperrt weiterhin `alarmSaveBlocked` (der Bildschirm nennt es vorher selbst); zusätzlich fragen
        // auch sie die Sicherung (`watchlistWriteRefused`)
        let alarms = try source("AppData+Alarms.swift")
        XCTAssertTrue(body(alarms, "func saveAlarm(").contains("!alarmSaveBlocked"))
        XCTAssertTrue(body(alarms, "func createAlarm(").contains("!alarmSaveBlocked"))
    }

    /// F-08, Review UX3-02/05, IOS3-R04: Die Rückfrage kommt EINMAL je Aktion VOR der Aktion im
    /// Bildschirm (`withWatchlistConfirm`/`confirmWatchlistWrite`); der Schreibweg fragt nicht und führt nichts erneut
    /// aus (kein `retry`), sondern lehnt ohne Bestätigung ab. Gezeigt wird sie über allem (`WatchlistNotSavedGatePresenter`).
    func testActionsConfirmFirstAndWritePathsOnlyRefuse() throws {
        let lock = try source("AppData+WatchlistLock.swift")
        XCTAssertFalse(lock.contains("retry"), "Schreibweg führt nach «Trotzdem» nichts mehr erneut aus")
        // «Ohne Zustimmung abgelehnt, ohne Frage» als Verhalten: `WatchlistConsentBehaviorTests`
        // ARC7-02: nur über `withWatchlistConfirm` (Aktion unter `runConsented`), nicht direkt aus Bildschirmen
        XCTAssertTrue(lock.contains("private func confirmWatchlistWrite() async -> Bool"))
        for file in ["AppData+Watchlist.swift", "AppData+WatchEdits.swift", "AppData+Reading.swift", "AppData+Alarms.swift"] {
            XCTAssertFalse(try source(file).contains("retry:"), "\(file): Schreibweg mit retry")
        }
        // Jeder Bildschirm, der Schreibwege des Nutzers aufruft, fragt vorher
        let features = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Features")
        let screens = [
            "Watchlist/WatchlistEditing.swift", "Watchlist/WatchSheetActions.swift", "Watchlist/WatchActionsSheet.swift",
            "Watchlist/WatchSheetGroup.swift", "Watchlist/WatchGroupEditSheet.swift", "Watchlist/WatchEditSheet.swift",
            "Watchlist/WatchlistScreen+Starter.swift", "Explorer/ExplorerBulkActions.swift",
            "Explorer/ExplorerSelectionActions.swift", "Alarms/AlarmsScreen+Save.swift",
            "Alarms/AlarmsOverviewScreen.swift",
            "Settings/SettingsAlarmPages.swift", "Cycle/MarketCoinSheets.swift",
        ]
        for file in screens {
            let text = try String(contentsOf: features.appendingPathComponent(file), encoding: .utf8)
            XCTAssertTrue(text.contains("withWatchlistConfirm"), "\(file) fragt nicht vorher")
        }
        // Frage über allem statt am obersten registrierten Bildschirm (unter einem Blatt konnte sie ausbleiben)
        let hint = try String(contentsOf: features.deletingLastPathComponent().appendingPathComponent("Components/WatchlistLockedHint.swift"),
                              encoding: .utf8)
        XCTAssertFalse(hint.contains(".alert("))
        // Alarm speichern: «Grenze erreicht» nur bei `.full`, Erfolg (Haptik, Bestätigung) nur bei `.saved`
        let alarms = try String(contentsOf: features.appendingPathComponent("Alarms/AlarmsScreen+Save.swift"), encoding: .utf8)
        XCTAssertTrue(alarms.contains("guard case .saved(let firstAlarm) = outcome"))
    }

    /// Kurse, Alarmzustand und Widget schreiben weiter — kein Hinweis, keine Sperre.
    func testBackgroundWritesDoNotAskTheLock() throws {
        for file in ["AppData+Refresh.swift", "AppData+Live.swift", "AppData.swift"] {
            XCTAssertFalse(try source(file).contains("watchlistWriteRefused("), "\(file) darf nicht gesperrt werden")
        }
    }
}
