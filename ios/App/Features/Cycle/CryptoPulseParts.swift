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

/// Grosse Schlagzeile in `onSurface`, optional mit kleinem schrägem Pfeil in der Kursfarbe.
struct PulseHeadline: View {
    let text: String
    /// SF Symbol (`arrow.up.right` / `arrow.down.right`) oder nil.
    let glyph: String?
    let glyphColor: Color?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if let glyph, let glyphColor {
                Image(systemName: glyph)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(glyphColor)
                    .accessibilityHidden(true)
            }
            Text(text)
                .font(AppFont.headline)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Zähler der Marktbreite wie die Puls-Zeile der Merkliste: «↗ 22 steigen» und «↘ 8 fallen»
/// als farbige Schrift in Steigend- bzw. Fallend-Farbe (wie Android `PulseHero`).
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
