import SwiftUI

/// Grund für einen ungültigen Schwellwert (`AlarmDraft.thresholdProblem`), wie Android `ThresholdProblem`.
enum ThresholdProblem: Equatable {
    case EMPTY, INVALID, FUNDING_RANGE, OI_RANGE, PERCENT_RANGE
}

extension AlarmCondition {
    /// Fallende Prozent-Alarme («fällt um x %», «Open Interest fällt um x %»): mehr als 100 % kann nichts fallen — ein
    /// solcher Alarm löste nie aus (seit Build 102 abgelehnt). Seit Build 103 auch genau 100 % (Wert 0 wird nie
    /// gemeldet): der Schwellwert muss kleiner als `maxFallPercent` sein. Wie Android `isFallingPercent`.
    var isFallingPercent: Bool { self == .CHANGE_PERCENT_DOWN || self == .OI_DOWN }

    /// Obergrenze (ausschliesslich, seit Build 103) eines fallenden Prozent-Alarms.
    static let maxFallPercent = 100.0
}

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
    /// Zuletzt vorgeschlagener Betrag (einfacher Modus); nil = keiner. Steht er unverändert im
    /// Feld, folgt er beim Wechsel «über» ↔ «unter» der neuen Richtung — wie Android.
    var suggestedText: String? = nil
    /// Neuer Alarm: Feld leer, der aktuelle Kurs nur als Platzhalter (`blankFor`) — so
    /// fragt «Löst sofort aus» nur, wenn der Nutzer selbst eine überschrittene Marke tippt. Fällt mit
    /// der ersten Eingabe weg. Wie Android.
    var blankStart = false

    static let windowChoices = [1, 4, 12, 24]
    /// Standard-Zeitfenster für «bewegt sich um x % in y Stunden».
    static let defaultWindowHours = 4
    /// Wählbare Faktoren für den Volumen-Spike.
    static let volumeFactors: [Double] = [2, 3, 5, 10]
    static let defaultVolumeFactor: Double = 3

    /// Faktor als Text ohne «.0»: 3 → "3".
    static func formatFactor(_ value: Double) -> String {
        plain(value)
    }

    /// Bedingung wechseln. Beim Volumen-Spike ist der Wert ein Faktor (×2…×10),
    /// deshalb beim Wechsel von/zu dieser Bedingung den Wert neu setzen.
    func withCondition(_ newCondition: AlarmCondition) -> AlarmDraft {
        guard newCondition != condition else { return self }
        var copy = switchCondition(newCondition)
        // Zwischen Kursmarke und Prozent wechseln: ein Kurs ist kein Prozentwert (und umgekehrt).
        // Zurück zur Kursmarke wieder mit dem aktuellen Kurs als Vorschlag (wie beim Anlegen).
        if condition.isPriceThreshold && newCondition.isPriceThreshold {
            // «über» ↔ «unter»: unveränderter Vorschlag folgt der Richtung (5 % über bzw. unter dem Kurs)
            if isUntouchedSuggestion && priceHint != nil { copy.suggest(for: newCondition) }
        } else if condition.isPriceThreshold != newCondition.isPriceThreshold {
            if newCondition.isPriceThreshold {
                copy.suggest(for: newCondition)
            } else if newCondition.isPercent {
                copy.thresholdText = ""
            }
        }
        return copy
    }

    /// Steht noch der Vorschlag im Feld (nicht angetippt)?
    var isUntouchedSuggestion: Bool {
        guard let suggestedText, !suggestedText.isEmpty else { return false }
        return thresholdText == suggestedText
    }

    /// Vorschlag für `condition` aus dem aktuellen Kurs in der Währung des Schwellwerts (`priceHint`).
    private mutating func suggest(for condition: AlarmCondition) {
        let text = AlarmTemplates.suggestedThresholdText(
            priceHint, condition: condition, decimalSeparator: ThresholdParser.localeDecimalSeparator)
        thresholdText = text
        suggestedText = text
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
        if exceedsFall { return nil }
        if condition.isOpenInterest {
            let value = ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
            return DerivativesAlarm.isValidThreshold(condition, value) ? value : nil
        }
        return ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator,
                              priceHint: condition.isPriceThreshold ? priceHint : nil)
    }

    var isValid: Bool { threshold != nil }

    /// Fallender Prozent-Alarm mit gültiger Zahl ab `AlarmCondition.maxFallPercent` (seit Build 103 auch genau 100)?
    private var exceedsFall: Bool {
        guard condition.isFallingPercent,
              let value = ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator)
        else { return false }
        return value >= AlarmCondition.maxFallPercent
    }

    /// Warum der Knopf gesperrt ist (nil = gültig) — für den Fehlertext unter dem Feld. Folgt genau
    /// `threshold`: leer; Funding ausserhalb ±`DerivativesAlarm.maxFundingPercent` bzw. keine Zahl;
    /// Open Interest über dem Höchstwert; fallend ab 100 %; sonst keine Zahl grösser als 0. Wie Android.
    var thresholdProblem: ThresholdProblem? {
        if isValid { return nil }
        if thresholdText.trimmingCharacters(in: .whitespaces).isEmpty { return .EMPTY }
        if condition.isFunding { return .FUNDING_RANGE }
        if exceedsFall { return .PERCENT_RANGE }
        if condition.isOpenInterest,
           ThresholdParser.parse(thresholdText, decimalSeparator: ThresholdParser.localeDecimalSeparator) != nil {
            return .OI_RANGE
        }
        return .INVALID
    }

    /// Fehlertext, der unter dem Feld steht: wie `thresholdProblem`, nur «leer» nicht, solange im
    /// neuen Alarm noch nichts getippt wurde (`blankStart`). Der Knopf bleibt trotzdem gesperrt. Wie Android.
    var visibleProblem: ThresholdProblem? {
        let problem = thresholdProblem
        if problem == .EMPTY && blankStart { return nil }
        return problem
    }

    /// Hinweis «Betrag eingeben» unter dem gesperrten Knopf: nur solange das Feld eines neuen Alarms noch leer ist
    /// (der graue Platzhalter wirkt sonst wie ein Wert, und ohne Fehlertext bliebe offen, warum der Knopf gesperrt
    /// ist). Wie Android `showsEnterAmountHint`.
    var showsEnterAmountHint: Bool { thresholdProblem == .EMPTY && visibleProblem == nil }

    /// Satzkarte («Sag mir Bescheid, wenn …») zeigen? Nicht, solange der Hinweis «Wert eingeben» unter dem Knopf steht
    /// (`showsEnterAmountHint`) — sonst sagten Karte und Hinweis bei leerem Feld zweimal dasselbe. Wie Android.
    var showsSentence: Bool { !showsEnterAmountHint }

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

    /// Gespeicherter Schwellwert fürs Eingabefeld: ohne Exponent, Tausendertrennung und
    /// Stellenbegrenzung (1.2345e-10 bleibt «0.00000000012345»), Dezimalzeichen der Region —
    /// wie `AlarmsViewModel.formatThreshold` (Android, `toPlainString`).
    private static func plain(_ value: Double) -> String {
        DecimalText.plain(value)
            .replacingOccurrences(of: ".", with: String(ThresholdParser.localeDecimalSeparator))
    }

    /// Neuer Alarm im einfachen Modus: «Wenn BTC über [Betrag] geht», Betrag = 5 % über dem
    /// aktuellen Kurs, auf drei gültige Stellen (`AlarmTemplates.suggestedThresholdText`).
    /// Neuer Alarm mit leerem Feld; der aktuelle Kurs steht nur als Platzhalter im Feld. Wie Android `blankFor`.
    static func blankFor() -> AlarmDraft {
        AlarmDraft(blankStart: true)
    }

    static func newFor(lastPrice: Double?) -> AlarmDraft {
        let text = AlarmTemplates.suggestedThresholdText(
            lastPrice, condition: .PRICE_ABOVE, decimalSeparator: ThresholdParser.localeDecimalSeparator)
        return AlarmDraft(thresholdText: text, suggestedText: text)
    }

    static func from(_ alarm: Alarm) -> AlarmDraft {
        // Nur neue Hochs/Tiefs: Abstand 0 → Schalter an, Feld mit dem Standardabstand
        let newOnly = alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold)
        let shown = newOnly ? NearExtreme.defaultDistancePercent : alarm.threshold
        return AlarmDraft(
            id: alarm.id,
            condition: alarm.condition,
            thresholdText: plain(shown),
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
