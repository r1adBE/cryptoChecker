import Foundation

/// Regelstand der Länder-Vorlagen (`TaxTemplate.rulesDate`, «JJJJ-MM»): Sätze, Freibeträge und Grenzen (z. B.
/// `TaxTemplate.caInclusion`, `TaxTemplate.frExemptEur`) sind fest hinterlegt. Für ein Steuerjahr nach dem Jahr des
/// Regelstands können sie veraltet sein — die Seite «Steuer-Arbeitsunterlage» weist dann darauf hin, ohne zu
/// blockieren. Wie Android `TaxRulesDate`; gemeinsame Fälle in `tax_rules_stale.json`.
enum TaxRulesDate {
    /// Jahr und Monat des Regelstands; nil = nicht «JJJJ-MM» mit Ziffern 0–9 oder Monat ausserhalb 1–12.
    static func month(_ rulesDate: String) -> (year: Int, month: Int)? {
        let parts = rulesDate.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].count == 4, parts[1].count == 2 else { return nil }
        let latin = parts.allSatisfy { part in part.allSatisfy { ("0"..."9").contains($0) } }
        guard latin, let year = Int(parts[0]), let month = Int(parts[1]), (1...12).contains(month) else { return nil }
        return (year, month)
    }

    /// Liegt das Steuerjahr `taxYear` (Startjahr, wie `TaxTemplate.period`) nach dem Jahr von `rulesDate`?
    /// Unlesbarer Regelstand: nein (der hinterlegte `TaxTemplate.rulesDate` ist im Test als lesbar geprüft).
    static func rulesStale(taxYear: Int, rulesDate: String = TaxTemplate.rulesDate) -> Bool {
        guard let rules = month(rulesDate) else { return false }
        return taxYear > rules.year
    }

    /// Regelstand als Monat und Jahr in Sprache und Ziffern von `locale` («Okt. 2026», «أكتوبر ٢٠٢٦»; Vorlage
    /// «MMMyyyy» wie Android `MonthYearFormat`); nicht lesbar: der Rohwert.
    static func label(_ rulesDate: String = TaxTemplate.rulesDate, locale: Locale = LocaleNumbers.appLocale) -> String {
        guard let m = month(rulesDate) else { return rulesDate }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
        guard let date = calendar.date(from: DateComponents(year: m.year, month: m.month, day: 15)) else {
            return rulesDate
        }
        // Gregorianisch wie java.time unter Android (sonst etwa buddhistisches Jahr unter Thai)
        return DateFormatterCache.string(date, purpose: "rulesMonthYear", locale: locale,
                                         timeZone: calendar.timeZone) { f in
            f.calendar = Calendar(identifier: .gregorian)
            f.setLocalizedDateFormatFromTemplate("MMMyyyy")
        }
    }
}
