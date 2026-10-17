import Foundation

/// Takt der Widgets (nur iOS; Android-Widgets holen nichts selbst, sie zeigen den Stand der
/// Hintergrund-Prüfung): neu zeichnen und Kurse holen etwa alle 30 Minuten. Nach einem misslungenen
/// Versuch oder noch nicht geschriebenen Kursen höchstens EIN früher Neuversuch je Takt — vorher konnte
/// sich das Merkliste-Widget jede Minute neu laden und das Neuladen-Kontingent von WidgetKit aufbrauchen.
enum WidgetCadence {
    /// Normaler Abstand zwischen zwei Zeichnungen.
    static let reloadMinutes = 30
    /// Ab diesem Alter holt das Zeichnen zuerst neue Kurse: knapp unter dem Takt, damit das geplante
    /// Neuladen sicher holt, ein vorgezogenes (App, anderes Widget) aber nicht gleich noch einmal.
    static let staleAfterMillis: Int64 = 25 * 60_000
    /// Nach einem misslungenen Versuch (offline, kein einziger Kurs, Datei nicht schreibbar) so lange
    /// keinen neuen aus dem Zeichnen heraus — gleiche Grenze wie `staleAfterMillis`.
    static let retryAfterMillis: Int64 = staleAfterMillis
    /// Abstand des frühen Neuversuchs.
    static let quickRetrySeconds: Double = 60

    /// Nächstes Neuladen: der frühe Neuversuch nur, wenn er gewünscht ist und der letzte mindestens einen
    /// Takt zurückliegt (Uhr zurückgestellt: erlaubt); sonst der normale Takt.
    /// - Returns: Zeitpunkt und ob es der frühe Neuversuch ist (dann `lastQuickRetryAt` merken).
    static func nextReload(now: Date, wantsQuickRetry: Bool, lastQuickRetryAt: Date?) -> (date: Date, quickRetry: Bool) {
        let period = Double(reloadMinutes) * 60
        var allowed = wantsQuickRetry
        if allowed, let last = lastQuickRetryAt {
            let age = now.timeIntervalSince(last)
            allowed = age < 0 || age >= period
        }
        return (now.addingTimeInterval(allowed ? quickRetrySeconds : period), allowed)
    }
}
