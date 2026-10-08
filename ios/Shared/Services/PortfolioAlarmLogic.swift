import Foundation

/// Art eines Portfolio-Alarms («Portfolio-Wert»); Rohwerte = Kotlin-Enum-Namen (Sicherung).
enum PortfolioAlarmKind: String, Codable, CaseIterable, Sendable {
    /// Gesamtwert erreicht oder übersteigt den Betrag (in der Währung des Alarms).
    case VALUE_ABOVE
    /// Gesamtwert erreicht oder unterschreitet den Betrag.
    case VALUE_BELOW
    /// Veränderung «heute» (gewählte %-Basis, wie im Portfolio) mindestens +x %.
    case CHANGE_UP
    /// Veränderung «heute» höchstens −x %.
    case CHANGE_DOWN

    /// Schwellwert ist ein Betrag (sonst Prozent).
    var isValue: Bool { self == .VALUE_ABOVE || self == .VALUE_BELOW }
}

/// Ein Alarm «Portfolio-Wert» — entspricht `PortfolioAlarmEntity` (Android). Gespeichert in
/// `SharedStorage.Snapshot.portfolioAlarms` (optional: ältere Dateien haben keine).
struct PortfolioAlarm: Codable, Identifiable, Hashable, Sendable {
    var id: Int64
    var kind: PortfolioAlarmKind
    /// Betrag (VALUE_*) in `currency` oder Prozent (CHANGE_*), immer positiv.
    var threshold: Double
    /// Währung des Betrags (Umrechnungswährung beim Anlegen); bei Prozent nil.
    var currency: String?
    var enabled: Bool = true
    /// false = schaltet sich nach dem Auslösen ab.
    var repeating: Bool = false
    /// 0 = scharf, > 0 = gemeldet (Zeitpunkt), bis der Wert hinter die Marke zurückkehrt.
    var referenceAt: Int64 = 0
    var lastTriggeredAt: Int64 = 0
    /// Gemessener Wert beim letzten Auslösen (Betrag bzw. Prozent).
    var lastTriggeredValue: Double?

    init(id: Int64, kind: PortfolioAlarmKind, threshold: Double, currency: String?, enabled: Bool = true,
         repeating: Bool = false, referenceAt: Int64 = 0, lastTriggeredAt: Int64 = 0, lastTriggeredValue: Double? = nil) {
        self.id = id
        self.kind = kind
        self.threshold = threshold
        self.currency = currency
        self.enabled = enabled
        self.repeating = repeating
        self.referenceAt = referenceAt
        self.lastTriggeredAt = lastTriggeredAt
        self.lastTriggeredValue = lastTriggeredValue
    }

    // Fehlende Felder → Standard (tolerant wie `Alarm`)
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        kind = try c.decode(PortfolioAlarmKind.self, forKey: .kind)
        threshold = try c.decode(Double.self, forKey: .threshold)
        currency = try? c.decodeIfPresent(String.self, forKey: .currency)
        enabled = (try? c.decodeIfPresent(Bool.self, forKey: .enabled)) ?? true
        repeating = (try? c.decodeIfPresent(Bool.self, forKey: .repeating)) ?? false
        referenceAt = (try? c.decodeIfPresent(Int64.self, forKey: .referenceAt)) ?? 0
        lastTriggeredAt = (try? c.decodeIfPresent(Int64.self, forKey: .lastTriggeredAt)) ?? 0
        lastTriggeredValue = try? c.decodeIfPresent(Double.self, forKey: .lastTriggeredValue)
    }
}

/// Stand des Portfolios nach einer Aktualisierung (aus dem Widget-Stand) — wie `PortfolioReading`.
struct PortfolioReading: Equatable, Sendable {
    /// Gesamtwert in `currency`.
    var total: Double
    var currency: String
    /// Gesamtwert in USDT; nil = unbekannt.
    var totalUsdt: Double?
    /// Veränderung «heute» in Prozent; nil = keine Vergleichsbasis.
    var changePercent: Double?
    /// Keine offene Position.
    var empty: Bool
}

/// Ergebnis der Prüfung eines Portfolio-Alarms.
enum PortfolioAlarmDecision: Equatable, Sendable {
    case nothing
    /// Gemeldeter Alarm wieder scharf stellen.
    case rearm
    /// Melden; Gesamtwert (Alarmwährung) bzw. Veränderung in Prozent (bei «fällt» gespiegelt).
    case fire(Double)
}

/// Regeln der Portfolio-Alarme — Spiegel von `PortfolioAlarmLogic.kt` (getestet in
/// PortfolioAlarmLogicTests). Wie die Kursmarken der Paar-Alarme (`AlarmEvaluator.isLevelArmed`,
/// `shouldRearmLevel`, Hysterese `AlarmEvaluator.levelHysteresis`) und mit der Ruhezeit nach dem Auslösen.
enum PortfolioAlarmLogic {

    /// Gesamtwert in der Alarmwährung: gleiche Währung wie der Stand, sonst bei USD bzw. einem
    /// USD-Stablecoin der USDT-Wert, sonst nil (diesmal nicht prüfen).
    static func total(in currency: String?, _ reading: PortfolioReading) -> Double? {
        let wanted = CurrencyConversion.normalize(currency)
        guard !wanted.isEmpty else { return nil }
        if wanted == reading.currency.uppercased() { return reading.total.isFinite ? reading.total : nil }
        if CurrencyConversion.usdStables.contains(wanted) {
            guard let usdt = reading.totalUsdt, usdt.isFinite else { return nil }
            return usdt
        }
        return nil
    }

    /// Gemessener Wert: Betrag, +Veränderung (steigt) oder −Veränderung (fällt).
    static func measure(_ alarm: PortfolioAlarm, _ reading: PortfolioReading) -> Double? {
        switch alarm.kind {
        case .VALUE_ABOVE, .VALUE_BELOW:
            return total(in: alarm.currency, reading)
        case .CHANGE_UP:
            guard let p = reading.changePercent, p.isFinite else { return nil }
            return p
        case .CHANGE_DOWN:
            guard let p = reading.changePercent, p.isFinite else { return nil }
            return -p
        }
    }

    static func decide(_ alarm: PortfolioAlarm, _ reading: PortfolioReading, now: Int64,
                       cooldownMinutes: Int) -> PortfolioAlarmDecision {
        guard alarm.enabled, !reading.empty else { return .nothing }
        let threshold = alarm.threshold
        guard threshold.isFinite, threshold > 0, let value = measure(alarm, reading) else { return .nothing }
        // Ein leeres oder wertloses Portfolio meldet nie «unter Betrag»
        if alarm.kind.isValue && !(value > 0) { return .nothing }
        let below = alarm.kind == .VALUE_BELOW
        let h = AlarmEvaluator.levelHysteresis
        if alarm.referenceAt > 0 {
            let back = below ? value > threshold * (1 + h) : value < threshold * (1 - h)
            return back ? .rearm : .nothing
        }
        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return .nothing }
        }
        let crossed = below ? value <= threshold : value >= threshold
        return crossed ? .fire(value) : .nothing
    }

    /// Nach dem Melden: ein einmaliger Alarm schaltet sich ab, ein wiederholender bleibt an.
    static func enabledAfterFire(repeating: Bool) -> Bool { repeating }

    /// Gültige Eingabe? Betrag bzw. Prozent über 0 (Prozent höchstens 1000).
    static func isValidThreshold(_ kind: PortfolioAlarmKind, _ threshold: Double?) -> Bool {
        guard let threshold, threshold.isFinite, threshold > 0 else { return false }
        return kind.isValue || threshold <= 1000
    }
}
