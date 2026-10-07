import Foundation

/// Entscheidet, ob ein Alarm auslöst — wie `AlarmEvaluator.kt`.
enum AlarmEvaluator {

    static func shouldTrigger(alarm: Alarm, price: Double, previousPrice: Double?, now: Int64, cooldownMinutes: Int) -> Bool {
        guard alarm.enabled, price > 0 else { return false }

        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return false }
        }

        switch alarm.condition {
        // Kursmarke: nur scharf (referenceAt == 0) — also beim Überschreiten, nicht bei jeder
        // Aktualisierung, solange der Kurs jenseits der Marke bleibt. Siehe `shouldRearmLevel`.
        case .PRICE_ABOVE:
            return isLevelArmed(alarm) && price >= alarm.threshold
        case .PRICE_BELOW:
            return isLevelArmed(alarm) && price <= alarm.threshold
        case .CHANGE_PERCENT_UP:
            guard let change = changePercent(alarm: alarm, price: price, previousPrice: previousPrice) else { return false }
            return change >= alarm.threshold
        case .CHANGE_PERCENT_DOWN:
            guard let change = changePercent(alarm: alarm, price: price, previousPrice: previousPrice) else { return false }
            return change <= -alarm.threshold
        case .MOVE_PERCENT_WINDOW:
            if needsWindowReset(alarm: alarm, now: now) { return false }
            guard let reference = alarm.referencePrice, reference > 0 else { return false }
            return abs((price - reference) / reference * 100) >= alarm.threshold
        case .VOLUME_SPIKE:
            // Braucht Volumendaten, siehe `shouldTriggerVolumeSpike`.
            return false
        case .NEAR_HIGH, .NEAR_LOW:
            // Braucht Hoch/Tief des Zeitraums, siehe `NearExtreme.decide`.
            return false
        }
    }

    /// Hysterese der Kursalarme: 0,2 % der Marke (wie `AlarmEvaluator.LEVEL_HYSTERESIS`).
    static let levelHysteresis = 0.002

    /// Kursalarm (PRICE_ABOVE/PRICE_BELOW) scharf? Gespeichert in `referenceAt`: 0 = scharf,
    /// > 0 = schon gemeldet (Zeitpunkt), wartet auf die Rückkehr des Kurses auf die andere
    /// Seite. Neu angelegt, bearbeitet, wieder eingeschaltet → 0: liegt der Kurs dann schon
    /// jenseits der Marke, meldet der Alarm genau einmal.
    static func isLevelArmed(_ alarm: Alarm) -> Bool { alarm.referenceAt <= 0 }

    /// Gemeldeten Kursalarm wieder scharf stellen? Erst, wenn der Kurs um die Hysterese
    /// (`levelHysteresis` der Marke) auf die andere Seite zurückgekehrt ist. `price` in der
    /// Währung des Schwellwerts (wie bei `shouldTrigger`).
    static func shouldRearmLevel(alarm: Alarm, price: Double) -> Bool {
        guard alarm.condition.isPriceThreshold, !isLevelArmed(alarm) else { return false }
        guard price.isFinite, price > 0, alarm.threshold > 0 else { return false }
        if alarm.condition == .PRICE_ABOVE {
            return price < alarm.threshold * (1 - levelHysteresis)
        }
        return price > alarm.threshold * (1 + levelHysteresis)
    }

    /// Volumen-Spike: `ratio` = Volumen der letzten abgeschlossenen Stunde geteilt
    /// durch den Schnitt der 24 Stunden davor, `candleOpenTime` = Startzeit dieser
    /// Stundenkerze. Löst je Kerze höchstens einmal aus (gemerkt in `referenceAt`).
    static func shouldTriggerVolumeSpike(alarm: Alarm, ratio: Double?, candleOpenTime: Int64,
                                         now: Int64, cooldownMinutes: Int) -> Bool {
        guard alarm.condition == .VOLUME_SPIKE, alarm.enabled else { return false }
        guard let ratio, ratio.isFinite else { return false }
        guard candleOpenTime > 0, alarm.threshold > 0 else { return false }
        // Dieselbe Kerze wurde schon gemeldet
        if alarm.referenceAt == candleOpenTime { return false }

        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return false }
        }
        return ratio >= alarm.threshold
    }

    /// Bewegungs-Alarm ohne gültiges Fenster: Aufrufer setzt neuen Bezug.
    static func needsWindowReset(alarm: Alarm, now: Int64) -> Bool {
        guard alarm.condition == .MOVE_PERCENT_WINDOW else { return false }
        if alarm.referencePrice == nil || alarm.referenceAt <= 0 { return true }
        return now - alarm.referenceAt > Int64(max(alarm.windowHours, 1)) * 3_600_000
    }

    static func changePercent(alarm: Alarm, price: Double, previousPrice: Double?) -> Double? {
        guard let reference = alarm.referencePrice ?? previousPrice, reference > 0 else { return nil }
        return (price - reference) / reference * 100
    }
}

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
        }
    }

    static func describe(_ alarm: Alarm) -> String {
        let name = conditionName(alarm.condition)
        // «Volumen-Spike ×3»
        if alarm.condition == .VOLUME_SPIKE {
            return "\(name) ×\(factor(alarm.threshold))"
        }
        // «Nahe am Hoch 2.00% · 30 Tage»
        if alarm.condition.isNearExtreme {
            return "\(name) " + String(format: "%.2f%%", locale: Locale.current, alarm.threshold) + " · "
                + windowLabel(NearExtreme.windowDays(alarm.windowHours))
        }
        let value: String
        if alarm.condition.isPercent {
            value = String(format: "%.2f%%", locale: Locale.current, alarm.threshold)
        } else if let currency = alarm.convertCurrency {
            // Schwellwert in eigener Währung: «Über 60’000 CHF»
            value = PriceFormat.priceWithCurrency(alarm.threshold, currency)
        } else {
            value = PriceFormat.price(alarm.threshold)
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
        guard let threshold, threshold > 0, threshold.isFinite else { return L("alarm_sentence_incomplete") }
        switch condition {
        case .PRICE_ABOVE:
            return L("alarm_sentence_above", base, PriceFormat.priceWithCurrency(threshold, currency))
        case .PRICE_BELOW:
            return L("alarm_sentence_below", base, PriceFormat.priceWithCurrency(threshold, currency))
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
        }
    }

    /// «30 Tage», «90 Tage», «1 Jahr» — Zeitraum des Alarms «Nahe am Hoch/Tief».
    static func windowLabel(_ days: Int) -> String {
        days >= 365 ? L("alarm_near_window_year") : L("alarm_near_window_days", count: days)
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

    /// Prozent nach Region: «5%» (en), «5 %» (de).
    static func percent(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .percent
        f.locale = Locale.current
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        return f.string(from: NSNumber(value: value / 100)) ?? String(format: "%.2f%%", value)
    }

    /// Faktor ohne überflüssige Nachkommastellen: 3 → "3", 4.25 → "4.3" (Punkt, wie Android).
    static func factor(_ value: Double) -> String {
        guard value.isFinite else { return "0" }
        let rounded = (value * 10).rounded() / 10
        if abs(rounded) >= 1e15 { return String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), rounded) }
        if rounded.truncatingRemainder(dividingBy: 1) == 0 { return String(Int64(rounded)) }
        return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), rounded)
    }
}

/// Sätze für die Sprachausgabe — wie `SpokenText.kt`.
enum SpokenText {
    static func price(_ watch: Watch, _ price: Double) -> String {
        L("tts_price", marketName(watch), watch.baseAsset, watch.quoteAsset, PriceFormat.spokenPrice(price))
    }

    static func alarm(_ watch: Watch, _ condition: AlarmCondition, _ price: Double) -> String {
        let direction: String
        switch condition {
        case .PRICE_ABOVE, .CHANGE_PERCENT_UP: direction = L("tts_direction_up")
        case .PRICE_BELOW, .CHANGE_PERCENT_DOWN: direction = L("tts_direction_down")
        case .MOVE_PERCENT_WINDOW: direction = L("tts_direction_move")
        case .VOLUME_SPIKE: direction = L("tts_direction_volume_spike")
        case .NEAR_HIGH: direction = L("tts_direction_near_high")
        case .NEAR_LOW: direction = L("tts_direction_near_low")
        }
        return L("tts_alarm", marketName(watch), watch.baseAsset, watch.quoteAsset, direction, PriceFormat.spokenPrice(price))
    }

    private static func marketName(_ watch: Watch) -> String {
        MarketsConfig.market(watch.marketKey)?.ttsName ?? watch.marketName
    }
}
