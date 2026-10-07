import Foundation

/// «Wird an der Börse nicht mehr gehandelt»: Die vollständige Kursliste der Börse führt das
/// Paar nicht mehr. Ein Zustand, kein Fehler — die Zeile bleibt mit ihrem Hinweis stehen,
/// aber alles, was auf Marktdaten beruht, schweigt: kein ⚡, kein «Warum?», keine
/// Aktivitäts-Mitteilungen, keine 24-h-Pille, kein Mini-Chart, nicht im Puls, keine Alarme
/// auf dem alten Kurs. Wie `NotTraded.kt` (Android, mit NotTradedTest).
///
/// Nur Foundation: auch im Widget nutzbar.
enum NotTraded {
    /// Gespeicherter «Fehler» (`lastError`) für solche Paare — sprachunabhängig, die Anzeige übersetzt.
    static let marker = "Wird an der Börse nicht mehr gehandelt"

    /// Wird das Paar mit diesem `lastError` nicht mehr gehandelt?
    static func isMarker(_ lastError: String?) -> Bool { lastError == marker }

    /// 24-h-Veränderung zum Anzeigen (Pille, Puls, Widgets): nil («—») bei nicht gehandelten Paaren.
    static func shownChange24h(lastError: String?, change24h: Double?) -> Double? {
        isMarker(lastError) ? nil : change24h
    }

    /// Ids der nicht gehandelten Paare.
    static func ids(_ watches: [Watch]) -> Set<Int64> {
        Set(watches.filter { isMarker($0.lastError) }.map(\.id))
    }

    /// `map` ohne die Einträge nicht gehandelter Paare (z. B. gespeicherte Aktivitäts-Signale).
    static func withoutIds<V>(_ map: [Int64: V], _ notTraded: Set<Int64>) -> [Int64: V] {
        guard !notTraded.isEmpty, map.keys.contains(where: { notTraded.contains($0) }) else { return map }
        return map.filter { !notTraded.contains($0.key) }
    }
}

extension Watch {
    /// Paar wird an seiner Börse nicht mehr gehandelt (`NotTraded`).
    var isNotTraded: Bool { NotTraded.isMarker(lastError) }

    /// 24-h-Veränderung zum Anzeigen: nil («—») bei nicht gehandelten Paaren.
    var shownChange24h: Double? { NotTraded.shownChange24h(lastError: lastError, change24h: change24h) }
}
