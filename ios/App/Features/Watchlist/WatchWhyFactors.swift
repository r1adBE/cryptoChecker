import SwiftUI

// Faktoren von «Was bewegt sich mit?» — wie `WatchWhyFactors.kt`.

/// «Beobachtung»: die Faktoren als Messwerte (stärkster oben, neutrale abgeblendet, höchstens
/// fünf). Zeilen wie `MarketRow` (Trennlinie, Titel mit Nebenzeile, Wert rechts). Wie `WhyFactorCard` (Android).
struct WatchlistWhyFactorCard: View {
    let factors: [WhyFactor]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(factors.enumerated()), id: \.offset) { index, factor in
                WatchlistWhyFactorLine(factor: factor, divider: index > 0)
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, Spacing.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}

/// Herkunft und Stand je Quelle unter der Faktorkarte («Beobachtung»), klein und grau — wie
/// `WhyFreshnessLines` (Android). Ausweichquellen und Richtwerte sind benannt.
struct WatchlistWhyFreshness: View {
    let report: WhyReport

    var body: some View {
        let lines = WatchlistWhyTexts.freshnessLines(report)
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 1) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, Spacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Karte oben im «Was bewegt sich mit?»: «Kurz gesagt · Stand 14:00», darunter der eine Satz (`WhySummary`)
/// grösser und halbfett, die bis zu drei auffälligsten Faktoren (aus `WhyFactors.rank`, ohne
/// neutrale) als eine Zeile schlichter grauer Text («Volumen 2.4× · BTC ↗ +1.8 % · OI +12 %»).
/// Bewusst ohne «Sicherheit»-Stufe. Nur als zeitliches Zusammentreffen formuliert, nie als
/// Ursache. Wie `WhyBriefCard` (Android).
struct WatchlistWhyBriefCard: View {
    let report: WhyReport
    let factors: [WhyFactor]

    /// So viele auffällige Faktoren stehen in der Zeile der Karte.
    static let maxFactors = 3

    /// Gibt es etwas für die Karte (Satz oder auffällige Faktoren)?
    static func hasContent(_ report: WhyReport, factors: [WhyFactor]) -> Bool {
        !WhySummary.keys(report.reasons).isEmpty || factors.contains { !$0.neutral }
    }

    var body: some View {
        if Self.hasContent(report, factors: factors) {
            card
        }
    }

    private var card: some View {
        let brief: String? = WhySummary.keys(report.reasons).first.map { L($0) }
        return VStack(alignment: .leading, spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(L("why_brief_head", PriceFormat.time(report.dataTime)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                if let brief {
                    Text(brief)
                        .font(AppFont.title)
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            factorLine
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// Bis zu drei auffällige Faktoren als schlichter neutraler Text ohne Pillen, durch « · »
    /// getrennt; VoiceOver liest die ganzen Sätze. Keine → nichts.
    @ViewBuilder
    private var factorLine: some View {
        let notable = Array(factors.filter { !$0.neutral }.prefix(Self.maxFactors))
        if !notable.isEmpty {
            let lines = notable.map { ($0, WatchlistWhyTexts.factorLine($0)) }
            let text: String = lines.map { Self.briefText($0.0, $0.1) }.joined(separator: " · ")
            let spoken: String = lines.map { $0.1.spoken }.joined(separator: ", ")
            Text(text)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel(spoken)
        }
    }

    /// Kurzer Text eines Faktors in «Kurz gesagt»: Titel und Wert («Volumen 2.4×»); Gesamtmarkt nur der Wert
    /// («BTC +1.8 %»), Open Interest als «OI +12 %»; ohne Wert nur der Titel — wie Android.
    static func briefText(_ factor: WhyFactor, _ line: WatchlistWhyTexts.FactorLine) -> String {
        if line.value.isEmpty { return line.title }
        switch factor.kind {
        case .market: return line.value
        case .openInterest: return "OI " + line.value
        default: return line.title + " " + line.value
        }
    }
}

/// «Alle Werte und Quellen» auf-/zugeklappt; für die Dauer der Sitzung gemerkt, Standard zu.
enum WatchlistWhyAllValuesState {
    // Hinter einer Sperre statt `nonisolated(unsafe)`: thread-sicher, ohne Aufrufer an den MainActor zu binden
    private static let state = LockedBox(false)
    static var expanded: Bool {
        get { state.current }
        set { state.current = newValue }
    }
}

/// Zugeklappte Karte «Alle Werte und Quellen» (darunter grau «Beobachtung · Mögliche Einordnung ·
/// Quellen», rechts der Pfeil in der Themenfarbe); aufgeklappt darunter `content`. VoiceOver:
/// Knopf mit Zustand auf-/zugeklappt. Wie `WhyAllValuesCard` (Android).
struct WatchlistWhyAllValuesCard<Content: View>: View {
    @Binding var expanded: Bool
    @ViewBuilder let content: () -> Content

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            toggle
            if expanded {
                VStack(alignment: .leading, spacing: 0) {
                    content()
                }
                .padding([.horizontal, .bottom], Spacing.md)
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    private var toggle: some View {
        Button {
            Haptics.play(.selection)
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) {
                expanded.toggle()
            }
            WatchlistWhyAllValuesState.expanded = expanded
        } label: {
            HStack(spacing: Spacing.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("why_all_values"))
                        .font(AppFont.title)
                        .foregroundStyle(AppColors.onSurface)
                    Text(L("why_all_values_hint"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie das «Mehr» im Aktionsblatt)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("why_all_values") + ", " + L("why_all_values_hint"))
        .accessibilityValue(L(expanded ? "a11y_expanded" : "a11y_collapsed"))
        .accessibilityAddTraits(.isButton)
    }
}

/// «Mögliche Einordnung»: «Kurz gesagt: …» als ein Satz — nur als zeitliches Zusammentreffen
/// formuliert, nie als sichere Ursache — und der Gleichlauf mit Bitcoin. Wie `WhyContextCard` (Android).
struct WatchlistWhyContextCard: View {
    let brief: String?
    /// «SOL läuft derzeit eng mit Bitcoin.» bzw. «… unabhängig …»; nil = kein Satz.
    var btcLine: String? = nil

    /// Gibt es überhaupt etwas einzuordnen? Sonst entfallen Überschrift und Karte.
    var hasContent: Bool { brief != nil || btcLine != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let brief {
                Text("\(Text(L("why_brief_label")).fontWeight(.semibold)) \(brief)")
                    .font(AppFont.body)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let btcLine {
                Text(btcLine)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, brief != nil ? Spacing.sm : 0)
            }
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}

/// Abschnittsüberschrift im «Was bewegt sich mit?»-Blatt wie überall (`sectionTitleStyle`), rechts optional
/// klein der Datenstand («Datenstand 14:32»). Wie `WhySectionTitle` (Android).
struct WatchlistWhySectionTitle: View {
    let title: String
    var trailing: String? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Spacing.sm) {
            Text(title)
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let trailing {
                Text(trailing)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
    }
}

/// Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
/// abgeblendet. VoiceOver: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
private struct WatchlistWhyFactorLine: View {
    let factor: WhyFactor
    /// Trennlinie oben (nicht vor der ersten Zeile der Karte)
    var divider: Bool = true

    @Environment(\.appAccent) private var accent
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 32

    var body: some View {
        let texts = WatchlistWhyTexts.factorLine(factor)
        let tint = factor.neutral ? AppColors.onSurfaceVariant : accent.primary
        VStack(alignment: .leading, spacing: 0) {
            if divider {
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 1)
                    .accessibilityHidden(true)
            }
            HStack(spacing: Spacing.md) {
                // Akzentfarbe, neutral grau — nie Kursfarben (Volumen ↗ ist kein Kursanstieg)
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
