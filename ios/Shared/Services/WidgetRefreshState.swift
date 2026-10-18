import Foundation

/// Aktualisieren-Knopf im Merkliste-Widget mit derselben 15-s-Sperre wie die App (`RefreshDebounce`) — wie
/// `WidgetRefreshState` in Android: Ein Tipp, während eine vollständige Aktualisierung läuft oder die letzte
/// keine 15 s her ist, startet nichts. Solange die Sperre gilt — gleich nach dem Tipp, der die Aktualisierung
/// ausgelöst hat, wie nach jeder anderen vollständigen — zeigt das Widget statt des Symbols einen Countdown
/// (`blockedUntil`), von WidgetKit selbst heruntergezählt (`Text(timerInterval:countsDown:)`), also ohne Neuladen je
/// Sekunde; ein zusätzlicher Eintrag der Timeline zum Ende der Sperre bringt das Symbol zurück.
enum WidgetRefreshState {
    /// Was ein Tipp auf den Knopf auslöst.
    enum Tap: Equatable {
        /// Jetzt aktualisieren.
        case start
        /// Läuft schon — nichts tun.
        case running
        /// Gesperrt bis `until` (Wanduhr, ms): nichts holen, Countdown zeigen.
        case blocked(until: Int64)
    }

    /// Gleiche Grenzen wie `RefreshDebounce.decide`; gesperrt bis zum Ende des Fensters.
    static func onTap(running: Bool, lastFinishedAt: Int64, now: Int64,
                      windowMillis: Int64 = RefreshDebounce.windowMillis) -> Tap {
        switch RefreshDebounce.decide(running: running, lastFinishedAt: lastFinishedAt, now: now, windowMillis: windowMillis) {
        case .start: .start
        case .running: .running
        case .recent: .blocked(until: lastFinishedAt + windowMillis)
        }
    }

    /// Ende der Sperre, das ein Zeichnen jetzt zeigt (Wanduhr, ms); 0 = Knopf zeigen. Das spätere von
    /// gespeichertem Ende (Tipp, `Tap.blocked`) und Ende des Fensters nach der letzten vollständigen
    /// Aktualisierung — soweit es noch gilt (`remainingMillis`).
    /// Läuft eine Aktualisierung, die nach der letzten fertigen begonnen hat, kein Countdown (der Knopf bleibt,
    /// ein Tipp tut nichts). Gezeichnet am Ende einer Aktualisierung (Zeit schon gesetzt, Merker noch nicht
    /// freigegeben) gilt sie als fertig: dort beginnt der Countdown.
    /// - Parameter runningSince: Beginn der laufenden vollständigen Aktualisierung; 0 = keine
    static func blockedUntil(stored: Int64, lastFinishedAt: Int64, runningSince: Int64, now: Int64,
                             windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int64 {
        if runningSince > 0 && runningSince >= lastFinishedAt { return 0 }
        let afterRefresh = lastFinishedAt > 0 ? lastFinishedAt + windowMillis : 0
        return [stored, afterRefresh]
            .filter { remainingMillis(blockedUntil: $0, now: now, windowMillis: windowMillis) > 0 }
            .max() ?? 0
    }

    /// Restzeit der Sperre beim Zeichnen (ms); 0 = Knopf zeigen. Nie länger als ein Fenster: Wurde die Uhr
    /// zurückgestellt, stünde der Countdown sonst beliebig lange da.
    /// - Parameter blockedUntil: Ende der Sperre (`blockedUntil`, `Tap.blocked`); 0 = keine Sperre
    static func remainingMillis(blockedUntil: Int64, now: Int64,
                                windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int64 {
        if blockedUntil <= 0 { return 0 }
        let rest = blockedUntil - now
        return rest >= 1 && rest <= windowMillis ? rest : 0
    }

    /// Ganze Sekunden für VoiceOver (aufgerundet wie `RefreshDebounce.remainingSeconds`); 0 = frei.
    static func remainingSeconds(blockedUntil: Int64, now: Int64,
                                 windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int {
        Int((remainingMillis(blockedUntil: blockedUntil, now: now, windowMillis: windowMillis) + 999) / 1000)
    }

    /// Ende der Sperre, falls sie zum Zeitpunkt `now` noch gilt — dort braucht die Timeline einen Eintrag ohne
    /// Countdown; nil = keine Sperre.
    static func unlockAt(blockedUntil: Int64, now: Int64,
                         windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int64? {
        remainingMillis(blockedUntil: blockedUntil, now: now, windowMillis: windowMillis) > 0 ? blockedUntil : nil
    }
}
