import Foundation

/// Euro-Referenzkurse direkt von der Europäischen Zentralbank (frei, ohne Schlüssel, ohne Drittanbieter). Alle Werte
/// sind Einheiten je 1 EUR, Tage «yyyy-MM-dd» (nur EZB-Arbeitstage). Wie Android `EcbRates.kt`:
///  - `dailyURL`: der jüngste Tag, alle Währungen (Umrechnungszeile, Paarliste der Forex-Paare).
///  - `hist90dURL`: die letzten 90 Kalendertage, alle Währungen (Forex-Paare: Kurs und Veränderung zum vorigen
///    EZB-Tag; Ersatz für historische Kurse dieser Zeit, wenn das Data Portal nicht antwortet).
///  - `seriesURL`: ECB Data Portal (SDMX, JSON) für beliebige Tage und Zeiträume — eine Abfrage für alle angefragten
///    Währungen, nur diese Währungen und der Zeitraum gehen hinaus.
///  - `histURL`: alle Tage seit 1999 (gepackt rund 1 MB, ungepackt rund 8 MB): Ersatz für ältere Zeiträume, wenn das
///    Data Portal nicht antwortet (dort scheitern erstmals gestellte Abfragen immer wieder mit HTTP 504 nach 30 s).
/// Kreuzkurse (`cross`) mit derselben Genauigkeit wie die bisher bezogenen: Basis EUR = Wert der EZB unverändert, jede
/// andere Basis = Quotient der beiden Euro-Kurse, auf `significantDigits` signifikante Stellen gerundet (`round5`).
enum EcbRates {
    static let dailyURL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
    static let hist90dURL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"
    static let histURL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml"
    private static let dataAPI = "https://data-api.ecb.europa.eu/service/data/EXR/D."
    /// Bis so viele Tage vor heute deckt `hist90dURL` sicher ab (90 Kalendertage, Reserve für Zeitzone/Feiertage).
    static let hist90dSafeDays = 80
    /// Signifikante Stellen eines Kreuzkurses («%.5g», Genauigkeit der bisher bezogenen Kreuzkurse).
    static let significantDigits = 5

    /// Tag → Code → Kurs (Einheiten je 1 EUR).
    typealias Days = [String: [String: Double]]

    /// Tageskurse der Währungen `codes` (ohne EUR, sortiert) von `from` bis `to` als SDMX-JSON. Wie Android `seriesUrl`.
    static func seriesURL(codes: [String], from: LocalDay, to: LocalDay) -> String {
        dataAPI + Set(codes.filter { $0 != "EUR" }).sorted().joined(separator: "+")
            + ".EUR.SP00.A?startPeriod=\(from)&endPeriod=\(to)&format=jsondata&detail=dataonly"
    }

    /// Liegt `from` noch sicher in `hist90dURL` (bezogen auf `today`, UTC)?
    static func coveredByHist90d(from: LocalDay, today: LocalDay) -> Bool {
        !from.isBefore(today.plusDays(-hist90dSafeDays))
    }

    /// Tage aus `eurofxref-daily.xml`, `eurofxref-hist-90d.xml` oder `eurofxref-hist.xml`: Tag → Code → Kurs; mit
    /// `only` nur diese Codes. Kurse ≤ 0 oder ungültige Codes fallen weg. nil ohne lesbaren Tag oder mit DOCTYPE/ENTITY
    /// (externe Entitäten werden nie aufgelöst). Wie Android.
    static func parseXML(_ body: String, only: Set<String>? = nil) -> Days? {
        if body.contains("<!DOCTYPE") || body.contains("<!ENTITY") { return nil }
        guard let data = body.data(using: .utf8) else { return nil }
        let reader = CubeReader(only: only)
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        guard parser.parse() else { return nil }
        let days = reader.days.filter { !$0.value.isEmpty }
        return days.isEmpty ? nil : days
    }

    /// Liest `<Cube time="…">` und `<Cube currency="…" rate="…"/>`.
    private final class CubeReader: NSObject, XMLParserDelegate {
        var days: Days = [:]
        private var day: String?
        private let only: Set<String>?

        init(only: Set<String>?) {
            self.only = only
        }

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes: [String: String] = [:]) {
            guard elementName == "Cube" else { return }
            if let time = attributes["time"] {
                day = EcbRates.isDate(time) ? time : nil
                if let day, days[day] == nil { days[day] = [:] }
                return
            }
            guard let day, let code = attributes["currency"], EcbRates.isCode(code), only?.contains(code) ?? true,
                  let rate = attributes["rate"].flatMap({ Double($0) }), rate > 0, rate.isFinite else { return }
            days[day, default: [:]][code] = rate
        }
    }

    /// Antwort des Data Portals (`format=jsondata&detail=dataonly`): Tag → Code → Kurs. Leerer Text = gültige Antwort
    /// ohne Kurse im Zeitraum (leer). nil, wenn unlesbar (z. B. HTML-Fehlerseite). Wie Android `parseSdmx`.
    static func parseSDMX(_ body: String) -> Days? {
        if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [:] }
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let dimensions = (root["structure"] as? [String: Any])?["dimensions"] as? [String: Any],
              let seriesDims = dimensions["series"] as? [[String: Any]],
              let position = seriesDims.firstIndex(where: { $0["id"] as? String == "CURRENCY" }),
              let codes = seriesDims[position]["values"] as? [[String: Any]],
              let times = (dimensions["observation"] as? [[String: Any]])?.first?["values"] as? [[String: Any]],
              let dataSet = (root["dataSets"] as? [[String: Any]])?.first
        else { return nil }
        var out: Days = [:]
        for (key, value) in dataSet["series"] as? [String: Any] ?? [:] {
            let parts = key.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.indices.contains(position), let index = Int(parts[position]), codes.indices.contains(index),
                  let code = codes[index]["id"] as? String, isCode(code),
                  let observations = (value as? [String: Any])?["observations"] as? [String: Any] else { continue }
            for (slot, entry) in observations {
                guard let i = Int(slot), times.indices.contains(i), let date = times[i]["id"] as? String, isDate(date),
                      let rate = ((entry as? [Any])?.first as? NSNumber)?.doubleValue, rate > 0, rate.isFinite
                else { continue }
                out[date, default: [:]][code] = rate
            }
        }
        return out
    }

    /// Kurs `base` → `quote` aus den Euro-Kursen eines Tags: Einheiten `quote` je 1 `base`. Basis EUR = Wert der EZB,
    /// sonst Quotient auf `significantDigits` Stellen (`round5`). nil, wenn eine Seite fehlt oder beide gleich sind.
    static func cross(_ day: [String: Double], base: String, quote: String) -> Double? {
        guard base != quote, let baseRate = eurRate(day, base), let quoteRate = eurRate(day, quote) else { return nil }
        return base == "EUR" ? quoteRate : round5(quoteRate / baseRate)
    }

    /// Alle Kurse eines Tags zur Basis `base` (EUR eingeschlossen, `base` selbst nicht); leer ohne Kurs von `base`.
    static func rebase(_ day: [String: Double], base: String) -> [String: Double] {
        var out: [String: Double] = [:]
        for code in Array(day.keys) + ["EUR"] {
            if let rate = cross(day, base: base, quote: code) { out[code] = rate }
        }
        return out
    }

    /// Alle Codes eines Tags samt EUR, sortiert.
    static func codes(_ day: [String: Double]) -> [String] {
        Set(day.keys.filter(isCode) + ["EUR"]).sorted()
    }

    /// Tage innerhalb von `windowDays` vor dem jüngsten Tag, aufsteigend.
    static func recent(_ days: Days, windowDays: Int) -> [[String: Double]] {
        guard let newest = days.keys.max(), let start = LocalDay(iso: newest)?.plusDays(-windowDays).description
        else { return [] }
        return days.keys.filter { $0 >= start }.sorted().compactMap { days[$0] }
    }

    private static func eurRate(_ day: [String: Double], _ code: String) -> Double? {
        if code == "EUR" { return 1 }
        guard let rate = day[code], rate > 0, rate.isFinite else { return nil }
        return rate
    }

    /// `value` auf `significantDigits` signifikante Stellen, halbe Stelle aufwärts (wie «%.5g», bis auf
    /// exakte Halbwerte). Nur mit exakt darstellbaren Zehnerpotenzen gerechnet — gleiches Ergebnis wie Android.
    static func round5(_ value: Double) -> Double {
        guard value.isFinite, value > 0 else { return value }
        let shift = significantDigits - 1 - Int(log10(value).rounded(.down))
        if shift >= 0 {
            let scale = pow(10.0, Double(shift))
            return (value * scale + 0.5).rounded(.down) / scale
        }
        let scale = pow(10.0, Double(-shift))
        return (value / scale + 0.5).rounded(.down) * scale
    }

    /// Genau drei Grossbuchstaben A–Z.
    static func isCode(_ text: String) -> Bool {
        text.unicodeScalars.count == 3 && text.unicodeScalars.allSatisfy { $0.value >= 65 && $0.value <= 90 }
    }

    /// «yyyy-MM-dd» aus Ziffern.
    static func isDate(_ text: String) -> Bool {
        let scalars = Array(text.unicodeScalars)
        guard scalars.count == 10 else { return false }
        return scalars.enumerated().allSatisfy { index, c in
            index == 4 || index == 7 ? c == "-" : (c.value >= 48 && c.value <= 57)
        }
    }
}
