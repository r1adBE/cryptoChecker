import CryptoKit
import Foundation
import Security

// Verschlüsselte Dateien mit Nutzerdaten in der App Group (Build 59) — Gegenstück zu den verschlüsselten Datenbanken
// auf Android (SQLCipher, `DatabaseEncryptionMigration.kt`). Gleiche Regeln: nie Daten verlieren, Klartext erst
// ersetzen, wenn die verschlüsselte Fassung geprüft ist, bei jedem Fehler unverschlüsselt weiter und später erneut.

/// Format einer verschlüsselten Datei: 4 Bytes Kennung «CCSE», 1 Byte Version (1), 12 Bytes Nonce, Chiffrat (AES-256-GCM),
/// 16 Bytes Tag. Kennung und Version sind als Zusatzdaten mitgeprüft. JSON und Text beginnen nie mit der Kennung —
/// so liest jede Stelle alte Klartext-Dateien weiter, bis sie verschlüsselt sind.
enum ProtectedContainer {
    static let magic = Data("CCSE".utf8)
    static let version: UInt8 = 1
    static let nonceSize = 12
    static let tagSize = 16
    static var headerSize: Int { magic.count + 1 }

    enum Failure: Error, Equatable {
        case notSealed
        /// Aus einer neueren App-Version: nicht lesbar, aber auch nicht überschreiben.
        case unsupportedVersion(UInt8)
        /// Zu kurz, verändert oder mit anderem Schlüssel verschlüsselt (GCM-Prüfung schlägt fehl).
        case damaged
    }

    /// Beginnt mit der Kennung (gleich welche Version)?
    static func isSealed(_ data: Data) -> Bool {
        data.count >= headerSize && data.prefix(magic.count).elementsEqual(magic)
    }

    static func seal(_ plain: Data, key: SymmetricKey, nonce: AES.GCM.Nonce = AES.GCM.Nonce()) throws -> Data {
        let header = magic + Data([version])
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce, authenticating: header)
        return header + Data(box.nonce) + box.ciphertext + box.tag
    }

    static func open(_ sealed: Data, key: SymmetricKey) throws -> Data {
        guard isSealed(sealed) else { throw Failure.notSealed }
        let bytes = Data(sealed) // Indizes ab 0, auch wenn `sealed` ein Ausschnitt ist
        let found = bytes[magic.count]
        guard found == version else { throw Failure.unsupportedVersion(found) }
        guard bytes.count >= headerSize + nonceSize + tagSize else { throw Failure.damaged }
        do {
            let nonce = try AES.GCM.Nonce(data: bytes[headerSize ..< headerSize + nonceSize])
            let box = try AES.GCM.SealedBox(nonce: nonce,
                                            ciphertext: bytes[(headerSize + nonceSize) ..< (bytes.count - tagSize)],
                                            tag: bytes.suffix(tagSize))
            return try AES.GCM.open(box, using: key, authenticating: bytes.prefix(headerSize))
        } catch {
            throw Failure.damaged
        }
    }
}

/// Ablage des Schlüssels (Schlüsselbund; Tests: Fälschung).
protocol StorageKeychain: Sendable {
    /// Gespeicherter Schlüssel; nil = keiner. Wirft bei anderen Fehlern (z. B. vor dem ersten Entsperren).
    func read() throws -> Data?
    /// Speichert einen neuen Schlüssel; false = es gibt schon einen (dann gilt der gespeicherte).
    func add(_ key: Data) throws -> Bool
    /// Ist der gespeicherte Eintrag nur auf diesem Gerät lesbar (`…ThisDeviceOnly`)? nil = kein Eintrag.
    func isDeviceOnly() throws -> Bool?
    /// Den bestehenden Eintrag auf «nur dieses Gerät» umstellen — dieselben Schlüsselbytes, nie löschen und neu anlegen.
    func makeDeviceOnly() throws
    /// Eintrag löschen (nur «App zurücksetzen»); fehlt er, ist nichts zu tun.
    func delete() throws
}

struct StorageKeychainError: Error, Equatable {
    let status: OSStatus
}

/// Schlüssel im Schlüsselbund, geteilt mit den Widgets über die App Group als Zugriffsgruppe (dafür genügt die
/// vorhandene Berechtigung `com.apple.security.application-groups`; ein eigenes `keychain-access-groups` würde die
/// Standardgruppe aller Schlüsselbund-Einträge der App ändern).
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (Entscheid 17. Oktober 2026, Build 69): lesbar ab dem ersten
/// Entsperren nach dem Start (Widgets, Hintergrund), aber nie Teil eines iCloud-/Finder-Backups und nicht übertragbar
/// auf ein anderes iPhone — wer ein Geräte-Backup in die Hände bekommt, hat die Dateien, aber nie ihren Schlüssel.
/// Folge: Nach einem Gerätewechsel sind die verschlüsselten Dateien unlesbar; `prepareAtLaunch` legt sie beiseite
/// und `StorageRecoveryNotice` nennt das einmal. Weg zurück ist die Sicherungsdatei (Einstellungen › Sicherung sagt
/// das vorab). Bis Build 68 hiess der Zugriff `kSecAttrAccessibleAfterFirstUnlock`; `StorageKey.restrictToThisDevice`
/// stellt einen solchen Eintrag beim Start um. Nicht mit dem iCloud-Schlüsselbund synchronisiert.
struct SystemStorageKeychain: StorageKeychain {
    static let service = "com.cryptochecker.storage-key"
    static let account = "v1"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: Self.account,
         kSecAttrAccessGroup as String: SharedStorage.appGroup]
    }

    func read() throws -> Data? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw StorageKeychainError(status: status)
        }
    }

    func add(_ key: Data) throws -> Bool {
        var q = query
        q[kSecValueData as String] = key
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        switch status {
        case errSecSuccess: return true
        case errSecDuplicateItem: return false
        default: throw StorageKeychainError(status: status)
        }
    }

    func isDeviceOnly() throws -> Bool? {
        var q = query
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            let attributes = result as? [String: Any] ?? [:]
            let current = attributes[kSecAttrAccessible as String] as? String
            let wanted = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
            return current == wanted
        case errSecItemNotFound:
            return nil
        default:
            throw StorageKeychainError(status: status)
        }
    }

    func makeDeviceOnly() throws {
        // Abfrage samt App-Group-Zugriffsgruppe unverändert: die Widgets lesen den Eintrag weiter
        let change: [String: Any] = [kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, change as CFDictionary)
        guard status == errSecSuccess else { throw StorageKeychainError(status: status) }
    }

    func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw StorageKeychainError(status: status) }
    }
}

/// Der Schlüssel der verschlüsselten Dateien (256 Bit), je Prozess einmal gelesen. Anlegen nur durch die App beim Start
/// (`ProtectedStorage.prepareAtLaunch`) — Widgets lesen nur.
final class StorageKey: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = StorageKey(keychain: SystemStorageKeychain())

    enum Lookup {
        case key(SymmetricKey)
        /// Keiner gespeichert.
        case absent
        /// Gerade nicht lesbar (gesperrt, Schlüsselbund-Fehler, unerwarteter Inhalt): nichts verändern.
        case unavailable
    }

    /// Merker in der App Group: «App zurücksetzen» (`renew`) setzt einen neuen Wert. Ein anderer Prozess (Widget) mit
    /// gemerktem altem Schlüssel sieht den Wechsel und liest den Schlüsselbund neu — sonst versiegelte er weiter mit
    /// dem gelöschten Schlüssel, und die App könnte diese Dateien nie mehr öffnen.
    static let generationKey = "storage_key_generation"

    private let keychain: StorageKeychain
    private let readGeneration: @Sendable () -> String?
    private let writeGeneration: @Sendable (String) -> Void
    private let lock = NSLock()
    /// Nur ein gefundener Schlüssel wird gemerkt: Legt die App ihn später an, findet ihn das Widget beim nächsten Lesen.
    private var cached: SymmetricKey?
    /// `generationKey` beim Merken von `cached`.
    private var cachedGeneration: String?

    init(keychain: StorageKeychain,
         readGeneration: @escaping @Sendable () -> String? = { SharedStorage.defaults.string(forKey: StorageKey.generationKey) },
         writeGeneration: @escaping @Sendable (String) -> Void = { SharedStorage.defaults.set($0, forKey: StorageKey.generationKey) }) {
        self.keychain = keychain
        self.readGeneration = readGeneration
        self.writeGeneration = writeGeneration
    }

    func lookup() -> Lookup {
        lock.lock()
        defer { lock.unlock() }
        return lookupLocked()
    }

    /// Gibt es einen lesbaren Schlüssel (zum Versiegeln)? Wie `ProtectedFile.write`: nur dann wird geschrieben.
    var isAvailable: Bool {
        if case .key = lookup() { return true }
        return false
    }

    /// Gespeicherter Schlüssel, sonst ein neuer. nil = keiner möglich (Schlüsselbund gerade nicht verfügbar).
    func createIfAbsent() -> SymmetricKey? {
        lock.lock()
        defer { lock.unlock() }
        switch lookupLocked() {
        case .key(let key): return key
        case .unavailable: return nil
        case .absent: break
        }
        let fresh = SymmetricKey(size: .bits256)
        do {
            if try keychain.add(fresh.withUnsafeBytes { Data($0) }) {
                cached = fresh
                cachedGeneration = readGeneration()
                return fresh
            }
        } catch {
            return nil
        }
        // Gleichzeitig von einem anderen Prozess angelegt: der gespeicherte gilt
        if case .key(let stored) = lookupLocked() { return stored }
        return nil
    }

    /// Einen bis Build 68 angelegten Eintrag (`…AfterFirstUnlock`, im Geräte-Backup enthalten) auf «nur dieses Gerät»
    /// umstellen. Ändert nur den Zugriff des bestehenden Eintrags (`SecItemUpdate`), nie die Schlüsselbytes — ein neuer
    /// Schlüssel machte alle Dateien unlesbar. Scheitert es (z. B. vor dem ersten Entsperren), bleibt alles wie es ist
    /// und der nächste Start versucht es erneut. true = umgestellt oder schon so (oder kein Eintrag).
    @discardableResult
    func restrictToThisDevice() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        do {
            guard let deviceOnly = try keychain.isDeviceOnly(), !deviceOnly else { return true }
            try keychain.makeDeviceOnly()
            return true
        } catch {
            return false
        }
    }

    /// «App zurücksetzen»: alten Schlüssel löschen und gleich einen neuen anlegen — unter derselben Sperre, damit kein
    /// Schreiber dieses Prozesses dazwischen «kein Schlüssel» sieht (und Klartext schriebe). Die App läuft danach
    /// weiter und versiegelt ab hier mit dem neuen Schlüssel. Nur nach dem Löschen aller verschlüsselten Dateien
    /// aufrufen. false = Schlüsselbund nicht verfügbar; dann bleibt der bisherige Schlüssel (nichts geht verloren).
    @discardableResult
    func renew() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        do {
            try keychain.delete()
        } catch {
            return false
        }
        cached = nil
        writeGeneration(UUID().uuidString)
        let fresh = SymmetricKey(size: .bits256)
        // Scheitert das Anlegen, legt `prepareAtLaunch` beim nächsten Start einen an (keine Dateien mehr da)
        guard (try? keychain.add(fresh.withUnsafeBytes { Data($0) })) == true else { return true }
        cached = fresh
        cachedGeneration = readGeneration()
        return true
    }

    private func lookupLocked() -> Lookup {
        if let cached {
            let generation = readGeneration()
            if generation == cachedGeneration { return .key(cached) }
            // Anderswo zurückgesetzt: gemerkten Schlüssel verwerfen und neu lesen
            self.cached = nil
        }
        do {
            let generation = readGeneration()
            guard let data = try keychain.read() else { return .absent }
            guard data.count == 32 else { return .unavailable }
            let key = SymmetricKey(data: data)
            cached = key
            cachedGeneration = generation
            return .key(key)
        } catch {
            return .unavailable
        }
    }
}

/// Lesen und Schreiben der Dateien mit Nutzerdaten (Liste: `ProtectedStorage.files`).
enum ProtectedFile {
    enum ReadError: Error {
        /// Verschlüsselt, Schlüssel gerade nicht verfügbar (oder neueres Format): wie «nicht zugreifbar» — nicht überschreiben.
        case locked
        /// Verschlüsselt, aber beschädigt: wie eine kaputte Datei behandeln; `raw` = die Bytes zum Sichern.
        case damaged(raw: Data)
    }

    enum WriteError: Error {
        case verificationFailed
        case changedMeanwhile
        case renameFailed(Int32)
    }

    /// Klartext der Datei; eine alte unverschlüsselte Datei kommt unverändert zurück.
    static func read(_ url: URL, keys: StorageKey = .shared) throws -> Data {
        let raw = try Data(contentsOf: url)
        guard ProtectedContainer.isSealed(raw) else { return raw }
        guard case .key(let key) = keys.lookup() else { throw ReadError.locked }
        do {
            return try ProtectedContainer.open(raw, key: key)
        } catch ProtectedContainer.Failure.unsupportedVersion(_) {
            throw ReadError.locked
        } catch {
            throw ReadError.damaged(raw: raw)
        }
    }

    /// Schreibt verschlüsselt (geprüft, atomar ersetzt); ohne Schlüssel unverschlüsselt wie bis Build 58 — der nächste
    /// Start der App überführt die Datei. Liegt dort aber schon eine verschlüsselte Datei, wird sie ohne Schlüssel nie
    /// durch Klartext ersetzt (`locked`): sonst stünden z. B. Widget-Daten nach einem kurz gesperrten Schlüsselbund
    /// wieder unverschlüsselt da. Das Backup-Merkmal setzen die Aufrufer danach neu (neue Datei).
    /// `requireSealed` (Merkliste, Portfolio, Portfolio-Alarme, Adressen, Alarm-Protokoll): ohne Schlüssel nie Klartext,
    /// auch nicht für eine neue oder noch unverschlüsselte Datei — `locked`; der Aufrufer behält den Stand im Speicher
    /// und schreibt beim nächsten Mal
    /// (wie die Widget-Aufnahme, `PortfolioWidgetStore.writeSnapshotFile`).
    static func write(_ data: Data, to url: URL, keys: StorageKey = .shared, requireSealed: Bool = false) throws {
        guard case .key(let key) = keys.lookup() else {
            if requireSealed || ProtectedStorage.isSealedFile(url) { throw ReadError.locked }
            try data.write(to: url, options: [.atomic])
            return
        }
        try writeSealed(data, to: url, key: key)
    }

    /// Steht genau `data` schon in der gewünschten Form in der Datei (verschlüsselt, sobald es einen Schlüssel gibt)?
    /// Dann muss nicht geschrieben werden.
    static func holds(_ data: Data, at url: URL, keys: StorageKey = .shared) -> Bool {
        guard let raw = try? Data(contentsOf: url) else { return false }
        switch keys.lookup() {
        case .key(let key):
            return ProtectedContainer.isSealed(raw) && (try? ProtectedContainer.open(raw, key: key)) == data
        case .absent, .unavailable:
            return raw == data
        }
    }

    /// Kopie einer (ganz oder teilweise) unlesbaren Datei: Klartext wird verschlüsselt, beschädigt Verschlüsseltes
    /// bleibt Byte für Byte, wie es ist. Eine schon vorhandene gleiche Kopie bleibt.
    static func writeCopy(_ data: Data, to copy: URL, keys: StorageKey = .shared) throws {
        if let existing = try? Data(contentsOf: copy), existing == data { return }
        if ProtectedContainer.isSealed(data) {
            try data.write(to: copy, options: [.atomic])
            return
        }
        if let existing = try? read(copy, keys: keys), existing == data { return }
        try write(data, to: copy, keys: keys)
    }

    /// Versiegeln → in eine Nachbardatei schreiben → von dort lesen, öffnen und mit `data` vergleichen → atomar auf `url`
    /// umbenennen. `unchangedFrom`: nur ersetzen, wenn die Datei noch genau diese Bytes hat (Überführung).
    static func writeSealed(_ data: Data, to url: URL, key: SymmetricKey, unchangedFrom expected: Data? = nil) throws {
        let sealed = try ProtectedContainer.seal(data, key: key)
        let temp = url.deletingLastPathComponent()
            .appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString).tmp")
        do {
            try writeDurably(sealed, to: temp)
            guard try ProtectedContainer.open(Data(contentsOf: temp), key: key) == data else {
                throw WriteError.verificationFailed
            }
            if let expected, (try? Data(contentsOf: url)) != expected { throw WriteError.changedMeanwhile }
            // rename(2) ersetzt die Datei atomar: vorher die alte, danach die neue — nie eine halbe
            guard rename(temp.path, url.path) == 0 else { throw WriteError.renameFailed(errno) }
        } catch {
            try? FileManager.default.removeItem(at: temp)
            throw error
        }
    }

    /// Schreibt `data` in die neue Nachbardatei und erzwingt mit F_FULLFSYNC, dass die Bytes wirklich auf dem
    /// Speicher liegen, bevor `rename` sie sichtbar macht: Sonst kann nach einem Stromausfall oder Absturz des
    /// Geräts schon der neue Name auf eine leere oder halbe Datei zeigen (das Umbenennen ist im Journal, der Inhalt
    /// noch im Cache des Laufwerks). Kennt das Dateisystem F_FULLFSYNC nicht, wenigstens fsync. Seit Build 102.
    private static func writeDurably(_ data: Data, to temp: URL) throws {
        guard FileManager.default.createFile(atPath: temp.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: temp.path])
        }
        let handle = try FileHandle(forWritingTo: temp)
        do {
            try handle.write(contentsOf: data)
            if fcntl(handle.fileDescriptor, F_FULLFSYNC) == -1, fsync(handle.fileDescriptor) == -1 {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: temp.path])
            }
        } catch {
            try? handle.close()
            throw error
        }
        try handle.close()
    }
}
