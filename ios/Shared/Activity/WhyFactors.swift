import Foundation

/// Faktor im «Warum?»-Blatt; Reihenfolge = Rang bei gleicher Stärke.
enum WhyFactorKind: Int, Sendable, CaseIterable {
    case volume, market, volatility, openInterest, nearHigh, funding, sentiment
}

/// Pfeil vor dem Titel: ↑ / ↓ oder keiner.
enum WhyFactorDirection: Sendable {
    case up, down, none

    var glyph: String {
        switch self {
        case .up: "↑"
        case .down: "↓"
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

    /// Schlüssel der Nebenzeile; nil bei der Stimmung (dort steht die Fear-&-Greed-Stufe).
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
        }
    }
}

/// Ein Faktor als Daten; Text und Zahlenformat macht die Oberfläche (wie `WhyFactor` in Android).
///
/// `value` / `secondary` je Art:
///  - volume: value = Volumen-Verhältnis (3.4 = 3,4×)
///  - market: value = BTC 24h % (bei Bitcoin selbst: BTC), secondary = Coin 24h % bzw. ETH 24h % (oder nil)
///  - volatility: value = Faktor gegenüber üblich (|z|)
///  - openInterest: value = Open-Interest-Veränderung %
///  - nearHigh: value = Abstand zum 30-Tage-Hoch in % (≥ 0, 0 = auf dem Hoch)
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
}

/// Reine Regeln für die Faktorliste im «Warum?»-Blatt (wie `WhyFactors` in Android): aus denselben
/// Gründen wie «Kurz gesagt» und «Sicherheit» plus Open Interest und Nähe zum 30-Tage-Hoch, wenn es
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

    /// Alle Faktoren mit Daten, gerankt: auffällige nach Stärke, dann neutrale; höchstens `max`.
    static func rank(_ report: WhyReport, max: Int = maxFactors) -> [WhyFactor] {
        let all = factors(report)
        let active = all.filter { !$0.neutral }.sorted { a, b in
            a.strength != b.strength ? a.strength > b.strength : a.kind.rawValue < b.kind.rawValue
        }
        let neutral = all.filter(\.neutral).sorted { $0.kind.rawValue < $1.kind.rawValue }
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
                add(funding(r))
                if let oi = r.secondary { add(openInterest(oi)) }
            case .SENTIMENT:
                add(sentiment(r))
            }
        }
        add(nearHigh(price: report.price, high: report.high30d))
        return result
    }

    private static func direction(_ value: Double) -> WhyFactorDirection {
        value > 0 ? .up : (value < 0 ? .down : .none)
    }

    private static func volume(_ r: WhyReason) -> WhyFactor {
        switch r.kind {
        case .VOLUME_HIGH:
            return WhyFactor(kind: .volume, direction: .up, note: .volumeHigher, value: r.value,
                             strength: r.value / ActivityAnalyzer.volumeHighRatio, neutral: false)
        case .VOLUME_LOW:
            return WhyFactor(kind: .volume, direction: .down, note: .volumeLower, value: r.value,
                             strength: r.value > 0 ? ActivityAnalyzer.volumeLowRatio / r.value : 1, neutral: false)
        default:
            return WhyFactor(kind: .volume, direction: .none, note: .volumeUsual, value: r.value,
                             strength: 0, neutral: true)
        }
    }

    private static func market(_ r: WhyReason) -> WhyFactor {
        let threshold = ActivityAnalyzer.marketMovePercent
        switch r.kind {
        case .MARKET_WIDE:
            // value = BTC, secondary = Coin
            let follows = WhySummary.mark(r) == .supports
            return WhyFactor(kind: .market, direction: direction(r.value),
                             note: follows ? .marketPulls : .marketCoinLags, value: r.value, secondary: r.secondary,
                             // Coin zieht nicht mit: der Markt erklärt die Bewegung nur halb
                             strength: abs(r.value) / threshold * (follows ? 1 : 0.5), neutral: false)
        case .COIN_ONLY:
            // value = Coin, secondary = BTC → Faktor zeigt den Markt (BTC)
            return WhyFactor(kind: .market, direction: .none, note: .marketCoinAlone,
                             value: r.secondary ?? 0, secondary: r.value,
                             strength: abs(r.value) / ActivityAnalyzer.coinMovePercent, neutral: false)
        case .AGAINST_MARKET:
            let btc = r.secondary ?? 0
            return WhyFactor(kind: .market, direction: direction(btc), note: .marketAgainst,
                             value: btc, secondary: r.value,
                             strength: abs(r.value) / ActivityAnalyzer.coinMovePercent, neutral: false)
        case .MARKET_LEADER:
            // Bitcoin selbst: value = BTC, secondary = ETH
            let moves = abs(r.value) >= threshold
            return WhyFactor(kind: .market, direction: moves ? direction(r.value) : .none,
                             note: moves ? .marketLeaderMoves : .marketLeaderCalm,
                             value: r.value, secondary: r.secondary,
                             strength: moves ? abs(r.value) / threshold : 0, neutral: !moves)
        default:
            // MARKET_CALM: value = BTC, secondary = Coin
            return WhyFactor(kind: .market, direction: .none, note: .marketCalm, value: r.value,
                             secondary: r.secondary, strength: 0, neutral: true)
        }
    }

    private static func volatility(_ r: WhyReason) -> WhyFactor {
        if r.kind == .VOLATILITY_HIGH {
            return WhyFactor(kind: .volatility, direction: .up, note: .volatilityStronger, value: r.value,
                             strength: r.value / ActivityAnalyzer.volatilityHighZ, neutral: false)
        }
        return WhyFactor(kind: .volatility, direction: .none, note: .volatilityUsual, value: r.value,
                         strength: 0, neutral: true)
    }

    private static func funding(_ r: WhyReason) -> WhyFactor {
        let strength = abs(r.value) / ActivityAnalyzer.fundingExtremePercent
        switch r.kind {
        case .LEVERAGE_LONGS:
            return WhyFactor(kind: .funding, direction: .up, note: .fundingLongs, value: r.value,
                             strength: strength, neutral: false)
        case .LEVERAGE_SHORTS:
            return WhyFactor(kind: .funding, direction: .down, note: .fundingShorts, value: r.value,
                             strength: strength, neutral: false)
        default:
            return WhyFactor(kind: .funding, direction: .none, note: .fundingNeutral, value: r.value,
                             strength: 0, neutral: true)
        }
    }

    private static func openInterest(_ change: Double) -> WhyFactor {
        let notable = abs(change) >= oiNotablePercent
        let note: WhyFactorNote = !notable ? .oiFlat : (change > 0 ? .oiUp : .oiDown)
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

    private static func sentiment(_ r: WhyReason) -> WhyFactor {
        let extreme = r.tone == .warning
        var arrow: WhyFactorDirection = .none
        if let change = r.secondary, abs(change) >= sentimentArrowPoints { arrow = direction(change) }
        return WhyFactor(kind: .sentiment, direction: arrow, note: .sentiment, value: r.value, secondary: r.secondary,
                         strength: extreme ? 1 : 0, neutral: !extreme)
    }
}
