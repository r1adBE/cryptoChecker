import SwiftUI

/// Eingabezustand des Alarm-Blatts — wie `AlarmDraft`.
struct AlarmDraft: Identifiable, Equatable {
    var id: Int64 = 0
    var condition: AlarmCondition = .PRICE_ABOVE
    var thresholdText: String = ""
    var repeating = false
    var sound = true
    var vibrate = true
    var speak = false
    /// Zeitfenster für «bewegt sich um x % in y Stunden».
    var windowHours = 4
    /// Währung des Schwellwerts bei Kursalarmen; nil = Quote-Währung des Paars.
    var currency: String? = nil
    /// Aktueller Kurs in der Währung des Schwellwerts (nur Kursalarme): entscheidet bei
    /// mehrdeutiger Eingabe wie «60,000», siehe `ThresholdParser`. nil = unbekannt.
    var priceHint: Double? = nil
    /// «Nahe am Hoch/Tief»: nur neue Hochs/Tiefs melden (Abstand 0, Vorlage «Neues 30-Tage-Hoch»).
    /// Das Abstandsfeld behält seinen Wert für den Fall, dass wieder ausgeschaltet wird.
    var newExtremeOnly = false

    static let windowChoices = [1, 4, 12, 24]
    /// Standard-Zeitfenster für «bewegt sich um x % in y Stunden».
    static let defaultWindowHours = 4
    /// Wählbare Faktoren für den Volumen-Spike.
    static let volumeFactors: [Double] = [2, 3, 5, 10]
    static let defaultVolumeFactor: Double = 3

    /// Faktor als Text ohne «.0»: 3 → "3".
    static func formatFactor(_ value: Double) -> String {
        plain.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Bedingung wechseln. Beim Volumen-Spike ist der Wert ein Faktor (×2…×10),
    /// deshalb beim Wechsel von/zu dieser Bedingung den Wert neu setzen.
    func withCondition(_ newCondition: AlarmCondition) -> AlarmDraft {
        guard newCondition != condition else { return self }
        var copy = switchCondition(newCondition)
        // Zwischen Kursmarke und Prozent wechseln: ein Kurs ist kein Prozentwert (und umgekehrt).
        // Zurück zur Kursmarke wieder mit dem aktuellen Kurs als Vorschlag (wie beim Anlegen).
        if condition.isPriceThreshold != newCondition.isPriceThreshold {
            if newCondition.isPriceThreshold {
                copy.thresholdText = AlarmTemplates.suggestedThresholdText(
                    priceHint, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            } else if newCondition.isPercent {
                copy.thresholdText = ""
            }
        }
        return copy
    }

    private func switchCondition(_ newCondition: AlarmCondition) -> AlarmDraft {
        var copy = self
        if newCondition.isFunding {
            // Funding: Schwelle in % mit Vorzeichen (Vorschlag 0,05 %)
            if !condition.isFunding { copy.thresholdText = Self.formatFactor(DerivativesAlarm.defaultFundingPercent) }
            if condition.isNearExtreme { copy.windowHours = Self.defaultWindowHours }
        } else if newCondition.isOpenInterest {
            // Open Interest: Veränderung in % (Vorschlag 10 %), Fenster 1, 4 oder 24 Stunden
            if !condition.isOpenInterest { copy.thresholdText = Self.formatFactor(DerivativesAlarm.defaultOiPercent) }
            copy.windowHours = DerivativesAlarm.oiWindowHours(
                condition.isNearExtreme ? DerivativesAlarm.defaultOiWindowHours : windowHours)
        } else if newCondition == .VOLUME_SPIKE {
            copy.thresholdText = Self.formatFactor(Self.defaultVolumeFactor)
        } else if newCondition.isNearExtreme {
            // Nahe am Hoch/Tief: Abstand in % (Standard 2 %), Zeitraum in Tagen (Standard 30)
            if !condition.isPercent && !condition.isNearExtreme {
                copy.thresholdText = Self.formatFactor(NearExtreme.defaultDistancePercent)
            }
            if !condition.isNearExtreme { copy.windowHours = NearExtreme.defaultWindowDays }
        } else if condition.isNearExtreme {
            if !newCondition.isPercent { copy.thresholdText = "" }
            copy.windowHours = Self.defaultWindowHours
        } else if condition == .VOLUME_SPIKE || condition.isDerivatives {
            // Faktor bzw. Funding/Open Interest passen nicht zur neuen Bedingung
            copy.thresholdText = ""
        }
        copy.condition = newCondition
        return copy
    }

    /// Funding: Vorzeichen der Eingabe wechseln («0,01» ↔ «-0,01») — die Zifferntastatur hat kein Minus.
    func withToggledSign() -> AlarmDraft {
        var copy = self
        let text = thresholdText.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("-") || text.hasPrefix("\u{2212}") {
            copy.thresholdText = String(text.dropFirst()).trimmingCharacters(in: .whitespaces)
        } else if text.hasPrefix("+") {
            copy.thresholdText = "-" + String(text.dropFirst()).trimmingCharacters(in: .whitespaces)
        } else {
            copy.thresholdText = "-" + text
        }
        return copy
    }

    /// Gelesener Schwellwert (Tausendertrennung, Dezimalzeichen der Region; siehe `ThresholdParser`);
    /// nur Werte > 0 — ausser Funding (mit Vorzeichen, auch 0; `DerivativesAlarm.parseFunding`).
    var threshold: Double? {
        if condition.isNearExtreme && newExtremeOnly { return NearExtreme.newOnlyDistance }
        if condition.isFunding {
            return DerivativesAlarm.parseFunding(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
        }
        if condition.isOpenInterest {
            let value = ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            return DerivativesAlarm.isValidThreshold(condition, value) ? value : nil
        }
        return ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator,
                              priceHint: condition.isPriceThreshold ? priceHint : nil)
    }

    var isValid: Bool { threshold != nil }

    /// Währung des Schwellwerts wechseln (nil = Quote). Ist der Faktor Quote → `other`
    /// bekannt, wird ein bereits getippter Wert mit umgerechnet — wie `withCurrency` in Android.
    func withCurrency(_ newCurrency: String?, other: String, rate: Double?) -> AlarmDraft {
        guard newCurrency != currency else { return self }
        var copy = self
        copy.currency = newCurrency
        if let value = threshold, let rate, rate > 0, rate.isFinite {
            var converted: Double?
            if currency == nil && newCurrency == other { converted = value * rate }
            if currency == other && newCurrency == nil { converted = value / rate }
            if let converted, let text = Self.significant.string(from: NSNumber(value: converted)) {
                copy.thresholdText = text
            }
        }
        return copy
    }

    /// Umgerechneter Wert fürs Eingabefeld: sechs gültige Stellen, ohne Tausendertrennung,
    /// Dezimalzeichen der Region (liest sich so eindeutig zurück, siehe `ThresholdParser`).
    private static let significant: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.numberStyle = .decimal
        f.decimalSeparator = String(ThresholdParser.localeDecimalSeparator)
        f.usesGroupingSeparator = false
        f.usesSignificantDigits = true
        f.maximumSignificantDigits = 6
        return f
    }()

    private static let plain: NumberFormatter = {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.numberStyle = .decimal
        f.decimalSeparator = String(ThresholdParser.localeDecimalSeparator)
        f.usesGroupingSeparator = false
        f.maximumFractionDigits = 10
        return f
    }()

    /// Neuer Alarm im einfachen Modus: «Wenn BTC über [Kurs] geht», Betrag = aktueller Kurs
    /// auf drei gültige Stellen (`AlarmTemplates.suggestedThresholdText`).
    static func newFor(lastPrice: Double?) -> AlarmDraft {
        AlarmDraft(thresholdText: AlarmTemplates.suggestedThresholdText(
            lastPrice, decimalSeparator: ThresholdParser.localeDecimalSeparator))
    }

    static func from(_ alarm: Alarm) -> AlarmDraft {
        // Nur neue Hochs/Tiefs: Abstand 0 → Schalter an, Feld mit dem Standardabstand
        let newOnly = alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold)
        let shown = newOnly ? NearExtreme.defaultDistancePercent : alarm.threshold
        return AlarmDraft(
            id: alarm.id,
            condition: alarm.condition,
            thresholdText: plain.string(from: NSNumber(value: shown)) ?? String(shown),
            repeating: alarm.repeating,
            sound: alarm.sound,
            vibrate: alarm.vibrate,
            speak: alarm.speak,
            windowHours: alarm.condition.isNearExtreme ? NearExtreme.windowDays(alarm.windowHours)
                : (alarm.condition.isOpenInterest ? DerivativesAlarm.oiWindowHours(alarm.windowHours) : alarm.windowHours),
            currency: alarm.currency,
            newExtremeOnly: newOnly
        )
    }
}

/// Symbol je Alarmbedingung.
enum AlarmStyle {
    static func symbol(_ condition: AlarmCondition) -> String {
        switch condition {
        case .PRICE_ABOVE: "arrow.up.to.line"
        case .PRICE_BELOW: "arrow.down.to.line"
        case .CHANGE_PERCENT_UP: "chart.line.uptrend.xyaxis"
        case .CHANGE_PERCENT_DOWN: "chart.line.downtrend.xyaxis"
        case .MOVE_PERCENT_WINDOW: "arrow.up.arrow.down"
        case .VOLUME_SPIKE: "chart.bar.fill"
        case .NEAR_HIGH: "arrowtriangle.up.circle"
        case .NEAR_LOW: "arrowtriangle.down.circle"
        case .FUNDING_ABOVE: "arrow.up.circle"
        case .FUNDING_BELOW: "arrow.down.circle"
        case .OI_UP: "chart.bar.xaxis.ascending"
        case .OI_DOWN: "chart.bar.xaxis.descending"
        }
    }

    /// Beschreibung mit Gegenwert nur bei Kursalarmen — wie in Android. Hat der Alarm
    /// eine eigene Währung, steht sie schon in der Beschreibung.
    static func title(_ alarm: Alarm, quote: String) -> String {
        let showQuote = alarm.condition.isPriceThreshold && alarm.convertCurrency == nil && !quote.isEmpty
        return AlarmTexts.describe(alarm) + (showQuote ? " \(quote)" : "")
    }
}
