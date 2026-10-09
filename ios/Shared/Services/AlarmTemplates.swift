import Foundation

/// «Schnell-Alarme» im Alarm-Editor und der einfache Modus («Wenn BTC über 60’000 geht») —
/// wie `AlarmTemplates.kt`.
///
/// Ein Antippen legt den Alarm sofort an:
///  - ±1 % / ±5 %: Prozentalarm ab dem Kurs von jetzt (CHANGE_PERCENT_UP/DOWN mit
///    `referencePrice` = aktueller Kurs) — «bewegt sich um x % ab jetzt», einmalig.
///  - Neues 30-Tage-Hoch/-Tief: «Nahe am Hoch/Tief» mit Abstand 0 = nur neue Hochs/Tiefs
///    (`NearExtreme.newOnlyDistance`), Zeitraum 30 Tage.
///  - Volumen ×3: Volumen-Spike mit Faktor 3.
enum AlarmTemplates {

    /// Die Vorlagen in der Reihenfolge der Chips.
    enum Template: String, CaseIterable, Sendable {
        case UP_1, UP_5, DOWN_1, DOWN_5, NEW_HIGH_30, NEW_LOW_30, VOLUME_X3

        /// Vorzeichenbehafteter Prozentwert (±1, ±5) bei Prozent-Vorlagen, sonst nil.
        var percent: Double? {
            switch self {
            case .UP_1: 1
            case .UP_5: 5
            case .DOWN_1: -1
            case .DOWN_5: -5
            default: nil
            }
        }

        /// Braucht Tageskerzen (Hoch/Tief der letzten 30 Tage).
        var needsDailyRange: Bool { self == .NEW_HIGH_30 || self == .NEW_LOW_30 }

        /// Braucht Stundenkerzen mit Volumen.
        var needsHourlyVolume: Bool { self == .VOLUME_X3 }
    }

    /// Zeitraum der Vorlagen «Neues 30-Tage-Hoch/-Tief» in Tagen.
    static let newExtremeWindowDays = 30

    /// Faktor der Vorlage «Volumen ×3».
    static let volumeFactor: Double = 3

    /// Was ein Antippen speichert (übrige Felder wie beim normalen Anlegen).
    struct Definition: Equatable, Sendable {
        var condition: AlarmCondition
        var threshold: Double
        /// Bei NEAR_HIGH/NEAR_LOW der Zeitraum in Tagen, sonst 1 (unbenutzt).
        var windowHours: Int
        /// Bezugskurs der Prozentalarme (= Kurs beim Anlegen), sonst nil.
        var referencePrice: Double?
        var repeating = false
    }

    /// Sichtbare Vorlagen: Prozent-Vorlagen brauchen einen Kurs, Hoch/Tief Tageskerzen,
    /// Volumen Stundenkerzen (z. B. bei DEX-Paaren ohne Kerzenquelle ausgeblendet).
    static func available(hasPrice: Bool, hasDailyRange: Bool, hasHourlyVolume: Bool) -> [Template] {
        Template.allCases.filter { t in
            if t.percent != nil { return hasPrice }
            if t.needsDailyRange { return hasDailyRange }
            if t.needsHourlyVolume { return hasHourlyVolume }
            return false
        }
    }

    /// Alarm zur Vorlage; nil, wenn eine Prozent-Vorlage keinen gültigen Kurs hat.
    static func definition(_ template: Template, currentPrice: Double?) -> Definition? {
        if let percent = template.percent {
            guard let price = currentPrice, price.isFinite, price > 0 else { return nil }
            return Definition(condition: percent > 0 ? .CHANGE_PERCENT_UP : .CHANGE_PERCENT_DOWN,
                              threshold: abs(percent), windowHours: 1, referencePrice: price)
        }
        switch template {
        case .NEW_HIGH_30:
            return Definition(condition: .NEAR_HIGH, threshold: NearExtreme.newOnlyDistance,
                              windowHours: newExtremeWindowDays, referencePrice: nil)
        case .NEW_LOW_30:
            return Definition(condition: .NEAR_LOW, threshold: NearExtreme.newOnlyDistance,
                              windowHours: newExtremeWindowDays, referencePrice: nil)
        case .VOLUME_X3:
            return Definition(condition: .VOLUME_SPIKE, threshold: volumeFactor, windowHours: 1, referencePrice: nil)
        default:
            return nil
        }
    }

    private static let suggestedDigits = 3

    /// Vorschlag fürs Betragsfeld im einfachen Modus: aktueller Kurs auf drei gültige Stellen
    /// gerundet (63’412.57 → 63400, 1.2345 → 1.23, 0.00012345 → 0.000123), ohne Exponent und
    /// Tausendertrennung, mit dem Dezimalzeichen der Region — liest sich über `ThresholdParser`
    /// eindeutig zurück. Leer ohne gültigen Kurs. Kaufmännisch gerundet wie Android (HALF_UP
    /// auf der kürzesten Dezimaldarstellung des Kurses).
    static func suggestedThresholdText(_ price: Double?, decimalSeparator: Character = ".") -> String {
        guard let value = price, value.isFinite, value > 0 else { return "" }
        let exponent = Int(floor(log10(value)))
        let scale = suggestedDigits - 1 - exponent
        let number = NSDecimalNumber(string: "\(value)", locale: Locale(identifier: "en_US_POSIX"))
        guard number != NSDecimalNumber.notANumber else { return "" }
        let handler = NSDecimalNumberHandler(roundingMode: .plain, scale: Int16(scale), raiseOnExactness: false,
                                             raiseOnOverflow: false, raiseOnUnderflow: false, raiseOnDivideByZero: false)
        let rounded = number.rounding(accordingToBehavior: handler)
        let text = rounded.description(withLocale: Locale(identifier: "en_US_POSIX"))
        return text.replacingOccurrences(of: ".", with: String(ThresholdParser.normalized(decimalSeparator)))
    }

    /// Öffnet der Editor gleich mit «Erweitert»? Ja bei allem, was der einfache Satz nicht
    /// zeigt: andere Bedingungen als Kursmarken oder ein Schwellwert in eigener Währung.
    static func opensAdvanced(condition: AlarmCondition, currency: String?) -> Bool {
        let hasCurrency = !(currency ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        return !condition.isPriceThreshold || hasCurrency
    }
}
