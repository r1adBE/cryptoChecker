import SwiftUI
import WidgetKit
/// Portfolio-Widget auf dem Sperrbildschirm.
/// Sperrbildschirm: Titel, Gesamtwert, Veränderung über den Zeitraum des Widgets (Anzeige wie eingestellt). Beide Beträge sind
/// `privacySensitive`: bei gesperrtem Gerät verdeckt, nach dem Entsperren sichtbar.
struct PortfolioRectangularView: View {
    let entry: PortfolioEntry

    /// Alle Texte wachsen mit Dynamic Type, solange sie in der Höhe Platz haben (`WidgetPrimaryFit`).
    var body: some View {
        WidgetPrimaryFit(in: .vertical) { scale in content(scale: scale) }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .modifier(PortfolioRectangularA11y(entry: entry))
    }

    private func content(scale: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Portfolio aus: nur kurz «Portfolio aus» (keine Beträge)
            Text(L(entry.state == .off ? "widget_portfolio_off_short" : "widget_portfolio_name"))
                .font(.system(size: 13 * scale, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(WidgetTextSize.minimumFactor(1, primaryScale: scale))
            if entry.state == .off {
                EmptyView()
            } else if entry.locked {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10 * scale, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(L("widget_portfolio_locked_hint"))
                        .font(.system(size: 12 * scale))
                        .lineLimit(2)
                }
                .foregroundStyle(.secondary)
            } else if let s = entry.shown, !s.empty {
                let plan = entry.plan(for: s)
                // Währung klein und grau (`Text.quoted`)
                Text.quoted(PortfolioWidgetText.total(s, hidden: entry.hideAmounts), quote: s.currency,
                            size: 17 * scale, weight: .bold, design: .rounded, quoteColor: .secondary)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(WidgetTextSize.minimumFactor(0.6, primaryScale: scale))
                    .widgetAccentable()
                    .privacySensitive()
                if let line = PortfolioWidgetText.changeLine(plan, s, hidden: entry.hideAmounts) {
                    HStack(spacing: 2) {
                        ChangeArrowIcon(change: PortfolioWidgetText.directionValue(plan))
                            .font(.system(size: 8 * scale, weight: .bold))
                        Text.quoted(line, quote: s.currency, size: 12 * scale, weight: .regular, quoteColor: .secondary)
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .foregroundStyle(.secondary)
                    .privacySensitive()
                }
            } else {
                Text(L("widget_portfolio_empty"))
                    .font(.system(size: 12 * scale))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }
}

private struct PortfolioRectangularA11y: ViewModifier {
    let entry: PortfolioEntry
    /// Gesperrtes Gerät: Beträge verdeckt — dann auch VoiceOver ohne Beträge.
    @Environment(\.redactionReasons) var redactionReasons

    @ViewBuilder
    func body(content: Content) -> some View {
        if entry.state == .values, let s = entry.shown {
            content.accessibilityLabel(PortfolioWidgetText.accessibility(
                s, plan: entry.plan(for: s), outdated: entry.outdated, at: entry.date,
                hidden: entry.hideAmounts || redactionReasons.contains(.privacy)))
        } else {
            content
        }
    }
}
