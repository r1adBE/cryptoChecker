import Foundation

/// Ein Eintrag je Pool bzw. Paar in den App-Group-Einstellungen (`prefix` + Kennung) statt eines gemeinsamen
/// Wörterbuchs unter einem Schlüssel — wie die eigenen Einstellungsdateien auf Android (ein Schlüssel je Pool).
/// App, Hintergrund und Widget-Erweiterung schreiben gleichzeitig: Mit einem gemeinsamen Wörterbuch (lesen, ändern,
/// ganz zurückschreiben) überschrieb ein Prozess die Messung eines anderen. Ein einzelner Schlüssel ist für sich
/// atomar.
struct PerKeyDefaults: Sendable {
    /// Vorsilbe der Einzelschlüssel, z. B. «dex_liquidity.».
    let prefix: String
    /// Früherer gemeinsamer Schlüssel (Wörterbuch bzw. Liste von Watch-Ids) älterer Versionen.
    let legacyKey: String

    func key(_ id: String) -> String { prefix + id }

    /// Überführt den früheren gemeinsamen Eintrag einmal in Einzelschlüssel und entfernt ihn. Ein schon vorhandener
    /// Einzelschlüssel ist neuer (von einem anderen Prozess geschrieben) und bleibt. Wörterbuch → je Eintrag der
    /// Wert; Liste von Zahlen (Watch-Ids) → je Id `true`.
    func migrateLegacy(_ defaults: UserDefaults) {
        guard let legacy = defaults.object(forKey: legacyKey) else { return }
        if let dictionary = legacy as? [String: Any] {
            for (id, value) in dictionary where defaults.object(forKey: key(id)) == nil {
                defaults.set(value, forKey: key(id))
            }
        } else if let list = legacy as? [NSNumber] {
            for number in list where defaults.object(forKey: key(String(number.int64Value))) == nil {
                defaults.set(true, forKey: key(String(number.int64Value)))
            }
        }
        defaults.removeObject(forKey: legacyKey)
    }
}
