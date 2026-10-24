import Foundation

/// Sperre für die vom Nutzer erzwungene Gesamt-Aktualisierung (nach unten ziehen, Knopf
/// oben) — wie `RefreshDebounce` in Android: Läuft schon eine, oder ist die letzte
/// vollständige erst `windowMillis` her, startet keine neue. «Aktualisieren» im Menü ist so
/// lange grau und zählt die Sekunden herunter (`remainingSeconds`); nach unten ziehen wird trotzdem angenommen: Der
/// Kreisel dreht kurz (`pullEchoMillis`), danach «Gerade aktualisiert · vor N s» (`pullEchoSeconds`) — ohne neuen
/// Durchlauf. Ein einzelnes Paar (Aktionsblatt) ist ausgenommen.
enum RefreshDebounce {
    /// So lange nach dem Ende einer vollständigen Aktualisierung startet keine erzwungene neue.
    static let windowMillis: Int64 = 15_000
    /// Ziehen im Fenster: so lange dreht der Kreisel trotzdem (Rückmeldung «angenommen»), ohne Abfrage.
    static let pullEchoMillis: Int64 = 400

    enum Decision: Equatable {
        /// Jetzt aktualisieren.
        case start
        /// Läuft schon — nichts tun, der Kreisel dreht bereits.
        case running
        /// Eben erst fertig — kein neuer Durchlauf (Menüpunkt grau mit Countdown).
        case recent
    }

    /// - Parameters:
    ///   - running: läuft gerade eine vollständige Aktualisierung (gleich welcher Herkunft)?
    ///   - lastFinishedAt: Ende der letzten vollständigen Aktualisierung mit mindestens einem
    ///     Kurs (`SharedStorage.lastFullRefreshAt`; Live-Takte zählen nicht); 0 = noch keine. Liegt sie in der
    ///     Zukunft (Uhr verstellt), wird aktualisiert.
    static func decide(running: Bool, lastFinishedAt: Int64, now: Int64,
                       windowMillis: Int64 = RefreshDebounce.windowMillis) -> Decision {
        if running { return .running }
        if lastFinishedAt <= 0 { return .start }
        let age = now - lastFinishedAt
        return age >= 0 && age < windowMillis ? .recent : .start
    }

    /// Ziehen nach unten mit Ergebnis `decision`: Sekunden seit der letzten vollständigen Aktualisierung für «Gerade
    /// aktualisiert · vor N s» (abgerundet, mindestens 1) — nur bei `.recent`; sonst nil (es läuft schon bzw. ein neuer
    /// Durchlauf startet, dann zeigt der Kreisel genug). Wie Android.
    static func pullEchoSeconds(_ decision: Decision, lastFinishedAt: Int64, now: Int64) -> Int? {
        guard decision == .recent, lastFinishedAt > 0 else { return nil }
        let age = now - lastFinishedAt
        guard age >= 0 else { return nil }
        return Swift.max(Int(age / 1000), 1)
    }

    /// Ganze Sekunden, bis «Aktualisieren» wieder geht (aufgerundet: 14,2 s → 15), für den Countdown
    /// im Menü und die Meldung beim Ziehen. 0 = frei — gleiche Grenzen wie `decide` (noch keine,
    /// Fenster vorbei oder Zeitpunkt in der Zukunft). Ob gerade eine läuft, prüft der Aufrufer.
    static func remainingSeconds(lastFinishedAt: Int64, now: Int64,
                                 windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int {
        let rest = remainingMillis(lastFinishedAt: lastFinishedAt, now: now, windowMillis: windowMillis)
        return Int((rest + 999) / 1000)
    }

    /// Wie lange warten, bis `remainingSeconds` die nächste Zahl zeigt (1…1000 ms); 0 = frei.
    /// So springt der Countdown genau auf der Sekundengrenze, statt bis zu 1 s nachzuhinken.
    static func millisUntilNextChange(lastFinishedAt: Int64, now: Int64,
                                      windowMillis: Int64 = RefreshDebounce.windowMillis) -> Int64 {
        let rest = remainingMillis(lastFinishedAt: lastFinishedAt, now: now, windowMillis: windowMillis)
        return rest <= 0 ? 0 : (rest - 1) % 1000 + 1
    }

    private static func remainingMillis(lastFinishedAt: Int64, now: Int64, windowMillis: Int64) -> Int64 {
        if lastFinishedAt <= 0 { return 0 }
        let age = now - lastFinishedAt
        return age >= 0 && age < windowMillis ? windowMillis - age : 0
    }
}
