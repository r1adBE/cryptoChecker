import Foundation

// «Crypto Pulse» im Markt-Tab — reine Auswertung ohne Netz, wie
// `domain/market/CryptoPulse.kt`. Die Oberfläche übersetzt die Schlüssel und
// formatiert die Zahlen; Daten lädt `CryptoPulseSource`.

/// Eingaben. Alles optional — fehlende Bereiche entfallen.
struct PulseInput: Equatable, Sendable {
    /// 24-h-Veränderung in % (Binance-Ticker, sonst Coinbase).
    let btc: Double?
    let eth: Double?
    let sol: Double?
    /// BTC-Volumen der letzten abgeschlossenen Stunde ÷ Schnitt der 24 Stunden davor.
    let volumeRatio: Double?
    /// Fear & Greed (0–100).
    let fearGreed: Int?
    /// BTC-Funding in % je Periode (8 h), wie im «Warum»-Blatt.
    let fundingPercent: Double?
    /// Ethereum-Gebühr (normale Stufe) in gwei.
    let ethGasGwei: Double?
}

/// Eine Zeile «Markt heute», z. B. Bitcoin +3,2 %.
struct PulseCoinLine: Equatable, Sendable {
    let name: String
    let changePercent: Double
}

enum PulseAlts: Equatable, Sendable {
    case stronger, weaker, even

    var key: String {
        switch self {
        case .stronger: "pulse_alts_stronger"
        case .weaker: "pulse_alts_weaker"
        case .even: "pulse_alts_even"
        }
    }
}

enum PulseVolume: Equatable, Sendable {
    case high, low, normal

    var key: String {
        switch self {
        case .high: "pulse_volume_high"
        case .low: "pulse_volume_low"
        case .normal: "pulse_volume_normal"
        }
    }
}

enum PulseFunding: Equatable, Sendable {
    case high, slight, neutral, negative

    var key: String {
        switch self {
        case .high: "pulse_funding_high"
        case .slight: "pulse_funding_slight"
        case .neutral: "pulse_funding_neutral"
        case .negative: "pulse_funding_negative"
        }
    }
}

enum PulseGas: Equatable, Sendable {
    case low, normal, high

    var key: String {
        switch self {
        case .low: "pulse_gas_low"
        case .normal: "pulse_gas_normal"
        case .high: "pulse_gas_high"
        }
    }
}

enum PulseSummary: Equatable, Sendable {
    case broadUp, broadUpVolume, broadDown, broadDownVolume, mixed, calm
}

/// Erster Satz unter der Schlagzeile («Was gerade auffällt»), wie `PulseLeadKind` in Android.
enum PulseLeadKind: Equatable, Sendable {
    case btcLeads, altsStronger, btcStronger, broadUp, broadDown, driftUp, driftDown, mixed, calm

    var key: String {
        switch self {
        case .btcLeads: "pulse_lead_btc_leads"
        case .altsStronger: "pulse_lead_alts_stronger"
        case .btcStronger: "pulse_lead_btc_stronger"
        case .broadUp: "pulse_lead_broad_up"
        case .broadDown: "pulse_lead_broad_down"
        case .driftUp: "pulse_lead_drift_up"
        case .driftDown: "pulse_lead_drift_down"
        case .mixed: "pulse_lead_mixed"
        case .calm: "pulse_lead_calm"
        }
    }
}

/// Zweiter Satz: Volumen und/oder Funding, je Fall ein ganzer Satz.
/// Die `volAbove…`- und `volBelow…`-Fälle tragen `PulseLead.volumePercent` als Platzhalter.
enum PulseDetail: Equatable, Sendable {
    case volAbove, volAboveFundNeutral, volAboveFundHigh, volAboveFundNegative
    case volBelow, volBelowFundNeutral, volBelowFundHigh, volBelowFundNegative
    case volNormal, volNormalFundNeutral, volNormalFundHigh, volNormalFundNegative
    case fundNeutral, fundHigh, fundNegative

    var key: String {
        switch self {
        case .volAbove: "pulse_detail_vol_above"
        case .volAboveFundNeutral: "pulse_detail_vol_above_fund_neutral"
        case .volAboveFundHigh: "pulse_detail_vol_above_fund_high"
        case .volAboveFundNegative: "pulse_detail_vol_above_fund_negative"
        case .volBelow: "pulse_detail_vol_below"
        case .volBelowFundNeutral: "pulse_detail_vol_below_fund_neutral"
        case .volBelowFundHigh: "pulse_detail_vol_below_fund_high"
        case .volBelowFundNegative: "pulse_detail_vol_below_fund_negative"
        case .volNormal: "pulse_detail_vol_normal"
        case .volNormalFundNeutral: "pulse_detail_vol_normal_fund_neutral"
        case .volNormalFundHigh: "pulse_detail_vol_normal_fund_high"
        case .volNormalFundNegative: "pulse_detail_vol_normal_fund_negative"
        case .fundNeutral: "pulse_detail_fund_neutral"
        case .fundHigh: "pulse_detail_fund_high"
        case .fundNegative: "pulse_detail_fund_negative"
        }
    }

    /// Satz mit Prozent-Platzhalter?
    var hasPercent: Bool {
        switch self {
        case .volAbove, .volAboveFundNeutral, .volAboveFundHigh, .volAboveFundNegative,
             .volBelow, .volBelowFundNeutral, .volBelowFundHigh, .volBelowFundNegative: true
        default: false
        }
    }
}

/// Schlüssel + Argumente des Leitsatzes; Text macht die Oberfläche.
struct PulseLead: Equatable, Sendable {
    let kind: PulseLeadKind
    /// nil = weder Volumen noch Funding bekannt → nur ein Satz.
    let detail: PulseDetail?
    /// Abweichung vom üblichen Volumen in ganzen Prozent, ohne Vorzeichen (1.34 → 34).
    let volumePercent: Int
}

/// Zeile der Faktor-Checkliste unter «Warum?». Nur Werte ohne eigene Karte im
/// Markt-Tab: Volumen (Krypto-Markt), Fear & Greed und Gas haben eigene Karten.
enum PulseFactorKind: Equatable, Sendable {
    case funding
}

struct PulseFactor: Equatable, Sendable {
    let kind: PulseFactorKind
    let mark: WhySummary.Mark
}

/// Ergebnis; `nil` von `CryptoPulse.evaluate` = Marktdaten fehlen (`pulse_unavailable`).
struct PulseReport: Equatable, Sendable {
    let coins: [PulseCoinLine]
    let alts: PulseAlts
    let volume: PulseVolume?
    let volumeRatio: Double?
    let fearGreed: Int?
    let funding: PulseFunding?
    /// Funding in % je Periode (für die Checkliste).
    let fundingPercent: Double?
    let gas: PulseGas?
    let gasGwei: Double?
    let summary: PulseSummary
}

enum CryptoPulse {
    // Schwellen (gleich wie Android)
    static let broadMovePercent = 1.5
    static let calmPercent = 1.0
    static let altsGapPoints = 1.5
    static let volumeHighRatio = 1.5
    static let volumeLowRatio = 0.6
    static let fundingHighPercent = 0.03
    static let fundingSlightPercent = 0.01
    static let fundingNegativePercent = -0.005
    static let gasLowGwei = 2.0
    static let gasHighGwei = 10.0

    /// nil, wenn eine der drei 24-h-Veränderungen fehlt.
    static func evaluate(_ input: PulseInput) -> PulseReport? {
        guard let btc = finite(input.btc), let eth = finite(input.eth), let sol = finite(input.sol) else { return nil }
        let ratio = finite(input.volumeRatio)
        let funding = finite(input.fundingPercent)
        let gasGwei = finite(input.ethGasGwei).flatMap { $0 > 0 ? $0 : nil }
        let fearGreed = input.fearGreed.flatMap { (0...100).contains($0) ? $0 : nil }

        return PulseReport(
            coins: [
                PulseCoinLine(name: "Bitcoin", changePercent: btc),
                PulseCoinLine(name: "Ethereum", changePercent: eth),
                PulseCoinLine(name: "Solana", changePercent: sol),
            ],
            alts: alts(btc: btc, eth: eth, sol: sol),
            volume: ratio.map(volume),
            volumeRatio: ratio,
            fearGreed: fearGreed,
            funding: funding.map(fundingLevel),
            fundingPercent: funding,
            gas: gasGwei.map(gas),
            gasGwei: gasGwei,
            summary: summary(btc: btc, eth: eth, sol: sol, volumeRatio: ratio)
        )
    }

    static func alts(btc: Double, eth: Double, sol: Double) -> PulseAlts {
        let gap = (eth + sol) / 2 - btc
        if gap >= altsGapPoints { return .stronger }
        if gap <= -altsGapPoints { return .weaker }
        return .even
    }

    static func volume(_ ratio: Double) -> PulseVolume {
        if ratio >= volumeHighRatio { return .high }
        if ratio <= volumeLowRatio { return .low }
        return .normal
    }

    static func fundingLevel(_ percent: Double) -> PulseFunding {
        if percent >= fundingHighPercent { return .high }
        if percent >= fundingSlightPercent { return .slight }
        if percent > fundingNegativePercent { return .neutral }
        return .negative
    }

    static func gas(_ gwei: Double) -> PulseGas {
        if gwei < gasLowGwei { return .low }
        if gwei <= gasHighGwei { return .normal }
        return .high
    }

    /// Volumen gilt bis ±5 % Abweichung als «normal».
    static let volumeNormalBandPercent = 5

    /// Leitsatz unter der Schlagzeile, höchstens zwei kurze Sätze — gleiche Regeln
    /// und Reihenfolge wie `CryptoPulse.leadSentence` in Android:
    /// 1. ruhig → breit fallend → breit steigend mit BTC als grösster Bewegung →
    ///    Altcoins stärker / schwächer → breit steigend → leicht steigend/fallend
    ///    (alle gleichgerichtet) → auseinander.
    /// 2. Volumen (echtes Verhältnis, ±5 % = normal) und Funding, sofern bekannt.
    static func leadSentence(_ report: PulseReport) -> PulseLead {
        let all = report.coins.map(\.changePercent)
        let btc = all.first ?? 0
        let broadUp = report.summary == .broadUp || report.summary == .broadUpVolume
        let broadDown = report.summary == .broadDown || report.summary == .broadDownVolume
        let btcLargest = all.allSatisfy { abs(btc) >= abs($0) }

        let kind: PulseLeadKind
        if report.summary == .calm {
            kind = .calm
        } else if broadDown {
            kind = .broadDown
        } else if broadUp && btcLargest {
            kind = .btcLeads
        } else if report.alts == .stronger {
            kind = .altsStronger
        } else if report.alts == .weaker {
            kind = .btcStronger
        } else if broadUp {
            kind = .broadUp
        } else if all.allSatisfy({ $0 >= 0 }) && all.contains(where: { $0 > 0 }) {
            kind = .driftUp
        } else if all.allSatisfy({ $0 <= 0 }) && all.contains(where: { $0 < 0 }) {
            kind = .driftDown
        } else {
            kind = .mixed
        }

        // Volumen: −1 unter, 0 normal, +1 über, nil unbekannt
        // Wie `Math.round` in Kotlin: halbe Werte Richtung +∞ (−4.5 → −4)
        let deviation: Int? = report.volumeRatio.map { Int((($0 - 1) * 100 + 0.5).rounded(.down)) }
        let volume: Int? = deviation.map { d in
            if abs(d) < volumeNormalBandPercent { return 0 }
            return d > 0 ? 1 : -1
        }
        // Funding: «leicht positiv» ist der übliche Grundsatz der Börsen → neutral
        let funding: Int?
        switch report.funding {
        case .high?: funding = 1
        case .negative?: funding = -1
        case .slight?, .neutral?: funding = 0
        case nil: funding = nil
        }
        var percent = 0
        if let deviation, volume != 0 { percent = abs(deviation) }
        return PulseLead(kind: kind, detail: detail(volume: volume, funding: funding), volumePercent: percent)
    }

    private static func detail(volume: Int?, funding: Int?) -> PulseDetail? {
        switch (volume, funding) {
        case (1?, 0?): .volAboveFundNeutral
        case (1?, 1?): .volAboveFundHigh
        case (1?, .some(-1)): .volAboveFundNegative
        case (1?, _): .volAbove
        case (.some(-1), 0?): .volBelowFundNeutral
        case (.some(-1), 1?): .volBelowFundHigh
        case (.some(-1), .some(-1)): .volBelowFundNegative
        case (.some(-1), _): .volBelow
        case (0?, 0?): .volNormalFundNeutral
        case (0?, 1?): .volNormalFundHigh
        case (0?, .some(-1)): .volNormalFundNegative
        case (0?, _): .volNormal
        case (nil, 0?): .fundNeutral
        case (nil, 1?): .fundHigh
        case (nil, .some(-1)): .fundNegative
        default: nil
        }
    }

    /// Faktor-Checkliste (nur bekannte Werte): ! Vorsicht, – neutral. Volumen,
    /// Fear & Greed und Gas stehen in eigenen Karten des Markt-Tabs und fehlen
    /// hier bewusst; leer = Abschnitt entfällt. Wie Android.
    static func factors(_ report: PulseReport) -> [PulseFactor] {
        var out: [PulseFactor] = []
        if let level = report.funding {
            let caution = level == .high || level == .negative
            out.append(PulseFactor(kind: .funding, mark: caution ? .caution : .neutral))
        }
        return out
    }

    static func summary(btc: Double, eth: Double, sol: Double, volumeRatio: Double?) -> PulseSummary {
        let all = [btc, eth, sol]
        let highVolume = (volumeRatio ?? 0) >= volumeHighRatio
        if all.allSatisfy({ $0 >= broadMovePercent }) { return highVolume ? .broadUpVolume : .broadUp }
        if all.allSatisfy({ $0 <= -broadMovePercent }) { return highVolume ? .broadDownVolume : .broadDown }
        if all.allSatisfy({ abs($0) < calmPercent }) { return .calm }
        return .mixed
    }

    private static func finite(_ value: Double?) -> Double? {
        guard let value, value.isFinite else { return nil }
        return value
    }
}
