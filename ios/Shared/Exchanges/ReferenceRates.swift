import Foundation

/// Referenzkurse der Zentralbanken neben der EZB (`EcbRates`), frei und ohne Schlüssel. Wie Android `ReferenceRates.kt`,
/// gemeinsame Fälle in `Parity/reference_rates.json`.
///  - Nationalbank der Ukraine (NBU, bank.gov.ua): täglich, Kurse in UAH; eine Abfrage über `nbuWindowDays` Tage
///    (`nbuRangeURL`), eine Veröffentlichung erkennt man an `calcdate`.
///  - Polnische Nationalbank (NBP, api.nbp.pl): Tabelle B wöchentlich am Mittwoch, Kurse in PLN; USD und EUR derselben
///    Veröffentlichung aus der täglichen Tabelle A desselben `effectiveDate`.
/// Veröffentlichung = Code → Kurs in der Heimwährung (Heimwährung = 1). Paare jeder Währung gegen USD und EUR als
/// Kreuzkurs derselben Veröffentlichung auf 5 signifikante Stellen (`EcbRates.round5`); Veränderung gegen die vorige
/// Veröffentlichung derselben Quelle.
enum ReferenceRates {
    static let nbuListURL = "https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json"
    private static let nbuRange = "https://bank.gov.ua/NBU_Exchange/exchange_site"
    private static let nbpAPI = "https://api.nbp.pl/api/exchangerates/tables"
    static let nbpTableAURL = nbpAPI + "/A/last/15?format=json"
    static let nbpTableBURL = nbpAPI + "/B/last/2?format=json"
    static let nbpListAURL = nbpAPI + "/A?format=json"
    static let nbpListBURL = nbpAPI + "/B?format=json"
    static let nbuWindowDays = 7
    /// Neu gelesen wird spätestens nach so viel Zeit, wenn die erwartete Veröffentlichung noch fehlt (1 h).
    static let retryMillis: Int64 = 60 * 60 * 1000
    static let quotes = ["USD", "EUR"]

    /// Edelmetalle, Sonderziehungsrecht und zurückgezogene ISO-4217-Codes. Wie Android `EXCLUDED`.
    static let excluded: Set<String> = [
        "XAU", "XAG", "XPT", "XPD", "XDR",
        "ANG", "AZM", "BGN", "BYR", "CSD", "CUC", "CYP", "EEK", "GHC", "HRK", "LTL", "LVL", "MGF", "MRO", "MTL", "MZM",
        "ROL", "SDD", "SIT", "SKK", "SLL", "SRG", "STD", "TMM", "TRL", "VEB", "VEF", "XEU", "XFU", "YUM", "ZMK", "ZWD",
        "ZWL", "ZWR",
    ]

    static let kyiv = TimeZone(identifier: "Europe/Kiev") ?? .current
    static let warsaw = TimeZone(identifier: "Europe/Warsaw") ?? .current
    /// Uhrzeit (Warschau), ab der die Tabelle B des Mittwochs erscheint: 12:15.
    private static let nbpPublishedMinutes = 12 * 60 + 15

    typealias Day = [String: Double]

    // MARK: Quelle je Währung

    /// Jede Währung aus genau einer Quelle: EZB (samt EUR) vor NBU (samt UAH) vor NBP (samt PLN); `excluded` und
    /// ungültige Codes fallen weg.
    static func assign(ecb: Set<String>, nbu: Set<String>, nbp: Set<String>) -> [String: ReferenceSource] {
        var out: [String: ReferenceSource] = [:]
        for (codes, source) in [(ecb, ReferenceSource.ecb), (nbu, .nbu), (nbp, .nbp)] {
            for code in codes.union([source.home]).sorted()
            where EcbRates.isCode(code) && !excluded.contains(code) && out[code] == nil {
                out[code] = source
            }
        }
        return out
    }

    /// Paare der Quelle `source`: jede Währung gegen USD und EUR, Kennung «AEDUSD».
    static func pairs(_ assigned: [String: ReferenceSource], source: ReferenceSource) -> [ExchangePair] {
        assigned.filter { $0.value == source }.keys.sorted().flatMap { code in
            quotes.filter { $0 != code }.map { ExchangePair(code, $0, code + $0) }
        }
    }

    // MARK: Kurse

    /// Einheiten `quote` je 1 `base` aus einer Veröffentlichung (5 Stellen); nil ohne beide Kurse oder für `excluded`.
    static func cross(_ day: Day, base: String, quote: String) -> Double? {
        guard base != quote, !excluded.contains(base), !excluded.contains(quote),
              let b = day[base], b > 0, b.isFinite, let q = day[quote], q > 0, q.isFinite else { return nil }
        return EcbRates.round5(b / q)
    }

    /// Kurs aus der jüngsten Veröffentlichung, Veränderung gegen die vorige; Fehler, wenn die jüngste keinen Kurs hat.
    static func read(_ publications: [Day], base: String, quote: String, into ticker: inout Ticker) throws {
        guard let newest = publications.last, let last = cross(newest, base: base, quote: quote) else {
            throw JSONError(message: JSONError.noData)
        }
        ticker.last = last
        ticker.change24hPercent = publications.count >= 2
            ? cross(publications[publications.count - 2], base: base, quote: quote)
                .flatMap { Change24h.fromOpen(last: last, open: $0) }
            : nil
    }

    // MARK: NBU

    /// Ein Tag der NBU: Datum «yyyy-MM-dd», Tag der Festlegung, Kurse in UAH.
    struct NbuDay: Equatable {
        let date: String
        let calc: String
        let rates: Day
    }

    static func nbuRangeURL(today: LocalDay) -> String {
        "\(nbuRange)?start=\(compact(today.plusDays(-nbuWindowDays)))&end=\(compact(today))&sort=exchangedate&order=desc&json"
    }

    /// Codes der aktuellen NBU-Liste; nil, wenn unlesbar.
    static func nbuCodes(_ body: String) -> Set<String>? {
        guard let rows = try? JArray(string: body) else { return nil }
        let codes = Set(rows.objects.map { $0.optString("cc") }.filter(EcbRates.isCode))
        return codes.isEmpty ? nil : codes
    }

    /// Tage der Abfrage `nbuRangeURL`, aufsteigend; nil, wenn unlesbar oder leer. Wie Android `parseNbuRange`.
    static func parseNbuRange(_ body: String) -> [NbuDay]? {
        guard let rows = try? JArray(string: body) else { return nil }
        var rates: [String: Day] = [:]
        var calcs: [String: String] = [:]
        for row in rows.objects {
            guard let date = isoOf(row.optString("exchangedate")) else { continue }
            let code = row.optString("cc")
            guard EcbRates.isCode(code) else { continue }
            var perUnit = row.optDouble("rate_per_unit")
            if !perUnit.isFinite { perUnit = row.optDouble("rate") / row.optDouble("units", 1) }
            guard perUnit > 0, perUnit.isFinite else { continue }
            rates[date, default: [:]][code] = perUnit
            if calcs[date] == nil, let calc = isoOf(row.optString("calcdate")) { calcs[date] = calc }
        }
        let days = rates.keys.sorted().map { NbuDay(date: $0, calc: calcs[$0] ?? $0, rates: rates[$0] ?? [:]) }
        return days.isEmpty ? nil : days
    }

    /// Jüngste Veröffentlichung und die davor (anderes `calcdate`), aufsteigend, je mit UAH = 1.
    static func nbuPublications(_ days: [NbuDay]) -> [Day] {
        guard let newest = days.max(by: { $0.date < $1.date }) else { return [] }
        let previous = days.filter { $0.date < newest.date && $0.calc != newest.calc }.max(by: { $0.date < $1.date })
        return [previous, newest].compactMap { $0 }.map { $0.rates.merging(["UAH": 1]) { _, home in home } }
    }

    /// Gültig bis Mitternacht (Kiew), wenn der heutige Tag dabei ist; sonst `retryMillis`.
    static func nbuValidUntil(_ days: [NbuDay]?, now: Int64) -> Int64 {
        guard let newest = days?.map(\.date).max() else { return 0 }
        let today = dateIn(now, kyiv)
        if newest < today.description { return now + retryMillis }
        return millis(today.plusDays(1), minutes: 0, kyiv)
    }

    // MARK: NBP

    /// Tabellen der NBP: `effectiveDate` → Code → Kurs in PLN; nil, wenn unlesbar oder leer.
    static func parseNbp(_ body: String) -> [String: Day]? {
        guard let tables = try? JArray(string: body) else { return nil }
        var out: [String: Day] = [:]
        for table in tables.objects {
            let date = table.optString("effectiveDate")
            guard EcbRates.isDate(date), let rates = try? table.array("rates") else { continue }
            var day: Day = [:]
            for rate in rates.objects {
                let code = rate.optString("code")
                let mid = rate.optDouble("mid")
                if EcbRates.isCode(code), mid > 0, mid.isFinite { day[code] = mid }
            }
            if !day.isEmpty { out[date] = day }
        }
        return out.isEmpty ? nil : out
    }

    /// Die zwei jüngsten Tabellen B, aufsteigend, je ergänzt um Tabelle A desselben Tags und PLN = 1.
    static func nbpPublications(tableA: [String: Day]?, tableB: [String: Day]?) -> [Day] {
        guard let b = tableB else { return [] }
        return b.keys.sorted().suffix(2).map { date in
            (tableA?[date] ?? [:]).merging(b[date] ?? [:]) { _, fromB in fromB }.merging(["PLN": 1]) { _, home in home }
        }
    }

    /// Gültig bis zur nächsten Tabelle B (Mittwoch 12:15 Warschau), wenn die jüngste die erwartete ist und Tabelle A
    /// ihren Tag hat; sonst `retryMillis`. Wie Android `nbpValidUntil`.
    static func nbpValidUntil(tableA: [String: Day]?, tableB: [String: Day]?, now: Int64) -> Int64 {
        guard let newest = tableB?.keys.max(), let tableA else { return 0 }
        let expected = nbpExpectedWednesday(now: now)
        if newest < expected.plusDays(-2).description || tableA[newest] == nil { return now + retryMillis }
        return millis(expected.plusDays(7), minutes: nbpPublishedMinutes, warsaw)
    }

    /// Mittwoch der jüngsten Tabelle B, die zum Zeitpunkt `now` schon erschienen sein sollte.
    static func nbpExpectedWednesday(now: Int64) -> LocalDay {
        let today = dateIn(now, warsaw)
        // epochDay 0 = Donnerstag 1970-01-01: Montag = 0 … Sonntag = 6
        let weekday = ((today.epochDay + 3) % 7 + 7) % 7
        let wednesday = today.plusDays(-((weekday - 2 + 7) % 7))
        return now < millis(wednesday, minutes: nbpPublishedMinutes, warsaw) ? wednesday.plusDays(-7) : wednesday
    }

    // MARK: Hilfen

    /// Kalendertag von `now` (ms) in `zone`.
    static func dateIn(_ now: Int64, _ zone: TimeZone) -> LocalDay {
        LocalDay(date: Date(timeIntervalSince1970: Double(now) / 1000), timeZone: zone)
    }

    /// Zeitpunkt (ms) von `day` um `minutes` nach Mitternacht in `zone`.
    private static func millis(_ day: LocalDay, minutes: Int, _ zone: TimeZone) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = DateComponents(year: day.year, month: day.month, day: day.day, hour: minutes / 60, minute: minutes % 60)
        guard let date = calendar.date(from: parts) else { return 0 }
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// «05.11.2026» → «2026-11-05»; sonst nil.
    private static func isoOf(_ dotted: String) -> String? {
        let parts = dotted.trimmingCharacters(in: .whitespaces).split(separator: ".").map(String.init)
        guard parts.count == 3, parts[0].count == 2, parts[1].count == 2, parts[2].count == 4 else { return nil }
        let iso = "\(parts[2])-\(parts[1])-\(parts[0])"
        return EcbRates.isDate(iso) ? iso : nil
    }

    private static func compact(_ day: LocalDay) -> String { day.description.replacingOccurrences(of: "-", with: "") }
}
