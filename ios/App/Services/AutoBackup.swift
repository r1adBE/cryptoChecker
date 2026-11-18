import Foundation
import Security

/// Automatische Sicherung (`AutoBackupPolicy`, Entscheid Inhaber 17.11.2026): wöchentlich, verschlüsselt, in einen
/// gewählten Ordner (Dateien-App, auch iCloud Drive — ohne iCloud-Berechtigung der App), die neuesten 4 eigenen Dateien
/// bleiben. Ordner als Lesezeichen mit Sicherheitsbereich in den eigenen Einstellungen, Passwort im Schlüsselbund nur
/// dieses Geräts (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, nie im Backup). Geprüft beim Aktivwerden der App
/// und aus der Hintergrund-Aufgabe `AutoBackupTask` (Zeitpunkt bestimmt iOS). Wie Android `AutoBackupStore` / `AutoBackupRunner`.
@MainActor
final class AutoBackup: ObservableObject {
    static let shared = AutoBackup()

    struct State: Equatable {
        var enabled = false
        var folderName: String?
        var lastSuccessAt: Int64 = 0
        var lastAttemptAt: Int64 = 0
        var lastFailed = false
    }

    @Published private(set) var state = State()
    @Published private(set) var running = false

    private let defaults: UserDefaults
    /// Schlüsselbund und Ordner-Lesezeichen (`LiveAutoBackupAccess`); in Tests eine Fälschung.
    private let access: any AutoBackupAccess
    /// Hintergrund-Aufgabe nach jedem neuen Zustand vormerken bzw. absagen (`AutoBackupTask.schedule`); in Tests leer.
    private let reschedule: @MainActor (State) -> Void
    private static let enabledKey = "auto_backup_enabled"
    private static let bookmarkKey = "auto_backup_bookmark"
    private static let folderNameKey = "auto_backup_folder_name"
    private static let successKey = "auto_backup_last_success_at"
    private static let attemptKey = "auto_backup_last_attempt_at"
    private static let failedKey = "auto_backup_last_failed"

    init(defaults: UserDefaults = .standard, access: any AutoBackupAccess = LiveAutoBackupAccess(),
         reschedule: @escaping @MainActor (State) -> Void = AutoBackupTask.schedule) {
        self.defaults = defaults
        self.access = access
        self.reschedule = reschedule
        state = read()
    }

    private func read() -> State {
        State(enabled: defaults.bool(forKey: Self.enabledKey), folderName: defaults.string(forKey: Self.folderNameKey),
              lastSuccessAt: Int64(defaults.double(forKey: Self.successKey)),
              lastAttemptAt: Int64(defaults.double(forKey: Self.attemptKey)), lastFailed: defaults.bool(forKey: Self.failedKey))
    }

    /// Einschalten mit gewähltem Ordner und Passwort, danach gleich die erste Sicherung. false = nicht gemerkt.
    @discardableResult
    func enable(folder: URL, password: String, data: AppData) -> Bool {
        guard let bookmark = access.bookmark(folder), access.storePassword(password) else { return false }
        defaults.set(bookmark, forKey: Self.bookmarkKey)
        defaults.set(folder.lastPathComponent, forKey: Self.folderNameKey)
        defaults.set(true, forKey: Self.enabledKey)
        defaults.set(false, forKey: Self.failedKey)
        defaults.set(0, forKey: Self.attemptKey)
        state = read()
        reschedule(state)
        backUpNow(data)
        return true
    }

    /// Anderer Ordner (Passwort bleibt), danach gleich sichern.
    func changeFolder(_ folder: URL, data: AppData) {
        guard let bookmark = access.bookmark(folder) else { return }
        defaults.set(bookmark, forKey: Self.bookmarkKey)
        defaults.set(folder.lastPathComponent, forKey: Self.folderNameKey)
        defaults.set(false, forKey: Self.failedKey)
        state = read()
        backUpNow(data)
    }

    /// Ausschalten: Passwort und Ordner vergessen (vorhandene Dateien bleiben im Ordner).
    func disable() {
        for key in [Self.enabledKey, Self.bookmarkKey, Self.folderNameKey, Self.successKey, Self.attemptKey, Self.failedKey] {
            defaults.removeObject(forKey: key)
        }
        access.deletePassword()
        state = read()
        reschedule(state)
    }

    /// Beim Aktivwerden der App und aus `AutoBackupTask`: nur wenn fällig. Liefert den Lauf (nil = keiner).
    @discardableResult
    func runIfDue(_ data: AppData) -> Task<Void, Never>? {
        let now = EpochTime.nowMillis
        guard AutoBackupPolicy.isDue(enabled: state.enabled, lastSuccessAt: state.lastSuccessAt,
                                     lastAttemptAt: state.lastAttemptAt, now: now) else { return nil }
        return backUpNow(data)
    }

    /// «Jetzt sichern»: ein Lauf zur Zeit; Bauen und Verschlüsseln abseits des Haupt-Threads. Liefert den Lauf
    /// (nil = keiner gestartet).
    @discardableResult
    func backUpNow(_ data: AppData) -> Task<Void, Never>? {
        guard state.enabled, !running else { return nil }
        // Gespeicherter Stand gesperrt bzw. beschädigt: keine Datei, die ihn nicht enthält und gültig aussieht
        if BackupManager.backupRefusalKey(BackupManager.storageAccess(data)) != nil {
            record(false)
            return nil
        }
        guard let password = access.readPassword(), let folder = resolvedFolder() else {
            record(false)
            return nil
        }
        let input = BackupManager.exportInput(data)
        let now = EpochTime.nowMillis
        defaults.set(Double(now), forKey: Self.attemptKey)
        running = true
        return Task {
            let backgroundTask = BackgroundTaskGuard.begin("autoBackup")
            defer { backgroundTask.end() }
            let built = await BackupFileWork.build(input, password: password)
            let ok: Bool
            if case .success(let file) = built {
                ok = await Task.detached(priority: .utility) { Self.write(file, to: folder, now: now) }.value
            } else {
                ok = false
            }
            running = false
            record(ok)
        }
    }

    private func record(_ ok: Bool) {
        let now = EpochTime.nowMillis
        defaults.set(Double(now), forKey: Self.attemptKey)
        defaults.set(!ok, forKey: Self.failedKey)
        if ok { defaults.set(Double(now), forKey: Self.successKey) }
        if !ok { ErrorLog.record(area: "autoBackup", type: "failed") }
        state = read()
        reschedule(state)
    }

    private func resolvedFolder() -> URL? {
        guard let bookmark = defaults.data(forKey: Self.bookmarkKey) else { return nil }
        guard let resolved = access.resolveBookmark(bookmark) else { return nil }
        if resolved.stale, let fresh = access.bookmark(resolved.url) { defaults.set(fresh, forKey: Self.bookmarkKey) }
        return resolved.url
    }

    /// Datei schreiben, zurücklesen (`BackupManager.verify`), ältere eigene Dateien über `AutoBackupPolicy.keep` löschen.
    nonisolated private static func write(_ file: Data, to folder: URL, now: Int64) -> Bool {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        let target = folder.appendingPathComponent(AutoBackupPolicy.fileName(now))
        var ok = false
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { url in
            do {
                try file.write(to: url, options: .atomic)
                ok = BackupManager.verify(written: file, readBack: try Data(contentsOf: url)) == nil
            } catch {
                ok = false
            }
            if !ok { try? FileManager.default.removeItem(at: url) }
        }
        guard ok, coordinationError == nil else { return false }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for name in AutoBackupPolicy.toDelete(names) {
            AppLog.attempt("Alte automatische Sicherung löschen") {
                try FileManager.default.removeItem(at: folder.appendingPathComponent(name))
            }
        }
        return true
    }
}

/// Was `AutoBackup` vom Gerät braucht: Passwort im Schlüsselbund und Ordner als Lesezeichen. In der App
/// `LiveAutoBackupAccess`, in Tests eine Fälschung.
@MainActor
protocol AutoBackupAccess {
    /// Passwort ablegen (ersetzt ein altes); false = nicht gemerkt.
    func storePassword(_ password: String) -> Bool
    func readPassword() -> String?
    func deletePassword()
    /// Lesezeichen des gewählten Ordners; nil = keins.
    func bookmark(_ folder: URL) -> Data?
    /// Ordner zum Lesezeichen und ob es erneuert werden sollte; nil = nicht auflösbar.
    func resolveBookmark(_ bookmark: Data) -> (url: URL, stale: Bool)?
}

/// Echtes Gerät: Schlüsselbund nur dieses Geräts und Lesezeichen mit Sicherheitsbereich.
@MainActor
struct LiveAutoBackupAccess: AutoBackupAccess {
    private static let service = "com.cryptochecker.auto-backup"
    private static let account = "password"

    func bookmark(_ folder: URL) -> Data? {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        return try? folder.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    func resolveBookmark(_ bookmark: Data) -> (url: URL, stale: Bool)? {
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else { return nil }
        return (url, stale)
    }

    // MARK: Passwort im Schlüsselbund

    private var passwordQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.service,
         kSecAttrAccount as String: Self.account]
    }

    func storePassword(_ password: String) -> Bool {
        SecItemDelete(passwordQuery as CFDictionary)
        var q = passwordQuery
        q[kSecValueData as String] = Data(password.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func readPassword() -> String? {
        var q = passwordQuery
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func deletePassword() {
        SecItemDelete(passwordQuery as CFDictionary)
    }
}
