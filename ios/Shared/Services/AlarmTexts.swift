import Foundation

/// Alarmbedingung als Text — wie `AlarmTexts.kt`.
enum AlarmTexts {
    static func conditionName(_ c: AlarmCondition) -> String {
        switch c {
        case .PRICE_ABOVE: L("alarm_condition_above")
        case .PRICE_BELOW: L("alarm_condition_below")
        case .CHANGE_PERCENT_UP: L("alarm_condition_up_percent")
        case .CHANGE_PERCENT_DOWN: L("alarm_condition_down_percent")
        case .MOVE_PERCENT_WINDOW: L("alarm_condition_move_percent")
        case .VOLUME_SPIKE: L("alarm_condition_volume_spike")
        case .NEAR_HIGH: L("alarm_condition_near_high")
        case .NEAR_LOW: L("alarm_condition_near_low")
        case .FUNDING_ABOVE: L("alarm_condition_funding_above")
        case .FUNDING_BELOW: L("alarm_condition_funding_below")
        case .OI_UP: L("alarm_condition_oi_up")
        case .OI_DOWN: L("alarm_condition_oi_down")
        }
    }

    static func describe(_ alarm: Alarm) -> String {
        let name = conditionName(alarm.condition)
        // «Volumen-Spike ×3»
        if alarm.condition == .VOLUME_SPIKE {
            return "\(name) ×\(factor(alarm.threshold))"
        }
        // «Funding über 0,05 %»
        if alarm.condition.isFunding {
            return "\(name) " + fundingPercent(alarm.threshold)
        }
        // «Open Interest steigt 10 % in 4 Std.»
        if alarm.condition.isOpenInterest {
            return "\(name) \(percent(alarm.threshold)) "
                + L("alarm_window_hours", count: DerivativesAlarm.oiWindowHours(alarm.windowHours))
        }
        // Nur neue Hochs/Tiefs (Abstand 0): «Neues 30-Tage-Hoch»
        if alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold) {
            return newExtremeLabel(high: alarm.condition == .NEAR_HIGH, days: alarm.windowHours)
        }
        // «Nahe am Hoch 2 % · 30 Tage» — Prozent im Format der Sprache wie Chip und Editor (wie Android)
        if alarm.condition.isNearExtreme {
            return "\(name) " + percent(alarm.threshold) + " · "
                + windowLabel(NearExtreme.windowDays(alarm.windowHours))
        }
        let value: String
        if alarm.condition.isPercent {
            value = percent(alarm.threshold)
        } else if let currency = alarm.convertCurrency {
            // Schwellwert in eigener Währung: «Über 60’000 CHF» — genau wie gespeichert (1.0005 bleibt 1.0005)
            value = PriceFormat.thresholdWithCurrency(alarm.threshold, currency)
        } else {
            value = PriceFormat.threshold(alarm.threshold)
        }
        if alarm.condition == .MOVE_PERCENT_WINDOW {
            return "\(name) \(value) " + L("alarm_window_hours", count: alarm.windowHours)
        }
        return "\(name) \(value)"
    }

    /// Alarm als Satz, z. B. «Sag mir Bescheid, wenn BTC über 70’000 USDT steigt.» —
    /// `base` = Basis-Symbol, `currency` = Währung des Schwellwerts (Kursalarme),
    /// `windowHours` nur für «bewegt sich um x % in y Stunden». Ohne gültigen Wert
    /// der Hinweis `alarm_sentence_incomplete`.
    static func sentence(condition: AlarmCondition, base: String, threshold: Double?,
                         currency: String, windowHours: Int) -> String {
        // Nur neue Hochs/Tiefs: «Sag mir Bescheid, wenn BTC ein neues 30-Tage-Hoch erreicht.»
        if condition.isNearExtreme, let threshold, NearExtreme.isNewOnly(threshold) {
            guard !base.trimmingCharacters(in: .whitespaces).isEmpty else { return L("alarm_sentence_incomplete") }
            return L("alarm_sentence_new_extreme", base, extremeLabel(high: condition == .NEAR_HIGH, days: windowHours))
        }
        // Funding: mit Vorzeichen, auch 0 («unter 0 %» = Funding wird negativ)
        if condition.isFunding {
            guard let threshold, DerivativesAlarm.isValidThreshold(condition, threshold),
                  !base.trimmingCharacters(in: .whitespaces).isEmpty else { return L("alarm_sentence_incomplete") }
            return L(condition == .FUNDING_ABOVE ? "alarm_sentence_funding_above" : "alarm_sentence_funding_below",
                     base, fundingPercent(threshold))
        }
        guard let threshold, threshold > 0, threshold.isFinite else { return L("alarm_sentence_incomplete") }
        switch condition {
        case .PRICE_ABOVE:
            return L("alarm_sentence_above", base, PriceFormat.thresholdWithCurrency(threshold, currency))
        case .PRICE_BELOW:
            return L("alarm_sentence_below", base, PriceFormat.thresholdWithCurrency(threshold, currency))
        case .CHANGE_PERCENT_UP:
            return L("alarm_sentence_up_percent", base, percent(threshold))
        case .CHANGE_PERCENT_DOWN:
            return L("alarm_sentence_down_percent", base, percent(threshold))
        case .MOVE_PERCENT_WINDOW:
            return L("alarm_sentence_move_percent", count: max(windowHours, 1), base, percent(threshold), max(windowHours, 1))
        case .VOLUME_SPIKE:
            return L("alarm_sentence_volume", base, factor(threshold))
        case .NEAR_HIGH:
            // «Sag mir Bescheid, wenn BTC höchstens 2 % unter dem 30-Tage-Hoch liegt.» (windowHours = Tage)
            return L("alarm_sentence_near_high", base, percent(threshold), extremeLabel(high: true, days: windowHours))
        case .NEAR_LOW:
            return L("alarm_sentence_near_low", base, percent(threshold), extremeLabel(high: false, days: windowHours))
        case .FUNDING_ABOVE, .FUNDING_BELOW:
            // Oben behandelt (Vorzeichen erlaubt)
            return L("alarm_sentence_incomplete")
        case .OI_UP, .OI_DOWN:
            // «Sag mir Bescheid, wenn das Open Interest von BTC innerhalb von 4 Stunden um 10 % steigt.»
            let hours = DerivativesAlarm.oiWindowHours(windowHours)
            return L(condition == .OI_UP ? "alarm_sentence_oi_up" : "alarm_sentence_oi_down",
                     count: hours, base, percent(threshold), hours)
        }
    }

    /// «30 Tage», «90 Tage», «1 Jahr» — Zeitraum des Alarms «Nahe am Hoch/Tief».
    static func windowLabel(_ days: Int) -> String {
        days >= 365 ? L("alarm_near_window_year") : L("alarm_near_window_days", count: days)
    }

    /// «Neues 30-Tage-Hoch», «Neues Jahrestief» — Titel und Schnell-Alarm.
    static func newExtremeLabel(high: Bool, days: Int) -> String {
        L("alarm_new_extreme", extremeLabel(high: high, days: days))
    }

    /// «30-Tage-Hoch», «Jahrestief» … für Satz und Benachrichtigung.
    static func extremeLabel(high: Bool, days: Int) -> String {
        switch NearExtreme.windowDays(days) {
        case 90: return L(high ? "alarm_near_extreme_high_90" : "alarm_near_extreme_low_90")
        case 365: return L(high ? "alarm_near_extreme_high_365" : "alarm_near_extreme_low_365")
        default: return L(high ? "alarm_near_extreme_high_30" : "alarm_near_extreme_low_30")
        }
    }

    /// Benachrichtigung «BTC ist 1,6 % unter dem 30-Tage-Hoch (98’450 / 100’050)» bzw.
    /// «Neues 30-Tage-Hoch für BTC: 101’200 USDT (bisher 100’050)». Kurse in `currency`.
    static func nearExtremeText(_ alarm: Alarm, symbol: String, currency: String, price: Double,
                                newExtreme: Bool, distancePercent: Double, extreme: Double) -> String {
        let high = alarm.condition == .NEAR_HIGH
        let label = extremeLabel(high: high, days: alarm.windowHours)
        if newExtreme {
            return L("alarm_near_notification_new", label, symbol,
                     PriceFormat.priceWithCurrency(price, currency), PriceFormat.price(extreme))
        }
        // Eine Nachkommastelle (1.63 → 1.6), damit die Meldung ruhig bleibt
        let distance = percent((distancePercent * 10).rounded() / 10)
        return L(high ? "alarm_near_notification_high" : "alarm_near_notification_low",
                 symbol, distance, label, PriceFormat.price(price), PriceFormat.price(extreme))
    }

    /// Satz zu einem gespeicherten Alarm; Kursalarme in seiner Währung, sonst in der Quote.
    static func sentence(_ alarm: Alarm, base: String, quote: String) -> String {
        sentence(condition: alarm.condition, base: base, threshold: alarm.threshold,
                 currency: alarm.convertCurrency ?? quote, windowHours: alarm.windowHours)
    }

    /// Prozent nach Sprache: «5%» (en), «5 %» (de), höchstens zwei Nachkommastellen. Über `PercentText`
    /// statt `NumberFormatter.percent`, damit iOS und Android trotz unterschiedlicher ICU-Stände gleich schreiben
    /// (wie `AlarmSentence.percent`) — mit Tausendertrennung wie dort («1,000%»; Open-Interest-Schwellen bis 1000 %).
    static func percent(_ value: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        PercentText.unsigned(value, decimals: 2, minDecimals: 0, locale: locale, grouping: true)
    }

    /// Funding Rate: «0,05 %», «−0,0125 %» — bis vier Nachkommastellen, echtes Minus (U+2212) vor dem Ganzen; gerundet 0
    /// ohne Vorzeichen, nie «−0 %» (wie `AlarmSentence.fundingPercent`).
    static func fundingPercent(_ value: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        // positive Funding ohne «+», nur das Minus
        let text = PercentText.signed(value, decimals: 4, minDecimals: 0, locale: locale, grouping: true)
        return text.hasPrefix("+") ? String(text.dropFirst()) : text
    }

    /// Veränderung mit Vorzeichen und einer Nachkommastelle: «+12,3 %», «−4 %» (echtes Minus U+2212).
    static func signedPercent(_ value: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        PercentText.signed(value, decimals: 1, minDecimals: 0, locale: locale, grouping: true)
    }

    /// Gemessener Wert in der Mitteilung eines Funding- bzw. Open-Interest-Alarms:
    /// Funding «0,061 %», Open-Interest-Veränderung «+12,3 %» — wie `AlarmTexts.derivativesValue`.
    static func derivativesValue(_ condition: AlarmCondition, _ value: Double) -> String {
        condition.isFunding ? fundingPercent(value) : signedPercent(value)
    }

    /// Teile mit « · » an `body` hängen; leere fallen weg — wie Android `AlarmTextNote.join`.
    static func join(_ body: String, _ parts: [String?]) -> String {
        ([body] + parts.compactMap { $0 }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
            .joined(separator: " · ")
    }

    /// Zusatz am Alarmtext («… · Binance (Kerzen)», «… · USDT = 1 angenommen»): `candleSource` = Börse fremder
    /// Stundenkerzen beim Volumen-Spike, `usdtAssumed` = Schwelle in anderer Währung mit USDT = 1 umgerechnet. Wie Android.
    static func withNote(_ body: String, candleSource: String? = nil, usdtAssumed: Bool = false) -> String {
        join(body, [candleSource.map { L("why_src_candles", $0) }, usdtAssumed ? L("portfolio_usdt_assumed") : nil])
    }

    static func factor(_ value: Double) -> String {
        guard value.isFinite else { return LocaleNumbers.integer(0) }
        return LocaleNumbers.decimal(value, maxDecimals: 1, minDecimals: 0)
    }
}
