import Foundation

/// «Live» muss stimmen (K-1): Frische-Schwelle je Paar und das Wort «Live» in der Status-Pille —
/// wie `LiveStatus.kt`.
///
///  - Paar am Live-Strom (seine Börse liefert gerade per WebSocket Kurse, nur bei «Live, solange
///    offen»): veraltet nach `liveStaleMillis` (2 Min.) ohne neuen Kurs.
///  - sonst die Abfrage-Schwelle: dreimal das Hintergrund-Intervall, mindestens 15 Min.
///    (`OutdatedRule`). Das gilt in beiden Modi — auch bei «Live, solange offen» fragt die App Börsen
///    ohne WebSocket bei offener Merkliste nicht selbst im Takt ab (nur Hintergrund oder von Hand).
///  - «Live» in der Pille nur, wenn ALLE gehandelten, aktiven Paare der Ansicht am Strom und frisch
///    sind; gemischt steht der gewohnte Satz («Alle Kurse aktuell · vor 12 Min.» mit der Zeit der
///    abgefragten Paare, `shownTime`).
enum LiveStatus {
    /// Ohne neuen Kurs aus dem Strom so lange frisch.
    static let liveStaleMillis: Int64 = OutdatedRule.liveMillis

    /// Schwelle für abgefragte Paare (beide Modi): max(3 × Hintergrund-Intervall, 15 Min.).
    static func pollingStaleAfterMillis(backgroundIntervalMinutes: Int) -> Int64 {
        OutdatedRule.afterMillis(backgroundIntervalMinutes: backgroundIntervalMinutes)
    }

    /// Schwelle eines Paars: am Strom 2 Min., sonst die Abfrage-Schwelle `pollingAfter`.
    static func staleAfterMillis(onLiveSocket: Bool, pollingAfter: Int64) -> Int64 {
        onLiveSocket ? min(liveStaleMillis, pollingAfter) : pollingAfter
    }

    /// «Live» in der Pille? Nur mit mindestens einem Paar, keinem veralteten (`stale`) und allen
    /// `tradedIds` (gehandelt, aktiv) in `liveIds`.
    static func allLive(tradedIds: [Int64], liveIds: Set<Int64>, stale: Int) -> Bool {
        !tradedIds.isEmpty && stale == 0 && tradedIds.allSatisfy { liveIds.contains($0) }
    }

    /// Zeit hinter «vor X Min.»: Sind einige Paare live und andere abgefragt, die jüngste der
    /// abgefragten (sonst wirkte die letzte Abfrage dank der Live-Paare «gerade eben»); sonst die
    /// jüngste aller. 0 = nie.
    static func shownTime(updates: [(id: Int64, time: Int64)], liveIds: Set<Int64>) -> Int64 {
        let newest = updates.map(\.time).max() ?? 0
        if liveIds.isEmpty { return newest }
        let polled = updates.filter { !liveIds.contains($0.id) }.map(\.time).max() ?? 0
        return polled > 0 ? polled : newest
    }
}
