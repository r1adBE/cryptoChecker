import Foundation

/// Texte der Uhr aus `Localizable.xcstrings` (Android-Schlüssel, 75 Sprachen) und Zahlen kurz formatiert — wie
/// Android `WatchFormat`; Alter veralteter Kurse wie Android `WatchAge`.
enum WatchText {
    static func t(_ key: String, _ args: CVarArg...) -> String {
        let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
        return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
    }

    /// Kurs mit festen Nachkommastellen (`WatchPriceFormat.price`, wie Android `WatchFormat.price`).
    static func price(_ value: Double) -> String { WatchPriceFormat.price(value) }

    /// Kurs knapp für kleine Komplikationen «84’450» (`WatchPriceFormat.compactPrice`).
    static func compactPrice(_ value: Double) -> String { WatchPriceFormat.compactPrice(value) }

    /// Grund für «—» (`WatchPairQuotes.missingReason` mit dem letzten eigenen Abruf, `WatchPairOutcomes`): Pool,
    /// Laufzeit-Kontrakt → die Uhr kann das Paar nicht selbst laden; gefragt, aber keine gefragte Börse führt das Paar
    /// → Kurse dieser Börse nur vom iPhone; gefragt, aber keine Börse antwortete → Börsen gerade nicht erreichbar; noch
    /// nicht gefragt → eigene Börse nicht erreichbar bzw. nur vom iPhone. Wie Android `missingReason`.
    static func missingReason(_ key: String) -> String {
        guard let pair = WatchPair.parse(key) else { return t("watch_error") }
        switch WatchPairQuotes.missingReason(pair, outcome: WatchPairOutcomes.outcome(pair.key)) {
        case .phoneOnly: return t("a11y_watch_no_price_phone_only")
        case .ownUnreachable: return t("a11y_watch_no_price_unreachable", pair.exchange)
        case .unreachable: return t("a11y_watch_no_price_exchanges_unreachable")
        case .viaPhone: return t("a11y_watch_no_price_via_phone", pair.exchange)
        }
    }

    /// Änderung in der Schreibweise der iPhone-App (`WatchPercent`, wie Android `WatchFormat.change`).
    static func change(_ value: Double?) -> String { WatchPercent.change(value) }

    /// «vor 47 Min.» — kurz und klein, steht hinter «+1.23%  ·» (wie Android `WatchAge.short`).
    static func ago(_ time: Int64, now: Int64) -> String {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale.current
        f.unitsStyle = .abbreviated
        f.formattingContext = .middleOfSentence
        return f.localizedString(for: date(time), relativeTo: date(now))
    }

    /// «Kurs veraltet, zuletzt aktualisiert vor 47 Minuten» für VoiceOver (wie Android `WatchAge.spoken`).
    static func spokenAge(_ time: Int64, now: Int64) -> String {
        let age = WatchFreshness.age(time, now: now)
        let updated: String? = switch age.unit {
        case .justNow: nil
        case .minutes: t("a11y_watch_updated_minutes", age.count)
        case .hours: t("a11y_watch_updated_hours", age.count)
        case .days: t("a11y_watch_updated_days", age.count)
        }
        return [t("a11y_watch_outdated"), updated].compactMap { $0 }.joined(separator: ", ")
    }

    /// Portfolio-Wert «CHF 12’345.67»; verborgen «•••», ohne Wert «—» (wie Android `WatchPortfolioText.total`).
    static func portfolioTotal(_ p: WatchPortfolio) -> String {
        if p.hidden { return "•••" }
        guard let total = p.total else { return "—" }
        return total.formatted(.currency(code: p.currency).locale(Locale.current))
    }

    /// Kurz für kleine Komplikationen «12K»; verborgen «•••».
    static func portfolioCompact(_ p: WatchPortfolio) -> String {
        if p.hidden { return "•••" }
        guard let total = p.total else { return "—" }
        return total.formatted(.number.notation(.compactName).precision(.significantDigits(3)).locale(Locale.current))
    }

    /// «+1.20 % heute»; verborgen «•••».
    static func portfolioToday(_ p: WatchPortfolio) -> String {
        p.hidden ? "•••" : t("watch_portfolio_today", change(p.changePercent))
    }

    /// Für VoiceOver: «Portfolio, CHF 12’345.67, +1.20 % heute» bzw. «Portfolio, Beträge verborgen».
    static func portfolioSpoken(_ p: WatchPortfolio) -> String {
        let title = t("watch_portfolio")
        return p.hidden ? "\(title), \(t("a11y_watch_portfolio_hidden"))"
            : "\(title), \(portfolioTotal(p)), \(portfolioToday(p))"
    }

    /// Epoch-ms → Datum.
    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }
}
