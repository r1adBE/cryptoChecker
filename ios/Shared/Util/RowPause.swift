import Foundation

/// Börse pausiert (`ExchangeBackoff`: zu viele Anfragen bzw. wiederholte Zeitüberschreitungen): Die Zeile der
/// Merkliste sagt es statt der normalen Zeitzeile («Binance · pausiert bis 19:45», Bernstein wie «veraltet») —
/// sonst stand das nur im Bericht der letzten Aktualisierung, und die Zeile wirkte einfach alt.
/// Reine Logik — wie `RowPause.kt` (gemeinsame Fälle `Parity/row_pause.json`).
enum RowPause {

    /// Ende der Pause je Börsen-Schlüssel aus den gespeicherten Zuständen; Zustände ohne Pausen-Ende fehlen.
    static func pauses(_ states: [String: ExchangeBackoff.State]) -> [String: Int64] {
        states.filter { $0.value.pausedUntil > 0 }.mapValues(\.pausedUntil)
    }

    /// Ende der Pause, das die Zeile zeigt; nil = normale Zeitzeile. Deaktiviertes Paar: nie (es wird ohnehin nicht
    /// abgefragt). Abgelaufene oder unmögliche Pause (gleiche Grenze wie `ExchangeBackoff.isPaused`): nie.
    static func shownUntil(disabled: Bool, pausedUntil: Int64?, now: Int64) -> Int64? {
        guard !disabled, let pausedUntil else { return nil }
        return ExchangeBackoff.isPaused(ExchangeBackoff.State(pausedUntil: pausedUntil), now: now) ? pausedUntil : nil
    }
}
