import XCTest
@testable import CryptoChecker

/// Sperre der Merkliste (seit Build 103, Review F-08), wie Android `WatchRepositoryLockTest`: Ist die gespeicherte
/// Merkliste gerade nicht lesbar (`watchlistLocked`), lehnt `AppData` jeden Schreibweg des Nutzers ab, Hintergrund nicht;
/// der Hinweis ist gedrosselt und erscheint nur auf dem zuletzt erschienenen Bildschirm. Die Schreibwege prüft der Test
/// an der Quelle, weil `AppData` die echte App Group nutzt.
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
        // Neue Alarme sperrt weiterhin `alarmSaveBlocked` (der Bildschirm nennt es vorher selbst)
        let alarms = try source("AppData+Alarms.swift")
        XCTAssertTrue(body(alarms, "func saveAlarm(").contains("!alarmSaveBlocked"))
        XCTAssertTrue(body(alarms, "func createAlarm(").contains("!alarmSaveBlocked"))
    }

    /// Kurse, Alarmzustand und Widget schreiben weiter — kein Hinweis, keine Sperre.
    func testBackgroundWritesDoNotAskTheLock() throws {
        for file in ["AppData+Refresh.swift", "AppData+Live.swift", "AppData.swift"] {
            XCTAssertFalse(try source(file).contains("watchlistWriteRefused("), "\(file) darf nicht gesperrt werden")
        }
    }
}
