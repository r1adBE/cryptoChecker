import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Verschlüsselte Dateien (Build 59): Format (`ProtectedContainer`), Lesen und Schreiben (`ProtectedFile`) und die
/// Überführung beim Start (`ProtectedStorage.prepareAtLaunch`) mit gefälschtem Schlüsselbund — jeder Fehler lässt die
/// Klartext-Datei stehen. Gegenstück zu `DatabaseEncryptionMigrationTest.kt` (Android, Datenbanken). Läuft in einem
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
        /// Zugriff des Eintrags: true = `…ThisDeviceOnly`; neu angelegte Einträge sind es immer (Build 69).
        var deviceOnly = true
        /// Fehler beim Umstellen (z. B. vor dem ersten Entsperren).
        var updateStatus: OSStatus?
        private(set) var updates = 0

        func read() throws -> Data? {
            if let readStatus { throw StorageKeychainError(status: readStatus) }
            return stored
        }

        func add(_ key: Data) throws -> Bool {
            if let addStatus { throw StorageKeychainError(status: addStatus) }
            if let racingKey { stored = racingKey; return false }
            guard stored == nil else { return false }
            stored = key
            deviceOnly = true
            adds += 1
            return true
        }

        func isDeviceOnly() throws -> Bool? {
            if let readStatus { throw StorageKeychainError(status: readStatus) }
            return stored == nil ? nil : deviceOnly
        }

        func makeDeviceOnly() throws {
            if let updateStatus { throw StorageKeychainError(status: updateStatus) }
            deviceOnly = true
            updates += 1
        }
    }

    private var dir: URL!
    private var watchlist: URL { dir.appendingPathComponent("watchlist.json") }
    private var portfolio: URL { dir.appendingPathComponent("portfolio.json") }
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

    private func files(_ urls: URL...) -> [(url: URL, coordination: URL)] { urls.map { ($0, $0) } }

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

    func testWithoutKeyWritesAndReadsPlainAsBefore() throws {
        let k = keys(keychain(with: nil))
        try ProtectedFile.write(json, to: watchlist, keys: k)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
        XCTAssertEqual(try ProtectedFile.read(watchlist, keys: k), json)
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
        XCTAssertTrue(ProtectedFile.holds(json, at: watchlist, keys: noKey))
        // Klartext mit vorhandenem Schlüssel: neu schreiben (verschlüsselt)
        XCTAssertFalse(ProtectedFile.holds(json, at: watchlist, keys: withKey))
        try ProtectedFile.write(json, to: watchlist, keys: withKey)
        XCTAssertTrue(ProtectedFile.holds(json, at: watchlist, keys: withKey))
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
        try json.write(to: watchlist)
        try Data(#"{"transactions":[]}"#.utf8).write(to: portfolio)
        BackupExclusion.set(excluded: true, for: portfolio)
        let fake = keychain(with: nil)
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist, portfolio), keys: keys(fake))
        XCTAssertEqual(outcome.encrypted, ["watchlist.json", "portfolio.json"])
        XCTAssertEqual(outcome.setAside, [])
        XCTAssertEqual(fake.adds, 1)
        let k = keys(fake) // frischer Prozess: liest den gespeicherten Schlüssel
        XCTAssertTrue(ProtectedContainer.isSealed(try Data(contentsOf: watchlist)))
        XCTAssertEqual(try ProtectedFile.read(watchlist, keys: k), json)
        XCTAssertEqual(try ProtectedFile.read(portfolio, keys: k), Data(#"{"transactions":[]}"#.utf8))
        XCTAssertEqual(BackupExclusion.isExcluded(portfolio), true)
        XCTAssertEqual(BackupExclusion.isExcluded(watchlist), false)
        XCTAssertEqual(try leftovers(), [])

        // Zweiter Start: nichts mehr zu tun
        XCTAssertEqual(ProtectedStorage.prepareAtLaunch(files: files(watchlist, portfolio), keys: k), .init())
    }

    func testEncryptionPendingOnlyWhileSomethingStaysPlain() throws {
        try json.write(to: watchlist)
        let fake = keychain(with: nil)
        let first = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        XCTAssertEqual(first.encrypted, ["watchlist.json"])
        XCTAssertFalse(first.encryptionPending)
        // Alles verschlüsselt, Schlüsselbund später gesperrt: verschlüsselte Dateien sind kein «noch nicht aktiv»
        fake.readStatus = errSecInteractionNotAllowed
        XCTAssertFalse(ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake)).encryptionPending)
    }

    // MARK: Schlüssel nur auf diesem Gerät (Build 69)

    func testOldKeychainItemIsRestrictedToThisDeviceKeepingTheKey() throws {
        let sealed = try ProtectedContainer.seal(json, key: fixedKey)
        try sealed.write(to: watchlist)
        let fake = keychain(with: fixedKey)
        fake.deviceOnly = false // angelegt bis Build 68 (`…AfterFirstUnlock`)
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        XCTAssertEqual(outcome, .init())
        XCTAssertTrue(fake.deviceOnly)
        XCTAssertEqual(fake.updates, 1)
        XCTAssertEqual(fake.adds, 0)
        // Dieselben Schlüsselbytes: die Datei bleibt lesbar
        XCTAssertEqual(fake.stored, fixedKey.withUnsafeBytes { Data($0) })
        XCTAssertEqual(try ProtectedFile.read(watchlist, keys: keys(fake)), json)
        // Nächster Start: schon umgestellt, keine weitere Änderung
        ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        XCTAssertEqual(fake.updates, 1)
    }

    func testFailedRestrictionChangesNothingAndRetriesNextLaunch() throws {
        let sealed = try ProtectedContainer.seal(json, key: fixedKey)
        try sealed.write(to: watchlist)
        let fake = keychain(with: fixedKey)
        fake.deviceOnly = false
        fake.updateStatus = errSecInteractionNotAllowed
        XCTAssertFalse(keys(fake).restrictToThisDevice())
        XCTAssertFalse(fake.deviceOnly)
        XCTAssertEqual(fake.stored, fixedKey.withUnsafeBytes { Data($0) })
        XCTAssertEqual(try ProtectedFile.read(watchlist, keys: keys(fake)), json)
        fake.updateStatus = nil
        XCTAssertTrue(keys(fake).restrictToThisDevice())
        XCTAssertTrue(fake.deviceOnly)
    }

    func testRestrictionWithoutKeychainItemDoesNothing() {
        let fake = keychain(with: nil)
        XCTAssertTrue(keys(fake).restrictToThisDevice())
        XCTAssertEqual(fake.updates, 0)
        XCTAssertNil(fake.stored)
    }

    func testMissingFilesAreSkipped() {
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(keychain(with: nil)))
        XCTAssertEqual(outcome, .init())
        XCTAssertFalse(FileManager.default.fileExists(atPath: watchlist.path))
    }

    func testUnreadableKeychainTouchesNothing() throws {
        try json.write(to: watchlist)
        let fake = FakeKeychain()
        fake.readStatus = errSecInteractionNotAllowed
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        // Unverändert, aber vermerkt: Einstellungen › Sicherung nennt «Verschlüsselung noch nicht aktiv»
        XCTAssertEqual(outcome, ProtectedStorage.LaunchOutcome(keptPlain: ["watchlist.json"]))
        XCTAssertTrue(outcome.encryptionPending)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
        XCTAssertEqual(fake.adds, 0)
    }

    func testNoKeyPossibleKeepsPlain() throws {
        try json.write(to: watchlist)
        let fake = keychain(with: nil)
        fake.addStatus = errSecMissingEntitlement
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        XCTAssertEqual(outcome, ProtectedStorage.LaunchOutcome(keptPlain: ["watchlist.json"]))
        XCTAssertTrue(outcome.encryptionPending)
        XCTAssertEqual(try Data(contentsOf: watchlist), json)
    }

    func testLostKeySetsSealedFilesAsideThenStartsWithNewKey() throws {
        // Verschlüsselt mit einem Schlüssel, den es nicht mehr gibt; daneben eine noch unverschlüsselte Datei
        let sealed = try ProtectedContainer.seal(json, key: fixedKey)
        try sealed.write(to: watchlist)
        try Data(#"{"transactions":[]}"#.utf8).write(to: portfolio)
        let fake = keychain(with: nil)
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist, portfolio), keys: keys(fake))
        XCTAssertEqual(outcome.setAside, ["watchlist.json.locked"])
        XCTAssertEqual(outcome.encrypted, ["portfolio.json"])
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("watchlist.json.locked")), sealed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: watchlist.path))
        XCTAssertEqual(fake.adds, 1)
        XCTAssertEqual(try ProtectedFile.read(portfolio, keys: keys(fake)), Data(#"{"transactions":[]}"#.utf8))
    }

    func testKeyAddedConcurrentlyByOtherProcessWins() throws {
        try json.write(to: watchlist)
        let fake = keychain(with: nil)
        fake.racingKey = fixedKey.withUnsafeBytes { Data($0) }
        let outcome = ProtectedStorage.prepareAtLaunch(files: files(watchlist), keys: keys(fake))
        XCTAssertEqual(outcome.encrypted, ["watchlist.json"])
        // Mit dem Schlüssel des anderen Prozesses verschlüsselt
        XCTAssertEqual(try ProtectedContainer.open(Data(contentsOf: watchlist), key: fixedKey), json)
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
                               "watched_addresses.json", "portfolio_widget.json"])
        // Merkliste und Portfolio-Alarme stimmen sich wie `SharedStorage` über watchlist.json ab
        let alarms = ProtectedStorage.files.first { $0.url.lastPathComponent == "portfolio_alarms.json" }
        XCTAssertEqual(alarms?.coordination.lastPathComponent, "watchlist.json")
    }
}
