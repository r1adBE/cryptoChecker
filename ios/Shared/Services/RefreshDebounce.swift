import Foundation

/// Sperre für die vom Nutzer erzwungene Gesamt-Aktualisierung (nach unten ziehen, Knopf
/// oben) — wie `RefreshDebounce` in Android: Läuft schon eine, oder ist die letzte
/// vollständige erst `windowMillis` her, startet keine neue. Die Merkliste zeigt dann den
/// bestehenden Stand bzw. kurz «Gerade aktualisiert», ohne Fehlermeldung. Ein einzelnes
/// Paar (Aktionsblatt) ist ausgenommen.
enum RefreshDebounce {
    /// So lange nach dem Ende einer vollständigen Aktualisierung startet keine erzwungene neue.
    static let windowMillis: Int64 = 15_000

    enum Decision: Equatable {
        /// Jetzt aktualisieren.
        case start
        /// Läuft schon — nichts tun, der Kreisel dreht bereits.
        case running
        /// Eben erst fertig — kurzer Hinweis «Gerade aktualisiert», kein neuer Durchlauf.
        case recent
    }

    /// - Parameters:
    ///   - running: läuft gerade eine vollständige Aktualisierung (gleich welcher Herkunft)?
    ///   - lastFinishedAt: Ende der letzten vollständigen Aktualisierung mit mindestens einem
    ///     Kurs; 0 = noch keine. Liegt sie in der Zukunft (Uhr verstellt), wird aktualisiert.
    static func decide(running: Bool, lastFinishedAt: Int64, now: Int64,
                       windowMillis: Int64 = RefreshDebounce.windowMillis) -> Decision {
        if running { return .running }
        if lastFinishedAt <= 0 { return .start }
        let age = now - lastFinishedAt
        return age >= 0 && age < windowMillis ? .recent : .start
    }
}
