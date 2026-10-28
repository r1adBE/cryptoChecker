import SwiftUI

/// «Was gerade auffällt», die eine Heldenkarte oben im Markt-Tab (nur diese Karte ist
/// betont — grösste Schrift, leichte Tönung; alle Karten darunter ruhig). Liest sich als
/// Was passiert (Schlagzeile + Leitsatz) → «Kurz gesagt» (`plain`, bis zu drei Sätze in Alltagssprache)
/// → Belege (Coin-Änderungen, Funding-Chip) → «Was bewegt sich mit?» (aufklappbar: Altcoins vs. Bitcoin und
/// Faktor-Checkliste – / !). Den Stand der Daten zeigt die Status-Pille darüber (nicht doppelt). Kann der Puls
/// nicht laden, steht hier die Fehlerzeile — «Kurz gesagt» bleibt, soweit es Sätze gibt.
/// VoiceOver: die Karte ist eine Gruppe; Überzeile, Schlagzeile und Leitsatz ein Element (Überschrift), jeder
/// Satz von «Kurz gesagt» eins, die Belege eins, «Was bewegt sich mit?» separat, jede Checklisten-Zeile eins.
/// Wie `CryptoPulseCard.kt`. Daten: `CycleViewModel.pulse` plus die schon
/// geladenen Karten Fear & Greed und Netzwerkgebühren (nur für die Auswertung,
/// nicht nochmals angezeigt — beide haben eigene Karten).
@MainActor
struct CryptoPulseCard: View {
    let market: CycleLoad<PulseMarketData>
    let fearGreed: FearGreed?
    let gas: GasReport?
    /// «Kurz gesagt» (`MarketPlainSummaryBlock.summary`); nil = (noch) keine Sätze.
    let plain: PlainSummary?
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
                MarketPlainLines(summary: plain)
            case .loaded:
                if let report {
                    content(report)
                } else {
                    unavailable
                    MarketPlainLines(summary: plain)
                }
            }
            // Ein Hinweis für die ganze Karte: mit «Kurz gesagt» der längere (nennt die 24 Stunden)
            Text(L(plain != nil ? "market_plain_footnote" : "pulse_disclaimer"))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, Spacing.md)
            // Marktbreite und Gesamtmarkt kommen von CoinGecko, ebenso Richtung (Marktkapitalisierung) und
            // «Heute auffällig» (Umsatz-Anteil) in «Kurz gesagt»
            if report != nil || plain?.usesCoinGecko == true {
                CoinGeckoAttribution()
            }
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
        .clipShape(RoundedRectangle(cornerRadius: CardLevel.top.radius, style: .continuous))
        // Die Karte ist für VoiceOver eine Gruppe
        .accessibilityElement(children: .contain)
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

    /// Überzeile «Was gerade auffällt» mit Info-Zeichen. Mit Daten liest VoiceOver die Überzeile
    /// im Element der Schlagzeile mit (hier dann ausgeblendet); das Info-Zeichen bleibt ein
    /// eigener Knopf.
    private var header: some View {
        HStack(spacing: 2) {
            overline
            MarketInfoButton(topic: .pulse, compact: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var overline: some View {
        HStack(spacing: Spacing.xs) {
            Image(systemName: "waveform.path.ecg")
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent.primary)
                .accessibilityHidden(true)
            Text(L("pulse_now_title"))
                .sectionTitleStyle()
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityHidden(report != nil)
    }

    private var unavailable: some View {
        CycleUnavailableRow(onRetry: onRetry)
            .padding(.top, Spacing.md)
    }

    @ViewBuilder
    private func content(_ report: PulseReport) -> some View {
        let headline = L(Self.headlineKey(report.summary))
        let lead = Self.leadText(report)
        let chips = Self.metricChips(report)
        let factors = CryptoPulse.factors(report)
        VStack(alignment: .leading, spacing: 0) {
            // Was passiert — für VoiceOver ein Element und eine Überschrift (mit Überzeile)
            VStack(alignment: .leading, spacing: 0) {
                PulseHeadline(text: headline, glyph: Self.glyph(report.summary),
                              glyphColor: directionColor(report.summary))
                    .padding(.top, Spacing.sm)
                Text(lead)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.xs)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(A11y.join([L("pulse_now_title"), headline, lead]))
            .accessibilityAddTraits(.isHeader)

            // «Kurz gesagt» als Körper der Karte direkt unter der Schlagzeile
            MarketPlainLines(summary: plain)

            // Belege — für VoiceOver ein Element
            VStack(alignment: .leading, spacing: 0) {
                // Zeitraum aller Veränderungen der Karte: 24 Stunden (Binance-24-h-Ticker bzw. Stundenkerzen,
                // Top 30 und Krypto-Markt von CoinGecko, ebenfalls 24 h) — klein und grau vor den Coins
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
                PulseFacts(report: report)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spokenEvidence(coins: report.coins, chips: chips) + Self.spokenExtras(report))

            // Stand und «Was bewegt sich mit? →»
            HStack(spacing: 8) {
                if let time = market.value?.time {
                    Text(L("pulse_updated", Self.timeFormatter.string(from: Date(millis: time))))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Button {
                    Haptics.play(.selection)
                    expanded.toggle()
                } label: {
                    // Klappt nur auf (kein Seitenwechsel): Pfeil nach unten bzw. oben wie bei «Einordnung»
                    ExpandToggleLabel(title: L(expanded ? "pulse_less" : "pulse_details_action"), expanded: expanded)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, Spacing.sm)

            if expanded {
                // BTC/ETH/SOL stehen schon in der Coin-Zeile oben — hier nur Altcoins vs. Bitcoin
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
    /// «24h» klein und grau (wie der Zeitraum neben den Änderungen der Merkliste).
    static var periodLabel: some View {
        Text(L("why_change_24h"))
            .font(.caption)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .lineLimit(1)
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
                                    report.marketCap24h.flatMap { change in
                                        A11y.change(change).map { L("label_value", L("pulse_period_24h_spoken"), $0) }
                                    }]))
        }
        let text = A11y.join(parts)
        return text.isEmpty ? "" : ". " + text
    }

    /// Schräger Pfeil (SF Symbol) nach der Richtung (nie getauscht), gemischt/ruhig ohne Zeichen.
    static func glyph(_ summary: PulseSummary) -> String? {
        switch direction(summary) {
        case 1: "arrow.up.right"
        case -1: "arrow.down.right"
        default: nil
        }
    }

    /// Kennzahl-Chips, nur die zutreffenden: Hebel-Stimmung (Funding) optimistisch/pessimistisch. Volumen
    /// (Krypto-Markt) und Fear & Greed haben eigene Karten im Tab und erscheinen hier nicht doppelt.
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
        // Fertig formatiert (Ziffern, Abstand und Zeichen der Sprache) statt eines festen «%» in der Übersetzung
        let second = detail.hasPercent
            ? L(detail.key, PercentText.unsigned(Double(lead.volumePercent), decimals: 0))
            : L(detail.key)
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

    /// VoiceOver für die Belege: «Letzte 24 Stunden», «Bitcoin, gestiegen um 2.80%» je Coin, dann die Kennzahl-Chips.
    static func spokenEvidence(coins: [PulseCoinLine], chips: [String]) -> String {
        var parts: [String?] = [L("pulse_period_24h_spoken")]
        for coin in coins {
            parts.append(A11y.join([coin.name, A11y.change(coin.shownChange)]))
        }
        for chip in chips {
            parts.append(chip)
        }
        return A11y.join(parts)
    }
}

extension PulseCoinLine {
    /// Wie angezeigt (eine Nachkommastelle): unter 0.05 % gilt als unverändert —
    /// sonst stünden «0.0 %» mit Pfeil und Kursfarbe da (wie Android).
    var shownChange: Double { abs(changePercent) < 0.05 ? 0 : changePercent }
}
