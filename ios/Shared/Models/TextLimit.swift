import Foundation

/// Freitext auf sichtbare Zeichen begrenzen — wie Android `TextLimit.kt`. Swift zählt mit `count`/`prefix`
/// schon Graphem-Cluster; hier steht nur die gemeinsame Reihenfolge fürs Säubern.
enum TextLimit {
    /// Trimmen, auf `max` Zeichen kürzen und nochmals trimmen (ein Schnitt kann auf ein Leerzeichen
    /// fallen); leer = nil. Für Notizen, Gruppen- und Börsennamen — aus der Eingabe wie aus einer Sicherung.
    static func clean(_ raw: String?, max: Int) -> String? {
        guard let raw, max > 0 else { return nil }
        let text = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    /// Mehrzeiligen Freitext als eine Zeile (Notiz unter einem Paar der Merkliste): Zeilenumbrüche (LF, CR, U+2028,
    /// U+2029) samt Leerzeichen und Tabs darum werden ein Leerzeichen, leere Zeilen fallen weg. Wie Android
    /// `TextLimit.singleLine`.
    static func singleLine(_ text: String) -> String {
        let breaks: Set<Unicode.Scalar> = ["\n", "\r", "\u{2028}", "\u{2029}"]
        return text.unicodeScalars.split(whereSeparator: { breaks.contains($0) })
            .map { String(String.UnicodeScalarView($0)).trimmingCharacters(in: CharacterSet(charactersIn: " \t")) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}
