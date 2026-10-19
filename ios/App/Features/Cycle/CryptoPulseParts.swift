import SwiftUI

// Bausteine der Karte «Crypto Pulse»: Lade-Platzhalter, Schlagzeile, Breite-Pillen, Coin-Chips, Kennzahl-Chips,
// Abschnittstitel und Textzeilen.
// Eigene Datei, weil die Karte selbst nur Zustand und Anordnung regelt (wie `CryptoPulseParts.kt`).

/// Platzhalter in der Form des geladenen Inhalts (Überzeile steht schon darüber):
/// Schlagzeile, Leitsatz über zwei Zeilen, die Zeile mit drei Coin-Änderungen und die Zeile
/// mit Stand und «Was bewegt sich mit?». Höhen aus der echten Typografie — beim Laden springt nichts.
struct PulseSkeleton: View {
    var body: some View {
        SkeletonPulse {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: " ")
                    .displayFont(.compact, design: .default)
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
                // Zeile «24h  BTC ↘ −0.3%  ETH ↗ +1.2%  SOL ↗ +0.8%»: Zeitraum und drei Coin-Angaben
                HStack(alignment: .center, spacing: 12) {
                    Text(verbatim: " ")
                        .font(.caption)
                        .cycleSkeletonBar(width: 28)
                    ForEach(0..<3, id: \.self) { _ in
                        // Gleiche Höhe wie `PulseAssetChip`, nur ohne Inhalt
                        Text(verbatim: " ")
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            .cycleSkeletonBar(width: 84)
                    }
                }
                .padding(.top, 12)
                HStack(spacing: 8) {
                    Text(verbatim: " ")
                        .font(.caption.monospacedDigit())
                        .cycleSkeletonBar(width: 90)
                    Spacer(minLength: 0)
                    // Platz des «Was bewegt sich mit?»-Knopfs
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

/// Grosse Schlagzeile in `onSurface` (Display kompakt, 28 pt: die grösste Schrift von «Jetzt», nur in der
/// Heldenkarte; Android `displayCompact`), optional mit schrägem Pfeil in der Kursfarbe.
struct PulseHeadline: View {
    let text: String
    /// SF Symbol (`arrow.up.right` / `arrow.down.right`) oder nil.
    let glyph: String?
    let glyphColor: Color?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if let glyph, let glyphColor {
                Image(systemName: glyph)
                    .font(AppFont.headline)
                    .foregroundStyle(glyphColor)
                    .accessibilityHidden(true)
            }
            Text(text)
                .displayFont(.compact, design: .default)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Unter den Coin-Änderungen: «Top 30 · 24h   ↗ 22 steigen  ↘ 8 fallen», «Krypto-Markt   3,42 Bio. USD  ↗ +2,1 % · 24h»
/// und der Satz zur Marktbreite (alles nur mit Daten). Gesprochen im Element der Belege (`CryptoPulseCard`).
struct PulseFacts: View {
    let report: PulseReport

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if report.breadth != nil || report.marketCapUsd != nil {
                VStack(alignment: .leading, spacing: 6) {
                    if let breadth = report.breadth {
                        factRow(L("pulse_breadth_label", breadth.total) + " · " + L("why_change_24h")) {
                            PulseBreadthPills(breadth: breadth)
                        }
                    }
                    if let cap = report.marketCapUsd {
                        factRow(L("market_cap_title")) {
                            HStack(spacing: 8) {
                                Text(CycleFormat.compactMoney(cap, "USD"))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(AppColors.onSurface)
                                if let change = report.marketCap24h {
                                    // «↘ −2.77% · 24h»: Änderung farbig, Zeitraum klein und grau dahinter
                                    // (die Summe selbst ist ein Stand, keine Veränderung)
                                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                                        ChangePill(change: change)
                                        Text("· " + L("why_change_24h"))
                                            .font(.caption)
                                            .foregroundStyle(AppColors.onSurfaceVariant)
                                            .lineLimit(1)
                                    }
                                }
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
    }

    /// Zeile «Bezeichnung … Wert».
    private func factRow<Value: View>(_ label: String, @ViewBuilder value: () -> Value) -> some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
            Spacer(minLength: 8)
            value()
        }
    }
}

/// Zähler der Marktbreite wie die Puls-Zeile der Merkliste: «↗ 22 steigen» und «↘ 8 fallen»
/// als farbige Schrift in Steigend- bzw. Fallend-Farbe (wie Android `PulseEvidence`).
struct PulseBreadthPills: View {
    let breadth: PulseBreadth

    var body: some View {
        HStack(spacing: Spacing.sm) {
            TonePill(text: L("watchlist_pulse_up", count: breadth.up), tone: .up, icon: "arrow.up.right")
            TonePill(text: L("watchlist_pulse_down", count: breadth.down), tone: .down, icon: "arrow.down.right")
        }
        .fixedSize()
    }
}

/// «24h  BTC ↘ −0.3%  ETH ↗ +1.2%  SOL ↗ +0.8%»: Zeitraum klein und grau vorne, dann die Coins;
/// bei sehr grosser Schrift untereinander (Android: `FlowRow` bricht um).
struct PulseAssetChips: View {
    let coins: [PulseCoinLine]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                CryptoPulseCard.periodLabel
                ForEach(coins, id: \.name) { coin in
                    PulseAssetChip(line: coin)
                }
            }
            VStack(alignment: .leading, spacing: Spacing.xs) {
                CryptoPulseCard.periodLabel
                ForEach(coins, id: \.name) { coin in
                    PulseAssetChip(line: coin)
                }
            }
        }
    }
}

/// «BTC ↗ +2.8%»: Kürzel in Textfarbe, Änderung farbig ohne Pille — Pfeil und Vorzeichen wie die
/// Widgets (wie Android `PulseCoinChange`).
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
        // «0.0%» (unter 0.05 %) grau wie Android `PulseCoinChange`
        let tone = PillTone.forValue(line.shownChange) { abs($0) < 0.05 }
        let content: Color = tone.content(priceColors, highContrast: highContrast, inverted: inverted)
        HStack(spacing: 4) {
            Text(verbatim: ticker)
                .foregroundStyle(AppColors.onSurface)
            changeText(tone)
                .foregroundStyle(content)
        }
        .scaledFont(size: 14, weight: .semibold, design: .rounded, relativeTo: .subheadline, monospacedDigit: true)
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([line.name, A11y.change(line.shownChange)]))
    }

    /// «↗ +2.8%» — Pfeil nur bei Richtung, Vorzeichen wie `PriceFormat.signedPercent` (Android gleich).
    private func changeText(_ tone: PillTone) -> Text {
        let percent = Text(verbatim: PriceFormat.signedPercent(line.shownChange, decimals: 1))
        guard let symbol = tone.arrowSymbol else { return percent }
        let arrow = Text(Image(systemName: symbol)).fontWeight(.bold)
        return arrow + Text(verbatim: " ") + percent
    }
}

/// Kleiner neutraler Chip (Funding) — Bedeutung steht im Text.
struct PulseMetricChip: View {
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

struct PulseSectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .sectionTitleStyle()
            .padding(.top, Spacing.md)
            .accessibilityAddTraits(.isHeader)
    }
}

struct PulseTextRow: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.monospacedDigit())
            .foregroundStyle(AppColors.onSurface)
            .fixedSize(horizontal: false, vertical: true)
    }
}
