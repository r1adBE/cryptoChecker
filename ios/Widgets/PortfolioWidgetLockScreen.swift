import SwiftUI
import WidgetKit
/// Portfolio-Widget auf dem Sperrbildschirm.
/// Sperrbildschirm: Titel, Gesamtwert, Veränderung über 24 h. Beide Beträge sind
/// `privacySensitive`: bei gesperrtem Gerät verdeckt, nach dem Entsperren sichtbar.
struct PortfolioRectangularView: View {
    let entry: PortfolioEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("widget_portfolio_name"))
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
            if entry.locked {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(L("widget_portfolio_locked_hint"))
                        .font(.system(size: 12))
                        .lineLimit(2)
                }
                .foregroundStyle(.secondary)
            } else if let s = entry.snapshot, !s.empty {
                Text(PortfolioWidgetText.total(s, hidden: entry.hideAmounts))
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
                    .privacySensitive()
                if let line = PortfolioWidgetText.changeLine(s, hidden: entry.hideAmounts) {
                    HStack(spacing: 2) {
                        ChangeArrowIcon(change: PortfolioWidgetText.directionValue(s))
                            .font(.system(size: 8, weight: .bold))
                        Text(line)
                            .font(.system(size: 11))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .foregroundStyle(.secondary)
                    .privacySensitive()
                }
            } else {
                Text(L("widget_portfolio_empty"))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .modifier(PortfolioRectangularA11y(entry: entry))
    }
}

private struct PortfolioRectangularA11y: ViewModifier {
    let entry: PortfolioEntry
    /// Gesperrtes Gerät: Beträge verdeckt — dann auch VoiceOver ohne Beträge.
    @Environment(\.redactionReasons) var redactionReasons

    @ViewBuilder
    func body(content: Content) -> some View {
        if !entry.locked, let s = entry.snapshot, !s.empty {
            content.accessibilityLabel(PortfolioWidgetText.accessibility(
                s, outdated: entry.outdated, at: entry.date,
                hidden: entry.hideAmounts || redactionReasons.contains(.privacy)))
        } else {
            content
        }
    }
}
