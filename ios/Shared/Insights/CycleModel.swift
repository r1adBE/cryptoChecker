import Foundation

/// Rohdaten für das Zyklus-Modell. Fehlende Werte (nil) zählen einfach nicht —
/// das Modell rechnet mit dem, was verfügbar ist.
struct CycleInputs: Equatable, Sendable {
    /// Aktueller BTC-Kurs in USD.
    var price: Double
    /// Durchschnitt der letzten 200 Tagesschlusskurse.
    var sma200d: Double?
    /// Durchschnitt der letzten 111 bzw. 350 Tagesschlusskurse (Pi-Cycle).
    var sma111d: Double?
    var sma350d: Double?
    /// Schlusskurs vor 30 Tagen.
    var price30dAgo: Double?
    /// Durchschnitt der letzten 200 Wochenschlusskurse.
    var sma200w: Double?
    /// Allzeithoch und wann es war.
    var ath: Double?
    var athDate: LocalDay?
    /// On-Chain (Coin Metrics): Marktwert / realisierter Wert.
    var mvrv: Double?
    /// Mining-Einnahmen heute geteilt durch ihren 365-Tage-Schnitt.
    var puell: Double?
    /// Hashrate: 30- und 60-Tage-Schnitt (Hash Ribbons).
    var hash30d: Double?
    var hash60d: Double?
    var today: LocalDay = .today()
}

/// Marktzone. Rohwerte = Kotlin-Enum-Namen (gespeichert unter "last_zone").
enum MarketZone: String, CaseIterable, Sendable, Codable {
    case EXTREME_BEAR, BEAR, NEUTRAL, BULL, EXTREME_BULL

    /// Schlüssel des Anzeigenamens (`R.string.zone_*`).
    var labelKey: String {
        switch self {
        case .EXTREME_BEAR: "zone_extreme_bear"
        case .BEAR: "zone_bear"
        case .NEUTRAL: "zone_neutral"
        case .BULL: "zone_bull"
        case .EXTREME_BULL: "zone_extreme_bull"
        }
    }

    /// Schlüssel der Kurzdeutung (`R.string.zone_hint_*`).
    var hintKey: String {
        switch self {
        case .EXTREME_BEAR: "zone_hint_extreme_bear"
        case .BEAR: "zone_hint_bear"
        case .NEUTRAL: "zone_hint_neutral"
        case .BULL: "zone_hint_bull"
        case .EXTREME_BULL: "zone_hint_extreme_bull"
        }
    }
}

enum SignalId: String, Sendable, Codable {
    case MVRV_NUPL, PUELL, MAYER, MA200W, DRAWDOWN, HASH_RIBBON, PARABOLIC, HALVING_TIME, ATH_TIME
}

/// Ein einzelnes Signal mit seinem Beitrag zum Score.
struct CycleSignal: Equatable, Sendable, Codable {
    let id: SignalId
    /// Anzeige des Messwerts, z. B. "1.58".
    let value: String?
    let topPoints: Int
    let bottomPoints: Int
}

struct CycleReport: Equatable, Sendable, Codable {
    let zone: MarketZone
    /// 0 = extrem bärisch, 100 = extrem bullisch (Top-Zone).
    let index: Int
    let topScore: Int
    let bottomScore: Int
    let signals: [CycleSignal]
    let cycle: CycleInfo
    let monthsSinceAth: Int?
    let drawdownPercent: Double?
    let onChainAvailable: Bool
}

/// Zonenmodell nach dem Prinzip „mehrere unabhängige Signale müssen
/// gleichzeitig in dieselbe Richtung zeigen“ — wie `CycleModel.kt`.
///
/// Top-Score (max. 10)                      Bottom-Score (max. 10)
///  MVRV/NUPL  ≥2,4 +1 · ≥2,8 +2 · ≥3,5 +3   MVRV ≤1,5 +1 · ≤1,2 +2 · ≤1,0 (NUPL ≤0) +3
///  Puell      ≥2,5 +1 · ≥3,5 +2             Puell ≤0,7 +1 · ≤0,5 +2
///  Mayer      ≥2,0 +1 · ≥2,4 +2             Rückgang vom ATH ≥50 % +1 · ≥70 % +2
///  200W-Mult. ≥3,5 +1                       Kurs ≤ 200-Wochen-Schnitt +1
///  Parabolisch (Pi-Cycle o. +40 % in 30 T.) +1   Hash-Ribbon-Kapitulation +1
///  12–18 Monate nach Halving +1             ≥12 Monate nach ATH +1
enum CycleModel {

    static func evaluate(_ input: CycleInputs) -> CycleReport {
        let cycle = BitcoinCycle.info(today: input.today)
        var signals: [CycleSignal] = []

        // MVRV / NUPL
        if let mvrv = input.mvrv {
            let nupl = 1.0 - 1.0 / mvrv
            let top = mvrv >= 3.5 ? 3 : mvrv >= 2.8 ? 2 : mvrv >= 2.4 ? 1 : 0
            let bottom = mvrv <= 1.0 ? 3 : mvrv <= 1.2 ? 2 : mvrv <= 1.5 ? 1 : 0
            signals.append(CycleSignal(id: .MVRV_NUPL, value: Indicators.fmt("%.2f · NUPL %.2f", mvrv, nupl),
                                       topPoints: top, bottomPoints: bottom))
        }

        // Puell Multiple
        if let p = input.puell {
            let top = p >= 3.5 ? 2 : p >= 2.5 ? 1 : 0
            let bottom = p <= 0.5 ? 2 : p <= 0.7 ? 1 : 0
            signals.append(CycleSignal(id: .PUELL, value: Indicators.fmt("%.2f", p), topPoints: top, bottomPoints: bottom))
        }

        // Mayer Multiple (Kurs / 200-Tage-Schnitt)
        var mayer: Double? = nil
        if let sma = input.sma200d, sma > 0 { mayer = input.price / sma }
        if let m = mayer {
            let top = m >= 2.4 ? 2 : m >= 2.0 ? 1 : 0
            signals.append(CycleSignal(id: .MAYER, value: Indicators.fmt("%.2f", m), topPoints: top, bottomPoints: 0))
        }

        // 200-Wochen-Schnitt
        if let ma = input.sma200w, ma > 0 {
            let mult = input.price / ma
            signals.append(CycleSignal(id: .MA200W, value: Indicators.fmt("%.2f×", mult),
                                       topPoints: mult >= 3.5 ? 1 : 0,
                                       bottomPoints: mult <= 1.0 ? 1 : 0))
        }

        // Rückgang vom Allzeithoch
        var drawdown: Double? = nil
        if let ath = input.ath, ath > 0 { drawdown = max(0.0, (1.0 - input.price / ath) * 100.0) }
        if let dd = drawdown {
            let bottom = dd >= 70 ? 2 : dd >= 50 ? 1 : 0
            signals.append(CycleSignal(id: .DRAWDOWN, value: Indicators.fmt("−%.0f %%", dd), topPoints: 0, bottomPoints: bottom))
        }

        // Hash Ribbons: 30-Tage-Schnitt unter 60-Tage-Schnitt = Miner geben auf
        if let h30 = input.hash30d, let h60 = input.hash60d, h60 > 0 {
            let capitulation = h30 < h60
            signals.append(CycleSignal(id: .HASH_RIBBON, value: capitulation ? "↓" : "↑",
                                       topPoints: 0, bottomPoints: capitulation ? 1 : 0))
        }

        // Parabolischer Anstieg: Pi-Cycle-Top oder +40 % in 30 Tagen
        var piCycle = false
        if let s111 = input.sma111d, let s350 = input.sma350d { piCycle = s111 >= 2 * s350 }
        var gain30: Double? = nil
        if let p30 = input.price30dAgo, p30 > 0 { gain30 = (input.price / p30 - 1.0) * 100.0 }
        if gain30 != nil || input.sma350d != nil {
            let parabolic = piCycle || (gain30 ?? 0) >= 40.0
            signals.append(CycleSignal(id: .PARABOLIC, value: gain30.map { Indicators.fmt("%+.0f %%", $0) },
                                       topPoints: parabolic ? 1 : 0, bottomPoints: 0))
        }

        // Zeit: Halving-Fenster und Abstand zum Hoch
        let inTopWindow = (12...18).contains(cycle.monthsSinceHalving)
        signals.append(CycleSignal(id: .HALVING_TIME, value: LocaleNumbers.integer(cycle.monthsSinceHalving),
                                   topPoints: inTopWindow ? 1 : 0, bottomPoints: 0))

        let monthsSinceAth = input.athDate.map { $0.months(until: input.today) }
        if let m = monthsSinceAth {
            // Nur zählen, wenn das Hoch auch deutlich zurückliegt (sonst sind wir am Hoch).
            let bottom = (m >= 12 && (drawdown ?? 0) >= 20) ? 1 : 0
            signals.append(CycleSignal(id: .ATH_TIME, value: LocaleNumbers.integer(m), topPoints: 0, bottomPoints: bottom))
        }

        let top = min(signals.reduce(0) { $0 + $1.topPoints }, 10)
        let bottom = min(signals.reduce(0) { $0 + $1.bottomPoints }, 10)
        let uptrend: Bool? = mayer.map { $0 >= 1.0 }

        let zone = ZoneRules.zone(top: top, bottom: bottom, uptrend: uptrend)
        let index = ZoneRules.index(top: top, bottom: bottom, uptrend: uptrend)

        return CycleReport(
            zone: zone,
            index: index,
            topScore: top,
            bottomScore: bottom,
            signals: signals,
            cycle: cycle,
            monthsSinceAth: monthsSinceAth,
            drawdownPercent: drawdown,
            onChainAvailable: input.mvrv != nil || input.puell != nil
        )
    }
}

/// Gemeinsame Regeln von Bitcoin- und Coin-Modell (Zone und Skalenwert).
enum ZoneRules {
    static func zone(top: Int, bottom: Int, uptrend: Bool?) -> MarketZone {
        if top >= 7 { return .EXTREME_BULL }
        if bottom >= 7 { return .EXTREME_BEAR }
        if top >= 4 && top > bottom { return .BULL }
        if bottom >= 4 && bottom > top { return .BEAR }
        if uptrend == true && top >= bottom { return .BULL }
        if uptrend == false && bottom >= top { return .BEAR }
        return .NEUTRAL
    }

    static func index(top: Int, bottom: Int, uptrend: Bool?) -> Int {
        let trendShift: Int
        switch uptrend {
        case .some(true): trendShift = 8
        case .some(false): trendShift = -8
        case .none: trendShift = 0
        }
        return min(max(50 + (top - bottom) * 5 + trendShift, 0), 100)
    }
}
