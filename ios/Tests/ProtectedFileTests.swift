import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Verschlüsselte Dateien: Format (`ProtectedContainer`), Lesen und Schreiben (`ProtectedFile`) und die
/// Überführung beim Start (`ProtectedStorage.prepareAtLaunch`) mit gefälschtem Schlüsselbund — jeder Fehler lässt die
/// Klartext-Datei stehen. Nur iOS (Android überführt keinen Klartext). Läuft in einem
/// temporären Ordner, nie auf der App Group oder dem echten Schlüsselbund.
final class ProtectedFileTests: XCTestCase {

    /// Schlüsselbund im Speicher; Fehler je Aufruf einstellbar.
    final class FakeKeychain: StorageKeychain, @unchecked Sendable { // @unchecked: Test-Fake, nur nacheinander aus dem Testablauf benutzt (keine Sperre nötig)
        var stored: Data?
        var readStatus: OSStatus?
        var addStatus: OSStatus?
        /// Ein anderer Prozess legt beim Hinzufügen gleichzeitig diesen Schlüssel an.
        var racingKey: Data?
        private(set) var adds = 0

        func read() throws -> Data? {
            if let readStatus { throw StorageKeychainError(status: readStatus) }
            return stored
        }

        func add(_ key: Data) throws -> Bool {
            if let addStatus { throw StorageKeychainError(status: addStatus) }
            if let racingKey { stored = racingKey; return false }
            guard stored == nil else { return false }
            stored = key
            adds += 1
            return true
        }

        /// Fehler beim Löschen (z. B. Schlüsselbund gesperrt).
        var deleteStatus: OSStatus?
        private(set) var deletes = 0

        func delete() throws {
            if let deleteStatus { throw StorageKeychainError(status: deleteStatus) }
            stored = nil
            deletes += 1
        }
    }

    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolio: URL { dir.appendingPathComponent("portfolio.json") }
    /// Kopien unlesbarer Dateien: die einzigen, die ohne Schlüssel unverschlüsselt entstehen (`ProtectedFile.writeCopy`).
    private var watchlistCopy: URL { dir.appendingPathComponent("watchlist.unreadable.json") }
    private var portfolioCopy: URL { dir.appendingPathComponent("portfolio.unreadable.json") }
    private let fixedKey = SymmetricKey(data: Data(repeating: 7, count: 32))
    private let json = Data(#"{"watches":[{"id":1,"baseAsset":"BTC","note":"Ø 77 500 ✓"}]}"#.utf8)

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("protected-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func keys(_ keychain: FakeKeychain) -> StorageKey { StorageKey(keychain: keychain) }

    private func keychain(with key: SymmetricKey?) -> FakeKeychain {
        let fake = FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        return fake
    }

    private func files(_ urls: URL..., sealedOnly: Bool = false) -> [ProtectedStorage.Entry] {
        urls.map { ($0, $0, sealedOnly) }
    }

    private func leftovers() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".tmp") }
    }

    // MARK: Format

    func testSealedLayoutIsMagicVersionNonceCiphertextTag() throws {
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 1, count: 12))
        let sealed = try ProtectedContainer.seal(json, key: fixedKey, nonce: nonce)
        XCTAssertEqual(Array(sealed.prefix(5)), Array("CCSE".utf8) + [1])
        XCTAssertEqual(Array(sealed[5 ..< 17]), Array(repeating: 1, count: 12))
        XCTAssertEqual(sealed.count, 5 + 12 + json.count + 16)
        XCTAssertTrue(ProtectedContainer.isSealed(sealed))
        XCTAssertEqual(try ProtectedContainer.open(sealed, key: fixedKey), json)
        // Gleicher Schlüssel und Nonce: gleiches Ergebnis (Format stabil), neue Nonce: anderes
        XCTAssertEqual(try ProtectedContainer.seal(json, key: fixedKey, nonce: nonce), sealed)
        XCTAssertNotEqual(try ProtectedContainer.seal(json, key: fixedKey), sealed)
    }

    func testPlainJsonAndTextAreNotSealed() {
        XCTAssertFalse(ProtectedContainer.isSealed(json))
        XCTAssertFalse(ProtectedContainer.isSealed(Data("CCS".utf8)))
        XCTAssertFalse(ProtectedContainer.isSealed(Data()))
        XCTAssertThrowsError(try ProtectedContainer.open(json, key: fixedKey)) {
            XCTAssertEqual($0 as? ProtectedContainer.Failure, .notSealed)
        }
    }

    func testTamperedTruncatedOrForeignKeyIsDamaged() throws {
        let sealed = try ProtectedContainer.seal(json, key: fixedKey)
        var flipped = sealed
        flipped[20] ^= 0x01
        var header = sealed
        let cases: [Data] = [flipped, sealed.prefix(5 + 12 + 10), sealed.prefix(sealed.count - 1)]
        for data in cases {
            XCTAssertThrowsError(try ProtectedContainer.open(data, key: fixedKey)) {
                XCTAssertEqual($0 as? ProtectedContainer.Failure, .damaged)
            }
        }
        XCTAssertThrowsError(try ProtectedContainer.open(sealed, key: SymmetricKey(size: .bits256))) {
            XCTAssertEqual($0 as? ProtectedContainer.Failure, .damaged)
        }
        header[4] = 2
        XCTAssertThrowsError(try ProtectedContainer.open(header, key: fixedKey)) {
            XCTAssertEqual($0 as? ProtectedContainer.Failure, .unsupportedVersion(2))
        }
    }

    // MARK: Lesen und Schreiben

    func testWriteWithKeyIsSealedAndReadsBack() throws {
        let k = keys(keychain(with: fixedKey))
        try ProtectedFile.write(json, to: watchlist, keys: k)
        let raw = try Data(contentsOf: watchlist)
        XCTAssertTrue(ProtectedContainer.isSealed(raw))
        XCTAssertNil(raw.range(of: Data("BTC".utf8)))
        XCTAssertEqual(try ProtectedFile.read(watchlist, keys: k), json)
        XCTAssertEqual(try leftovers(), [])
    }

    /// Ohne `requireSealed` und ohne Schlüssel (nur Kopien, `writeCopy`) unverschlüsselt; Lesen nimmt Klartext nie an.
    func testWithoutKeyWritesPlainButReadTreatsPlainAsDamaged() throws {
        let k = keys(keychain(with: nil))
        try ProtectedFile.write(json, to: watchlistCopy, keys: k)
        XCTAssertEqual(try Data(contentsOf: watchlistCopy), json)
        for reader in [k, keys(keychain(with: fixedKey))] {
            XCTAssertThrowsError(try ProtectedFile.read(watchlistCopy, keys: reader)) { error in
                guard case ProtectedFile.ReadError.damaged(let raw) = error else { return XCTFail("erwartet .damaged") }
                XCTAssertEqual(raw, json)
            }
        }
    }

    func testSealedFileWithoutKeyIsLockedNotDamaged() throws {
        try ProtectedFile.write(json, to: watchlist, keys: keys(keychain(with: fixedKey)))
        let locked = FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        for fake in [keychain(with: nil), locked] {
            XCTAssertThrowsError(try ProtectedFile.read(watchlist, keys: keys(fake))) { error in
                guard case ProtectedFile.ReadError.locked = error else { return XCTFail("erwartet .locked, nicht \(error)") }
            }
        }
    }

    func testWithoutKeyNeverReplacesSealedFileWithPlain() throws {
        try ProtectedFile.write(json, to: watchlist, keys: keys(keychain(with: fixedKey)))
        let before = try Data(contentsOf: watchlist)
        let locked = FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        XCTAssertThrowsError(try ProtectedFile.write(Data("{}".utf8), to: watchlist, keys: keys(locked))) { error in
            guard case ProtectedFile.ReadError.locked = error else { return XCTFail("erwartet .locked, nicht \(error)") }
        }
        XCTAssertEqual(try Data(contentsOf: watchlist), before)
    }

    func testNewerFormatIsLockedSoNobodyOverwritesIt() throws {
        var sealed = try ProtectedContainer.seal(json, key: fixedKey)
        sealed[4] = 9
        try sealed.write(to: watchlist)
        XCTAssertThrowsError(try ProtectedFile.read(watchlist, keys: keys(keychain(with: fixedKey)))) { error in
            guard case ProtectedFile.ReadError.locked = error else { return XCTFail("erwartet .locked") }
        }
    }

    func testDamagedFileHandsBackRawBytes() throws {
        var sealed = try ProtectedContainer.seal(json, key: fixedKey)
        sealed[sealed.count - 3] ^= 0xff
        try sealed.write(to: watchlist)
        XCTAssertThrowsError(try ProtectedFile.read(watchlist, keys: keys(keychain(with: fixedKey)))) { error in
            guard case ProtectedFile.ReadError.damaged(let raw) = error else { return XCTFail("erwartet .damaged") }
            XCTAssertEqual(raw, sealed)
        }
    }

    func testHoldsOnlyInWantedForm() throws {
        let withKey = keys(keychain(with: fixedKey))
        let noKey = keys(keychain(with: nil))
        try json.write(to: watchlist)
        // Klartext: nie «schon da» (neu schreiben, versiegelt bzw. ohne Schlüssel gar nicht)
        XCTAssertFalse(ProtectedFile.holds(json, at: watchlist, keys: noKey))
        XCTAssertFalse(ProtectedFile.holds(json, at: watchlist, keys: withKey))
        try ProtectedFile.write(json, to: watchlist, keys: withKey)
        XCTAssertTrue(ProtectedFile.holds(json, at: watchlist, keys: withKey))
        XCTAssertFalse(ProtectedFile.holds(json, at: watchlist, keys: noKey))
        XCTAssertFalse(ProtectedFile.holds(Data("{}".utf8), at: watchlist, keys: withKey))
    }

    func testCopyOfPlainIsSealedCopyOfDamagedStaysByteForByte() throws {
        let k = keys(keychain(with: fixedKey))
        let copy = dir.appendingPathComponent("watchlist.unreadable.json")
        try ProtectedFile.writeCopy(Data("{ kaputt".utf8), to: copy, keys: k)
        XCTAssertTrue(ProtectedContainer.isSealed(try Data(contentsOf: copy)))
        XCTAssertEqual(try ProtectedFile.read(copy, keys: k), Data("{ kaputt".utf8))

        var damaged = try ProtectedContainer.seal(json, key: fixedKey)
        damaged[10] ^= 0x01
        try ProtectedFile.writeCopy(damaged, to: copy, keys: k)
        XCTAssertEqual(try Data(contentsOf: copy), damaged)
    }

    func testChangedMeanwhileKeepsNewerFile() throws {
        try json.write(to: watchlist)
        XCTAssertThrowsError(try ProtectedFile.writeSealed(json, to: watchlist, key: fixedKey, unchangedFrom: Data("{}".utf8)))
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
        XCTAssertEqual(try leftovers(), [])
    }

    // MARK: Überführung beim Start

    func testFirstLaunchCreatesKeyAndEncryptsKeepingBackupFlag() throws {
        try json.write(to: watchlistCopy)
        try Data(#"{"transactions":[]}"#.utf8).write(to: portfolioCopy)
        BackupExclusion.set(excluded: true, for: portfolioCopy)
        let fake = keychain(with: nil)
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy, portfolioCopy), keys: keys(fake), defaults: isolatedDefaults())
        XCTAssertEqual(outcome.encrypted, ["watchlist.unreadable.json", "portfolio.unreadable.json"])
        XCTAssertEqual(outcome.setAside, [])
        XCTAssertEqual(fake.adds, 1)
        let k = keys(fake) // frischer Prozess: liest den gespeicherten Schlüssel
        XCTAssertTrue(ProtectedContainer.isSealed(try Data(contentsOf: watchlistCopy)))
        XCTAssertEqual(try ProtectedFile.read(watchlistCopy, keys: k), json)
        XCTAssertEqual(try ProtectedFile.read(portfolioCopy, keys: k), Data(#"{"transactions":[]}"#.utf8))
        XCTAssertEqual(BackupExclusion.isExcluded(portfolioCopy), true)
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), false)
        XCTAssertEqual(try leftovers(), [])

        // Zweiter Start: nichts mehr zu tun
        XCTAssertEqual(ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy, portfolioCopy), keys: k, defaults: isolatedDefaults()), .init())
    }

    func testEncryptionPendingOnlyWhileSomethingStaysPlain() throws {
        try json.write(to: watchlistCopy)
        let fake = keychain(with: nil)
        let first = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(fake), defaults: isolatedDefaults())
        XCTAssertEqual(first.encrypted, ["watchlist.unreadable.json"])
        XCTAssertFalse(first.encryptionPending)
        // Alles verschlüsselt, Schlüsselbund später gesperrt: verschlüsselte Dateien sind kein «noch nicht aktiv»
        fake.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(fake), defaults: isolatedDefaults()).encryptionPending)
    }

    func testMissingFilesAreSkipped() {
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(keychain(with: nil)), defaults: isolatedDefaults())
        XCTAssertEqual(outcome, .init())
        XCTAssertFalse(FileManager.default.fileExists(atPath: watchlistCopy.path))
    }

    func testUnreadableKeychainTouchesNothing() throws {
        try json.write(to: watchlistCopy)
        let fake = FakeKeychain()
        fake.readStatus = errSecInteractionNotAllowed
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(fake), defaults: isolatedDefaults())
        // Unverändert, aber vermerkt: Einstellungen › Sicherung nennt «Verschlüsselung noch nicht aktiv»
        XCTAssertEqual(outcome, ProtectedStorage.LaunchOutcome(keptPlain: ["watchlist.unreadable.json"]))
        XCTAssertTrue(outcome.encryptionPending)
        XCTAssertEqual(try Data(contentsOf: watchlistCopy), json)
        XCTAssertEqual(fake.adds, 0)
    }

    func testNoKeyPossibleKeepsPlain() throws {
        try json.write(to: watchlistCopy)
        let fake = keychain(with: nil)
        fake.addStatus = errSecMissingEntitlement
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(fake), defaults: isolatedDefaults())
        XCTAssertEqual(outcome, ProtectedStorage.LaunchOutcome(keptPlain: ["watchlist.unreadable.json"]))
        XCTAssertTrue(outcome.encryptionPending)
        XCTAssertEqual(try Data(contentsOf: watchlistCopy), json)
    }

    func testLostKeySetsSealedFilesAsideThenStartsWithNewKey() throws {
        // Verschlüsselt mit einem Schlüssel, den es nicht mehr gibt; daneben eine noch unverschlüsselte Kopie
        let sealed = try ProtectedContainer.seal(json, key: fixedKey)
        try sealed.write(to: watchlist)
        try Data(#"{"transactions":[]}"#.utf8).write(to: portfolioCopy)
        let fake = keychain(with: nil)
        let entries = files(watchlist, sealedOnly: true) + files(portfolioCopy)
        let outcome = ProtectedStorage.prepareAtLaunch(files: entries, keys: keys(fake), defaults: isolatedDefaults())
        XCTAssertEqual(outcome.setAside, ["watchlist.json.locked"])
        XCTAssertEqual(outcome.encrypted, ["portfolio.unreadable.json"])
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("watchlist.json.locked")), sealed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: watchlist.path))
        XCTAssertEqual(fake.adds, 1)
        XCTAssertEqual(try ProtectedFile.read(portfolioCopy, keys: keys(fake)), Data(#"{"transactions":[]}"#.utf8))
    }

    /// Unverschlüsselte Datei, die die App nur versiegelt schreibt (`sealedOnly`): nicht überführt, nicht als «noch nicht
    /// aktiv» vermerkt, unverändert liegen gelassen (Lesen behandelt sie wie eine kaputte, `ProtectedFile.read`).
    func testPlainSealedOnlyFileIsNotEncrypted() throws {
        try json.write(to: watchlist)
        let fake = keychain(with: nil)
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist, sealedOnly: true), keys: keys(fake),
                                                       defaults: isolatedDefaults())
        XCTAssertEqual(outcome, .init())
        XCTAssertEqual(fake.adds, 1)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
        fake.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(ProtectedStorage.prepareAtLaunch(files: files(watchlist, sealedOnly: true), keys: keys(fake),
                                                        defaults: isolatedDefaults()).encryptionPending)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
    }

    func testKeyAddedConcurrentlyByOtherProcessWins() throws {
        try json.write(to: watchlistCopy)
        let fake = keychain(with: nil)
        fake.racingKey = fixedKey.withUnsafeBytes { Data($0) }
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(fake), defaults: isolatedDefaults())
        XCTAssertEqual(outcome.encrypted, ["watchlist.unreadable.json"])
        // Mit dem Schlüssel des anderen Prozesses verschlüsselt
        XCTAssertEqual(try ProtectedContainer.open(Data(contentsOf: watchlistCopy), key: fixedKey), json)
    }

    func testUnexpectedKeychainContentIsUnavailableNotReplaced() throws {
        let fake = FakeKeychain()
        fake.stored = Data(repeating: 1, count: 16)
        let k = keys(fake)
        guard case .unavailable = k.lookup() else { return XCTFail("erwartet .unavailable") }
        XCTAssertNil(k.createIfAbsent())
        XCTAssertEqual(fake.stored, Data(repeating: 1, count: 16))
    }

    /// Die Liste der verschlüsselten Dateien: Nutzerdaten ja, reine Kurs-Zwischenspeicher nein.
    func testProtectedFilesCoverUserDataOnly() {
        let names = Set(ProtectedStorage.files.map(\.url.lastPathComponent))
        XCTAssertEqual(names, ["watchlist.json", "watchlist.unreadable.json", "portfolio_alarms.json",
                               "portfolio_alarms.unreadable.json", "portfolio.json", "portfolio.unreadable.json",
                               "watched_addresses.json", "portfolio_widget.json", "alarm_log.json"])
        // Merkliste und Portfolio-Alarme stimmen sich wie `SharedStorage` über watchlist.json ab
        let alarms = ProtectedStorage.files.first { $0.url.lastPathComponent == "portfolio_alarms.json" }
        XCTAssertEqual(alarms?.coordination.lastPathComponent, "watchlist.json")
        // Unverschlüsselt entstehen nur die Kopien unlesbarer Dateien; alles andere schreibt die App nur versiegelt
        let plainCapable = Set(ProtectedStorage.files.filter { !$0.sealedOnly }.map(\.url.lastPathComponent))
        XCTAssertEqual(plainCapable, ["watchlist.unreadable.json", "portfolio_alarms.unreadable.json",
                                      "portfolio.unreadable.json"])
    }
}

/// Unverschlüsselt gebliebene Dateien nicht im Geräte-Backup, versiegelt wieder nach eigener Regel
/// (`PlainBackupExclusion`). Eigene Einstellungs-Suite, nie die der App.
extension ProtectedFileTests {
    private func isolatedDefaults() -> UserDefaults {
        let name = "PlainBackupExclusion-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        // Nur den Namen erfassen (UserDefaults nicht in den Abschluss-Block senden, Swift 6)
        addTeardownBlock { UserDefaults(suiteName: name)?.removePersistentDomain(forName: name) }
        return defaults
    }

    func testKeptPlainIsExcludedAndGetsItsFlagBackWhenSealedLater() throws {
        try json.write(to: watchlistCopy)
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), false)
        let defaults = isolatedDefaults()
        let locked = FakeKeychain()
        locked.readStatus = errSecInteractionNotAllowed
        // Erster Start: Schlüsselbund gesperrt → Klartext bleibt, aber nicht im Backup
        let first = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(locked), defaults: defaults)
        XCTAssertEqual(first.keptPlain, ["watchlist.unreadable.json"])
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), true)
        // Zweiter Start, noch gesperrt: das gemerkte Merkmal von vorher bleibt «nicht ausgenommen»
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(locked), defaults: defaults)
        XCTAssertEqual(PlainBackupExclusion.remembered(watchlistCopy, defaults: defaults), false)
        // Dritter Start mit Schlüssel: versiegelt, wieder im Backup wie vorher, nichts mehr gemerkt
        let third = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(keychain(with: fixedKey)), defaults: defaults)
        XCTAssertEqual(third.encrypted, ["watchlist.unreadable.json"])
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), false)
        XCTAssertNil(PlainBackupExclusion.remembered(watchlistCopy, defaults: defaults))
    }

    func testExcludedBeforeStaysExcludedAfterSealing() throws {
        // Kopie, die schon ausgenommen war (wie die der Portfolio-Alarme bei ausgeschalteter Systemsicherung)
        try json.write(to: watchlistCopy)
        BackupExclusion.set(excluded: true, for: watchlistCopy)
        let defaults = isolatedDefaults()
        let noKey = keychain(with: nil)
        noKey.addStatus = errSecMissingEntitlement
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(noKey), defaults: defaults)
        XCTAssertEqual(PlainBackupExclusion.remembered(watchlistCopy, defaults: defaults), true)
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(keychain(with: fixedKey)), defaults: defaults)
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), true)
    }

    func testSealedByNormalWriteForgetsTheRememberedFlag() throws {
        try json.write(to: watchlistCopy)
        let defaults = isolatedDefaults()
        let noKey = keychain(with: nil)
        noKey.addStatus = errSecMissingEntitlement
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(noKey), defaults: defaults)
        XCTAssertNotNil(PlainBackupExclusion.remembered(watchlistCopy, defaults: defaults))
        // Während des Laufs kommt der Schlüssel: normales Schreiben legt eine neue (versiegelte) Datei ohne Merkmal an
        try ProtectedFile.write(json, to: watchlistCopy, keys: keys(keychain(with: fixedKey)), requireSealed: true)
        XCTAssertEqual(BackupExclusion.isExcluded(watchlistCopy), false)
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlistCopy), keys: keys(keychain(with: fixedKey)), defaults: defaults)
        XCTAssertNil(PlainBackupExclusion.remembered(watchlistCopy, defaults: defaults))
    }
}

/// Beiseitegelegte und ersetzte Dateien: nie im Geräte-Backup, nach bestätigtem Hinweis und Frist weg (`AsideRetention`).
extension ProtectedFileTests {
    func testLockedAndReplacedCopiesGoAfterRetentionOnlyWhenAllowed() throws {
        try ProtectedContainer.seal(json, key: fixedKey).write(to: watchlist)
        // Mit fremdem Schlüssel versiegelt: öffnet nie, bleibt beiseitegelegt
        let locked = watchlist.appendingPathExtension("locked")
        try ProtectedContainer.seal(json, key: SymmetricKey(size: .bits256)).write(to: locked)
        let replaced = watchlist.appendingPathExtension("replaced")
        try ProtectedContainer.seal(json, key: fixedKey).write(to: replaced)
        let defaults = isolatedDefaults()
        let start: Int64 = 1_000
        func launch(at now: Int64, allowed: Bool) {
            _ = ProtectedStorage.prepareAtLaunch(files: files(watchlist, sealedOnly: true), keys: keys(keychain(with: fixedKey)),
                                                 defaults: defaults, asideDeletionAllowed: allowed, now: now)
        }
        launch(at: start, allowed: true)
        XCTAssertEqual(BackupExclusion.isExcluded(locked), true)
        XCTAssertEqual(BackupExclusion.isExcluded(replaced), true)
        // Frist um, Hinweis noch offen: bleibt
        launch(at: start + AsideRetention.maxAgeMillis, allowed: false)
        XCTAssertTrue(FileManager.default.fileExists(atPath: locked.path))
        // Bestätigt: beide weg, die aktuelle Datei bleibt
        launch(at: start + AsideRetention.maxAgeMillis, allowed: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: locked.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: replaced.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: watchlist.path))
    }

    func testNoRetentionWhenKeyWasJustLost() throws {
        let locked = watchlist.appendingPathExtension("locked")
        try ProtectedContainer.seal(json, key: SymmetricKey(size: .bits256)).write(to: locked)
        let defaults = isolatedDefaults()
        let fake = keychain(with: fixedKey)
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlist, sealedOnly: true), keys: keys(fake), defaults: defaults,
                                             asideDeletionAllowed: true, now: 0)
        // Schlüssel weg (neu angelegt): auch nach der Frist nichts löschen
        fake.stored = nil
        _ = ProtectedStorage.prepareAtLaunch(files: files(watchlist, sealedOnly: true), keys: keys(fake), defaults: defaults,
                                             asideDeletionAllowed: true, now: AsideRetention.maxAgeMillis)
        XCTAssertTrue(FileManager.default.fileExists(atPath: locked.path))
    }
}
