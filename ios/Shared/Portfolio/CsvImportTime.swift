import Foundation

// CSV-Import (`CsvImportParser`): Zeitpunkte (ISO, Tag zuerst/Monat zuerst) in der Zeitzone der Datei lesen — wie `CsvImportTime.kt` (Android).

extension CsvImportParser {

    private static let iso = try? NSRegularExpression(
        pattern: "^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]{1,9}))?)?)?\\s*(Z|UTC|[+-][0-9]{2}:?[0-9]{2})?$")
    private static let dayFirst = try? NSRegularExpression(
        pattern: "^([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{4})(?:[ T,]+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?)?$")

    /// Gruppen eines vollständigen Treffers ("" für nicht beteiligte Gruppen); nil = kein Treffer.
    private static func groups(_ regex: NSRegularExpression?, _ text: String) -> [String]? {
        guard let regex else { return nil }
        let ns = text as NSString
        guard let m = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)),
              m.range.location == 0, m.range.length == ns.length else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func int(_ s: String) -> Int { Int(s) ?? 0 }

    /// Zeit mit Versatz oder «Z»/«UTC» (dann zählt keine Zeitzone) — wie Android `hasOffset`.
    static func hasOffset(_ text: String) -> Bool {
        guard let g = groups(iso, trim(text)) else { return false }
        return !g[8].isEmpty
    }

    static func timeZone(_ zone: String) -> TimeZone {
        TimeZone(identifier: zone) ?? TimeZone(secondsFromGMT: 0) ?? .current
    }

    /// Zeit aus der Datei → UTC-Millisekunden; nil = unlesbar (Formate wie Android `parseTime`). `monthFirst`:
    /// «03/04/2024» bzw. «03-04-2024» = 4. März (`.MONTH_FIRST`); mit «.» immer Tag zuerst.
    static func parseTime(_ text: String, zone: String = defaultZone, monthFirst: Bool = false) -> Int64? {
        guard let clock = parseClock(text, monthFirst: monthFirst) else { return nil }
        guard let offsetMinutes = clock.offsetMinutes else { return toUtc(clock.local, zone: zone) }
        return clock.local - Int64(offsetMinutes) * 60_000
    }

    /// Kalenderzeit (als wäre sie UTC) einer Zeit OHNE Versatz; nil = mit Versatz/«Z» oder unlesbar.
    static func localTime(_ text: String, monthFirst: Bool = false) -> Int64? {
        guard let clock = parseClock(text, monthFirst: monthFirst), clock.offsetMinutes == nil else { return nil }
        return clock.local
    }

    /// Erstes Trennzeichen eines Datums «03/04/2024» ist ein Punkt (dann immer Tag zuerst)?
    private static func dotted(_ t: String, firstPart: String) -> Bool {
        let chars = Array(t)
        return firstPart.count < chars.count && chars[firstPart.count] == "."
    }

    /// Kalenderzeit (als wäre sie UTC) und Versatz in Minuten (nil = ohne Versatz); nil = unlesbar.
    private static func parseClock(_ text: String, monthFirst: Bool) -> (local: Int64, offsetMinutes: Int?)? {
        let t = trim(text)
        if let g = groups(iso, t) {
            let frac = String((g[7] + "000").prefix(3))
            guard let local = localMillis(int(g[1]), int(g[2]), int(g[3]), int(g[4]), int(g[5]), int(g[6]), int(frac)) else {
                return nil
            }
            let off = g[8]
            if off.isEmpty { return (local, nil) }
            if off == "Z" || off == "UTC" { return (local, 0) }
            let digits = Array(off.dropFirst().replacingOccurrences(of: ":", with: ""))
            let minutes = int(String(digits[0..<2])) * 60 + int(String(digits[2..<4]))
            return (local, off.hasPrefix("-") ? -minutes : minutes)
        }
        if let g = groups(dayFirst, t) {
            // «.» ist immer Tag zuerst; «/» und «-» je nach Reihenfolge der Datei
            let swap = monthFirst && !dotted(t, firstPart: g[1])
            let day = swap ? int(g[2]) : int(g[1])
            let month = swap ? int(g[1]) : int(g[2])
            guard let local = localMillis(int(g[3]), month, day, int(g[4]), int(g[5]), int(g[6]), 0) else {
                return nil
            }
            return (local, nil)
        }
        return nil
    }

    /// Reihenfolge Tag/Monat der Datumsspalte (`texts`) — wie Android `detectDateOrder`: Zahl > 12 vorne → `.DAY_FIRST`,
    /// an zweiter Stelle → `.MONTH_FIRST` (vorne gewinnt bei Widerspruch); nur mehrdeutige Daten (Tag ≠ Monat) → nil
    /// (Vorschau fragt). Nur Trenner «/» und «-»; ISO und «03.04.2024» sind eindeutig → `.DAY_FIRST`.
    static func detectDateOrder(_ texts: [String]) -> CsvDateOrder? {
        var dayFirstSeen = false
        var monthFirstSeen = false
        var ambiguous = false
        for raw in texts {
            let t = trim(raw)
            guard let g = groups(dayFirst, t), !dotted(t, firstPart: g[1]) else { continue }
            let a = int(g[1])
            let b = int(g[2])
            if a > 12 {
                dayFirstSeen = true
            } else if b > 12 {
                monthFirstSeen = true
            } else if a != b {
                ambiguous = true
            }
        }
        if dayFirstSeen { return .DAY_FIRST }
        if monthFirstSeen { return .MONTH_FIRST }
        return ambiguous ? nil : .DAY_FIRST
    }

    /// Kalenderzeit als wäre sie UTC → ms; ungültiges Datum = nil.
    static func localMillis(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int, _ ms: Int) -> Int64? {
        guard (1...12).contains(mo), d >= 1, d <= daysInMonth(y, mo), h <= 23, mi <= 59, s <= 59 else { return nil }
        // In Schritten mit festen Typen (lange Int64-Kette mit Umwandlungen ist für den Type-Checker teuer)
        let hours: Int64 = daysFromCivil(y, mo, d) * 24 + Int64(h)
        let minutes: Int64 = hours * 60 + Int64(mi)
        let seconds: Int64 = Int64(s) * 1000 + Int64(ms)
        return minutes * 60_000 + seconds
    }

    private static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 2: return ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Tage seit 1970-01-01 (proleptisch gregorianisch, H. Hinnant).
    static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int64 {
        let y = Int64(m <= 2 ? y0 - 1 : y0)
        let era = CsvImport.floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = Int64((m + 9) % 12)
        // In Schritten mit festen Typen (für den Type-Checker)
        let monthDays: Int64 = (153 * mp + 2) / 5
        let doy: Int64 = monthDays + Int64(d) - 1
        let yearDays: Int64 = yoe * 365 + yoe / 4
        let doe: Int64 = yearDays - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Ortszeit in `zone` → UTC, zwei Schritte über den Versatz (gleich wie Android): Herbst-Doppelstunde
    /// = spätere (Winterzeit), Frühlingslücke = eine Stunde vorgerückt.
    static func toUtc(_ local: Int64, zone: String) -> Int64 {
        let tz = timeZone(zone)
        func offset(_ utc: Int64) -> Int64 {
            Int64(tz.secondsFromGMT(for: Date(timeIntervalSince1970: Double(utc) / 1000))) * 1000
        }
        let guess = local - offset(local)
        return local - offset(guess)
    }
}
