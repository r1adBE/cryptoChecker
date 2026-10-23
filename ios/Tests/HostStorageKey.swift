import Foundation
import XCTest
@testable import CryptoChecker

/// Schlüssel der verschlüsselten Dateien für Tests, die auf dem Speicher der Test-App laufen (`AppData.shared`,
/// `SharedStorage` mit `StorageKey.shared`): Hat der Host keinen Schlüsselbund (unsignierter CI-Simulator, ohne
/// Berechtigung), ersetzt ein Test-Schlüsselbund im Speicher mit festem Schlüssel den echten — für den Rest des
/// Testprozesses. Mit echtem Schlüsselbund (signiert) bleibt alles, wie es ist.
/// Fester Schlüssel: Dateien eines früheren Testlaufs auf demselben unsignierten Simulator bleiben lesbar.
enum HostStorageKey {
    /// Thread-sicherer Schlüsselbund im Speicher (App und Hintergrund-Threads lesen gleichzeitig).
    final class MemoryKeychain: StorageKeychain {
        private let stored: LockedBox<Data?>

        init(key: Data?) {
            stored = LockedBox(key)
        }

        func read() throws -> Data? { stored.current }

        func add(_ key: Data) throws -> Bool {
            var added = false
            stored.write { value in
                guard value == nil else { return }
                value = key
                added = true
            }
            return added
        }

        func isDeviceOnly() throws -> Bool? { stored.current == nil ? nil : true }

        func makeDeviceOnly() throws {}

        func delete() throws { stored.current = nil }
    }

    /// 32 feste Bytes, nur für den Test-Schlüsselbund.
    static let testKey = Data(UInt8(0xA0)...UInt8(0xBF))

    /// Prozessweiten Schlüssel sicherstellen (nur ohne lesbaren Schlüsselbund ersetzt). true = Test-Schlüsselbund aktiv.
    @discardableResult
    static func ensureAvailable() -> Bool {
        guard case .unavailable = StorageKey.shared.lookup() else { return false }
        StorageKey.shared.replaceKeychainForTesting(MemoryKeychain(key: testKey))
        return true
    }

    /// Wie `ensureAvailable`, dazu eine Merkliste auf der Platte und `AppData` im Einklang damit: Ohne Datei (frischer
    /// Simulator; ohne Schlüssel konnte die App nichts versiegelt speichern) wird der Stand der App jetzt geschrieben,
    /// danach liest `AppData` neu (Sperre «nicht zugreifbar» bzw. «wartet auf Schlüssel» vom Start fällt weg).
    @MainActor
    static func prepareAppStorage() async throws {
        ensureAvailable()
        XCTAssertTrue(StorageKey.shared.isAvailable, "kein Schlüssel für die verschlüsselten Dateien")
        let data = AppData.shared
        if case .missing = SharedStorage.readSnapshot() {
            SharedStorage.saveSnapshotInBackground(data.snapshot)
            let written = await SharedStorage.snapshotWritesSettled()
            XCTAssertTrue(written, "Merkliste der Test-App nicht angelegt")
        }
        // Ändert die App zwischendurch etwas, übernimmt `reloadFromDisk` nichts: dann noch einmal
        for _ in 0..<3 {
            await data.reloadFromDisk()
            if data.watchlistStorageNotice == nil { break }
        }
        XCTAssertNil(data.watchlistStorageNotice, "Merkliste der Test-App gesperrt bzw. nicht gespeichert")
        guard case .ok = SharedStorage.readSnapshot() else {
            XCTFail("Merkliste der Test-App nicht lesbar")
            throw NotReady()
        }
    }

    struct NotReady: Error {}
}
