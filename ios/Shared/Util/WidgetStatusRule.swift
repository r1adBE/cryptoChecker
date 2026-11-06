import Foundation

/// Was die Zeit-Zeile eines Widgets zeigt (Kopf der Merkliste, Fusszeile von Einzel-, Portfolio- und Puls-Widget):
/// ohne Internet «Keine Verbindung · Stand 14:05» (Zeit der letzten erfolgreichen Aktualisierung, ohne bekannte Zeit
/// nur «Keine Verbindung»), sonst bei altem Stand «veraltet · 06:42», sonst die Uhrzeit. Vorrang: offline > veraltet >
/// Uhrzeit. Reine Regel; gemeinsame Testfälle `widget_status.json`, Kotlin-Spiegel `WidgetStatusRule.kt`.
enum WidgetStatusRule {
    enum Kind: String {
        case offline, offlineNoTime, outdated, time

        /// «Keine Verbindung» (mit oder ohne Zeit).
        var isOffline: Bool { self == .offline || self == .offlineNoTime }
    }

    /// - Parameter time: letzte erfolgreiche Aktualisierung (ms); 0 oder kleiner = keine.
    static func kind(online: Bool, outdated: Bool, time: Int64) -> Kind {
        if !online { return time > 0 ? .offline : .offlineNoTime }
        return outdated ? .outdated : .time
    }
}
