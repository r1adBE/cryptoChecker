import Foundation

/// Kalendertag ohne Uhrzeit — Ersatz für `java.time.LocalDate`.
///
/// Rechnet mit Tagen seit 1970-01-01 (proleptischer gregorianischer Kalender),
/// damit Tagesabstände und Monatszählung genau wie in der Android-Fassung sind.
struct LocalDay: Hashable, Comparable, Sendable, Codable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    init(_ year: Int, _ month: Int, _ day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Tage seit 1970-01-01.
    init(epochDay: Int) {
        // Algorithmus von Howard Hinnant (civil_from_days)
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
    }

    /// Tag (UTC) eines Zeitstempels in Millisekunden.
    init(epochMillisUTC millis: Int64) {
        let dayMillis: Int64 = 86_400_000
        var days = millis / dayMillis
        if millis % dayMillis < 0 { days -= 1 }
        self.init(epochDay: Int(days))
    }

    /// Tag eines Zeitpunkts in der angegebenen Zeitzone.
    init(date: Date, timeZone: TimeZone = .current) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day], from: date)
        self.init(c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    /// Heute in der Zeitzone des Geräts (`LocalDate.now()`).
    static func today() -> LocalDay { LocalDay(date: Date()) }

    /// Heute in UTC (`LocalDate.now(ZoneOffset.UTC)`).
    static func todayUTC() -> LocalDay {
        LocalDay(date: Date(), timeZone: TimeZone(identifier: "UTC") ?? .current)
    }

    /// Text "yyyy-MM-dd" (ISO), z. B. für Abfrage-Parameter.
    init?(iso: String) {
        let parts = iso.prefix(10).split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        self.init(y, m, d)
    }

    var epochDay: Int {
        // Algorithmus von Howard Hinnant (days_from_civil)
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    func plusDays(_ days: Int) -> LocalDay { LocalDay(epochDay: epochDay + days) }

    /// Wie `ChronoUnit.DAYS.between(self, other)`.
    func days(until other: LocalDay) -> Int { other.epochDay - epochDay }

    /// Wie `ChronoUnit.MONTHS.between(self, other)`: nur volle Monate.
    func months(until other: LocalDay) -> Int {
        let packed1 = (year * 12 + month - 1) * 32 + day
        let packed2 = (other.year * 12 + other.month - 1) * 32 + other.day
        return (packed2 - packed1) / 32
    }

    func isAfter(_ other: LocalDay) -> Bool { self > other }
    func isBefore(_ other: LocalDay) -> Bool { self < other }

    /// Mitternacht dieses Tags in der Zeitzone des Geräts (für die Anzeige).
    var date: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        return cal.date(from: DateComponents(year: year, month: month, day: day)) ?? Date()
    }

    /// Mitternacht UTC in Millisekunden.
    var epochMillisUTC: Int64 { Int64(epochDay) * 86_400_000 }

    var description: String { String(format: "%04ld-%02ld-%02ld", year, month, day) }

    static func < (a: LocalDay, b: LocalDay) -> Bool {
        if a.year != b.year { return a.year < b.year }
        if a.month != b.month { return a.month < b.month }
        return a.day < b.day
    }
}
