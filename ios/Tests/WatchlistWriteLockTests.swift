import XCTest
@testable import CryptoChecker

/// Sperre der Merkliste, wie Android `WatchlistWriteRefusalTest`: Regeln der Sperre, der gedrosselte Hinweis, nur der
/// oberste Bildschirm meldet, und das Ergebnis des Alarm-Speicherns. Dass jeder Schreibweg gesperrt ablehnt, prüfen
/// `WatchlistLockBehaviorTests` und `WatchlistConsentBehaviorTests`; dass jeder Bildschirm vorher fragt, die Regel
/// `ios-write-consent` in `.github/scripts/source_rules.py`.
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

    // MARK: Ergebnis des Alarm-Speicherns

    /// «Paar voll» nur bei `.full`; abgelehnt bzw. abgebrochen still — wie Android `AlarmSaveOutcomeTest`.
    func testAlarmSaveOutcomeReportsTheLimitOnlyWhenFull() {
        var limit = 0
        XCTAssertEqual(AlarmSaveOutcome.saved(firstAlarm: true).savedOr { limit += 1 }, true)
        XCTAssertEqual(AlarmSaveOutcome.saved(firstAlarm: false).savedOr { limit += 1 }, false)
        XCTAssertEqual(limit, 0)
        XCTAssertNil(AlarmSaveOutcome.refused.savedOr { limit += 1 })
        XCTAssertEqual(limit, 0)
        XCTAssertNil(AlarmSaveOutcome.full.savedOr { limit += 1 })
        XCTAssertEqual(limit, 1)
    }
}
