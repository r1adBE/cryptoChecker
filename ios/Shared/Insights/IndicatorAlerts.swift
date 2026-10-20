import Foundation

/// Eigene Grenzen für Bitcoin-Kennzahlen — wie `IndicatorAlertKind` / `IndicatorAlertRules` (Android, dort getestet).
/// Die Grenze wählt der Nutzer aus festen Zahlen; die App bewertet nichts und nennt keine Zone. Gespeichert je Art
/// als Ganzzahl (Faktoren in Hundertsteln, Prozent ganz); 0 = aus. Zweimal täglich geprüft (`ZoneCheck`).
enum IndicatorAlertKind: String, CaseIterable, Sendable {
    case MAYER_ABOVE, MAYER_BELOW, MVRV_ABOVE, MVRV_BELOW
    /// Abstand zum Allzeithoch höchstens X %.
    case ATH_WITHIN

    var indicator: IndicatorId {
        switch self {
        case .MAYER_ABOVE, .MAYER_BELOW: .MAYER
        case .MVRV_ABOVE, .MVRV_BELOW: .MVRV
        case .ATH_WITHIN: .ATH_DISTANCE
        }
    }

    var above: Bool { self == .MAYER_ABOVE || self == .MVRV_ABOVE }

    var choices: [Int] {
        switch self {
        case .MAYER_ABOVE: [0, 150, 200, 240, 300]
        case .MAYER_BELOW: [0, 100, 80, 60]
        case .MVRV_ABOVE: [0, 250, 300, 350]
        case .MVRV_BELOW: [0, 100, 80]
        case .ATH_WITHIN: [0, 1, 3, 5, 10]
        }
    }

    var scale: Double { self == .ATH_WITHIN ? 1 : 100 }

    /// Beschriftung der Einstellungszeile.
    var labelKey: String {
        switch self {
        case .MAYER_ABOVE: "settings_ind_mayer_above"
        case .MAYER_BELOW: "settings_ind_mayer_below"
        case .MVRV_ABOVE: "settings_ind_mvrv_above"
        case .MVRV_BELOW: "settings_ind_mvrv_below"
        case .ATH_WITHIN: "settings_ind_ath_within"
        }
    }

    /// Grenze als Text: Faktor «2.40×», Prozent «5 %».
    func valueText(_ stored: Int) -> String {
        scale == 1 ? LocaleNumbers.integer(stored) + " %" : LocaleNumbers.decimal(Double(stored) / scale, maxDecimals: 2, minDecimals: 2) + "×"
    }
}

enum IndicatorAlertRules {
    static func threshold(_ kind: IndicatorAlertKind, stored: Int) -> Double? {
        stored > 0 ? Double(stored) / kind.scale : nil
    }

    static func crossed(_ kind: IndicatorAlertKind, value: Double, threshold: Double) -> Bool {
        kind.above ? value >= threshold : value <= threshold
    }

    struct Decision: Equatable {
        let notify: Bool
        /// Neuer Zustand «jenseits der Grenze»; nil = nicht ändern.
        let crossed: Bool?
    }

    static func decide(_ kind: IndicatorAlertKind, value: Double?, threshold: Double?, wasCrossed: Bool) -> Decision {
        guard let threshold else { return Decision(notify: false, crossed: false) }
        guard let v = value, v.isFinite else { return Decision(notify: false, crossed: nil) }
        let now = crossed(kind, value: v, threshold: threshold)
        if now && !wasCrossed { return Decision(notify: true, crossed: true) }
        if now { return Decision(notify: false, crossed: nil) }
        return Decision(notify: false, crossed: false)
    }

    /// Gemerkter Zustand «jenseits der Grenze» für die eingestellte Grenze `current` (gespeicherte Ganzzahl): Wurde er bei
    /// einer anderen Grenze gemerkt, zählt er nicht — eine neue Grenze bewertet neu (wie Fear & Greed,
    /// `MarketNoticeGate.boundChanged`); `storedThreshold` nil = Stand vor dieser Regel: übernehmen. Wie Android.
    static func wasCrossed(storedCrossed: Bool, storedThreshold: Int?, current: Int) -> Bool {
        storedCrossed && !MarketNoticeGate.boundChanged(stored: storedThreshold, current: current)
    }

    /// Eingeschaltete Grenzen aus den Einstellungen (Schlüssel = Name der Art).
    static func active(_ stored: [String: Int]) -> [IndicatorAlertKind: Double] {
        var out: [IndicatorAlertKind: Double] = [:]
        for kind in IndicatorAlertKind.allCases {
            if let v = stored[kind.rawValue], kind.choices.contains(v), let t = threshold(kind, stored: v) { out[kind] = t }
        }
        return out
    }
}
