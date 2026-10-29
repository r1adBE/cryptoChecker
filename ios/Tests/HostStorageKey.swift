import Foundation
import Security
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
            stored.modify { value in
                guard value == nil else { return false }
                value = key
                return true
            }
        }

        func delete() throws { stored.current = nil }
    }

    /// 32 feste Bytes, nur für den Test-Schlüsselbund.
    static let testKey = Data(UInt8(0xA0)...UInt8(0xBF))

    /// Prozessweiten Schlüssel sicherstellen: ersetzt NUR, wenn dem Test-Host die Schlüsselbund-Berechtigung fehlt
    /// (unsignierter CI-Simulator, errSecMissingEntitlement). Andere Fehler (gesperrt, kaputter Eintrag) bleiben
    /// sichtbar, damit auf einem signierten Gerät nie mit dem öffentlich bekannten Test-Schlüssel versiegelt wird.
    /// true = Test-Schlüsselbund aktiv.
    @discardableResult
    static func ensureAvailable() -> Bool {
        guard case .unavailable = StorageKey.shared.lookup() else { return false }
        let status: OSStatus?
        do {
            // Lesen ohne Fehler (Eintrag fehlt): erst ein Probe-Eintrag zeigt, ob Anlegen an der Berechtigung scheitert
            _ = try SystemStorageKeychain().read()
            status = probeAddStatus()
        } catch let error as StorageKeychainError {
            status = error.status
        } catch {
            status = nil
        }
        guard shouldUseTestKeychain(status: status) else { return false }
        StorageKey.shared.replaceKeychainForTesting(MemoryKeychain(key: testKey))
        return true
    }

    /// Nur fehlende Berechtigung (-34018) oder kein Schlüsselbund (-25291) rechtfertigen den Test-Schlüsselbund; ein
    /// gesperrter oder verweigerter Schlüsselbund nicht, ein unbekannter Zustand (nil) auch nicht.
    static func shouldUseTestKeychain(status: OSStatus?) -> Bool {
        guard let status else { return false }
        return status == errSecMissingEntitlement || status == errSecNotAvailable
    }

    /// Status beim Anlegen eines eigenen Probe-Eintrags (eigener Dienstname, gleich wieder gelöscht); berührt den
    /// Schlüssel der App nicht. errSecSuccess/errSecDuplicateItem = Schlüsselbund nutzbar.
    static func probeAddStatus() -> OSStatus {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "cryptochecker.tests.keychain-probe",
            kSecAttrAccount as String: "probe",
        ]
        var item = query
        item[kSecValueData as String] = Data([1])
        let status = SecItemAdd(item as CFDictionary, nil)
        SecItemDelete(query as CFDictionary)
        return status
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
