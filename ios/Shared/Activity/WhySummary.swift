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
        case .PEG:
            // Stablecoin: spürbare Abweichung von 1:1 gilt als erhöht (wie Android)
            return StablePeg.isNotable(reason.value) ? .caution : .neutral
        }
    }

    /// Schlüssel der Sätze von «Kurz gesagt», erster Satz zuerst; leer = nichts zeigen.
    static func keys(_ reasons: [WhyReason]) -> [String] {
        let kinds = Set(reasons.map(\.kind))
        let volumeHigh = kinds.contains(.VOLUME_HIGH)
        let leader = reasons.first { $0.kind == .MARKET_LEADER }
        let calm = kinds.contains(.MARKET_CALM) || (leader.map { !leaderMoving($0) } ?? false)
        let marketWide = kinds.contains(.MARKET_WIDE) || (leader.map(leaderMoving) ?? false)

        let peg = reasons.first { $0.kind == .PEG }

        var out: [String] = []
        if let peg {
            // Stablecoin: Abweichung von 1:1 statt Markt-Einordnung (wie Android)
            out.append(StablePeg.isNotable(peg.value) ? "why_summary_peg_off" : "why_summary_peg_near")
        } else if calm {
            out.append("why_summary_calm")
        } else if kinds.contains(.COIN_ONLY) {
            out.append(volumeHigh ? "why_summary_coin_volume" : "why_summary_coin")
        } else if kinds.contains(.AGAINST_MARKET) {
            out.append("why_summary_against")
        } else if marketWide {
            out.append(volumeHigh ? "why_summary_market_volume" : "why_summary_market")
        }
        if kinds.contains(.VOLUME_LOW) && !calm && peg == nil {
            out.append("why_summary_thin")
        }
        if kinds.contains(.LEVERAGE_LONGS) {
            out.append("why_summary_longs")
        } else if kinds.contains(.LEVERAGE_SHORTS) {
            out.append("why_summary_shorts")
        }
        return out
    }

    /// Marktweit nur «ähnlich»: Die gezeigte Referenz blieb unter ihrer Schwelle, bewegte sich aber in
    /// dieselbe Richtung und mindestens halb so stark wie der Coin (wie Android).
    static func isSimilarOnly(_ reason: WhyReason) -> Bool {
        guard let threshold = reason.threshold else { return false }
        return reason.kind == .MARKET_WIDE && abs(reason.value) < threshold
    }

    /// Bitcoin bewegt sich so stark, wie es für «der ganze Markt bewegt sich» nötig ist (Schwelle aus dem Bericht).
    static func leaderMoving(_ reason: WhyReason) -> Bool {
        reason.value.isFinite && abs(reason.value) >= (reason.threshold ?? ActivityAnalyzer.marketMovePercent)
    }

    private static func sameDirection(_ a: Double, _ b: Double?) -> Bool {
        guard let b, a.isFinite, b.isFinite, a != 0, b != 0 else { return false }
        return (a > 0) == (b > 0)
    }
}
