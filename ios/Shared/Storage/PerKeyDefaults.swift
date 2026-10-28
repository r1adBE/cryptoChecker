import Foundation

/// Ein Eintrag je Pool bzw. Paar in den App-Group-Einstellungen (`prefix` + Kennung) statt eines gemeinsamen
/// Wörterbuchs unter einem Schlüssel — wie die eigenen Einstellungsdateien auf Android (ein Schlüssel je Pool).
/// App, Hintergrund und Widget-Erweiterung schreiben gleichzeitig: Mit einem gemeinsamen Wörterbuch (lesen, ändern,
/// ganz zurückschreiben) überschriebe ein Prozess die Messung eines anderen. Ein einzelner Schlüssel ist für sich
/// atomar.
struct PerKeyDefaults: Sendable {
    /// Vorsilbe der Einzelschlüssel, z. B. «dex_liquidity.».
    let prefix: String

    func key(_ id: String) -> String { prefix + id }
}
