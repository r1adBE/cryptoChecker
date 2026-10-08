import Foundation

/// «Kurz gesagt» und Status-Markierungen im «Warum»-Blatt — reine Auswertung
/// der vorhandenen Gründe, wie `WhySummary` in Android.
enum WhySummary {

    /// Markierung vor dem Titel eines Grundes.
    enum Mark: Equatable, Sendable {
        /// ✓ stützt die Bewegung
        case supports
        /// ! erhöht / Vorsicht
        case caution
        /// – neutral
        case neutral

        var glyph: String {
            switch self {
            case .supports: "✓"
            case .caution: "!"
            case .neutral: "–"
            }
        }

        /// Wird von VoiceOver an die Zeile angehängt.
        var a11yKey: String {
            switch self {
            case .supports: "why_dot_supports"
            case .caution: "why_dot_caution"
            case .neutral: "why_dot_neutral"
            }
        }
    }

    static func mark(_ reason: WhyReason) -> Mark {
        switch reason.kind {
        case .VOLUME_HIGH, .VOLATILITY_HIGH:
            return .supports
        case .MARKET_WIDE:
            // Markt bewegt sich in dieselbe Richtung wie der Coin
            return sameDirection(reason.value, reason.secondary) ? .supports : .neutral
        case .MARKET_LEADER:
            // Bitcoin ist selbst der Markt: bewegt er sich deutlich, stützt das die Bewegung (wie Android)
            return leaderMoving(reason) ? .supports : .neutral
        case .LEVERAGE_LONGS, .LEVERAGE_SHORTS, .VOLUME_LOW, .AGAINST_MARKET:
            return .caution
        case .SENTIMENT:
            // Extreme Angst oder Gier gilt als erhöht (wie Android)
            return reason.tone == .warning ? .caution : .neutral
        case .COIN_ONLY, .MARKET_CALM, .VOLUME_NORMAL, .LEVERAGE_BALANCED, .VOLATILITY_NORMAL:
            return .neutral
        }
    }

    /// Schlüssel der Sätze von «Kurz gesagt», erster Satz zuerst; leer = nichts zeigen.
    static func keys(_ reasons: [WhyReason]) -> [String] {
        let kinds = Set(reasons.map(\.kind))
        let volumeHigh = kinds.contains(.VOLUME_HIGH)
        let leader = reasons.first { $0.kind == .MARKET_LEADER }
        let calm = kinds.contains(.MARKET_CALM) || (leader.map { !leaderMoving($0) } ?? false)
        let marketWide = kinds.contains(.MARKET_WIDE) || (leader.map(leaderMoving) ?? false)

        var out: [String] = []
        if calm {
            out.append("why_summary_calm")
        } else if kinds.contains(.COIN_ONLY) {
            out.append(volumeHigh ? "why_summary_coin_volume" : "why_summary_coin")
        } else if kinds.contains(.AGAINST_MARKET) {
            out.append("why_summary_against")
        } else if marketWide {
            out.append(volumeHigh ? "why_summary_market_volume" : "why_summary_market")
        }
        if kinds.contains(.VOLUME_LOW) && !calm {
            out.append("why_summary_thin")
        }
        if kinds.contains(.LEVERAGE_LONGS) {
            out.append("why_summary_longs")
        } else if kinds.contains(.LEVERAGE_SHORTS) {
            out.append("why_summary_shorts")
        }
        return out
    }

    // MARK: Sicherheit der Einordnung

    /// «Sicherheit: hoch / mittel / niedrig» der wahrscheinlichen Gründe.
    enum ConfidenceLevel: Equatable, Sendable {
        case low, medium, high

        var key: String {
            switch self {
            case .high: "why_confidence_high"
            case .medium: "why_confidence_medium"
            case .low: "why_confidence_low"
            }
        }
    }

    /// Wie sicher die Einordnung ist — nie eine sichere Ursache, nur wie viele unabhängige
    /// Hinweise zusammenpassen («2 von 4 Hinweisen deuten darauf hin»). Wie `WhyConfidence` (Android).
    struct Confidence: Equatable, Sendable {
        let level: ConfidenceLevel
        /// So viele Hinweise deuten auf die Einordnung.
        let agreeing: Int
        /// So viele Hinweise liessen sich prüfen (Markt vs. Coin, Volumen, Volatilität, Hebel).
        let total: Int
        /// Daten unvollständig (Marktvergleich oder mehrere Hinweise fehlen).
        let partialData: Bool
    }

    /// Ab so vielen geprüften Hinweisen gelten die Daten als vollständig genug.
    static let minHintsForGoodData = 3
    /// «hoch» braucht so viele passende starke Hinweise.
    static let highMinStrong = 3

    private enum Hint: Hashable { case market, volume, volatility, leverage }
    private enum Fit { case agrees, contradicts, neutral }

    private static func hint(_ kind: WhyReasonKind) -> Hint? {
        switch kind {
        case .MARKET_WIDE, .COIN_ONLY, .AGAINST_MARKET, .MARKET_CALM, .MARKET_LEADER: return .market
        case .VOLUME_HIGH, .VOLUME_LOW, .VOLUME_NORMAL: return .volume
        case .VOLATILITY_HIGH, .VOLATILITY_NORMAL: return .volatility
        case .LEVERAGE_LONGS, .LEVERAGE_SHORTS, .LEVERAGE_BALANCED: return .leverage
        case .SENTIMENT: return nil
        }
    }

    private static func fit(_ r: WhyReason, calm: Bool) -> Fit {
        if calm {
            switch r.kind {
            case .MARKET_CALM: return .agrees
            case .MARKET_LEADER: return mark(r) == .supports ? .contradicts : .agrees
            case .VOLUME_NORMAL, .VOLUME_LOW, .VOLATILITY_NORMAL: return .agrees
            case .VOLUME_HIGH, .VOLATILITY_HIGH: return .contradicts
            case .LEVERAGE_BALANCED: return r.strong ? .neutral : .agrees
            default: return .neutral
            }
        }
        switch r.kind {
        case .COIN_ONLY, .AGAINST_MARKET: return .agrees
        case .MARKET_WIDE, .MARKET_LEADER: return mark(r) == .supports ? .agrees : .neutral
        case .VOLUME_HIGH, .VOLATILITY_HIGH: return .agrees
        // Wenig Volumen hinter einer Bewegung: spricht gegen eine klare Einordnung
        case .VOLUME_LOW: return .contradicts
        case .LEVERAGE_LONGS, .LEVERAGE_SHORTS: return .agrees
        // Ausgeglichenes Funding zählt nur mit sprunghaftem Open Interest
        case .LEVERAGE_BALANCED: return r.strong ? .agrees : .neutral
        default: return .neutral
        }
    }

    /// Sicherheit der Einordnung (Hinweise: Markt vs. Coin, Volumen, Volatilität, Hebel;
    /// Fear & Greed zählt nicht). nil = nichts prüfbar, dann keine Sicherheit zeigen.
    ///  - hoch: ≥ 3 passende starke Hinweise, vollständige Daten, kein Widerspruch
    ///  - niedrig: unvollständige Daten, keine Einordnung, kein oder nur ein schwacher Hinweis
    ///  - sonst mittel
    static func confidence(_ reasons: [WhyReason], hasMarketData: Bool) -> Confidence? {
        guard hasMarketData else { return nil }
        var byHint: [Hint: WhyReason] = [:]
        var order: [Hint] = []
        for r in reasons {
            guard let h = hint(r.kind), byHint[h] == nil else { continue }
            byHint[h] = r
            order.append(h)
        }
        guard !order.isEmpty else { return nil }

        let kinds = Set(reasons.map(\.kind))
        let leader = reasons.first { $0.kind == .MARKET_LEADER }
        let calm = kinds.contains(.MARKET_CALM) || (leader.map { !leaderMoving($0) } ?? false)
        let hasHeadline = calm || kinds.contains(.COIN_ONLY) || kinds.contains(.AGAINST_MARKET)
            || kinds.contains(.MARKET_WIDE) || leader != nil

        var agreeing = 0
        var strongAgreeing = 0
        var contradiction = false
        for h in order {
            guard let r = byHint[h] else { continue }
            switch fit(r, calm: calm) {
            case .agrees:
                agreeing += 1
                // Ruhe ist keine Stärke-Frage: jeder passende Hinweis zählt voll
                if calm || r.strong { strongAgreeing += 1 }
            case .contradicts:
                contradiction = true
            case .neutral:
                break
            }
        }
        let total = order.count
        let partial = byHint[.market] == nil || total < minHintsForGoodData
        let level: ConfidenceLevel
        if partial || !hasHeadline || agreeing == 0 || (agreeing == 1 && strongAgreeing == 0) {
            level = .low
        } else if strongAgreeing >= highMinStrong && !contradiction {
            level = .high
        } else {
            level = .medium
        }
        return Confidence(level: level, agreeing: agreeing, total: total, partialData: partial)
    }

    /// Bitcoin bewegt sich so stark, wie es für «der ganze Markt bewegt sich» nötig ist.
    private static func leaderMoving(_ reason: WhyReason) -> Bool {
        reason.value.isFinite && abs(reason.value) >= ActivityAnalyzer.marketMovePercent
    }

    private static func sameDirection(_ a: Double, _ b: Double?) -> Bool {
        guard let b, a.isFinite, b.isFinite, a != 0, b != 0 else { return false }
        return (a > 0) == (b > 0)
    }
}
