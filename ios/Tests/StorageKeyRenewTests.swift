import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// «App zurücksetzen» erneuert den Schlüssel der verschlüsselten Dateien (`StorageKey.renew`, seit Build 99): alter
/// Eintrag weg, neuer sofort da; ein zweiter Prozess (Widget) mit gemerktem altem Schlüssel liest nach dem Wechsel neu.
/// Mit gefälschtem Schlüsselbund und Merker im Speicher — nie die App Group oder der echte Schlüsselbund.
final class StorageKeyRenewTests: XCTestCase {
    /// Merker `storage_key_generation`, geteilt wie die App Group zwischen App und Widget.
    final class GenerationBox: @unchecked Sendable { // @unchecked: Test-Hilfe, nur nacheinander aus dem Testablauf benutzt
        var value: String?
    }

    private func keys(_ keychain: ProtectedFileTests.FakeKeychain, _ box: GenerationBox) -> StorageKey {
        StorageKey(keychain: keychain, readGeneration: { box.value }, writeGeneration: { box.value = $0 })
    }

    private func key(_ keys: StorageKey) -> SymmetricKey? {
        if case .key(let key) = keys.lookup() { return key }
        return nil
    }

    private func bytes(_ key: SymmetricKey?) -> Data? {
        key?.withUnsafeBytes { Data($0) }
    }

    func testRenewReplacesKeyAndOldSealedDataNoLongerOpens() throws {
        let keychain = ProtectedFileTests.FakeKeychain()
        let box = GenerationBox()
        let app = keys(keychain, box)
        let old = try XCTUnwrap(app.createIfAbsent())
        let sealed = try ProtectedContainer.seal(Data("geheim".utf8), key: old)

        XCTAssertTrue(app.renew())

        XCTAssertEqual(keychain.deletes, 1)
        let fresh = try XCTUnwrap(key(app))
        XCTAssertNotEqual(bytes(fresh), bytes(old))
        XCTAssertEqual(keychain.stored, bytes(fresh), "neuer Schlüssel steht im Schlüsselbund")
        XCTAssertThrowsError(try ProtectedContainer.open(sealed, key: fresh))
        XCTAssertNotNil(box.value, "Merker gesetzt")
    }

    func testRenewWithoutKeychainKeepsOldKey() throws {
        let keychain = ProtectedFileTests.FakeKeychain()
        let box = GenerationBox()
        let app = keys(keychain, box)
        let old = try XCTUnwrap(app.createIfAbsent())
        keychain.deleteStatus = errSecInteractionNotAllowed

        XCTAssertFalse(app.renew())

        XCTAssertEqual(bytes(key(app)), bytes(old))
        XCTAssertEqual(keychain.stored, bytes(old))
        XCTAssertNil(box.value)
    }

    func testOtherProcessDropsCachedKeyAfterRenew() throws {
        let keychain = ProtectedFileTests.FakeKeychain()
        let box = GenerationBox()
        let app = keys(keychain, box)
        let widget = keys(keychain, box)
        let old = try XCTUnwrap(app.createIfAbsent())
        XCTAssertEqual(bytes(key(widget)), bytes(old), "Widget merkt sich den alten Schlüssel")

        app.renew()

        let fresh = try XCTUnwrap(key(app))
        XCTAssertEqual(bytes(key(widget)), bytes(fresh), "nach dem Zurücksetzen liest das Widget neu")
    }

    func testCachedKeyStaysWithoutGenerationChange() throws {
        let keychain = ProtectedFileTests.FakeKeychain()
        let box = GenerationBox()
        let app = keys(keychain, box)
        let created = try XCTUnwrap(app.createIfAbsent())
        // Schlüsselbund gerade nicht lesbar: der gemerkte Schlüssel gilt weiter
        keychain.readStatus = errSecInteractionNotAllowed
        XCTAssertEqual(bytes(key(app)), bytes(created))
    }

    func testWriteAfterRenewSealsWithNewKey() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("renew-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("watchlist.json")
        let keychain = ProtectedFileTests.FakeKeychain()
        let box = GenerationBox()
        let app = keys(keychain, box)
        _ = try XCTUnwrap(app.createIfAbsent())

        app.renew()
        try ProtectedFile.write(Data("{}".utf8), to: url, keys: app, requireSealed: true)

        let fresh = try XCTUnwrap(key(app))
        XCTAssertEqual(try ProtectedContainer.open(Data(contentsOf: url), key: fresh), Data("{}".utf8))
    }
}
