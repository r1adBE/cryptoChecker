import Foundation

// «Crypto Pulse» im Markt-Tab — reine Auswertung ohne Netz, wie
// `domain/market/CryptoPulse.kt`. Die Oberfläche übersetzt die Schlüssel und
// formatiert die Zahlen; Daten lädt `CryptoPulseSource`.

enum CryptoPulse {
    // Schwellen (gleich wie Android)
    static let altsGapPoints = 1.5
    static let volumeHighRatio = 1.5
    static let volumeLowRatio = 0.6
    static let fundingHighPercent = 0.03
    static let fundingSlightPercent = 0.01
    static let fundingNegativePercent = -0.005
    static let gasLowGwei = 2.0
    static let gasHighGwei = 10.0
    /// Marktbreite aus so vielen der grössten Coins; darunter nichts (zu wenig aussagekräftig).
    static let breadthCoins = 30
    static let breadthMinCoins = 15
    /// Ab diesem Anteil steigender bzw. fallender Coins gilt die Bewegung als breit.
    static let breadthBroadShare = 0.75
    /// Bitcoin bewegt sich mindestens so weit, die Mehrheit aber nicht mit → «schmal».
    static let breadthBtcMovePercent = 1.0
    static let breadthNarrowShare = 0.4

    /// nil, wenn eine der drei 24-h-Veränderungen fehlt.
    static func evaluate(_ input: PulseInput) -> PulseReport? {
        guard let btc = finite(input.btc), let eth = finite(input.eth), let sol = finite(input.sol) else { return nil }
        let ratio = finite(input.volumeRatio)
        let funding = finite(input.fundingPercent)
        let gasGwei = finite(input.ethGasGwei).flatMap { $0 > 0 ? $0 : nil }
        let fearGreed = input.fearGreed.flatMap { (0...100).contains($0) ? $0 : nil }

        let breadth = Self.breadth(input.topChanges)
        let cap = finite(input.marketCapUsd).flatMap { $0 > 0 ? $0 : nil }
        let cap24h = cap == nil ? nil : finite(input.marketCap24h)
        let move = MarketMoveScale.decide(btc: btc, eth: eth, sol: sol, marketChange: cap24h, breadth: breadth)
        var report = PulseReport(
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
            summary: summary(move, volumeRatio: ratio)
        )
        report.breadth = breadth
        report.breadthNote = breadth.flatMap { breadthNote($0, btc24h: btc) }
        report.marketCapUsd = cap
        report.marketCap24h = cap24h
        return report
    }

    /// Steigend (> 0), fallend (< 0) und Anzahl der ersten `breadthCoins` gültigen Werte;
    /// nil bei weniger als `breadthMinCoins`.
    static func breadth(_ changes: [Double]) -> PulseBreadth? {
        let valid = Array(changes.filter(\.isFinite).prefix(breadthCoins))
        guard valid.count >= breadthMinCoins else { return nil }
        return PulseBreadth(up: valid.filter { $0 > 0 }.count, down: valid.filter { $0 < 0 }.count, total: valid.count)
    }

    /// Satz nur in deutlichen Fällen (wie Android `CryptoPulse.breadthNote`).
    static func breadthNote(_ breadth: PulseBreadth, btc24h: Double) -> PulseBreadthNote? {
        let upShare = Double(breadth.up) / Double(breadth.total)
        let downShare = Double(breadth.down) / Double(breadth.total)
        if upShare >= breadthBroadShare - 1e-9 { return .broadUp }
        if downShare >= breadthBroadShare - 1e-9 { return .broadDown }
        if btc24h >= breadthBtcMovePercent && upShare < breadthNarrowShare { return .narrowUp }
        if btc24h <= -breadthBtcMovePercent && downShare < breadthNarrowShare { return .narrowDown }
        return nil
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
    /// 1. Nach derselben Richtung und Stärke wie die Schlagzeile (`move`): ruhig → deutlich fallend
    ///    («breit») → deutlich steigend mit BTC als grösster Bewegung → Altcoins stärker / schwächer →
    ///    deutlich steigend → leicht steigend/fallend → auseinander. «Leicht» kommt so nur, wo auch der
    ///    Gesamtwert nicht «deutlich» ist.
    /// 2. Volumen (echtes Verhältnis, ±5 % = normal) und Funding, sofern bekannt.
    static func leadSentence(_ report: PulseReport) -> PulseLead {
        let all = report.coins.map(\.changePercent)
        let btc = all.first ?? 0
        let move = Self.move(report)
        let clear = move.level == .clear
        let up = move.direction == .up
        let down = move.direction == .down
        let btcLargest = btc > 0 && all.allSatisfy { abs(btc) >= abs($0) }

        let kind: PulseLeadKind
        if move.direction == .calm {
            kind = .calm
        } else if down && clear {
            kind = .broadDown
        } else if up && clear && btcLargest && report.alts != .stronger {
            // «Bitcoin steigt am stärksten» nur, wenn die Altcoins nicht klar stärker sind
            kind = .btcLeads
        } else if report.alts == .stronger {
            kind = .altsStronger
        } else if report.alts == .weaker {
            kind = .btcStronger
        } else if up && clear {
            kind = .broadUp
        } else if up {
            kind = .driftUp
        } else if down {
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

    /// Schlagzeile aus der Marktrichtung (`MarketMoveScale.decide`) und dem BTC-Volumen.
    static func summary(_ move: MarketMove, volumeRatio: Double?) -> PulseSummary {
        let highVolume = (volumeRatio ?? 0) >= volumeHighRatio
        switch move.direction {
        case .up: return highVolume ? .broadUpVolume : .broadUp
        case .down: return highVolume ? .broadDownVolume : .broadDown
        case .calm: return .calm
        case .mixed: return .mixed
        }
    }

    /// Marktrichtung eines Berichts — dieselbe Entscheidung wie für `PulseReport.summary`.
    static func move(_ report: PulseReport) -> MarketMove {
        let changes = report.coins.map(\.changePercent)
        guard changes.count == 3 else { return MarketMove(direction: .mixed, level: nil) }
        return MarketMoveScale.decide(btc: changes[0], eth: changes[1], sol: changes[2],
                                      marketChange: report.marketCap24h, breadth: report.breadth)
    }

    private static func finite(_ value: Double?) -> Double? {
        guard let value, value.isFinite else { return nil }
        return value
    }
}
