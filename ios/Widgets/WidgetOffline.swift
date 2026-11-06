import Foundation

/// «Keine Verbindung» in den Widgets (wie `WidgetOffline.kt`): Ohne Internet zeigt die Zeit-Zeile «Keine Verbindung ·
/// Stand 14:05» statt der Uhrzeit bzw. «veraltet · …» (`WidgetStatusRule`) — derselbe Text wie die Statuszeile der
/// Merkliste (`offline_status_since`). Die Zeilen behalten ihre letzten Kurse.
///
/// Erkennung wie die App (`NetworkStatus`, NWPathMonitor). Ein frischer Prozess der Erweiterung kennt den Pfad oft
/// noch nicht: kurz auf die erste Meldung warten (`NetworkStatus.isOnlineSettled`). Eine Timeline ohne Netz bittet
/// um ein frühes Neuladen (`WidgetData.reload`, `wantsQuickRetry`) — so verschwindet der Hinweis, wenn das Netz
/// zurück ist; die App lädt die Widgets nach ihrer Aktualisierung ohnehin neu.
enum WidgetOffline {
    /// Ohne Internet? (Unbekannt gilt als online — lieber versuchen.)
    static func isOffline() async -> Bool {
        !(await NetworkStatus.shared.isOnlineSettled())
    }

    /// «Keine Verbindung · Stand 14:05» (bzw. mit Tag, `WidgetOutdated.stamp`); ohne Zeit «Keine Verbindung».
    static func label(_ time: Int64, at date: Date) -> String {
        time > 0 ? L("offline_status_since", WidgetOutdated.stamp(time, at: date)) : short
    }

    /// «Keine Verbindung» — enge Stufe, wenn die Zeit nicht mehr passt.
    static var short: String { L("offline_status") }
}
