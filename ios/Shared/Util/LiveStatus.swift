import Foundation

/// «Live» muss stimmen: Frische-Schwelle je Paar und das Wort «Live» in der Status-Pille —
/// wie `LiveStatus.kt`.
///
///  - Paar am Live-Strom (seine Börse liefert gerade per WebSocket Kurse, nur bei «Live, solange
///    offen»): Frische am letzten Takt des Stroms gemessen (`freshnessTime`), nicht am letzten
///    Kurswechsel — ein ruhiges Paar an einem gesunden Strom ist nicht veraltet. Bleiben die Takte
///    aus, fällt das Paar aus dem Strom (`LiveRules.freshMillis`) und es gilt die Abfrage-Schwelle;
///    `liveStaleMillis` (2 Min.) bleibt die Schwelle am Strom.
///  - sonst die Abfrage-Schwelle: dreimal das Hintergrund-Intervall, mindestens 15 Min.
///    (`OutdatedRule`). Das gilt in beiden Modi — auch bei «Live, solange offen» fragt die App Börsen
///    ohne WebSocket bei offener Merkliste nicht selbst im Takt ab (nur Hintergrund oder von Hand).
///  - «Live» in der Pille nur, wenn ALLE gehandelten, aktiven Paare der Ansicht am Strom und frisch
///    sind; sonst steht der gewohnte Satz («Kurse der gewählten Börsen · vor 12 Min.» mit der Zeit
///    des ältesten Kurses, `shownTime`).
enum LiveStatus {
    /// Ohne neuen Kurs aus dem Strom so lange frisch.
    static let liveStaleMillis: Int64 = OutdatedRule.liveMillis

    /// Schwelle für abgefragte Paare (beide Modi): max(3 × Hintergrund-Intervall, 15 Min.).
    static func pollingStaleAfterMillis(backgroundIntervalMinutes: Int) -> Int64 {
        OutdatedRule.afterMillis(backgroundIntervalMinutes: backgroundIntervalMinutes)
    }

    /// Schwelle eines Paars: am Strom 2 Min. (ab `freshnessTime`), sonst die Abfrage-Schwelle `pollingAfter`.
    static func staleAfterMillis(onLiveSocket: Bool, pollingAfter: Int64) -> Int64 {
        onLiveSocket ? min(liveStaleMillis, pollingAfter) : pollingAfter
    }

    /// Zeit, an der die Frische eines Paars gemessen wird. Am Live-Strom (`onLiveSocket`: letzter Takt höchstens
    /// `LiveRules.freshMillis` alt) der letzte Takt — auch ein Takt ohne Kurswechsel bestätigt den Kurs. Vorsichtig
    /// die früheste mögliche Taktzeit (jetzt − `LiveRules.freshMillis`), nie vor `lastUpdate`. Sonst `lastUpdate`
    /// (letzter Abruf bzw. Kurswechsel). Nur für die Anzeige; gespeichert bleibt `lastUpdate`.
    static func freshnessTime(lastUpdate: Int64, onLiveSocket: Bool, now: Int64) -> Int64 {
        onLiveSocket ? max(lastUpdate, now - LiveRules.freshMillis) : lastUpdate
    }

    /// «Live» in der Pille? Nur mit mindestens einem Paar, keinem veralteten (`stale`) und allen
    /// `tradedIds` (gehandelt, aktiv) in `liveIds`.
    static func allLive(tradedIds: [Int64], liveIds: Set<Int64>, stale: Int) -> Bool {
        !tradedIds.isEmpty && stale == 0 && tradedIds.allSatisfy { liveIds.contains($0) }
    }

    /// Zeit hinter «vor X Min.»: der älteste Kurs der gezeigten Paare (Zeiten > 0), damit der Kopf
    /// nicht «vor 1 Min.» sagt, während einzelne Zeilen fast 15 Min. alt sind. 0 = nie.
    static func shownTime(updateTimes: [Int64]) -> Int64 {
        updateTimes.filter { $0 > 0 }.min() ?? 0
    }
}
