import XCTest
@testable import CryptoChecker

/// Speicher und Sicherheit: Merkliste ohne Schlüssel (Hinweis wie Android), gesperrtes Portfolio sperrt auch
/// die beobachteten Adressen, Lesen der Merkliste im Hintergrund übernimmt nie einen veralteten Stand.
final class StorageSafetyTests: XCTestCase {

    // MARK: Merkliste ohne Schlüssel

    func testWaitsForKeyOnlyWithoutFileAndWithoutKey() {
        XCTAssertTrue(WatchlistStorageLock.waitsForKey(.missing, keyAvailable: false))
        XCTAssertFalse(WatchlistStorageLock.waitsForKey(.missing, keyAvailable: true))
        // Datei da: gelesen, gesperrt (eigener Hinweis) bzw. kaputt (gesichert) — nicht «wartet auf den Schlüssel»
        XCTAssertFalse(WatchlistStorageLock.waitsForKey(.ok(SharedStorage.Snapshot()), keyAvailable: false))
        XCTAssertFalse(WatchlistStorageLock.waitsForKey(.inaccessible, keyAvailable: false))
        XCTAssertFalse(WatchlistStorageLock.waitsForKey(.unreadable, keyAvailable: false))
        // Gleicher Hinweis wie Android Notice.NOT_SAVED; Alarme bleiben erlaubt
        XCTAssertEqual(WatchlistStorageLock.notice(locked: false, savePending: true), .notSaved)
        XCTAssertFalse(WatchlistStorageLock.blocksAlarmSave(locked: false))
    }

    // MARK: Gesperrtes Portfolio

    func testPortfolioLockedOnlyWithStoredStateAndWithoutKey() {
        XCTAssertTrue(PortfolioStorageLock.isLocked(keyAvailable: false, storedFileExists: true))
        // Ohne gespeicherten Stand nichts zu verlieren: im Speicher warten (wie Android ohne Datei)
        XCTAssertFalse(PortfolioStorageLock.isLocked(keyAvailable: false, storedFileExists: false))
        XCTAssertFalse(PortfolioStorageLock.isLocked(keyAvailable: true, storedFileExists: true))
        XCTAssertFalse(PortfolioStorageLock.isLocked(keyAvailable: true, storedFileExists: false))
    }

    // MARK: Lesen im Hintergrund

    func testDiskReloadAppliesOnlyUnchangedLatestRead() {
        let ticket = DiskReloadTicket(sequence: 3, generation: 10, reset: 0)
        XCTAssertEqual(ticket.verdict(sequence: 3, generation: 10, reset: 0), .apply)
        // Seither geändert (Paar hinzugefügt, Live-Kurs, synchrones Lesen): Speicher gilt
        XCTAssertEqual(ticket.verdict(sequence: 3, generation: 11, reset: 0), .stale)
        // Neueres Lesen begonnen oder zurückgesetzt: nichts tun
        XCTAssertEqual(ticket.verdict(sequence: 4, generation: 10, reset: 0), .superseded)
        XCTAssertEqual(ticket.verdict(sequence: 3, generation: 10, reset: 1), .superseded)
        XCTAssertEqual(ticket.verdict(sequence: 4, generation: 11, reset: 0), .superseded)
    }
}
