import Foundation
import Security

/// Automatische Sicherung (`AutoBackupPolicy`, Entscheid Inhaber 17.11.2026): wöchentlich, verschlüsselt, in einen
/// gewählten Ordner (Dateien-App, auch iCloud Drive — ohne iCloud-Berechtigung der App), die neuesten 4 eigenen Dateien
/// bleiben. Ordner als Lesezeichen mit Sicherheitsbereich in den eigenen Einstellungen, Passwort im Schlüsselbund nur
/// dieses Geräts (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, nie im Backup). Fällig beim Aktivwerden der App.
/// Wie Android `AutoBackupStore` / `AutoBackupRunner`.
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

    private let defaults = UserDefaults.standard
    private static let enabledKey = "auto_backup_enabled"
    private static let bookmarkKey = "auto_backup_bookmark"
    private static let folderNameKey = "auto_backup_folder_name"
    private static let successKey = "auto_backup_last_success_at"
    private static let attemptKey = "auto_backup_last_attempt_at"
    private static let failedKey = "auto_backup_last_failed"
    nonisolated private static let service = "com.cryptochecker.auto-backup"
    nonisolated private static let account = "password"

    private init() { state = read() }

    private func read() -> State {
        State(enabled: defaults.bool(forKey: Self.enabledKey), folderName: defaults.string(forKey: Self.folderNameKey),
              lastSuccessAt: Int64(defaults.double(forKey: Self.successKey)),
              lastAttemptAt: Int64(defaults.double(forKey: Self.attemptKey)), lastFailed: defaults.bool(forKey: Self.failedKey))
    }

    /// Einschalten mit gewähltem Ordner und Passwort, danach gleich die erste Sicherung. false = nicht gemerkt.
    @discardableResult
    func enable(folder: URL, password: String, data: AppData) -> Bool {
        guard let bookmark = Self.bookmark(folder), Self.storePassword(password) else { return false }
        defaults.set(bookmark, forKey: Self.bookmarkKey)
        defaults.set(folder.lastPathComponent, forKey: Self.folderNameKey)
        defaults.set(true, forKey: Self.enabledKey)
        defaults.set(false, forKey: Self.failedKey)
        defaults.set(0, forKey: Self.attemptKey)
        state = read()
        backUpNow(data)
        return true
    }

    /// Anderer Ordner (Passwort bleibt), danach gleich sichern.
    func changeFolder(_ folder: URL, data: AppData) {
        guard let bookmark = Self.bookmark(folder) else { return }
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
        SecItemDelete(Self.passwordQuery as CFDictionary)
        state = read()
    }

    /// Beim Aktivwerden der App: nur wenn fällig.
    func runIfDue(_ data: AppData) {
        let now = EpochTime.nowMillis
        guard AutoBackupPolicy.isDue(enabled: state.enabled, lastSuccessAt: state.lastSuccessAt,
                                     lastAttemptAt: state.lastAttemptAt, now: now) else { return }
        backUpNow(data)
    }

    /// «Jetzt sichern»: ein Lauf zur Zeit; Bauen und Verschlüsseln abseits des Haupt-Threads.
    func backUpNow(_ data: AppData) {
        guard state.enabled, !running else { return }
        // Gespeicherter Stand gesperrt bzw. beschädigt: keine Datei, die ihn nicht enthält und gültig aussieht
        if BackupManager.backupRefusalKey(BackupManager.storageAccess(data)) != nil { return record(false) }
        guard let password = Self.readPassword(), let folder = resolvedFolder() else { return record(false) }
        let input = BackupManager.exportInput(data)
        let now = EpochTime.nowMillis
        defaults.set(Double(now), forKey: Self.attemptKey)
        running = true
        Task {
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
    }

    private func resolvedFolder() -> URL? {
        guard let bookmark = defaults.data(forKey: Self.bookmarkKey) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else { return nil }
        if stale, let fresh = Self.bookmark(url) { defaults.set(fresh, forKey: Self.bookmarkKey) }
        return url
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

    nonisolated private static func bookmark(_ folder: URL) -> Data? {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        return try? folder.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    // MARK: Passwort im Schlüsselbund

    nonisolated private static var passwordQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    nonisolated private static func storePassword(_ password: String) -> Bool {
        SecItemDelete(passwordQuery as CFDictionary)
        var q = passwordQuery
        q[kSecValueData as String] = Data(password.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    nonisolated private static func readPassword() -> String? {
        var q = passwordQuery
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
