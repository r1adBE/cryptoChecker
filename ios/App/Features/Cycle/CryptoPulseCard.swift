import SwiftUI

/// «Was gerade auffällt» oben im Markt-Tab (Held des Tabs). Liest sich als
/// Was passiert (Schlagzeile + Leitsatz) → Belege (Kurs-Chips, Funding-Chip)
/// → Warum (aufklappbar: Altcoins vs. Bitcoin und Faktor-Checkliste – / !).
/// VoiceOver: Überzeile, Schlagzeile, Leitsatz und Chips als ein Element,
/// «Warum?» separat, jede Checklisten-Zeile ein Element.
/// Wie `CryptoPulseCard.kt`. Daten: `CycleViewModel.pulse` plus die schon
/// geladenen Karten Fear & Greed und Netzwerkgebühren (nur für die Auswertung,
/// nicht nochmals angezeigt — beide haben eigene Karten).
@MainActor
struct CryptoPulseCard: View {
    let market: CycleLoad<PulseMarketData>
    let fearGreed: FearGreed?
    let gas: GasReport?
    let onRetry: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private var report: PulseReport? {
        guard let data = market.value else { return nil }
        let ethGas = gas?.evm.first(where: { $0.network == .ethereum })?.normalGwei
        return CryptoPulse.evaluate(PulseInput(
            btc: data.btc, eth: data.eth, sol: data.sol,
            volumeRatio: data.volumeRatio,
            fearGreed: fearGreed?.value,
            fundingPercent: data.fundingPercent,
            ethGasGwei: ethGas,
            topChanges: data.topChanges ?? [],
            marketCapUsd: data.marketCapUsd,
            marketCap24h: data.marketCap24h
        ))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            switch market {
            case .loading:
                PulseSkeleton()
            case .failed:
                unavailable
            case .loaded:
                if let report {
                    content(report)
                } else {
                    unavailable
                }
            }
            Text(L("pulse_disclaimer"))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Spacing.md)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.lg)
        .background {
            ZStack {
                AppColors.container
                // Ganz leichter Schein oben in der Kursfarbe der Richtung (nicht bei hohem Kontrast)
                if let tint = topTint {
                    LinearGradient(colors: [tint.opacity(0.08), tint.opacity(0)],
                                   startPoint: .top, endPoint: .center)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: CycleCardTitle.cornerRadius, style: .continuous))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: expanded)
    }

    /// Breite Bewegung: +1 steigend, −1 fallend, 0 gemischt/ruhig.
    private static func direction(_ summary: PulseSummary) -> Int {
        switch summary {
        case .broadUp, .broadUpVolume: 1
        case .broadDown, .broadDownVolume: -1
        case .mixed, .calm: 0
        }
    }

    private static func headlineKey(_ summary: PulseSummary) -> String {
        switch summary {
        case .broadUp, .broadUpVolume: "pulse_headline_up"
        case .broadDown, .broadDownVolume: "pulse_headline_down"
        case .mixed: "pulse_headline_mixed"
        case .calm: "pulse_headline_calm"
        }
    }

    /// Kursfarbe zur Richtung (mit Tausch), nil bei gemischt/ruhig.
    private func directionColor(_ summary: PulseSummary) -> Color? {
        let dir = Self.direction(summary)
        guard dir != 0 else { return nil }
        return priceColors.forChange(Double(dir), highContrast: highContrast, inverted: inverted)
    }

    private var topTint: Color? {
        guard !highContrast, let report else { return nil }
        return directionColor(report.summary)
    }

    /// Überzeile «Was gerade auffällt». Mit Daten liest VoiceOver sie im
    /// Element der Schlagzeile mit (hier dann ausgeblendet).
    private var header: some View {
        HStack(spacing: Spacing.xs) {
            Image(systemName: "waveform.path.ecg")
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent.primary)
                .accessibilityHidden(true)
            Text(L("pulse_now_title"))
                .sectionTitleStyle()
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityHidden(report != nil)
    }

    private var unavailable: some View {
        HStack(spacing: 8) {
            Text(L("pulse_unavailable"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            CycleRetryButton(action: onRetry)
        }
        .padding(.top, Spacing.md)
    }

    @ViewBuilder
    private func content(_ report: PulseReport) -> some View {
        let headline = L(Self.headlineKey(report.summary))
        let lead = Self.leadText(report)
        let chips = Self.metricChips(report)
        let factors = CryptoPulse.factors(report)
        VStack(alignment: .leading, spacing: 0) {
            // Was passiert und Belege — für VoiceOver ein Element (mit Überzeile)
            VStack(alignment: .leading, spacing: 0) {
                PulseHeadline(text: headline, glyph: Self.glyph(report.summary),
                              glyphColor: directionColor(report.summary))
                    .padding(.top, Spacing.sm)
                Text(lead)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.xs)
                PulseAssetChips(coins: report.coins)
                    .padding(.top, 12)
                if !chips.isEmpty {
                    FlowLayout(spacing: Spacing.xs) {
                        ForEach(chips, id: \.self) { chip in
                            PulseMetricChip(text: chip)
                        }
                    }
                    .padding(.top, Spacing.sm)
                }
                // «Top 30   ▲ 22 · ▼ 8» und «Krypto-Markt   3,42 Bio. USD  ▲ +2,1 %»
                if report.breadth != nil || report.marketCapUsd != nil {
                    VStack(alignment: .leading, spacing: 6) {
                        if let breadth = report.breadth {
                            factRow(L("pulse_breadth_label", breadth.total)) {
                                HStack(spacing: 6) {
                                    Text("▲ " + LocaleNumbers.integer(breadth.up))
                                        .foregroundStyle(priceColors.forChange(1, highContrast: highContrast, inverted: inverted))
                                    Text("·").foregroundStyle(AppColors.onSurfaceVariant)
                                    Text("▼ " + LocaleNumbers.integer(breadth.down))
                                        .foregroundStyle(priceColors.forChange(-1, highContrast: highContrast, inverted: inverted))
                                }
                                .font(.subheadline.weight(.semibold).monospacedDigit())
                            }
                        }
                        if let cap = report.marketCapUsd {
                            factRow(L("market_cap_title")) {
                                HStack(spacing: 8) {
                                    Text(CycleFormat.compactMoney(cap, "USD"))
                                        .font(.subheadline.monospacedDigit())
                                        .foregroundStyle(AppColors.onSurface)
                                    if let change = report.marketCap24h { ChangePill(change: change) }
                                }
                            }
                        }
                    }
                    .padding(.top, 12)
                }
                if let note = report.breadthNote {
                    Text(L(note.key))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, Spacing.sm)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spokenSummary(overline: L("pulse_now_title"), headline: headline,
                                                   lead: lead, coins: report.coins, chips: chips)
                                + Self.spokenExtras(report))
            .accessibilityAddTraits(.isHeader)

            // Stand und «Warum? →»
            HStack(spacing: 8) {
                if let time = market.value?.time {
                    Text(L("pulse_updated", Self.timeFormatter.string(from: Date(millis: time))))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Button {
                    WatchlistHaptics.selection()
                    expanded.toggle()
                } label: {
                    Text(L(expanded ? "pulse_less" : "pulse_why_action"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .padding(.vertical, Spacing.sm)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, Spacing.sm)

            if expanded {
                // BTC/ETH/SOL stehen schon in den Kurs-Chips oben — hier nur Altcoins vs. Bitcoin
                PulseSectionTitle(text: L("pulse_section_market"))
                PulseTextRow(text: L(report.alts.key))
                    .padding(.top, 4)
                // Was «Top 30» bedeutet (nur mit Marktbreite)
                if let breadth = report.breadth {
                    PulseTextRow(text: L("pulse_breadth_hint", breadth.total))
                        .padding(.top, 4)
                }

                if !factors.isEmpty {
                    PulseSectionTitle(text: L("pulse_section_factors"))
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(factors, id: \.kind) { factor in
                            Self.factorRow(report, factor)
                        }
                    }
                    .padding(.top, 2)
                }
            }
        }
    }
}

extension CryptoPulseCard {
    /// Zeile «Bezeichnung … Wert» unter den Kurs-Chips.
    func factRow<Value: View>(_ label: String, @ViewBuilder value: () -> Value) -> some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
            Spacer(minLength: 8)
            value()
        }
    }

    /// VoiceOver: Satz zur Marktbreite, «Top 30: 22 steigen, 8 fallen», Krypto-Markt mit Veränderung.
    static func spokenExtras(_ report: PulseReport) -> String {
        var parts: [String?] = []
        if let note = report.breadthNote { parts.append(L(note.key)) }
        if let breadth = report.breadth {
            parts.append(L("pulse_breadth_spoken", breadth.total, breadth.up, breadth.down))
        }
        if let cap = report.marketCapUsd {
            parts.append(A11y.join([L("market_cap_title"), CycleFormat.compactMoney(cap, "USD"),
                                    report.marketCap24h.flatMap { A11y.change($0) }]))
        }
        let text = A11y.join(parts)
        return text.isEmpty ? "" : ". " + text
    }

    /// ▲ / ▼ nach der Richtung (nie getauscht), gemischt/ruhig ohne Zeichen.
    static func glyph(_ summary: PulseSummary) -> String? {
        switch direction(summary) {
        case 1: "▲"
        case -1: "▼"
        default: nil
        }
    }

    /// Kennzahl-Chips, nur die zutreffenden: Funding hoch/negativ. Volumen (Krypto-Markt)
    /// und Fear & Greed haben eigene Karten im Tab und erscheinen hier nicht doppelt.
    static func metricChips(_ report: PulseReport) -> [String] {
        var chips: [String] = []
        switch report.funding {
        case .high?: chips.append(L("pulse_chip_funding_high"))
        case .negative?: chips.append(L("pulse_chip_funding_negative"))
        default: break
        }
        return chips
    }

    /// Leitsatz aus `CryptoPulse.leadSentence`: ein oder zwei ganze Sätze.
    static func leadText(_ report: PulseReport) -> String {
        let lead = CryptoPulse.leadSentence(report)
        let first = L(lead.kind.key)
        guard let detail = lead.detail else { return first }
        let second = detail.hasPercent ? L(detail.key, lead.volumePercent) : L(detail.key)
        return first + " " + second
    }

    /// Eine Zeile der Checkliste, z. B. «! Funding … +0.045 %». Volumen, Fear & Greed
    /// und Gas stehen in eigenen Karten des Tabs (siehe `CryptoPulse.factors`).
    @ViewBuilder
    static func factorRow(_ report: PulseReport, _ factor: PulseFactor) -> some View {
        switch factor.kind {
        case .funding:
            if let percent = report.fundingPercent {
                FactorRow(mark: factor.mark, title: L("factor_funding"), value: ActivityTexts.percent(percent, 3))
            }
        }
    }

    /// VoiceOver: Überzeile, Schlagzeile, Leitsatz, «Bitcoin, gestiegen um 2.80%» je Coin, dann die Kennzahl-Chips.
    static func spokenSummary(overline: String, headline: String, lead: String,
                              coins: [PulseCoinLine], chips: [String]) -> String {
        var parts: [String?] = [overline, headline, lead]
        for coin in coins {
            parts.append(A11y.join([coin.name, A11y.change(coin.shownChange)]))
        }
        for chip in chips {
            parts.append(chip)
        }
        return A11y.join(parts)
    }
}

/// Platzhalter in der Form des geladenen Inhalts (Überzeile steht schon darüber):
/// Schlagzeile, Leitsatz über zwei Zeilen, drei Kurs-Chips in Chip-Höhe und die Zeile
/// mit Stand und «Warum?». Höhen aus der echten Typografie — beim Laden springt nichts.
private struct PulseSkeleton: View {
    var body: some View {
        SkeletonPulse {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: " ")
                    .font(AppFont.headline)
                    .cycleSkeletonBar(width: 180)
                    .padding(.top, Spacing.sm)
                Text(verbatim: " ")
                    .font(.body)
                    .cycleSkeletonBar()
                    .padding(.top, Spacing.xs)
                Text(verbatim: " ")
                    .font(.body)
                    .cycleSkeletonBar()
                    .padding(.trailing, 72)
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { _ in
                        // Gleiche Form und Höhe wie `PulseAssetChip`, nur ohne Inhalt
                        Text(verbatim: " ")
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            .hidden()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Spacing.sm)
                            .background(AppColors.containerHighest, in: Capsule())
                    }
                }
                .padding(.top, 12)
                HStack(spacing: 8) {
                    Text(verbatim: " ")
                        .font(.caption.monospacedDigit())
                        .cycleSkeletonBar(width: 90)
                    Spacer(minLength: 0)
                    // Platz des «Warum?»-Knopfs
                    Text(verbatim: " ")
                        .font(.subheadline.weight(.semibold))
                        .padding(.vertical, Spacing.sm)
                        .hidden()
                }
                .padding(.top, Spacing.sm)
            }
        }
    }
}

/// Grosse Schlagzeile in `onSurface`, optional mit kleinem Richtungszeichen in der Kursfarbe.
private struct PulseHeadline: View {
    let text: String
    let glyph: String?
    let glyphColor: Color?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if let glyph, let glyphColor {
                Text(glyph)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(glyphColor)
            }
            Text(text)
                .font(AppFont.headline)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Drei gleich breite Chips «BTC ↗ +2.8 %»; bei sehr grosser Schrift untereinander.
private struct PulseAssetChips: View {
    let coins: [PulseCoinLine]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                ForEach(coins, id: \.name) { coin in
                    PulseAssetChip(line: coin)
                        .frame(maxWidth: .infinity)
                }
            }
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ForEach(coins, id: \.name) { coin in
                    PulseAssetChip(line: coin)
                }
            }
        }
    }
}

/// Wie die Prozent-Pille der Merkliste: Tönung, Vorzeichen, Pfeil, Kursfarbe.
private struct PulseAssetChip: View {
    let line: PulseCoinLine
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    private var ticker: String {
        switch line.name {
        case "Bitcoin": "BTC"
        case "Ethereum": "ETH"
        case "Solana": "SOL"
        default: line.name
        }
    }

    var body: some View {
        let flat = PriceFormat.changePercent(line.shownChange) == nil
        let color = flat ? AppColors.onSurfaceVariant
            : priceColors.forChange(line.shownChange, highContrast: highContrast, inverted: inverted)
        HStack(spacing: 4) {
            Text(ticker)
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(AppColors.onSurface)
            ChangeArrowIcon(change: line.shownChange)
                .scaledFont(size: 9, weight: .bold, relativeTo: .caption)
                .foregroundStyle(color)
            Text(ActivityTexts.percent(line.shownChange, 1))
                .font(AppFont.amount(.subheadline, weight: .semibold))
                .foregroundStyle(color)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.sm)
        .background(color.opacity(0.14), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([line.name, A11y.change(line.shownChange)]))
    }
}

/// Kleiner neutraler Chip (Funding) — Bedeutung steht im Text.
private struct PulseMetricChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium).monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .padding(.horizontal, Spacing.sm)
            .padding(.vertical, 4)
            .background(AppColors.containerHigh, in: Capsule())
            .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}

private struct PulseSectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .sectionTitleStyle()
            .padding(.top, Spacing.md)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct PulseTextRow: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private extension PulseCoinLine {
    /// Wie angezeigt (eine Nachkommastelle): unter 0.05 % gilt als unverändert —
    /// sonst stünden «0.0 %» mit Pfeil und Kursfarbe da (wie Android).
    var shownChange: Double { abs(changePercent) < 0.05 ? 0 : changePercent }
}
