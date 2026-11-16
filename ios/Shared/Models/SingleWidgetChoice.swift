import Foundation

/// Paar des Einzel-Widgets: gewählt als «Id|Paar» (`WatchIdentity.text`). Eine eingespielte Sicherung kann dieselbe Id
/// einem anderen Paar geben — dann zeigt das Widget «Paar wählen» statt still ein fremdes Paar (B3-08). Ältere Wahl ohne
/// Paar (nur die Id) gilt weiter. Wie Android `WidgetPrefs.chosenWatch`.
enum SingleWidgetChoice {
    /// Kennung der Wahl in der Widget-Einrichtung.
    static func entityId(_ watch: Watch) -> String { "\(watch.id)|" + WatchIdentity.text(watch.identity) }

    /// Id des gewählten Paars (auch aus einer älteren Wahl ohne Paar).
    static func watchId(_ entityId: String) -> Int64? {
        Int64(entityId.split(separator: "|", maxSplits: 1).first.map(String.init) ?? entityId)
    }

    /// Zeigt `watch` (gleiche Id) noch das gewählte Paar? Ohne gemerktes Paar (nur Id): ja.
    static func showsSamePair(_ entityId: String, _ watch: Watch) -> Bool {
        guard let bar = entityId.firstIndex(of: "|") else { return true }
        return String(entityId[entityId.index(after: bar)...]) == WatchIdentity.text(watch.identity)
    }

    /// Das gewählte Paar in `watches` — nil, wenn es fehlt oder unter der Id inzwischen ein anderes Paar steht.
    static func chosen(_ entityId: String, in watches: [Watch]) -> Watch? {
        guard let id = watchId(entityId) else { return nil }
        return watches.first { $0.id == id }.flatMap { showsSamePair(entityId, $0) ? $0 : nil }
    }
}
