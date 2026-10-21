import Foundation

/// Freitext auf sichtbare Zeichen begrenzen — wie Android `TextLimit.kt`. Swift zählt mit `count`/`prefix`
/// schon Graphem-Cluster; hier steht nur die gemeinsame Reihenfolge fürs Säubern (seit Build 99).
enum TextLimit {
    /// Trimmen, auf `max` Zeichen kürzen und nochmals trimmen (ein Schnitt kann auf ein Leerzeichen
    /// fallen); leer = nil. Für Notizen, Gruppen- und Börsennamen — aus der Eingabe wie aus einer Sicherung.
    static func clean(_ raw: String?, max: Int) -> String? {
        guard let raw, max > 0 else { return nil }
        let text = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
}
