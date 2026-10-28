import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// `WatchlistStorageLock`: Alarme sperren und Dauerhinweis, solange die gespeicherte Merkliste da, aber nicht lesbar ist
/// (wie Android `WatchlistStorageLockTest`) — nicht bei fehlender oder kaputter Datei und nicht bei verzögertem Speichern.
/// Läuft in einem temporären Ordner mit gefälschtem Schlüsselbund, nie auf der App Group.
final class WatchlistStorageLockTests: XCTestCase {

    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolioAlarms: URL { dir.appendingPathComponent("portfolio_alarms.json") }
    private let fixedKey = SymmetricKey(data: Data(repeating: 7, count: 32))
    private let json = Data("""
    {"watches":[{"id":1,"exchangeKey":"binance","baseAsset":"BTC","quoteAsset":"USDT"}],
     "alarms":[],"nextWatchId":2,"nextAlarmId":1}
    """.utf8)

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("watchlist-lock-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
        // Prozessweite Merker der gastgebenden App nicht verfälscht zurücklassen
        SharedStorage.watchlistPending = false
        SharedStorage.portfolioAlarmsPending = false
    }

    private func keys(_ key: SymmetricKey?, locked: Bool = false) -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        if locked { fake.readStatus = errSecInteractionNotAllowed }
        return StorageKey(keychain: fake)
    }

    private func readLocked(_ keys: StorageKey) -> Bool {
        WatchlistStorageLock.isLocked(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms, keys: keys))
    }

    func testOnlyInaccessibleLocks() {
        XCTAssertTrue(WatchlistStorageLock.isLocked(.inaccessible))
        XCTAssertFalse(WatchlistStorageLock.isLocked(.ok(SharedStorage.Snapshot())))
        XCTAssertFalse(WatchlistStorageLock.isLocked(.missing))
        XCTAssertFalse(WatchlistStorageLock.isLocked(.unreadable))
    }

    /// Schon einmal gelesen (Speicher hält den echten Stand): ein später unlesbarer Zugriff, z. B. im Hintergrund bei
    /// gesperrtem Gerät, sperrt nicht mehr — nur ohne erfolgreiches Lesen in diesem Lauf.
    func testLockedOnlyWhenNeverReadInThisRun() {
        XCTAssertTrue(WatchlistStorageLock.isLocked(.inaccessible, readBefore: false))
        XCTAssertFalse(WatchlistStorageLock.isLocked(.inaccessible, readBefore: true))
        XCTAssertFalse(WatchlistStorageLock.isLocked(.ok(SharedStorage.Snapshot()), readBefore: false))
        XCTAssertTrue(WatchlistStorageLock.holdsStoredState(.ok(SharedStorage.Snapshot())))
        XCTAssertTrue(WatchlistStorageLock.holdsStoredState(.missing))
        XCTAssertFalse(WatchlistStorageLock.holdsStoredState(.inaccessible))
        XCTAssertFalse(WatchlistStorageLock.holdsStoredState(.unreadable))
    }

    func testAlarmSaveBlockedOnlyWhenLocked() {
        XCTAssertTrue(WatchlistStorageLock.blocksAlarmSave(locked: true))
        XCTAssertFalse(WatchlistStorageLock.blocksAlarmSave(locked: false))
    }

    /// Versiegelte Datei, Schlüsselbund gerade nicht lesbar: gesperrt; mit Schlüssel wieder frei.
    func testSealedFileWithoutKeyIsLocked() throws {
        let snap = try JSONDecoder().decode(SharedStorage.Snapshot.self, from: json)
        XCTAssertTrue(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                               includePortfolioInBackup: true, keys: keys(fixedKey)))
        XCTAssertTrue(readLocked(keys(fixedKey, locked: true)))
        XCTAssertFalse(readLocked(keys(fixedKey)))
    }

    /// Noch keine Datei bzw. alte Klartext-Datei ohne Schlüssel (Speichern wartet, `watchlistPending`): nicht gesperrt —
    /// der Stand im Speicher ist vollständig und geht mit dem nächsten Speichern hinaus.
    func testMissingOrPendingIsNotLocked() throws {
        XCTAssertFalse(readLocked(keys(nil, locked: true)))
        try json.write(to: watchlist, options: [.atomic])
        let snap = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                         keys: keys(nil, locked: true)).snapshot)
        XCTAssertFalse(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: portfolioAlarms,
                                                includePortfolioInBackup: true, keys: keys(nil, locked: true)))
        XCTAssertTrue(SharedStorage.watchlistPending)
        XCTAssertFalse(readLocked(keys(nil, locked: true)))
        // Dauerhinweis «wird nicht gespeichert» (wie Android Notice.NOT_SAVED), Alarme bleiben erlaubt
        XCTAssertEqual(WatchlistStorageLock.notice(locked: false, savePending: SharedStorage.watchlistPending), .notSaved)
    }

    /// Hinweis wie Android `WatchlistStorageLock.notice`: gesperrt vor «nicht gespeichert», sonst keiner.
    func testNoticeMatchesAndroid() {
        XCTAssertEqual(WatchlistStorageLock.notice(locked: true, savePending: true), .kept)
        XCTAssertEqual(WatchlistStorageLock.notice(locked: true, savePending: false), .kept)
        XCTAssertEqual(WatchlistStorageLock.notice(locked: false, savePending: true), .notSaved)
        XCTAssertNil(WatchlistStorageLock.notice(locked: false, savePending: false))
    }
}
