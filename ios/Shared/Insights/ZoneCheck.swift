import Foundation

/// Prüft den Fear & Greed Index und meldet, wenn er die eingestellte Grenze unter- bzw.
/// überschreitet (reine Zahl, keine Deutung) — wie `ZoneCheckWorker.doWork`, aber ohne eigene
/// Planung (der Aufrufer entscheidet, wann es läuft). Die frühere Meldung «Marktphase
/// gewechselt» gibt es nicht mehr; der Name bleibt, damit die Planung gleich bleibt.
enum ZoneCheck {
    /// Zuletzt gemerkte Marktzone früherer Versionen (wird nur noch aufgeräumt).
    static let legacyKeyZone = "last_zone"
    static let keyFearGreed = "last_fear_greed"
    /// Grenzen, zu denen der gemerkte Stand gehört (wie Android).
    static let keyFearGreedBelow = "last_fear_greed_below"
    static let keyFearGreedAbove = "last_fear_greed_above"

    /// - Returns: immer true (Fear & Greed ohne Netz wird beim nächsten Lauf nachgeholt).
    @discardableResult
    static func run(settings: AppSettings) async -> Bool {
        let defaults = SharedStorage.defaults
        defaults.removeObject(forKey: legacyKeyZone)
        await checkFearGreed(below: settings.fearGreedBelow, above: settings.fearGreedAbove, defaults: defaults)
        await checkIndicators(settings.indicatorAlerts, defaults: defaults)
        return true
    }

    /// Eigene Grenzen der Bitcoin-Kennzahlen (`IndicatorAlertRules`): einmal melden, wenn ein Wert die Grenze
    /// erreicht; wieder scharf, sobald er zurück ist. Kam die Meldung nicht an, bleibt sie offen — wie Android.
    private static func checkIndicators(_ stored: [String: Int], defaults: UserDefaults) async {
        let active = IndicatorAlertRules.active(stored)
        for kind in IndicatorAlertKind.allCases where active[kind] == nil {
            defaults.removeObject(forKey: crossedKey(kind))
            defaults.removeObject(forKey: thresholdKey(kind))
        }
        guard !active.isEmpty, let inputs = try? await CycleDataSource.fetch() else { return }
        let report = BitcoinIndicatorModel.evaluate(inputs)
        for (kind, threshold) in active {
            let value = report.indicators.first { $0.id == kind.indicator }?.value
            // Gemerkter Zustand gilt nur für die Grenze, zu der er gehört: eine neue Grenze bewertet neu
            let storedThreshold = stored[kind.rawValue] ?? 0
            let wasCrossed = IndicatorAlertRules.wasCrossed(
                storedCrossed: defaults.bool(forKey: crossedKey(kind)),
                storedThreshold: defaults.object(forKey: thresholdKey(kind)) as? Int, current: storedThreshold)
            let decision = IndicatorAlertRules.decide(kind, value: value, threshold: threshold, wasCrossed: wasCrossed)
            var crossed = decision.crossed
            if decision.notify {
                var posted = false
                if let value, await Notifier.isAuthorized() {
                    posted = await Notifier.showIndicatorAlert(kind, name: IndicatorAlertText.name(kind.indicator),
                                                               value: IndicatorAlertText.value(kind.indicator, value),
                                                               threshold: IndicatorAlertText.value(kind.indicator, threshold))
                }
                if !posted { crossed = nil }
            }
            if let crossed {
                defaults.set(crossed, forKey: crossedKey(kind))
                defaults.set(storedThreshold, forKey: thresholdKey(kind))
            }
        }
    }

    static func crossedKey(_ kind: IndicatorAlertKind) -> String { "indicator_crossed_" + kind.rawValue.lowercased() }

    /// Grenze (gespeicherte Ganzzahl), zu der der gemerkte Zustand «jenseits» gehört (wie Android).
    static func thresholdKey(_ kind: IndicatorAlertKind) -> String { "indicator_threshold_" + kind.rawValue.lowercased() }

    /// Fear & Greed: einmal melden, wenn der Index die eingestellte Grenze
    /// unter- bzw. überschreitet — nicht bei jedem Lauf erneut.
    private static func checkFearGreed(below: Int, above: Int, defaults: UserDefaults) async {
        if below <= 0 && above <= 0 {
            defaults.removeObject(forKey: keyFearGreed)
            defaults.removeObject(forKey: keyFearGreedBelow)
            defaults.removeObject(forKey: keyFearGreedAbove)
            return
        }
        guard let value = try? await InsightsDataSource.fearGreed().value else { return }
        let last: Int? = defaults.object(forKey: keyFearGreed) != nil ? defaults.integer(forKey: keyFearGreed) : nil
        // Grenze seit dem gemerkten Stand geändert: für sie zählt der alte Stand nicht
        let belowChanged = MarketNoticeGate.boundChanged(stored: defaults.object(forKey: keyFearGreedBelow) as? Int, current: below)
        let aboveChanged = MarketNoticeGate.boundChanged(stored: defaults.object(forKey: keyFearGreedAbove) as? Int, current: above)
        func decide(_ canNotify: Bool) -> MarketNoticeGate.FearGreedStep {
            MarketNoticeGate.fearGreed(value: value, last: last, below: below, above: above, canNotify: canNotify,
                                       belowChanged: belowChanged, aboveChanged: aboveChanged)
        }
        // Erlaubnis nur prüfen, wenn es etwas zu melden gibt
        var decision = decide(true)
        if decision.step == .notify {
            let allowed = await Notifier.isAuthorized()
            decision = decide(allowed)
        }
        var posted = false
        if decision.step == .notify {
            switch decision.kind {
            case .below: posted = await Notifier.showFearGreed(value: value, below: below)
            case .above: posted = await Notifier.showFearGreed(value: value, above: above)
            case nil: posted = false
            }
        }
        // Nicht angekommen: alten Wert behalten, damit die Grenze später noch gemeldet wird
        if MarketNoticeGate.storeAfterNotify(decision.step, posted: posted) {
            defaults.set(value, forKey: keyFearGreed)
            defaults.set(below, forKey: keyFearGreedBelow)
            defaults.set(above, forKey: keyFearGreedAbove)
        }
    }
}

/// Name und Wert einer Bitcoin-Kennzahl für Mitteilungen — wie `IndicatorTexts` (Android).
enum IndicatorAlertText {
    static func name(_ id: IndicatorId) -> String {
        switch id {
        case .MAYER: "Mayer Multiple"
        case .MVRV: "MVRV"
        case .PUELL: "Puell Multiple"
        case .ATH_DISTANCE: L("ind_drawdown")
        case .PI_CYCLE_GAP: L("neutral_ind_pi_gap")
        default: id.rawValue
        }
    }

    static func value(_ id: IndicatorId, _ v: Double) -> String {
        switch id {
        case .MAYER, .MVRV, .PUELL: LocaleNumbers.decimal(v, maxDecimals: 2, minDecimals: 2) + "×"
        // Abstand als positive Zahl («3.2 %»), damit «unter deiner Grenze 5 %» stimmt
        case .ATH_DISTANCE: LocaleNumbers.decimal(v, maxDecimals: 1, minDecimals: 1) + " %"
        default: PriceFormat.signedPercent(v, decimals: 1)
        }
    }
}

/// Wann `ZoneCheck` einen neuen Fear-&-Greed-Stand speichern darf — wie
/// `MarketNoticeGate.kt`, gleiche Regel wie bei den Paar-Alarmen (`Notifier.isAuthorized`):
/// Kann die Mitteilung gerade nicht erscheinen, bleibt der alte Stand liegen und der Wechsel
/// wird gemeldet, sobald sie wieder ankommt. Gemerkt wird erst nach dem erfolgreichen Absetzen.
enum MarketNoticeGate {
    enum Step: Equatable {
        /// Nichts zu melden: neuen Stand einfach merken.
        case store
        /// Melden; den neuen Stand erst nach erfolgreichem Absetzen merken.
        case notify
        /// Wäre zu melden, kommt aber gerade nicht an: alten Stand behalten.
        case hold
    }

    enum FearGreedKind: Equatable { case below, above }

    struct FearGreedStep: Equatable {
        let step: Step
        var kind: FearGreedKind? = nil
    }

    /// Fear & Greed: einmal melden, wenn der Index die Grenze `below` unter- bzw. `above`
    /// überschreitet (Grenze ≤ 0 = aus); `last` nil = noch kein Stand gemerkt.
    /// `belowChanged`/`aboveChanged`: Diese Grenze ist seit dem gemerkten Stand eine andere — dann zählt `last`
    /// für sie nicht; die unveränderte andere Grenze bewertet weiter mit `last`. Wie Android.
    static func fearGreed(value: Int, last: Int?, below: Int, above: Int, canNotify: Bool,
                          belowChanged: Bool = false, aboveChanged: Bool = false) -> FearGreedStep {
        let kind: FearGreedKind?
        if below > 0 && value <= below && (belowChanged || (last.map { $0 > below } ?? true)) {
            kind = .below
        } else if above > 0 && value >= above && (aboveChanged || (last.map { $0 < above } ?? true)) {
            kind = .above
        } else {
            kind = nil
        }
        guard let kind else { return FearGreedStep(step: .store) }
        return FearGreedStep(step: canNotify ? .notify : .hold, kind: kind)
    }

    /// Gilt die Grenze `current` nicht mehr für den gemerkten Stand? `stored` nil = Stand vor dieser Regel:
    /// als unverändert werten (nach dem Update nichts doppelt). Wie `MarketNoticeGate.boundChanged` (Android).
    static func boundChanged(stored: Int?, current: Int) -> Bool {
        guard let stored else { return false }
        return stored != current
    }

    /// Neuen Stand speichern? `posted` = die Mitteilung wurde angenommen (nur bei `.notify` von Belang).
    static func storeAfterNotify(_ step: Step, posted: Bool) -> Bool {
        switch step {
        case .store: return true
        case .notify: return posted
        case .hold: return false
        }
    }
}
