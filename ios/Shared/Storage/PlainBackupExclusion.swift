import Foundation

/// Seit Build 93: Eine Datei mit Nutzerdaten, die beim Start unverschlüsselt bleibt (`ProtectedStorage.LaunchOutcome
/// .keptPlain`: Versiegeln gescheitert, Schlüsselbund nicht lesbar oder kein Schlüssel möglich), kommt so lange nicht ins
/// Geräte-Backup (iCloud, Finder, Übertragung) — wie die Momentaufnahme des Widgets. Der versiegelte Stand ist ohnehin nur
/// auf diesem Gerät lesbar (Schlüssel «nur dieses Gerät»), der Klartext wäre es überall.
///
/// Sobald die Datei versiegelt ist, gilt wieder ihre eigene Regel (z. B. `watchlist.json` nur ausgenommen, solange sie
/// Portfolio-Alarme trägt und «Portfolio in Systemsicherung» aus ist): Ein normales Schreiben legt ohnehin eine neue Datei
/// an, deren Merkmal der Schreiber setzt; versiegelt erst ein späterer Start (`ProtectedStorage.encrypt`), bekommt sie das
/// Merkmal von vor dem Ausnehmen zurück (hier gemerkt, je Pfad in den Einstellungen der App).
enum PlainBackupExclusion {
    static let defaultsKey = "plain_backup_exclusion_before"

    /// Merkmal, das vor dem Ausnehmen galt: nur beim ersten Mal gemerkt — ein späterer Start mit noch unverschlüsselter
    /// Datei sähe schon das eigene «ausgenommen». Unbekannt = nicht ausgenommen (Standard des Systems).
    static func rememberedBefore(current: Bool?, remembered: Bool?) -> Bool {
        remembered ?? current ?? false
    }

    /// Merkmal nach dem Versiegeln beim Start: das gemerkte von vorher, sonst das aktuelle (bis Build 92 immer dieses).
    static func afterSealing(current: Bool, remembered: Bool?) -> Bool {
        remembered ?? current
    }

    /// Merkmal nach «Portfolio in Systemsicherung» (`PortfolioStore.applyBackupPolicy`): unverschlüsselt immer ausgenommen.
    static func policyExcluded(includeInBackup: Bool, plain: Bool) -> Bool {
        !includeInBackup || plain
    }

    /// Unverschlüsselt geblieben: Merkmal von vorher merken (einmal), dann ausnehmen.
    static func excludeWhilePlain(_ url: URL, defaults: UserDefaults = .standard) {
        var memory = stored(defaults)
        let path = url.standardizedFileURL.path
        memory[path] = rememberedBefore(current: BackupExclusion.isExcluded(url), remembered: memory[path])
        defaults.set(memory, forKey: defaultsKey)
        BackupExclusion.set(excluded: true, for: url)
    }

    /// Gemerktes Merkmal von vor dem Ausnehmen; nil = keines.
    static func remembered(_ url: URL, defaults: UserDefaults = .standard) -> Bool? {
        stored(defaults)[url.standardizedFileURL.path]
    }

    /// Versiegelt (oder nicht mehr da): nichts mehr gemerkt.
    static func forget(_ url: URL, defaults: UserDefaults = .standard) {
        var memory = stored(defaults)
        guard memory.removeValue(forKey: url.standardizedFileURL.path) != nil else { return }
        defaults.set(memory, forKey: defaultsKey)
    }

    private static func stored(_ defaults: UserDefaults) -> [String: Bool] {
        defaults.dictionary(forKey: defaultsKey) as? [String: Bool] ?? [:]
    }
}
