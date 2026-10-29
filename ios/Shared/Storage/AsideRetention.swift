import Foundation

/// Beiseitegelegte (`*.locked`, `ProtectedStorage.prepareAtLaunch`) und ersetzte Dateien (`*.replaced`) nicht für immer
/// behalten: Sie sind eine Kopie der Nutzerdaten mehr auf dem Gerät, die die App nie mehr öffnet — wie Android
/// `AsideRetention.kt` (dort `<db>.unreadable…` und `<db>.replaced…`).
///
/// Gelöscht wird vorsichtig, nur wenn alles zutrifft:
///  - der gespeicherte Schlüssel war beim Start da (nicht eben verloren und neu angelegt),
///  - die Kopie öffnete mit ihm nicht (sonst holt der Start sie zurück),
///  - der Hinweis «Gespeicherte Daten liessen sich nicht entsperren» ist bestätigt (`allowed`),
///  - die App kennt die Kopie seit mindestens `maxAgeMillis` (gezählt ab dem ersten Start, der sie sah — die
///    Änderungszeit taugt nicht, Verschieben behält die des letzten Schreibens).
/// Gemerkt je Pfad in den Einstellungen der App (nicht der App Group, nie in einer Sicherung relevant).
enum AsideRetention {
    static let maxAgeDays = 30
    static let maxAgeMillis: Int64 = Int64(maxAgeDays) * 24 * 60 * 60 * 1000
    static let defaultsKey = "protected_storage_aside_seen"

    /// `delete`: jetzt zu löschende Kopien; `seen`: neuer Stand des Merkers (nur noch vorhandene Kopien).
    struct Selection: Equatable {
        let delete: [String]
        let seen: [String: Int64]
    }

    /// Rein, wie Android `AsideRetention.select`: neue Kopien und eine zurückgestellte Uhr (Zeit in der Zukunft) beginnen
    /// mit `now` — lieber länger behalten.
    static func select(copies: [String], seen: [String: Int64], now: Int64, allowed: Bool,
                       maxAgeMillis: Int64 = maxAgeMillis) -> Selection {
        var updated: [String: Int64] = [:]
        for copy in copies {
            if let at = seen[copy], at <= now { updated[copy] = at } else { updated[copy] = now }
        }
        // Gelöschte bleiben vermerkt: scheitert das Löschen, beginnt die Frist nicht von vorn
        let delete = allowed ? copies.filter { now - (updated[$0] ?? now) >= maxAgeMillis } : []
        return Selection(delete: delete, seen: updated)
    }

    /// Ablauf über alle Kopien: Merker lesen, auswählen, Merker schreiben; zurück die zu löschenden Pfade.
    static func expired(_ copies: [URL], now: Int64, allowed: Bool, defaults: UserDefaults = .standard) -> [URL] {
        let paths = copies.map { $0.standardizedFileURL.path }
        let stored = (defaults.dictionary(forKey: defaultsKey) as? [String: NSNumber] ?? [:]).mapValues { $0.int64Value }
        let selection = select(copies: paths, seen: stored, now: now, allowed: allowed)
        if selection.seen.isEmpty {
            defaults.removeObject(forKey: defaultsKey)
        } else {
            defaults.set(selection.seen.mapValues { NSNumber(value: $0) }, forKey: defaultsKey)
        }
        let delete = Set(selection.delete)
        return copies.filter { delete.contains($0.standardizedFileURL.path) }
    }
}
