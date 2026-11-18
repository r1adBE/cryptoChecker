import XCTest
@testable import CryptoChecker

/// Schlüsselbund und Ordner für `AutoBackup` im Test. Der Ordner lässt sich nie auflösen: So startet nie ein echter
/// Sicherungslauf, jeder Versuch endet sofort als gescheitert.
@MainActor
final class FakeAutoBackupAccess: AutoBackupAccess {
    var bookmarkData: Data? = Data("bookmark".utf8)
    var storeSucceeds = true
    private(set) var storedPassword: String?
    private(set) var deleteCount = 0

    func storePassword(_ password: String) -> Bool {
        guard storeSucceeds else { return false }
        storedPassword = password
        return true
    }

    func readPassword() -> String? { storedPassword }

    func deletePassword() {
        deleteCount += 1
        storedPassword = nil
    }

    func bookmark(_ folder: URL) -> Data? { bookmarkData }

    func resolveBookmark(_ bookmark: Data) -> (url: URL, stale: Bool)? { nil }
}

/// Automatische Sicherung als Zustand von `AutoBackup` — wie Android `AutoBackupStoreTest`: Einschalten nur mit Ordner
/// und Passwort, erster Versuch gleich danach, Ordner wechseln, Ausschalten, Fälligkeit. Eigene `UserDefaults` je Test;
/// jeder Zustand wird mit einer zweiten Instanz auf denselben Einstellungen gegengelesen.
@MainActor
final class AutoBackupTests: XCTestCase {
    private var folder: URL {
        URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true).appendingPathComponent("Sicherungen", isDirectory: true)
    }

    private func makeDefaults() throws -> (UserDefaults, String) {
        let suite = "auto-backup-\(UUID().uuidString)"
        return (try XCTUnwrap(UserDefaults(suiteName: suite)), suite)
    }

    /// Zweite Instanz auf denselben Einstellungen: Was sie liest, ist gespeichert.
    private func reread(_ defaults: UserDefaults) -> AutoBackup.State {
        AutoBackup(defaults: defaults, access: FakeAutoBackupAccess(), reschedule: { _ in }).state
    }

    func testFreshStoreIsOff() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let backup = AutoBackup(defaults: defaults, access: FakeAutoBackupAccess(), reschedule: { _ in })
        XCTAssertEqual(backup.state, AutoBackup.State())
        XCTAssertFalse(backup.running)
    }

    func testEnableNeedsBookmarkAndKeychain() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let access = FakeAutoBackupAccess()
        let backup = AutoBackup(defaults: defaults, access: access, reschedule: { _ in })

        access.bookmarkData = nil
        XCTAssertFalse(backup.enable(folder: folder, password: "geheim", data: AppData.shared))
        XCTAssertNil(access.storedPassword, "ohne Ordner kein Passwort abgelegt")

        access.bookmarkData = Data("bookmark".utf8)
        access.storeSucceeds = false
        XCTAssertFalse(backup.enable(folder: folder, password: "geheim", data: AppData.shared))

        XCTAssertEqual(backup.state, AutoBackup.State())
        XCTAssertEqual(reread(defaults), AutoBackup.State())
    }

    func testEnableRemembersFolderAndTriesFirstBackupAtOnce() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let access = FakeAutoBackupAccess()
        let backup = AutoBackup(defaults: defaults, access: access, reschedule: { _ in })

        XCTAssertTrue(backup.enable(folder: folder, password: "geheim", data: AppData.shared))

        XCTAssertEqual(access.storedPassword, "geheim")
        XCTAssertTrue(backup.state.enabled)
        XCTAssertEqual(backup.state.folderName, "Sicherungen")
        // Erster Versuch gleich danach (hier ohne auflösbaren Ordner: gescheitert, keine gelungene Sicherung)
        XCTAssertGreaterThan(backup.state.lastAttemptAt, 0)
        XCTAssertTrue(backup.state.lastFailed)
        XCTAssertEqual(backup.state.lastSuccessAt, 0)
        XCTAssertFalse(backup.running)
        XCTAssertEqual(reread(defaults), backup.state)
    }

    /// Gerade gescheitert: erst nach `AutoBackupPolicy.retryAfterMillis` wieder fällig, der Zustand bleibt.
    func testFailedAttemptIsNotRetriedAtOnce() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let backup = AutoBackup(defaults: defaults, access: FakeAutoBackupAccess(), reschedule: { _ in })
        XCTAssertTrue(backup.enable(folder: folder, password: "geheim", data: AppData.shared))
        let afterFirst = backup.state

        backup.runIfDue(AppData.shared)

        XCTAssertEqual(backup.state, afterFirst)
    }

    func testNothingRunsWhileOff() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let backup = AutoBackup(defaults: defaults, access: FakeAutoBackupAccess(), reschedule: { _ in })

        backup.runIfDue(AppData.shared)
        backup.backUpNow(AppData.shared)

        XCTAssertEqual(backup.state, AutoBackup.State())
        XCTAssertFalse(backup.running)
        XCTAssertEqual(reread(defaults), AutoBackup.State())
    }

    func testChangeFolderKeepsPasswordAndTriesAgain() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let access = FakeAutoBackupAccess()
        let backup = AutoBackup(defaults: defaults, access: access, reschedule: { _ in })
        XCTAssertTrue(backup.enable(folder: folder, password: "geheim", data: AppData.shared))
        let other = folder.deletingLastPathComponent().appendingPathComponent("Andere", isDirectory: true)

        access.bookmarkData = nil
        backup.changeFolder(other, data: AppData.shared)
        XCTAssertEqual(backup.state.folderName, "Sicherungen", "ohne Lesezeichen bleibt der alte Ordner")

        access.bookmarkData = Data("other".utf8)
        backup.changeFolder(other, data: AppData.shared)
        XCTAssertEqual(backup.state.folderName, "Andere")
        XCTAssertTrue(backup.state.enabled)
        XCTAssertEqual(access.storedPassword, "geheim")
        XCTAssertEqual(access.deleteCount, 0)
        XCTAssertEqual(reread(defaults), backup.state)
    }

    /// Ausschalten vergisst Passwort, Ordner und Zeiten.
    func testDisableForgetsEverything() throws {
        let (defaults, suite) = try makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let access = FakeAutoBackupAccess()
        let backup = AutoBackup(defaults: defaults, access: access, reschedule: { _ in })
        XCTAssertTrue(backup.enable(folder: folder, password: "geheim", data: AppData.shared))

        backup.disable()

        XCTAssertEqual(backup.state, AutoBackup.State())
        XCTAssertEqual(access.deleteCount, 1)
        XCTAssertNil(access.storedPassword)
        XCTAssertEqual(reread(defaults), AutoBackup.State())
    }
}

/// Vormerkung der Hintergrund-Aufgabe: frühestens dann, wenn `AutoBackupPolicy.isDue` wieder gilt.
final class AutoBackupTaskTests: XCTestCase {
    private let day: Int64 = 24 * 60 * 60 * 1000
    private let now: Int64 = 1_800_000_000_000

    func testNeverBackedUpBeginsNow() {
        XCTAssertEqual(AutoBackupTask.earliestBegin(lastSuccessAt: 0, lastAttemptAt: 0, now: now), now)
    }

    func testAfterSuccessBeginsAWeekLater() {
        let begin = AutoBackupTask.earliestBegin(lastSuccessAt: now - day, lastAttemptAt: now - day, now: now)
        XCTAssertEqual(begin, now + 6 * day)
        XCTAssertFalse(AutoBackupPolicy.isDue(enabled: true, lastSuccessAt: now - day, lastAttemptAt: now - day,
                                              now: begin - 1))
        XCTAssertTrue(AutoBackupPolicy.isDue(enabled: true, lastSuccessAt: now - day, lastAttemptAt: now - day, now: begin))
    }

    func testAfterFailureWaitsForTheRetry() {
        let attempt = now - 60_000
        let begin = AutoBackupTask.earliestBegin(lastSuccessAt: now - 30 * day, lastAttemptAt: attempt, now: now)
        XCTAssertEqual(begin, attempt + AutoBackupPolicy.retryAfterMillis)
    }

    func testTimesInTheFutureCountAsDue() {
        XCTAssertEqual(AutoBackupTask.earliestBegin(lastSuccessAt: now + day, lastAttemptAt: now + day, now: now), now)
    }
}
