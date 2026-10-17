import Foundation

/// Faktor im «Warum?»-Blatt; Reihenfolge = Rang bei gleicher Stärke.
enum WhyFactorKind: Int, Sendable, CaseIterable {
    case volume, market, volatility, openInterest, nearHigh, funding, sentiment
    case nearLow, position3d, peg
}

/// Pfeil vor dem Titel: ↗ / ↘ (schräg wie überall in der App) oder keiner.
enum WhyFactorDirection: Sendable {
    case up, down, none

    var glyph: String {
        switch self {
        case .up: PriceFormat.arrowUp
        case .down: PriceFormat.arrowDown
        case .none: "–"
        }
    }
}

/// Kurze Nebenzeile eines Faktors («höher als üblich», «BTC zieht den Markt mit» …).
enum WhyFactorNote: Sendable {
    case volumeHigher, volumeLower, volumeUsual
    case marketPulls, marketCoinLags, marketCoinAlone, marketAgainst, marketCalm
    case marketLeaderMoves, marketLeaderCalm
    case volatilityStronger, volatilityUsual
    case oiUp, oiDown, oiFlat
    case highAt, highBelow
    case fundingLongs, fundingShorts, fundingNeutral
    case sentiment
    /// Markt (ETH oder breiter Markt) bewegt sich in dieselbe Richtung wie der Coin.
    case marketMoves
    /// Open Interest gegenüber den älteren Messungen uneinheitlich.
    case oiMixed
    case lowAt, lowAbove
    case rangeNearHigh, rangeNearLow, rangeBelowHigh, rangeAboveLow
    case pegNear, pegBelow, pegAbove
    /// Stunde ungewöhnlich für diesen Coin (|z| ≥ 2), aber absolut unter 0,5 % — kein Ausschlag.
    case volatilityTiny

    /// Schlüssel der Nebenzeile; nil bei der Stimmung (dort steht die Fear-&-Greed-Stufe) und bei
    /// Zeilen mit Zahl im Text (Bindung unter/über 1:1, «absolut gering»: `WatchlistWhyTexts`).
    var key: String? {
        switch self {
        case .volumeHigher: "why_fn_volume_higher"
        case .volumeLower: "why_fn_volume_lower"
        case .volumeUsual: "why_fn_volume_usual"
        case .marketPulls: "why_fn_market_pulls"
        case .marketCoinLags: "why_fn_market_lags"
        case .marketCoinAlone: "why_fn_market_alone"
        case .marketAgainst: "why_fn_market_against"
        case .marketCalm: "why_fn_market_calm"
        case .marketLeaderMoves: "why_fn_leader_moves"
        case .marketLeaderCalm: "why_fn_leader_calm"
        case .volatilityStronger: "why_fn_volatility_stronger"
        case .volatilityUsual: "why_fn_volatility_usual"
        case .oiUp: "why_fn_oi_up"
        case .oiDown: "why_fn_oi_down"
        case .oiFlat: "why_fn_oi_flat"
        case .highAt: "why_fn_high_at"
        case .highBelow: "why_fn_high_below"
        case .fundingLongs: "why_fn_funding_longs"
        case .fundingShorts: "why_fn_funding_shorts"
        case .fundingNeutral: "why_fn_funding_neutral"
        case .sentiment: nil
        case .marketMoves: "why_fn_market_moves"
        case .oiMixed: "why_fn_oi_mixed"
        case .lowAt: "why_fn_low_at"
        case .lowAbove: "why_fn_low_above"
        case .rangeNearHigh: "why_fn_range_near_high"
        case .rangeNearLow: "why_fn_range_near_low"
        case .rangeBelowHigh: "why_fn_range_below_high"
        case .rangeAboveLow: "why_fn_range_above_low"
        case .pegNear: "why_fn_peg_near"
        case .pegBelow, .pegAbove, .volatilityTiny: nil
        }
    }
}

/// Ein Faktor als Daten; Text und Zahlenformat macht die Oberfläche (wie `WhyFactor` in Android).
///
/// `value` / `secondary` je Art:
///  - volume: value = Volumen-Verhältnis (3.4 = 3,4×)
///  - market: value = Referenz 24h % (`ref`: BTC, ETH oder breiter Markt; bei Bitcoin selbst: BTC),
///    secondary = Coin 24h % bzw. ETH 24h % (oder nil)
///  - volatility: value = Faktor gegenüber üblich (|z|)
///  - openInterest: value = Open-Interest-Veränderung %
///  - nearHigh: value = Abstand zum 30-Tage-Hoch in % (≥ 0, 0 = auf dem Hoch)
///  - nearLow: value = Abstand zum 30-Tage-Tief in % (≥ 0, 0 = auf dem Tief)
///  - position3d: value = Abstand zum 3-Tage-Hoch (≤ 0) bzw. -Tief (≥ 0) in %
///  - peg: value = Abweichung von 1:1 in % (mit Vorzeichen)
///  - funding: value = Funding Rate % je Periode
///  - sentiment: value = Fear & Greed (0–100), secondary = Veränderung zu gestern (oder nil)
struct WhyFactor: Equatable, Sendable {
    let kind: WhyFactorKind
    let direction: WhyFactorDirection
    let note: WhyFactorNote
    let value: Double
    var secondary: Double? = nil
    /// Rang: wie deutlich der Faktor über seiner Schwelle liegt (≥ 1 = auffällig).
    let strength: Double
    /// Unauffällig — wird abgeblendet und nach den auffälligen gezeigt.
    let neutral: Bool
    /// market: Referenz von `value`; nil = BTC.
    var ref: MarketRef? = nil
    /// volume: nur genähert («geschätzt»).
    var approximate: Bool = false
    /// funding: Verlauf («seit 24 Std. steigend»); nil = keiner.
    var trend: FundingTrend? = nil
    /// funding: Abrechnungsintervall in Stunden, wenn nicht 8 (Hyperliquid: 1 → «pro Std.»); nil = 8.
    var intervalHours: Int? = nil
}

/// Reine Regeln für die Faktorliste im «Warum?»-Blatt (wie `WhyFactors` in Android): aus denselben
/// Gründen wie «Kurz gesagt» plus Open Interest und Nähe zum 30-Tage-Hoch, wenn es
/// Daten gibt. Nur Faktoren mit Daten; auffällige zuerst (stärkster oben), neutrale abgeblendet
/// danach, höchstens `maxFactors`.
enum WhyFactors {
    static let maxFactors = 5
    /// Bis zu diesem Abstand (in %) zum 30-Tage-Hoch zählt «Nähe zum Hoch» als auffällig.
    static let nearHighPercent = 5.0
    /// Bis zu diesem Abstand (in %) steht «auf dem 30-Tage-Hoch».
    static let atHighPercent = 0.1
    /// Ab dieser Open-Interest-Veränderung (in %, Betrag) ist der Faktor nicht mehr neutral.
    static let oiNotablePercent = 5.0
    /// Ab dieser Veränderung zu gestern bekommt die Stimmung einen Pfeil.
    static let sentimentArrowPoints = 5.0
    /// «Nähe zum Tief»: gleiche Schwellen wie beim Hoch.
    static let nearLowPercent = nearHighPercent
    static let atLowPercent = atHighPercent

    /// Alle Faktoren mit Daten, gerankt: auffällige nach Stärke, dann neutrale; höchstens `max`.
    static func rank(_ report: WhyReport, max: Int = maxFactors) -> [WhyFactor] {
        let all = factors(report)
        let active = all.filter { !$0.neutral }.sorted { a, b in
            a.strength != b.strength ? a.strength > b.strength : a.kind.rawValue < b.kind.rawValue
        }
        // Neutrale nach Art; beim Stablecoin steht die Bindung (1:1) vorn
        let neutral = all.filter(\.neutral).sorted { a, b in
            let pa = a.kind == .peg ? 0 : 1, pb = b.kind == .peg ? 0 : 1
            return pa != pb ? pa < pb : a.kind.rawValue < b.kind.rawValue
        }
        return Array((active + neutral).prefix(Swift.max(max, 0)))
    }

    /// Faktoren in fester Reihenfolge (ungerankt), je Art höchstens einer.
    static func factors(_ report: WhyReport) -> [WhyFactor] {
        var result: [WhyFactor] = []
        var seen = Set<WhyFactorKind>()
        func add(_ factor: WhyFactor?) {
            guard let factor, factor.value.isFinite, !seen.contains(factor.kind) else { return }
            seen.insert(factor.kind)
            result.append(factor)
        }
        for r in report.reasons {
            switch r.kind {
            case .VOLUME_HIGH, .VOLUME_LOW, .VOLUME_NORMAL:
                add(volume(r))
            case .MARKET_WIDE, .COIN_ONLY, .AGAINST_MARKET, .MARKET_CALM, .MARKET_LEADER:
                add(market(r))
            case .VOLATILITY_HIGH, .VOLATILITY_NORMAL:
                add(volatility(r))
            case .LEVERAGE_LONGS, .LEVERAGE_SHORTS, .LEVERAGE_BALANCED:
                add(funding(r, trend: report.fundingTrend))
                if let oi = r.secondary { add(openInterest(oi, consistent: report.oiConsistent)) }
            case .SENTIMENT:
                add(sentiment(r))
            case .PEG:
                add(peg(r))
            }
        }
        let high = nearHigh(price: report.price, high: report.high30d)
        let low = nearLow(price: report.price, low: report.low30d)
        add(high)
        add(low)
        // 3-Tage-Lage: nicht doppelt, wenn schon «nahe am 30-Tage-Hoch/-Tief» auffällt
        if let p = report.position3d {
            let duplicate = (p.kind == .NEAR_HIGH && high?.neutral == false) || (p.kind == .NEAR_LOW && low != nil)
            if !duplicate { add(position3d(p)) }
        }
        return result
    }

    private static func direction(_ value: Double) -> WhyFactorDirection {
        value > 0 ? .up : (value < 0 ? .down : .none)
    }

    private static func volume(_ r: WhyReason) -> WhyFactor {
        switch r.kind {
        case .VOLUME_HIGH:
            return WhyFactor(kind: .volume, direction: .up, note: .volumeHigher, value: r.value,
                             strength: r.value / ActivityAnalyzer.volumeHighRatio, neutral: false,
                             approximate: r.approximate)
        case .VOLUME_LOW:
            return WhyFactor(kind: .volume, direction: .down, note: .volumeLower, value: r.value,
                             strength: r.value > 0 ? ActivityAnalyzer.volumeLowRatio / r.value : 1, neutral: false,
                             approximate: r.approximate)
        default:
            return WhyFactor(kind: .volume, direction: .none, note: .volumeUsual, value: r.value,
                             strength: 0, neutral: true, approximate: r.approximate)
        }
    }

    private static func market(_ r: WhyReason) -> WhyFactor {
        // Schwellen aus dem Bericht (relativ zur Schwankung), sonst die festen
        let marketThreshold = r.threshold ?? ActivityAnalyzer.marketMovePercent
        let coinThreshold = r.threshold ?? ActivityAnalyzer.coinMovePercent
        switch r.kind {
        case .MARKET_WIDE:
            // value = Referenz, secondary = Coin
            let follows = WhySummary.mark(r) == .supports
            let note: WhyFactorNote
            if !follows {
                note = .marketCoinLags
            } else if r.ref == nil || r.ref == .BTC {
                note = .marketPulls
            } else {
                note = .marketMoves
            }
            // Coin zieht nicht mit: der Markt erklärt die Bewegung nur halb
            let strength = abs(r.value) / marketThreshold * (follows ? 1 : 0.5)
            return WhyFactor(kind: .market, direction: direction(r.value), note: note, value: r.value,
                             secondary: r.secondary, strength: strength, neutral: false, ref: r.ref)
        case .COIN_ONLY:
            // value = Coin, secondary = Referenz → Faktor zeigt den Markt
            return WhyFactor(kind: .market, direction: .none, note: .marketCoinAlone,
                             value: r.secondary ?? 0, secondary: r.value,
                             strength: abs(r.value) / coinThreshold, neutral: false, ref: r.ref)
        case .AGAINST_MARKET:
            let market = r.secondary ?? 0
            return WhyFactor(kind: .market, direction: direction(market), note: .marketAgainst,
                             value: market, secondary: r.value,
                             strength: abs(r.value) / coinThreshold, neutral: false, ref: r.ref)
        case .MARKET_LEADER:
            // Bitcoin selbst: value = BTC, secondary = ETH
            let moves = abs(r.value) >= marketThreshold
            return WhyFactor(kind: .market, direction: moves ? direction(r.value) : .none,
                             note: moves ? .marketLeaderMoves : .marketLeaderCalm,
                             value: r.value, secondary: r.secondary,
                             strength: moves ? abs(r.value) / marketThreshold : 0, neutral: !moves)
        default:
            // MARKET_CALM: value = Referenz, secondary = Coin
            return WhyFactor(kind: .market, direction: .none, note: .marketCalm, value: r.value,
                             secondary: r.secondary, strength: 0, neutral: true, ref: r.ref)
        }
    }

    private static func volatility(_ r: WhyReason) -> WhyFactor {
        if r.kind == .VOLATILITY_HIGH {
            return WhyFactor(kind: .volatility, direction: .up, note: .volatilityStronger, value: r.value,
                             strength: r.value / ActivityAnalyzer.volatilityHighZ, neutral: false)
        }
        // |z| über der Schwelle, aber unter der Mindestbewegung: nicht «wie üblich» nennen
        let note: WhyFactorNote = r.value >= ActivityAnalyzer.volatilityHighZ ? .volatilityTiny : .volatilityUsual
        return WhyFactor(kind: .volatility, direction: .none, note: note, value: r.value,
                         strength: 0, neutral: true)
    }

    private static func funding(_ r: WhyReason, trend: FundingTrend?) -> WhyFactor {
        // Rang auf 8 Stunden umgerechnet (stündliches Funding zählt achtfach), Anzeige bleibt der Satz der Börse
        let per8h = ActivityAnalyzer.fundingPer8h(r.value, intervalHours: r.intervalHours)
        let strength = abs(per8h) / ActivityAnalyzer.fundingExtremePercent
        switch r.kind {
        case .LEVERAGE_LONGS:
            return WhyFactor(kind: .funding, direction: .up, note: .fundingLongs, value: r.value,
                             strength: strength, neutral: false, trend: trend, intervalHours: r.intervalHours)
        case .LEVERAGE_SHORTS:
            return WhyFactor(kind: .funding, direction: .down, note: .fundingShorts, value: r.value,
                             strength: strength, neutral: false, trend: trend, intervalHours: r.intervalHours)
        default:
            return WhyFactor(kind: .funding, direction: .none, note: .fundingNeutral, value: r.value,
                             strength: 0, neutral: true, trend: trend, intervalHours: r.intervalHours)
        }
    }

    /// `consistent` = false: gegenüber den älteren Messungen uneinheitlich — dann neutral.
    private static func openInterest(_ change: Double, consistent: Bool) -> WhyFactor {
        let large = abs(change) >= oiNotablePercent
        let notable = large && consistent
        let note: WhyFactorNote
        if large && !consistent {
            note = .oiMixed
        } else if !notable {
            note = .oiFlat
        } else {
            note = change > 0 ? .oiUp : .oiDown
        }
        return WhyFactor(kind: .openInterest, direction: notable ? direction(change) : .none, note: note,
                         value: change,
                         strength: notable ? abs(change) / ActivityAnalyzer.oiJumpPercent : 0, neutral: !notable)
    }

    /// Abstand zum 30-Tage-Hoch in %; nil ohne Kurs oder Hoch.
    static func distanceToHighPercent(price: Double?, high: Double?) -> Double? {
        guard let price, let high, price.isFinite, high.isFinite, price > 0, high > 0 else { return nil }
        return Swift.max((high - price) / high * 100, 0)
    }

    private static func nearHigh(price: Double?, high: Double?) -> WhyFactor? {
        guard let distance = distanceToHighPercent(price: price, high: high) else { return nil }
        let near = distance <= nearHighPercent
        return WhyFactor(kind: .nearHigh, direction: .none,
                         note: distance <= atHighPercent ? .highAt : .highBelow, value: distance,
                         // Näher am Hoch = stärker (1 bei 5 % Abstand, 2 auf dem Hoch)
                         strength: near ? 1 + (nearHighPercent - distance) / nearHighPercent : 0, neutral: !near)
    }

    /// Abstand zum 30-Tage-Tief in % (≥ 0); nil ohne Kurs oder Tief.
    static func distanceToLowPercent(price: Double?, low: Double?) -> Double? {
        guard let price, let low, price.isFinite, low.isFinite, price > 0, low > 0 else { return nil }
        return Swift.max((price - low) / low * 100, 0)
    }

    /// «Nähe zum 30-Tage-Tief» — gleiche Schwellen wie das Hoch, aber nur, wenn nahe: Weit über dem
    /// Tief zu stehen ist der Normalfall und bekäme sonst eine leere, abgeblendete Zeile.
    private static func nearLow(price: Double?, low: Double?) -> WhyFactor? {
        guard let distance = distanceToLowPercent(price: price, low: low), distance <= nearLowPercent else { return nil }
        return WhyFactor(kind: .nearLow, direction: .none,
                         note: distance <= atLowPercent ? .lowAt : .lowAbove, value: distance,
                         // Näher am Tief = stärker (1 bei 5 % Abstand, 2 auf dem Tief)
                         strength: 1 + (nearLowPercent - distance) / nearLowPercent, neutral: false)
    }

    /// Lage in drei Tagen (nur auffällige kommen im Bericht an).
    private static func position3d(_ p: Position3d) -> WhyFactor {
        let near = ActivityAnalyzer.rangeNearPercent
        switch p.kind {
        case .NEAR_HIGH:
            // 1 bei 1 % Abstand, 1,5 auf dem Hoch — schwächer als das 30-Tage-Hoch
            return WhyFactor(kind: .position3d, direction: .none, note: .rangeNearHigh, value: p.percent,
                             strength: 1 + (near - abs(p.percent)) / near * 0.5, neutral: false)
        case .NEAR_LOW:
            return WhyFactor(kind: .position3d, direction: .none, note: .rangeNearLow, value: p.percent,
                             strength: 1 + (near - abs(p.percent)) / near * 0.5, neutral: false)
        case .BELOW_HIGH:
            return WhyFactor(kind: .position3d, direction: .down, note: .rangeBelowHigh, value: p.percent,
                             strength: abs(p.percent) / p.threshold, neutral: false)
        case .ABOVE_LOW:
            return WhyFactor(kind: .position3d, direction: .up, note: .rangeAboveLow, value: p.percent,
                             strength: abs(p.percent) / p.threshold, neutral: false)
        }
    }

    /// Stablecoin: Abweichung von 1:1; unter `StablePeg.notablePercent` neutral.
    private static func peg(_ r: WhyReason) -> WhyFactor {
        let deviation = r.value
        let notable = StablePeg.isNotable(deviation)
        let note: WhyFactorNote
        if abs(deviation) <= StablePeg.nearPercent {
            note = .pegNear
        } else {
            note = deviation < 0 ? .pegBelow : .pegAbove
        }
        return WhyFactor(kind: .peg, direction: notable ? direction(deviation) : .none, note: note,
                         value: deviation,
                         strength: notable ? abs(deviation) / StablePeg.notablePercent : 0, neutral: !notable)
    }

    private static func sentiment(_ r: WhyReason) -> WhyFactor {
        let extreme = r.tone == .warning
        var arrow: WhyFactorDirection = .none
        if let change = r.secondary, abs(change) >= sentimentArrowPoints { arrow = direction(change) }
        return WhyFactor(kind: .sentiment, direction: arrow, note: .sentiment, value: r.value, secondary: r.secondary,
                         strength: extreme ? 1 : 0, neutral: !extreme)
    }
}
