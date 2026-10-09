import ActivityKit
import SwiftUI
import WidgetKit

/// Live-Aktivität eines Paars: Sperrbildschirm und Dynamic Island.
/// Starten/Beenden in der App (Aktionen eines Paars), Typ in `Shared/Services/PriceActivity.swift`.
struct PriceLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PriceActivityAttributes.self) { context in
            // Fester dunkler Grund: Kursfarben (Dunkel-Fassung) und Text bleiben lesbar
            PriceActivityLockScreenView(attributes: context.attributes, state: context.state, isStale: context.isStale)
                .environment(\.colorScheme, .dark)
                .activityBackgroundTint(WidgetPalette.liveActivityBackground)
                .activitySystemActionForegroundColor(WidgetPalette.liveActivityForeground)
                .widgetURL(WidgetData.watchURL(context.attributes.watchId))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(context.attributes.pairLabel)
                            .font(.system(size: 14, weight: .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(context.attributes.exchangeName)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    PriceActivityChange(change: context.state.change24h, basis: context.state.basis ?? .default, size: 14)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(context.state.priceText)
                            .font(.system(size: 22, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        Spacer(minLength: 6)
                        Text(PriceActivityText.updated(context.state.updatedAt))
                            .font(.system(size: 11, weight: .medium))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                Text(context.attributes.symbol)
                    .font(.system(size: 13, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            } compactTrailing: {
                Text(context.state.priceText)
                    .font(.system(size: 13, weight: .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundStyle(PriceActivityText.color(context.state.change24h))
            } minimal: {
                Text(String(context.attributes.symbol.prefix(4)))
                    .font(.system(size: 11, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            .widgetURL(WidgetData.watchURL(context.attributes.watchId))
        }
    }
}

/// Sperrbildschirm: Paar und Börse, Kurs gross, Veränderung mit Vorzeichen,
/// Pfeil und Kursfarbe, «Stand HH:mm».
private struct PriceActivityLockScreenView: View {
    let attributes: PriceActivityAttributes
    let state: PriceActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                WidgetCoinBadge(symbol: attributes.symbol, size: 22, dark: true, always: true)
                Text(attributes.pairLabel)
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                Text(attributes.exchangeName)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                PriceActivityChange(change: state.change24h, basis: state.basis ?? .default, size: 14)
            }
            HStack(alignment: .firstTextBaseline) {
                Text(state.priceText)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Spacer(minLength: 6)
                Text(PriceActivityText.updated(state.updatedAt))
                    .font(.system(size: 12, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .opacity(isStale ? 0.6 : 1)
        }
        .padding(16)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([
            L("a11y_row_pair", attributes.pairLabel, attributes.exchangeName),
            state.priceText,
            A11y.change(state.change24h, basis: state.basis ?? .default),
            PriceActivityText.updated(state.updatedAt),
        ]))
    }
}

/// «↗ +1.24% 24h» bzw. «… heute» (Veränderung gemäss %-Basis) in der Kursfarbe; ohne Bewegung
/// grau «0.00%», ohne Bezug grau «—».
private struct PriceActivityChange: View {
    let change: Double?
    let basis: ChangeBasis
    let size: CGFloat

    var body: some View {
        HStack(spacing: 2) {
            ChangeArrowIcon(change: change)
                .font(.system(size: size * 0.75, weight: .bold))
            Text(change == nil ? "—" : (PriceFormat.changePercent(change) ?? WidgetChangeLabel.zeroText))
                .font(.system(size: size, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
            // Zeitraum nur, wenn eingeschaltet (Einstellungen › %-Änderung)
            if SharedStorage.loadSettings().showChangePeriod {
                Text(A11y.changeShortLabel(basis))
                    .font(.system(size: size * 0.75, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .foregroundStyle(PriceActivityText.color(change))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.change(change, basis: basis))
    }
}

enum PriceActivityText {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    /// «Stand 14:05» (gemeinsamer Schlüssel `pulse_updated`).
    static func updated(_ date: Date) -> String {
        L("pulse_updated", timeFormatter.string(from: date))
    }

    /// Kursfarbe nach den App-Einstellungen (Schema, Tausch, hoher Kontrast).
    /// Live-Aktivitäten liegen auf dunklem Grund (Sperrbildschirm, Dynamic Island).
    static func color(_ change: Double?) -> Color {
        guard let change, PriceFormat.changePercent(change) != nil else { return .secondary }
        let settings = SharedStorage.loadSettings()
        let hex = change >= 0
            ? settings.priceColorScheme.upHex(dark: true, highContrast: settings.highContrast, inverted: settings.priceColorsInverted)
            : settings.priceColorScheme.downHex(dark: true, highContrast: settings.highContrast, inverted: settings.priceColorsInverted)
        return Color(hex: hex)
    }
}
