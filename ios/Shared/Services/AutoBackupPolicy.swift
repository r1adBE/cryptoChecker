import Foundation

/// Automatische Sicherung (Entscheid Inhaber 17.11.2026): wöchentlich, verschlüsselt, in einen gewählten Ordner; die
/// letzten `keep` eigenen Dateien bleiben. Fällig beim Öffnen der App (`isDue`); nach einem Fehlversuch frühestens nach
/// `retryAfterMillis` wieder. Gelöscht werden nur Dateien mit dem eigenen Namensmuster (`isOwnFile`), nie andere.
/// Reine Regeln; wie Android `AutoBackupPolicy`, gemeinsame Testfälle `auto_backup.json`.
enum AutoBackupPolicy {
    static let intervalMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    static let retryAfterMillis: Int64 = 6 * 60 * 60 * 1000
    static let keep = 4
    static let prefix = "cryptochecker-auto-"
    static let suffix = ".json"

    /// Fällig? Eingeschaltet, die letzte gelungene Sicherung ist `intervalMillis` alt (oder keine, oder in der Zukunft)
    /// und der letzte Versuch `retryAfterMillis` her.
    static func isDue(enabled: Bool, lastSuccessAt: Int64, lastAttemptAt: Int64, now: Int64) -> Bool {
        guard enabled else { return false }
        let successDue = lastSuccessAt <= 0 || lastSuccessAt > now || now - lastSuccessAt >= intervalMillis
        let attemptDue = lastAttemptAt <= 0 || lastAttemptAt > now || now - lastAttemptAt >= retryAfterMillis
        return successDue && attemptDue
    }

    /// «cryptochecker-auto-2026-11-17-1830.json» (Ortszeit `timeZone`).
    static func fileName(_ millis: Int64, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd-HHmm"
        return prefix + formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000)) + suffix
    }

    static func isOwnFile(_ name: String) -> Bool {
        name.range(of: #"^cryptochecker-auto-\d{4}-\d{2}-\d{2}-\d{4}\.json$"#, options: .regularExpression) != nil
    }

    /// Eigene Dateien über die neuesten `keep` hinaus (älteste zuerst); fremde Namen nie.
    static func toDelete(_ names: [String], keep: Int = keep) -> [String] {
        Array(Set(names.filter(isOwnFile)).sorted(by: >).dropFirst(keep)).sorted()
    }
}
