import CryptoKit
import Security
import XCTest
@testable import CryptoChecker

/// Kurzer Schlüsselbund-Fehler beim Start (`StorageKey.retrying`): zweimal wiederholt, dann gilt der Schlüssel; ein
/// gesperrtes Gerät wird nicht wiederholt. Mit gefälschtem Schlüsselbund, ohne echtes Warten.
final class StorageKeyRetryTests: XCTestCase {
    /// Scheitert bei den ersten `failures` Zugriffen mit `status`, danach wie ein normaler Schlüsselbund.
    final class FlakyKeychain: StorageKeychain, @unchecked Sendable { // @unchecked: nur nacheinander im Test benutzt
        var stored: Data?
        var failures: Int
        let status: OSStatus
        private(set) var calls = 0

        init(failures: Int, status: OSStatus, stored: Data? = nil) {
            self.failures = failures
            self.status = status
            self.stored = stored
        }

        private func failIfDue() throws {
            calls += 1
            if failures > 0 {
                failures -= 1
                throw StorageKeychainError(status: status)
            }
        }

        func read() throws -> Data? {
            try failIfDue()
            return stored
        }

        /// Nur beim Anlegen: so viele Fehler, bevor es klappt.
        var addFailures = 0
        private(set) var addCalls = 0

        func add(_ key: Data) throws -> Bool {
            addCalls += 1
            if addFailures > 0 {
                addFailures -= 1
                throw StorageKeychainError(status: status)
            }
            guard stored == nil else { return false }
            stored = key
            return true
        }

        func delete() throws { stored = nil }
    }

    final class Pauses: @unchecked Sendable { // @unchecked: nur nacheinander im Test benutzt
        var values: [UInt32] = []
    }

    private func keys(_ keychain: FlakyKeychain, _ pauses: Pauses) -> StorageKey {
        StorageKey(keychain: keychain, readGeneration: { nil }, writeGeneration: { _ in }, pause: { pauses.values.append($0) })
    }

    private let stored = Data(repeating: 5, count: 32)

    func testShortKeychainErrorIsRetriedAndTheKeyFound() {
        let keychain = FlakyKeychain(failures: 2, status: errSecNotAvailable, stored: stored)
        let pauses = Pauses()
        guard case .key = keys(keychain, pauses).lookup() else { return XCTFail("Schlüssel nach zwei Fehlern erwartet") }
        XCTAssertEqual(keychain.calls, 3)
        XCTAssertEqual(pauses.values, StorageKey.retryPauses)
    }

    func testLastingErrorStaysUnavailableAfterTwoRetries() {
        let keychain = FlakyKeychain(failures: 10, status: errSecNotAvailable, stored: stored)
        let pauses = Pauses()
        guard case .unavailable = keys(keychain, pauses).lookup() else { return XCTFail("nicht verfügbar erwartet") }
        XCTAssertEqual(keychain.calls, 3, "höchstens zwei Wiederholungen")
    }

    func testLockedDeviceIsNotRetried() {
        let keychain = FlakyKeychain(failures: 1, status: errSecInteractionNotAllowed, stored: stored)
        let pauses = Pauses()
        guard case .unavailable = keys(keychain, pauses).lookup() else { return XCTFail("gesperrt: nicht verfügbar") }
        XCTAssertEqual(keychain.calls, 1)
        XCTAssertTrue(pauses.values.isEmpty)
    }

    func testCreatingRetriesAShortAddError() {
        let keychain = FlakyKeychain(failures: 0, status: errSecNotAvailable)
        keychain.addFailures = 1
        let pauses = Pauses()
        // Lesen findet nichts (kein Fehler), das Anlegen scheitert einmal und klappt beim zweiten Versuch
        XCTAssertNotNil(keys(keychain, pauses).createIfAbsent())
        XCTAssertNotNil(keychain.stored)
        XCTAssertEqual(keychain.addCalls, 2)
        XCTAssertEqual(pauses.values, [StorageKey.retryPauses[0]])
    }
}
