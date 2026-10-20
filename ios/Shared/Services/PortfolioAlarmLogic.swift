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
    /// Devisenkurse USD → Währung für Alarme in einer anderen Währung als der Stand (z. B. nach einem Wechsel
    /// der Umrechnungswährung); fehlt der Kurs, wird diesmal nicht geprüft — wie Android.
    var usdTo: [String: Double] = [:]
    /// Offene Coins ohne aktuellen Kurs (`PortfolioWidgetSnapshot.missingPrices`): sie zählen im Gesamtwert als 0 — ein
    /// Wert-Alarm wird dann weder gemeldet noch wieder scharf gestellt. Wie Android.
    var missingPrices = 0
    /// Coins, die in «heute» fehlen (kein Vergleichskurs, `todaySkipped`): die Veränderung gilt nur für einen Teil — ein
    /// %-Alarm wird dann weder gemeldet noch wieder scharf gestellt. Wie Android.
    var todaySkipped = 0
    /// Bewertete Coins mit altem Kurs (`PortfolioWidgetSnapshot.stalePrices`): ein alter Kurs gälte sonst als «jetzt» —
    /// dann prüft KEIN Portfolio-Alarm (weder melden noch wieder scharf stellen). Wie Android.
    var stalePrices = 0
    /// Live-Kurs USDT/USD (`DepegAlarm.liveUsdtUsd`) für Alarme in einer anderen Währung als der Stand: USDT gilt in Fiat
    /// (auch USD) seinen Marktkurs wie im Portfolio-Kopf (`PortfolioStables.usdtUsd`); nil = keiner (× 1, Näherung).
    /// Wie Android.
    var usdtUsdLive: Double? = nil
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

    /// Gesamtwert in der Alarmwährung: gleiche Währung wie der Stand; bei USDT der USDT-Wert; bei USD und den übrigen
    /// USD-Stablecoins (USDC, DAI …) der USDT-Wert mal USDT/USD (`usdtUsdLive`) — sie gelten wie USD 1 Dollar; sonst
    /// USDT-Wert mal Devisenkurs (`usdTo`) mal USDT/USD (`PortfolioStables.usdtTo`) — wie der Portfolio-Kopf (USDT zum
    /// Marktkurs); ohne Kurs nil (diesmal nicht prüfen). Wie Android `totalIn`.
    static func total(in currency: String?, _ reading: PortfolioReading) -> Double? {
        let wanted = CurrencyConversion.normalize(currency)
        guard !wanted.isEmpty else { return nil }
        if wanted == reading.currency.uppercased() { return reading.total.isFinite ? reading.total : nil }
        guard let usdt = reading.totalUsdt, usdt.isFinite else { return nil }
        let usdtUsd = PortfolioStables.usdtUsdLive(reading.usdtUsdLive)
        if wanted == "USDT" { return usdt }
        // USD-Stablecoins ausser USDT wie USD: USDT-Wert × USDT/USD (bei entkoppeltem USDT sonst falsch)
        if wanted == "USD" || CurrencyConversion.usdStables.contains(wanted) { return usdt * usdtUsd.rate }
        guard let rate = reading.usdTo[wanted], rate.isFinite, rate > 0,
              let factor = PortfolioStables.usdtTo(rate, usdt: usdtUsd) else { return nil }
        return usdt * factor
    }

    /// Rechnet `total(in:)` für einen Betrags-Alarm mit USDT = 1, weil USDT/USD nicht gemessen ist (`usdtUsdLive`
    /// fehlt)? Nur in einer anderen Währung als der Stand und nicht in USDT selbst — dann steht «USDT = 1 angenommen» im
    /// Alarmtext. Wie Android `usdtAssumed`.
    static func usdtAssumed(_ alarm: PortfolioAlarm, _ reading: PortfolioReading) -> Bool {
        guard alarm.kind.isValue else { return false }
        let wanted = CurrencyConversion.normalize(alarm.currency)
        guard !wanted.isEmpty, wanted != reading.currency.uppercased(), wanted != "USDT" else { return false }
        return PortfolioStables.usdtUsdLive(reading.usdtUsdLive).approximate
    }

    /// Alarmwährungen, für die ein Devisenkurs nötig ist (nicht die des Stands, kein USD-Stablecoin).
    static func currenciesNeedingRate(_ currencies: [String?], readingCurrency: String) -> Set<String> {
        Set(currencies.compactMap { c -> String? in
            let code = CurrencyConversion.normalize(c)
            guard !code.isEmpty, code != readingCurrency.uppercased(), !CurrencyConversion.usdStables.contains(code) else { return nil }
            return code
        })
    }

    /// Abstand zurück hinter die Marke, bevor ein %-Alarm wieder scharf ist: 20 % der Marke, mindestens
    /// 0.5 Prozentpunkte — wie Android (`changeRearmMargin`).
    static func changeRearmMargin(_ threshold: Double) -> Double { max(threshold * 0.2, 0.5) }

    /// Gemessener Wert: Betrag, +Veränderung (steigt) oder −Veränderung (fällt).
    static func measure(_ alarm: PortfolioAlarm, _ reading: PortfolioReading) -> Double? {
        switch alarm.kind {
        case .VALUE_ABOVE, .VALUE_BELOW:
            return total(in: alarm.currency, reading)
        // Prozente auf 1e-6 gerundet (`AlarmPercent.rounded`) wie bei den Kursalarmen — sonst verfehlt «+3 %» genau 3 % um
        // einen Gleitkomma-Rest (wie Android)
        case .CHANGE_UP:
            guard let p = reading.changePercent, p.isFinite else { return nil }
            return AlarmPercent.rounded(p)
        case .CHANGE_DOWN:
            guard let p = reading.changePercent, p.isFinite else { return nil }
            return AlarmPercent.rounded(-p)
        }
    }

    /// Die Messung ist unvollständig: Wert-Alarm mit fehlendem Kurs (Coin zählt als 0 — «unter Betrag» löste sonst
    /// fälschlich aus), %-Alarm mit fehlendem Vergleichskurs. Dann diesmal gar nicht prüfen (kein Melden, kein
    /// Wieder-scharf-Stellen), für «darüber» wie «darunter». Ein alter Kurs (`stalePrices`) sperrt jede Art. Wie Android.
    static func incomplete(_ kind: PortfolioAlarmKind, _ reading: PortfolioReading) -> Bool {
        if reading.stalePrices > 0 { return true }
        return kind.isValue ? reading.missingPrices > 0 : reading.todaySkipped > 0
    }

    static func decide(_ alarm: PortfolioAlarm, _ reading: PortfolioReading, now: Int64,
                       cooldownMinutes: Int) -> PortfolioAlarmDecision {
        guard alarm.enabled, !reading.empty else { return .nothing }
        if incomplete(alarm.kind, reading) { return .nothing }
        let threshold = alarm.threshold
        guard threshold.isFinite, threshold > 0, let value = measure(alarm, reading) else { return .nothing }
        // Ein leeres oder wertloses Portfolio meldet nie «unter Betrag»
        if alarm.kind.isValue && !(value > 0) { return .nothing }
        let below = alarm.kind == .VALUE_BELOW
        let h = AlarmEvaluator.levelHysteresis
        if alarm.referenceAt > 0 {
            // Frühestens `minLevelRearmMillis` nach der Meldung wieder scharf (wie die Kursmarken,
            // `AlarmEvaluator.shouldRearmLevel`); Meldung in der Zukunft (Uhr zurückgestellt) blockiert nicht
            let elapsed = now - alarm.referenceAt
            if elapsed >= 0 && elapsed < AlarmEvaluator.minLevelRearmMillis { return .nothing }
            let back: Bool
            if below { back = value > threshold * (1 + h) }
            else if alarm.kind.isValue { back = value < threshold * (1 - h) }
            // Prozent-Alarme: deutlich zurück, nicht nur ein Zittern um die Marke
            else { back = value < threshold - changeRearmMargin(threshold) }
            return back ? .rearm : .nothing
        }
        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < SafeMillis.minutes(cooldownMinutes) { return .nothing }
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
