import Foundation

/// Zeitpunkt eines vergangenen Ereignisses (z. B. «zuletzt ausgelöst» auf der Alarmkarte): heute nur die Uhrzeit mit
/// Sekunden wie `PriceFormat.time` («14:02:11»), an einem anderen Tag Datum und Uhrzeit kurz («12.10.26, 14:02») —
/// vorher stand nur die Uhrzeit da, auch Tage später. Wie `MomentText.kt` (Android).
enum MomentText {

    /// Liegen `millis` und `now` am selben Kalendertag in `timeZone`?
    static func isSameDay(_ millis: Int64, _ now: Int64, timeZone: TimeZone = .current) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.isDate(Date(millis: millis), inSameDayAs: Date(millis: now))
    }

    /// Heute: Uhrzeit mit Sekunden; sonst Datum und Uhrzeit kurz; nicht gesetzt (≤ 0): «—».
    static func moment(_ millis: Int64, now: Int64 = TimeUtils.nowMillis, timeZone: TimeZone = .current,
                       locale: Locale = LocaleNumbers.appLocale) -> String {
        guard millis > 0 else { return "—" }
        let date = Date(millis: millis)
        // Formatter zwischengespeichert (`DateFormatterCache`): Alarmkarte und -liste rufen das je Zeile und Neuzeichnen
        if isSameDay(millis, now, timeZone: timeZone) {
            return DateFormatterCache.string(date, purpose: "momentToday", locale: locale, timeZone: timeZone) { f in
                f.dateStyle = .none
                f.timeStyle = .medium
            }
        }
        return DateFormatterCache.string(date, purpose: "momentEarlier", locale: locale, timeZone: timeZone) { f in
            f.dateStyle = .short
            f.timeStyle = .short
        }
    }
}
