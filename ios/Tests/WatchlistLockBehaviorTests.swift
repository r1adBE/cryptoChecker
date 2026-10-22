import XCTest
@testable import CryptoChecker

/// Notfall-Sperre der Merkliste als Verhalten (seit Build 103, Review TST-02), wie Android `WatchlistLockDeviceTest`:
/// Mit `watchlistLocked` ruft der Test jeden Schreibweg des Nutzers von `AppData` auf und prüft, dass sich weder Merkliste
/// noch Alarme noch Einstellungen ändern und jeder Weg die Ablehnung meldet (`watchlistLockedHints`, Drosselung je Aufruf
/// zurückgesetzt). Ergänzt die Quelltext-Prüfung in `WatchlistWriteLockTests`. Läuft auf `AppData.shared` der Test-App;
/// gesperrt schreibt dort nichts. Synchron auf dem Hauptthread: Kein Lesen der Datei kann die Sperre zwischendurch ändern.
@MainActor
final class WatchlistLockBehaviorTests: XCTestCase {

    private func encoded(_ snapshot: SharedStorage.Snapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(snapshot)
    }

    func testLockedWatchlistIgnoresEveryUserWrite() throws {
        let data = AppData.shared
        let wasLocked = data.watchlistLocked
        let hintAt = data.watchlistLockedHintAt
        data.watchlistLocked = true
        defer {
            data.watchlistLocked = wasLocked
            data.watchlistLockedHintAt = hintAt
        }
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        let sol = MarketPair("SOL", "USDT", "SOLUSDT")
        // Vorhandenes Paar, sonst ein erfundenes: gesperrt endet jeder Weg vor dem Nachschlagen
        let watch = data.snapshot.watches.first
            ?? Watch(id: 999_001, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        let alarmId = data.snapshot.alarms.first?.id ?? 999_002
        let deleted = DeletedWatch(watch: Watch(id: 999_003, marketKey: "Binance", marketName: "Binance", baseAsset: "SOL",
                                                quoteAsset: "USDT", pairId: "SOLUSDT"),
                                   alarms: [], activity: nil, selectedGroup: nil)

        // Jeder Schreibweg des Nutzers mit Argumenten, die ungesperrt etwas änderten; Rückgabe «gesperrt» gleich mit
        let userWrites: [(String, () -> Void)] = [
            ("addStarterCoins", { XCTAssertEqual(data.addStarterCoins(["SOL"]), 0) }),
            ("changePair", { XCTAssertNil(data.changePair(watch.id, market: market, pair: sol)) }),
            ("delete", { data.delete(watch) }),
            ("deleteForUndo(watch)", { XCTAssertNil(data.deleteForUndo(watch)) }),
            ("restore(one)", { XCTAssertEqual(data.restore(deleted), UndoRestoreResult()) }),
            ("deleteForUndo(ids)", { XCTAssertTrue(data.deleteForUndo(ids: [watch.id]).isEmpty) }),
            ("restore(many)", { XCTAssertEqual(data.restore([deleted]), UndoRestoreResult()) }),
            ("deleteAllForUndo", { XCTAssertTrue(data.deleteAllForUndo().isEmpty) }),
            ("addWatch", { XCTAssertNil(data.addWatch(market: market, pair: sol)) }),
            ("tryAddWatch", { XCTAssertEqual(data.tryAddWatch(market: market, pair: sol), .locked) }),
            ("addWatches", { XCTAssertTrue(data.addWatches(market: market, pairs: [sol]).locked) }),
            ("setNotificationEnabled", { data.setNotificationEnabled(watch, !watch.notificationEnabled) }),
            ("setAllNotifications", { data.setAllNotifications(false) }),
            ("setTtsEnabled", { XCTAssertFalse(data.setTtsEnabled(watch, !watch.ttsEnabled)) }),
            ("setNote", { data.setNote(watch, "neu") }),
            ("setGroup(watch)", { data.setGroup(watch, "Neu") }),
            ("setGroup(ids)", { data.setGroup(ids: [watch.id], "Neu") }),
            ("setFavorite(ids)", { data.setFavorite(ids: [watch.id], true) }),
            ("setDisabled", { data.setDisabled(ids: [watch.id], true) }),
            ("toggleFavorite", { data.toggleFavorite(watch) }),
            ("move", { data.move(watch, .bottom) }),
            ("reorder", { data.reorder(Array(data.snapshot.watches.map(\.id).reversed()) + [watch.id]) }),
            ("applyGroupEdit", { data.applyGroupEdit(oldName: nil, newName: "Neu", memberIds: [watch.id]) }),
            ("deleteGroup", { data.deleteGroup(watch.groupName ?? "Neu") }),
            ("deleteAlarm", { data.deleteAlarm(alarmId) }),
            ("setAlarmEnabled", { data.setAlarmEnabled(alarmId, false) }),
        ]

        let snapshotBefore = try encoded(data.snapshot)
        let settingsBefore = data.settings
        for (name, write) in userWrites {
            // Drosselung des Hinweises zurücksetzen: Jeder Weg muss die Ablehnung selbst melden
            data.watchlistLockedHintAt = nil
            let hints = data.watchlistLockedHints
            write()
            XCTAssertEqual(data.watchlistLockedHints, hints &+ 1, "\(name) meldet die Sperre nicht")
            XCTAssertEqual(try encoded(data.snapshot), snapshotBefore, "\(name) hat trotz Sperre geschrieben")
            XCTAssertEqual(data.settings, settingsBefore, "\(name) hat trotz Sperre Einstellungen geändert")
        }

        // Neue Alarme sperrt `alarmSaveBlocked` (ohne eigenen Hinweis: der Bildschirm nennt es vorher)
        XCTAssertFalse(data.saveAlarm(watchId: watch.id, id: 0, condition: .PRICE_ABOVE, threshold: 1, repeating: false,
                                      sound: true, vibrate: true, speak: false, windowHours: 1))
        let created = data.createAlarm(watchId: watch.id,
                                       from: AlarmTemplates.Definition(condition: .PRICE_ABOVE, threshold: 1, windowHours: 1,
                                                                       referencePrice: nil))
        XCTAssertEqual(created.id, 0)
        XCTAssertEqual(try encoded(data.snapshot), snapshotBefore)
    }
}
