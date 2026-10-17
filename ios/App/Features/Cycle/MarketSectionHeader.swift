import SwiftUI

// Auf-/zuklappbarer Abschnittskopf im Markt-Tab (Titel, zugeklappt mit Zusammenfassung).
// Eigene Datei, weil der Kopf über mehreren Karten steht und mit der einzelnen Zeile nichts teilt
// (wie `MarketSectionHeader.kt`).

/// Überschrift eines zuklappbaren Abschnitts im Markt-Tab («Einordnung», «Daten»): links der
/// Titel, zugeklappt rechts eine kurze Zusammenfassung (`summary`, z. B. «Gier 72 · Neutral»),
/// dahinter ein Pfeil. Tippen klappt auf und zu (`onToggle`; die Animation macht der Aufrufer).
///
/// VoiceOver: eine Überschrift und Taste mit Wert auf-/zugeklappt und dem Hinweis «… einblenden»
/// bzw. «… ausblenden»; zugeklappt wird die Zusammenfassung mitgelesen.
/// Wie `MarketSectionHeader` (MarketRow.kt).
struct MarketSectionHeader: View {
    let title: String
    let summary: String
    let expanded: Bool
    let onToggle: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: Spacing.md) {
                Text(title)
                    .sectionTitleStyle()
                    .lineLimit(1)
                    .layoutPriority(1)
                // Offen: keine Zusammenfassung (die Zeilen stehen darunter)
                Text(verbatim: expanded ? "" : summary)
                    .font(AppFont.label)
                    .monospacedDigit()
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie `ExpandToggleLabel`)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
            }
            .padding(.horizontal, 4)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: expanded || summary.isEmpty ? title : "\(title), \(summary)"))
        .accessibilityValue(L(expanded ? "a11y_expanded" : "a11y_collapsed"))
        .accessibilityHint(L(expanded ? "market_section_collapse" : "market_section_expand", title))
        .accessibilityAddTraits([.isHeader, .isButton])
    }
}
