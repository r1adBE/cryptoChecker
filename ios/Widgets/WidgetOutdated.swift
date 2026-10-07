import Foundation

/// Veraltete Kurse in den Widgets (wie `WidgetOutdated` in Android): Statt nur der Uhrzeit
/// steht «veraltet · 06:42» — das Wort, nicht nur eine Farbe —, VoiceOver sagt «veraltet,
/// letzte Aktualisierung 06:42». Regel wie in der Merkliste (`OutdatedRule`).
/// Gemessen wird am Datum des Timeline-Eintrags: Jede Timeline bekommt einen zweiten Eintrag
/// zum Zeitpunkt, an dem der nächste Stand veraltet ([entries]) — so wechselt das Widget
/// auch dann zu «veraltet», wenn iOS es nicht neu lädt.
enum WidgetOutdated {
    /// Grenze aus den aktuellen Einstellungen (3 × Intervall, mind. 15 Min.).
    static var afterMillis: Int64 { OutdatedRule.afterMillis(SharedStorage.loadSettings()) }

    static func millis(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    /// Zeitpunkt `time` am Datum `date` veraltet?
    static func isOutdated(_ time: Int64, at date: Date, afterMillis: Int64) -> Bool {
        OutdatedRule.isOutdated(time, now: millis(date), afterMillis: afterMillis)
    }

    /// Kurs eines Paares veraltet? «Nicht mehr gehandelt» ist ein Zustand und zählt nicht.
    static func isOutdated(_ watch: Watch, at date: Date, afterMillis: Int64) -> Bool {
        !ConnectionErrors.isNotTraded(watch.lastError) && isOutdated(watch.lastUpdate, at: date, afterMillis: afterMillis)
    }

    /// «06:42» (am selben Tag) bzw. «5. Okt., 06:42» — bei altem Stand zählt auch der Tag.
    static func stamp(_ time: Int64, at date: Date) -> String {
        let moment = Date(timeIntervalSince1970: TimeInterval(time) / 1000)
        if Calendar.current.isDate(moment, inSameDayAs: date) {
            return moment.formatted(date: .omitted, time: .shortened)
        }
        return moment.formatted(.dateTime.day().month(.abbreviated).hour().minute())
    }

    /// «veraltet · 06:42»
    static func label(_ time: Int64, at date: Date) -> String {
        L("widget_outdated_time", stamp(time, at: date))
    }

    /// «veraltet, letzte Aktualisierung 06:42»
    static func spoken(_ time: Int64, at date: Date) -> String {
        L("a11y_stale", stamp(time, at: date))
    }

    /// Uhrzeit wie bisher (`PriceFormat.time`) oder, wenn veraltet, «veraltet · 06:42».
    static func timeText(_ time: Int64, outdated: Bool, at date: Date) -> String {
        outdated ? label(time, at: date) : PriceFormat.time(time)
    }

    /// Einträge der Timeline: `entry` jetzt und, falls einer der `times` noch veraltet, eine
    /// Kopie zu diesem Zeitpunkt (`at`). Auch nach dem gewünschten Neuladen: iOS lädt nach
    /// seinem eigenen Budget oft später — bis dahin zeigt das Widget die Einträge der Reihe nach.
    static func entries<Entry>(_ entry: Entry, date: Date, times: [Int64], afterMillis: Int64,
                               at: (Date) -> Entry) -> [Entry] {
        guard let next = OutdatedRule.nextChangeAt(times, now: millis(date), afterMillis: afterMillis) else {
            return [entry]
        }
        return [entry, at(Date(timeIntervalSince1970: TimeInterval(next) / 1000))]
    }
}
