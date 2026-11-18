import Foundation

/// Art des Leitsatzes im Widget: stärkster Coin in Richtung des Markts, beide Enden oder der Satz der Karte.
enum PulseWidgetLeadKind: Equatable, Sendable {
    case up, down, mixed, card
}

/// Leitsatz des Widgets «Was gerade auffällt»: `kind` mit `top`/`bottom` (Gewinner/Verlierer der Rangliste) oder bei
/// `.card` der Satz der Karte (`card`); `detail` nur, wenn er etwas sagt (sonst nil).
struct PulseWidgetLeadChoice: Equatable, Sendable {
    let kind: PulseWidgetLeadKind
    let top: MoverCoin?
    let bottom: MoverCoin?
    let card: PulseLead
    let detail: PulseDetail?
}

/// Konkreter Leitsatz für das Widget (reine Regel, wie Android `PulseWidgetLead`): statt «Der Markt gibt breit nach»
/// der Coin, der am stärksten fällt (bzw. steigt; gemischt beide Enden) mit seiner 24-h-Veränderung — aus derselben
/// Rangliste wie «Gewinner und Verlierer 24 h» (`movers`). Ohne Rangliste und bei ruhigem Markt der Satz der Karte.
/// Der Zusatz (Volumen, Hebel) entfällt, wenn er nur «normal/neutral» sagt. Keine Prognose: nur Werte der letzten 24 h.
enum PulseWidgetLead {
    static func choose(_ report: PulseReport, movers: MarketMoversResult?) -> PulseWidgetLeadChoice {
        let card = CryptoPulse.leadSentence(report)
        let top = movers?.gainers.first { ($0.change24h ?? 0) > 0 }
        let bottom = movers?.losers.first { ($0.change24h ?? 0) < 0 }
        let kind: PulseWidgetLeadKind
        switch report.summary {
        case .broadUp, .broadUpVolume: kind = top != nil ? .up : .card
        case .broadDown, .broadDownVolume: kind = bottom != nil ? .down : .card
        case .mixed: kind = top != nil && bottom != nil ? .mixed : .card
        case .calm: kind = .card
        }
        return PulseWidgetLeadChoice(kind: kind, top: top, bottom: bottom, card: card, detail: notable(card.detail))
    }

    /// Zusatz ohne die Teile «normal» und «neutral»; nichts Auffälliges = nil.
    static func notable(_ detail: PulseDetail?) -> PulseDetail? {
        switch detail {
        case nil, .volNormal, .volNormalFundNeutral, .fundNeutral: nil
        case .volAboveFundNeutral: .volAbove
        case .volBelowFundNeutral: .volBelow
        case .volNormalFundHigh: .fundHigh
        case .volNormalFundNegative: .fundNegative
        case let other: other
        }
    }
}
