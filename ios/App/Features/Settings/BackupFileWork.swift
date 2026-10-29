import Foundation

// Arbeit der Seite «Sichern & Wiederherstellen» abseits des Haupt-Threads (bis 64 MB JSON): gewählte Datei lesen und
// prüfen, entschlüsselten Klartext prüfen, Sicherung bauen und verschlüsseln, abgelegte Datei zurücklesen. Die Seite
// erfasst auf dem MainActor nur Wertkopien (`BackupManager.exportInput`) und zeigt das Ergebnis. Eigene Datei, weil die
// Seite an der Längengrenze ist; Android liest und baut ebenfalls abseits des Haupt-Threads (`BackupManager.kt`, IO).

extension BackupManager {
    /// Stand der Sicherung als Wertkopie, in einem Durchgang auf dem MainActor erfasst (`exportInput`).
    struct ExportInput: Sendable {
        let createdAt: Int64
        let watches: [Watch]
        let alarms: [Alarm]
        let favorites: [FavoriteKind: Set<String>]
        let settings: AppSettings
        let portfolio: [PortfolioTx]
        let portfolioAlarms: [PortfolioAlarm]
        let watchedAddresses: [WatchedAddress]
    }

    @MainActor
    static func exportInput(_ data: AppData) -> ExportInput {
        ExportInput(createdAt: EpochTime.nowMillis, watches: data.snapshot.watches, alarms: data.snapshot.alarms,
                    favorites: data.favorites, settings: data.settings, portfolio: data.portfolio,
                    portfolioAlarms: data.portfolioAlarms, watchedAddresses: data.watchedAddresses)
    }
}

enum BackupFileWork {
    /// Gewählte Datei: verschlüsselt (erst das Passwort) oder lesbar samt Vorschau der Rückfrage.
    enum Picked: Sendable {
        case encrypted(Data)
        case plain(Data, BackupManager.RestorePreview)
    }

    /// Fehler beim Lesen (`read`: zu gross bzw. nicht lesbar) oder beim Prüfen (`check`: keine Sicherung, neuere
    /// Version, kaputt).
    enum PickFailure: Error {
        case read(any Error)
        case check(any Error)
    }

    /// Datei lesen (höchstens 64 MB, `BackupManager.readFile`) und prüfen (`needsPassword`, sonst `preview`).
    static func pick(_ url: URL) async -> Result<Picked, PickFailure> {
        await Task.detached(priority: .userInitiated) { () -> Result<Picked, PickFailure> in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let file: Data
            do { file = try BackupManager.readFile(url) } catch { return .failure(.read(error)) }
            do {
                if try BackupManager.needsPassword(file) { return .success(.encrypted(file)) }
                return .success(.plain(file, try BackupManager.preview(file)))
            } catch {
                return .failure(.check(error))
            }
        }.value
    }

    /// Entschlüsselte Sicherung mit `password` (Argon2id, bis wenige s): Klartext samt Vorschau; eine neuere Version
    /// wird schon hier abgelehnt (`checkVersion`), eine nicht lesbare Vorschau gilt als leer.
    static func decrypt(_ file: Data, password: String) async -> Result<(plain: Data, preview: BackupManager.RestorePreview), any Error> {
        await Task.detached(priority: .userInitiated) {
            Result {
                let plain = try BackupManager.plainJSON(file, password: password)
                try BackupManager.checkVersion(plain)
                return (plain, (try? BackupManager.preview(plain)) ?? BackupManager.RestorePreview())
            }
        }.value
    }

    /// Datei der Sicherung aus `input`: Klartext (nie eine, die sich nicht mehr einlesen lässt, `checkWritable`), mit
    /// `password` verschlüsselt (`encryptedFile`).
    static func build(_ input: BackupManager.ExportInput, password: String?) async -> Result<Data, any Error> {
        await Task.detached(priority: .userInitiated) {
            Result {
                let plain = try BackupManager.plainExportData(input)
                try BackupLimits.checkWritable(plain)
                guard let password else { return plain }
                return try BackupManager.encryptedFile(plain, password: password)
            }
        }.value
    }

    /// Abgelegte Datei zurückgelesen und gleich (`BackupManager.verifyWritten`)?
    static func verified(_ url: URL, expected: Data) async -> Bool {
        await Task.detached(priority: .userInitiated) { BackupManager.verifyWritten(url, expected: expected) == nil }.value
    }
}
