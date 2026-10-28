import Foundation

/// Ziel der Glocke im Kopf der Merkliste: Ohne einen einzigen Alarm führt sie nicht
/// in eine leere Übersicht, sondern gleich dorthin, wo man einen anlegt. Reine Regel — wie Android
/// `AlarmBellRoute.kt` (gleiche Testfälle in AlarmBellRouteTests / AlarmBellRouteTest).
enum AlarmBellRoute: Equatable, Sendable {
    /// Mindestens ein Alarm: Übersicht (auch das Menü «Alle Alarme» führt immer dorthin).
    case overview
    /// Kein Alarm, genau ein Paar: dessen Alarm-Seite, die ohne Alarm gleich den Editor öffnet.
    case editor
    /// Kein Alarm, mehrere Paare: erst das Paar wählen (gleiche Auswahl wie in der leeren Übersicht), dann der Editor.
    case pickPair
    /// Kein Alarm und leere Merkliste: zuerst einen Coin hinzufügen.
    case addCoin

    /// - Parameters:
    ///   - alarmCount: alle Alarme, die die Übersicht zeigen würde (Paar-Alarme auch ausgeschaltet,
    ///     Portfolio-Alarme nur mit eingeschaltetem Portfolio); nil = noch nicht geladen → Übersicht
    ///   - watchCount: Paare der ganzen Merkliste (ohne Gruppen-Filter und Suche)
    static func of(alarmCount: Int?, watchCount: Int) -> AlarmBellRoute {
        guard let alarmCount, alarmCount == 0 else { return .overview }
        if watchCount == 1 { return .editor }
        if watchCount > 1 { return .pickPair }
        return .addCoin
    }
}
