import SwiftUI

// Faktoren von «Warum bewegt sich das?» — wie `WatchWhyFactors.kt`.

/// Kurzer Titel, Wert rechts (mit gesprochener Fassung) und Erklärung eines Grundes.
enum WatchlistWhyTexts {
    struct Factor {
        let title: String
        let value: String
        var spokenValue: String? = nil
    }

    static func factor(_ r: WhyReason) -> Factor {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func ref(_ title: String, _ symbol: String, _ v: Double?) -> Factor {
            guard let v, v.isFinite else { return Factor(title: title, value: "") }
            return Factor(title: title, value: symbol + " " + pct(v),
                          spokenValue: A11y.join([symbol, A11y.change(abs(v) < 0.05 ? 0 : v)]))
        }

        switch r.kind {
        case .MARKET_WIDE:
            return ref(L("why_f_market_wide"), "BTC", r.value)
        case .COIN_ONLY:
            return ref(L("why_f_coin_only"), "BTC", r.secondary)
        case .AGAINST_MARKET:
            return ref(L("why_f_against"), "BTC", r.secondary)
        case .MARKET_CALM:
            return ref(L("why_f_market_calm"), "BTC", r.value)
        case .MARKET_LEADER:
            // Der Coin ist Bitcoin selbst (24h steht oben): rechts ETH zum Vergleich
            return ref(L("why_f_leader"), "ETH", r.secondary)
        case .VOLUME_HIGH, .VOLUME_LOW, .VOLUME_NORMAL:
            let number = r.value.isFinite ? String(format: "%.2f", locale: Locale.current, r.value) : "—"
            return Factor(title: L("factor_volume"), value: number + "×",
                          spokenValue: L("factor_spoken_volume", number))
        case .LEVERAGE_LONGS:
            return Factor(title: L("why_f_leverage_longs"), value: ActivityTexts.percent(r.value, 3))
        case .LEVERAGE_SHORTS:
            return Factor(title: L("why_f_leverage_shorts"), value: ActivityTexts.percent(r.value, 3))
        case .LEVERAGE_BALANCED:
            return Factor(title: L("why_f_leverage_balanced"), value: ActivityTexts.percent(r.value, 3))
        case .VOLATILITY_HIGH, .VOLATILITY_NORMAL:
            let number = ActivityTexts.factor(r.value)
            return Factor(title: L("factor_volatility"), value: number + "×",
                          spokenValue: L("factor_spoken_volatility", number))
        case .SENTIMENT:
            let value = r.value.isFinite ? Int(r.value.rounded()) : 0
            let label = fearGreedLabel(value)
            let number = LocaleNumbers.integer(value)
            return Factor(title: L("factor_fear_greed"), value: "\(number) · \(label)",
                          spokenValue: "\(number), \(label)")
        }
    }

    /// Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz.
    struct FactorLine {
        let title: String
        let value: String
        let note: String
        let spoken: String
    }

    static func factorLine(_ f: WhyFactor) -> FactorLine {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func spokenChange(_ v: Double) -> String { A11y.change(abs(v) < 0.05 ? 0 : v) ?? "" }
        let title: String
        var value = ""
        var spokenValue = ""
        var note = f.note.key.map { L($0) } ?? ""
        switch f.kind {
        case .volume:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volume")
            value = number + "×"
            spokenValue = L("factor_spoken_volume", number)
        case .market:
            title = L("why_factor_market")
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            let leader = f.note == .marketLeaderMoves || f.note == .marketLeaderCalm
            let symbol = leader ? "ETH" : "BTC"
            let change: Double? = leader ? f.secondary : f.value
            if let change, change.isFinite {
                value = symbol + " " + pct(change)
                spokenValue = A11y.join([symbol, spokenChange(change)])
            }
        case .volatility:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volatility")
            value = number + "×"
            spokenValue = L("factor_spoken_volatility", number)
        case .openInterest:
            title = L("why_factor_futures")
            value = pct(f.value)
            spokenValue = spokenChange(f.value)
        case .nearHigh:
            title = L("why_factor_near_high")
            if f.note == .highBelow {
                value = pct(-f.value)
                spokenValue = spokenChange(-f.value)
            }
        case .funding:
            title = L("why_factor_funding")
            value = ActivityTexts.percent(f.value, 3)
            spokenValue = value
        case .sentiment:
            let points = f.value.isFinite ? Int(f.value.rounded()) : 0
            title = L("factor_fear_greed")
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
        let spoken = "\(title): \(note)" + (spokenValue.isEmpty ? "" : ", \(spokenValue)") + "."
        return FactorLine(title: title, value: value, note: note, spoken: spoken)
    }

    /// Erklärung mit formatierten Zahlen (unter «Details» bzw. bei «!»).
    static func explanation(_ r: WhyReason) -> String {
        func pct(_ v: Double?) -> String { v.map { ActivityTexts.percent($0, 1) } ?? "—" }
        func withOpenInterest(_ text: String, _ change: Double?) -> String {
            guard let change else { return text }
            return text + " " + L("why_open_interest_change", ActivityTexts.percent(change, 1))
        }

        switch r.kind {
        case .MARKET_WIDE:
            return L("why_market_wide_text", pct(r.secondary))
        case .COIN_ONLY:
            return L("why_coin_only_text", pct(r.value))
        case .AGAINST_MARKET:
            return L("why_against_market_text", pct(r.value), pct(r.secondary))
        case .MARKET_CALM:
            return L("why_market_calm_text", pct(r.secondary))
        case .MARKET_LEADER:
            return r.secondary.map { L("why_market_leader_text", pct($0)) } ?? L("why_market_leader_text_no_eth")
        case .VOLUME_HIGH:
            return L("why_volume_high_text")
        case .VOLUME_LOW:
            return L("why_volume_low_text")
        case .VOLUME_NORMAL:
            return L("why_volume_normal_text")
        case .LEVERAGE_LONGS:
            return withOpenInterest(L("why_leverage_longs_text"), r.secondary)
        case .LEVERAGE_SHORTS:
            return withOpenInterest(L("why_leverage_shorts_text"), r.secondary)
        case .LEVERAGE_BALANCED:
            return withOpenInterest(L("why_leverage_balanced_text"), r.secondary)
        case .VOLATILITY_HIGH:
            return L("why_volatility_high_text", pct(r.secondary))
        case .VOLATILITY_NORMAL:
            return L("why_volatility_normal_text", pct(r.secondary))
        case .SENTIMENT:
            if let change = r.secondary, change.isFinite {
                return L("why_sentiment_text", signedInt(Int(change.rounded())))
            }
            return L("why_sentiment_text_no_change")
        }
    }

    private static func signedInt(_ value: Int) -> String {
        if value > 0 { return "+" + LocaleNumbers.integer(value) }
        if value < 0 { return "−" + LocaleNumbers.integer(-value) }
        return "±" + LocaleNumbers.integer(0)
    }

    private static func fearGreedLabel(_ value: Int) -> String {
        switch ActivityAnalyzer.fearGreedLevel(value) {
        case .extremeFear: L("fng_extreme_fear")
        case .fear: L("fng_fear")
        case .neutral: L("fng_neutral")
        case .greed: L("fng_greed")
        case .extremeGreed: L("fng_extreme_greed")
        }
    }
}

/// «Wahrscheinliche Gründe · Sicherheit: mittel», darunter die Faktoren (stärkster oben, neutrale
/// abgeblendet, höchstens fünf) und «Kurz gesagt: …» als ein Satz — nie als sichere Ursache.
/// Zeilen wie `MarketRow` (Trennlinie, Titel mit Nebenzeile, Wert rechts).
struct WatchlistWhyFactorCard: View {
    let factors: [WhyFactor]
    let brief: String?
    var confidence: WhySummary.Confidence? = nil
    /// «SOL läuft derzeit eng mit Bitcoin.» bzw. «… unabhängig …»; nil = kein Satz.
    var btcLine: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("why_summary_title"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let confidence {
                        Text(L("why_confidence", L(confidence.level.key)))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppColors.onSurface)
                    }
                }
                if let confidence {
                    Text(Self.explanation(confidence))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            .padding(.bottom, Spacing.sm)

            ForEach(Array(factors.enumerated()), id: \.offset) { _, factor in
                WatchlistWhyFactorLine(factor: factor)
            }

            if let brief {
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 1)
                    .accessibilityHidden(true)
                Text("\(Text(L("why_brief_label")).fontWeight(.semibold)) \(brief)")
                    .font(AppFont.body)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.md)
            }
            if let btcLine {
                Text(btcLine)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.sm)
            }
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// «2 von 4 Hinweisen deuten darauf hin», bei Lücken mit «· Daten unvollständig».
    static func explanation(_ c: WhySummary.Confidence) -> String {
        let hints = L("why_confidence_hints", count: c.agreeing, c.agreeing, c.total)
        return c.partialData ? hints + " · " + L("why_confidence_partial") : hints
    }
}

/// Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
/// abgeblendet. VoiceOver: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
private struct WatchlistWhyFactorLine: View {
    let factor: WhyFactor

    @Environment(\.appAccent) private var accent
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 32

    var body: some View {
        let texts = WatchlistWhyTexts.factorLine(factor)
        let tint = factor.neutral ? AppColors.onSurfaceVariant : accent.primary
        VStack(alignment: .leading, spacing: 0) {
            Rectangle()
                .fill(AppColors.outlineVariant)
                .frame(height: 1)
                .accessibilityHidden(true)
            HStack(spacing: Spacing.md) {
                // Akzentfarbe, neutral grau — nie Kursfarben (Volumen ↑ ist kein Kursanstieg)
                Text(factor.direction.glyph)
                    .font(AppFont.title.weight(.bold))
                    .foregroundStyle(tint)
                    .frame(width: iconSize, height: iconSize)
                    .background(tint.opacity(0.12), in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(texts.title)
                        .font(AppFont.body)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(texts.note)
                        .font(AppFont.label)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !texts.value.isEmpty {
                    Text(texts.value)
                        .font(AppFont.title.monospacedDigit())
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            .padding(.vertical, Spacing.sm)
            .opacity(factor.neutral ? 0.6 : 1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(texts.spoken)
        }
    }
}
