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

    /// Adress-Schreibwege des Nutzers prüfen die Sperre, Guthaben-Abrufe und automatische Umstellungen nicht (wie Android
    /// `PortfolioStorageLockTest`). Prüft die Quelle, weil `AppData` die echte App Group nutzt.
    func testAddressUserWritesCheckLock() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/AppData+WatchedAddresses.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        func body(_ name: String) -> String {
            guard let start = source.range(of: "func \(name)(") else { XCTFail("\(name) fehlt"); return "" }
            let rest = source[start.upperBound...]
            let end = rest.range(of: "\n    func ")?.lowerBound ?? rest.range(of: "\n    private func ")?.lowerBound ?? rest.endIndex
            let nextPrivate = rest.range(of: "\n    private func ")?.lowerBound ?? rest.endIndex
            return String(rest[..<min(end, nextPrivate)])
        }
        for name in ["addWatchedAddress", "removeWatchedAddress", "restoreWatchedAddress", "changeWatchedAddress",
                     "setWatchedAddressLabel", "replaceWatchedAddresses", "mergeWatchedAddresses"] {
            XCTAssertTrue(body(name).contains("guard !portfolioStorageLocked"), "\(name) prüft die Sperre nicht")
        }
        for name in ["applyWatchedAddressUpdates", "applyWatchedAddressOverlapShowOnly", "rollBackWatchedAddresses"] {
            XCTAssertFalse(body(name).contains("portfolioStorageLocked"), "\(name) darf nicht gesperrt werden")
        }
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
