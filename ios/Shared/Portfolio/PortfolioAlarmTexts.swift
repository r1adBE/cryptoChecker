import Foundation

/// Texte der Portfolio-Alarme (Alarm-Übersicht, Portfolio, Mitteilung) — wie `PortfolioAlarmTexts.kt`.
/// Beträge werden mit «Beträge verbergen» (`hidden`) zu «•••»; Prozente bleiben sichtbar.
enum PortfolioAlarmTexts {

    /// «5.00%» bzw. «5,00 %» ohne Vorzeichen (Schreibweise je Sprache, `PercentText`) — wie Android
    /// `PortfolioSnapshotMath.unsignedPercent`.
    static func percent(_ value: Double) -> String {
        PercentText.unsigned(value, decimals: 2)
    }

    /// «+5.20%» / «−5.20%»; ±0 ohne Vorzeichen — wie Android `PortfolioSnapshotMath.signedPercent`.
    /// Auch «0.00%» als links-nach-rechts-Insel (wie jeder andere Wert und wie Android).
    /// `language` nur für Tests (RTL-Sprache).
    static func signedPercent(_ value: Double, language: String = BidiText.currentLanguage) -> String {
        BidiText.ltr(PercentText.signed(value, decimals: 2), language: language)
    }

    /// «50’000.00 CHF» bzw. «5.00%».
    static func threshold(_ alarm: PortfolioAlarm, hidden: Bool) -> String {
        if alarm.kind.isValue {
            return PortfolioInsights.mask(PriceFormat.valueWithCurrency(alarm.threshold, alarm.currency ?? "USD"), hidden: hidden)
        }
        return percent(alarm.threshold)
    }

    /// «Portfolio über 50’000.00 CHF», «Portfolio fällt um 5.00% (24h)».
    static func sentence(_ alarm: PortfolioAlarm, basis: ChangeBasis, hidden: Bool) -> String {
        let value = threshold(alarm, hidden: hidden)
        switch alarm.kind {
        case .VALUE_ABOVE: return L("portfolio_alarm_sentence_above", value)
        case .VALUE_BELOW: return L("portfolio_alarm_sentence_below", value)
        case .CHANGE_UP: return L("portfolio_alarm_sentence_up", value, A11y.changeShortLabel(basis))
        case .CHANGE_DOWN: return L("portfolio_alarm_sentence_down", value, A11y.changeShortLabel(basis))
        }
    }

    /// Gemessener Wert beim Auslösen: «50’210.00 CHF» bzw. «−5.20%» (bei «fällt» wieder die echte Veränderung).
    static func measured(_ alarm: PortfolioAlarm, measured: Double, hidden: Bool) -> String {
        switch alarm.kind {
        case .VALUE_ABOVE, .VALUE_BELOW:
            return PortfolioInsights.mask(PriceFormat.valueWithCurrency(measured, alarm.currency ?? "USD"), hidden: hidden)
        case .CHANGE_UP: return signedPercent(measured)
        case .CHANGE_DOWN: return signedPercent(-measured)
        }
    }

    /// Name der Art für die Auswahl beim Anlegen.
    static func kindLabel(_ kind: PortfolioAlarmKind) -> String {
        switch kind {
        case .VALUE_ABOVE: return L("portfolio_alarm_kind_above")
        case .VALUE_BELOW: return L("portfolio_alarm_kind_below")
        case .CHANGE_UP: return L("portfolio_alarm_kind_up")
        case .CHANGE_DOWN: return L("portfolio_alarm_kind_down")
        }
    }
}
