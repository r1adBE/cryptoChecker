import XCTest
@testable import CryptoChecker

/// Prozessübergreifende Alarm-Lease (`AlarmLease`) in einem eigenen Ordner: nehmen, zweites Nehmen scheitert,
/// freigeben, Übernahme nach Ablauf und verwaiste Lease eines beendeten Prozesses.
final class AlarmLeaseTests: XCTestCase {
    private var folder: URL!
    private var url: URL { folder.appendingPathComponent("alarm.lease") }

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("AlarmLeaseTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    /// Lease-Datei, die ein anderer (beendeter) Prozess liegen liess, mit Änderungsdatum `age` Sekunden zurück.
    private func writeForeignLease(age: TimeInterval) throws {
        try "fremder-token".write(to: url, atomically: false, encoding: .utf8)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-age)],
                                              ofItemAtPath: url.path)
    }

    func testSecondAcquireFailsWhileHeld() throws {
        let first = try XCTUnwrap(AlarmLease.tryAcquire(url: url))
        XCTAssertFalse(first.takenOver)
        XCTAssertNil(AlarmLease.tryAcquire(url: url))
        XCTAssertTrue(first.refresh())
        withExtendedLifetime(first) {}
    }

    func testReleaseDeletesFileAndAllowsNextAcquire() throws {
        let first = try XCTUnwrap(AlarmLease.tryAcquire(url: url))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        first.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        // Mehrfach freigeben ist erlaubt; danach gilt die Lease nicht mehr
        first.release()
        XCTAssertFalse(first.refresh())
        let second = try XCTUnwrap(AlarmLease.tryAcquire(url: url))
        XCTAssertFalse(second.takenOver)
        withExtendedLifetime(second) {}
    }

    /// Nach `staleAfterSeconds` ohne Auffrischen übernimmt ein anderer; der alte Halter verliert sie und löscht
    /// beim Freigeben nicht die Lease des neuen.
    func testTakeoverAfterExpiry() throws {
        let first = try XCTUnwrap(AlarmLease.tryAcquire(url: url))
        // Noch frisch: kein Übernehmen
        XCTAssertNil(AlarmLease.tryAcquire(now: Date().addingTimeInterval(AlarmLease.staleAfterSeconds - 5), url: url))
        let later = Date().addingTimeInterval(AlarmLease.staleAfterSeconds + 5)
        let second = try XCTUnwrap(AlarmLease.tryAcquire(now: later, url: url))
        XCTAssertTrue(second.takenOver)
        XCTAssertFalse(first.refresh(), "verlorene Lease darf keine Alarme mehr melden")
        first.release()
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(second.refresh())
        second.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    /// Verwaiste Lease eines beendeten Prozesses: `acquire` übernimmt sie (mit Nachprüfung); eine frische fremde
    /// Lease dagegen nicht — dann nach der Wartezeit nil.
    func testAbandonedLeaseIsTakenOverButFreshForeignLeaseIsNot() async throws {
        try writeForeignLease(age: AlarmLease.staleAfterSeconds + 60)
        let taken = await AlarmLease.acquire(timeoutSeconds: 1, url: url)
        let lease = try XCTUnwrap(taken)
        XCTAssertTrue(lease.takenOver)
        XCTAssertTrue(lease.refresh())
        lease.release()

        try writeForeignLease(age: 1)
        let blocked = await AlarmLease.acquire(timeoutSeconds: 0.3, url: url)
        XCTAssertNil(blocked)
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "fremder-token")
    }
}
