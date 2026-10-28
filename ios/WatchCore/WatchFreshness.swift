import Foundation

/// Alter der Kurse auf der Uhr (Uhr-App und Komplikation): «veraltet» nach derselben Regel wie Merkliste und Widgets
/// (`OutdatedRule`: dreimal das Intervall, mindestens 15 Min.), mit dem Takt der Komplikation (`updateMinutes`) als
/// Intervall — also nach 45 Min. Gezählt je Coin ab dem Stand seines Abrufs (letzter Abruf, der diesen Coin lieferte),
/// nicht ab dem letzten Handel: die 24-h-Ticker der Börsen geben dafür keine verlässliche Zeit. Alter für VoiceOver in
/// Minuten, Stunden, Tagen wie `A11ySpeech.age` (Android). Wie Android `WatchFreshness` (`Parity/watch_quotes.json`,
/// Abgleich mit `OutdatedRule` in `WatchQuotesParityTests`).
enum WatchFreshness {
    /// Takt der Komplikation: Zeitleiste alle 15 Min. (Android: `UPDATE_PERIOD_SECONDS` = 900).
    static let updateMinutes = 15

    /// = `OutdatedRule.afterMillis(backgroundIntervalMinutes: updateMinutes)` = max(3 × 15 Min., 15 Min.).
    static let outdatedAfterMillis: Int64 = 3 * Int64(updateMinutes) * 60_000

    /// Veraltet? Nie geladen (≤ 0) nie — dann steht ohnehin «—». Wie `OutdatedRule.isOutdated` (strikt älter).
    static func isOutdated(_ time: Int64, now: Int64) -> Bool {
        time > 0 && now - time > outdatedAfterMillis
    }

    /// Einheit des Alters; Rohwerte wie im Fixture.
    enum AgeUnit: String, Sendable { case justNow, minutes, hours, days }

    /// Alter in einer Einheit (`count` ganze Einheiten; bei `.justNow` 0).
    struct Age: Equatable, Sendable {
        let unit: AgeUnit
        let count: Int
    }

    /// Alter von `time` zu `now`: unter 1 Min. (auch Zeit in der Zukunft) «gerade eben», dann Minuten, Stunden, Tage.
    static func age(_ time: Int64, now: Int64) -> Age {
        let minute: Int64 = 60_000, hour = 60 * minute, day = 24 * hour
        let diff = now - time
        if diff < minute { return Age(unit: .justNow, count: 0) }
        if diff < hour { return Age(unit: .minutes, count: Int(diff / minute)) }
        if diff < day { return Age(unit: .hours, count: Int(diff / hour)) }
        return Age(unit: .days, count: Int(min(diff / day, Int64(Int.max))))
    }
}
