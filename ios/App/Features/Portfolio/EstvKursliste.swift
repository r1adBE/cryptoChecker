import Foundation

/// Hinweis auf die ESTV-Kursliste (ICTax) im Stichtag-Export: Für die Vermögenssteuer gelten die
/// offiziellen Jahresendkurse der ESTV. Es gibt keine dokumentierte, offene Schnittstelle dafür —
/// die App lädt nichts, sie verlinkt nur die Liste des Jahres (öffnet der Nutzer selbst).
/// Gleich wie `EstvKursliste.kt`.
enum EstvKursliste {
    static let host = "www.ictax.admin.ch"

    /// Sprachen der ICTax-Oberfläche; sonst Englisch.
    private static let languages: Set<String> = ["de", "fr", "it", "en"]
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Kursliste des Steuerjahrs `year` in der Sprache `language` (z. B. «de», «de-CH»).
    static func listUrl(year: Int, language: String) -> String {
        let lower = language.lowercased()
        let lang = String(lower.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "")
        let code = languages.contains(lang) ? lang : "en"
        return "https://\(host)/extern/\(code).html#/ratelist/\(year)"
    }

    /// Nur in CHF und per 31.12. (Stichtag der Steuerwerte).
    static func applies(day: CutoffDay, currency: String) -> Bool {
        currency.uppercased() == "CHF" && day.month == 12 && day.day == 31
    }

    /// Hinweiszeile aus `format` (1. = Jahr, 2. = Link, je `%@`) oder nil, wenn kein Hinweis gilt.
    static func noteLine(format: String?, day: CutoffDay, currency: String, language: String) -> String? {
        guard let format, !format.isEmpty, applies(day: day, currency: currency) else { return nil }
        return String(format: format, locale: posix, String(day.year), listUrl(year: day.year, language: language))
    }
}
