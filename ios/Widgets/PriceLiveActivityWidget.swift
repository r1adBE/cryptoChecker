import ActivityKit
import SwiftUI
import WidgetKit

/// Live-Aktivität eines Paars: Sperrbildschirm und Dynamic Island.
/// Starten/Beenden in der App (Aktionen eines Paars), Typ in `Shared/Services/PriceActivity.swift`.
/// Im Design der Home-Widgets: Fläche, Text- und Kursfarben aus `WidgetPalette`, Kurse in SF Rounded,
/// Abstände aus der 4-pt-Skala (`Spacing`).
struct PriceLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PriceActivityAttributes.self) { context in
            // Wie ein Widget mit Thema «System»: Fläche surfaceContainer in Hell bzw. Dunkel
            LiveActivityThemed { palette in
                PriceActivityLockScreenView(attributes: context.attributes, state: context.state,
                                            isStale: context.isStale, palette: palette)
                    .activityBackgroundTint(palette.base)
                    .activitySystemActionForegroundColor(palette.accent)
            }
            .widgetURL(WidgetData.watchURL(context.attributes.watchId))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    // Dynamic Island: immer schwarzer Grund → Dunkel-Fassung der Palette
                    LiveActivityThemed(dark: true) { palette in
                        VStack(alignment: .leading, spacing: Spacing.xs) {
                            Text(context.attributes.pairLabel)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(palette.text)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                            Text(context.attributes.exchangeName)
                                .widgetSecondaryFont()
                                .foregroundStyle(palette.secondary)
                                .lineLimit(1)
                        }
                        .padding(.leading, Spacing.xs)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    LiveActivityThemed(dark: true) { palette in
                        PriceActivityChange(state: context.state, palette: palette, size: 14)
                            .padding(.trailing, Spacing.xs)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    LiveActivityThemed(dark: true) { palette in
                        HStack(alignment: .firstTextBaseline, spacing: 0) {
                            PriceActivityPrice(attributes: context.attributes, state: context.state,
                                               palette: palette, size: 22, stale: false)
                            Spacer(minLength: Spacing.sm)
                            PriceActivityUpdated(date: context.state.updatedAt, palette: palette)
                        }
                        .padding(.horizontal, Spacing.xs)
                    }
                }
            } compactLeading: {
                LiveActivityThemed(dark: true) { palette in
                    Text(context.attributes.symbol)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            } compactTrailing: {
                LiveActivityThemed(dark: true) { palette in
                    Text(context.state.priceText)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .foregroundStyle(palette.change(context.state.change24h))
                }
            } minimal: {
                LiveActivityThemed(dark: true) { palette in
                    Text(String(context.attributes.symbol.prefix(4)))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                }
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
    let palette: WidgetPalette

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.sm) {
                // Plakette wie in den Widgets: Initialen in der Akzentfarbe
                WidgetCoinBadge(symbol: attributes.symbol, size: 22, dark: palette.dark,
                                accent: palette.accent, always: true)
                Text(attributes.pairLabel)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)
                Text(attributes.exchangeName)
                    .widgetSecondaryFont(13)
                    .foregroundStyle(palette.secondary)
                    .lineLimit(1)
                Spacer(minLength: Spacing.xs)
                PriceActivityChange(state: state, palette: palette, size: 14)
            }
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                PriceActivityPrice(attributes: attributes, state: state, palette: palette, size: 28, stale: isStale)
                Spacer(minLength: Spacing.sm)
                PriceActivityUpdated(date: state.updatedAt, palette: palette)
            }
        }
        .padding(Spacing.lg)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([
            L("a11y_row_pair", attributes.pairLabel, attributes.exchangeName),
            state.priceText,
            A11y.change(state.change24h, basis: state.basis ?? .default),
            PriceActivityText.updated(state.updatedAt),
        ]))
    }
}

/// Kurs in SF Rounded wie `WidgetPriceText`, die Währung klein und grau (`Text.quoted`).
/// Veraltet: gedämpft (Nebentext-Farbe) statt abgeblasst — bleibt AA.
private struct PriceActivityPrice: View {
    let attributes: PriceActivityAttributes
    let state: PriceActivityAttributes.ContentState
    let palette: WidgetPalette
    let size: CGFloat
    let stale: Bool

    var body: some View {
        Text.quoted(state.priceText, quote: PriceActivityText.quote(attributes.pairLabel), size: size,
                    weight: .bold, design: .rounded, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(stale ? palette.secondary : palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }
}

/// «Stand 14:05» als Nebentext der Widgets.
private struct PriceActivityUpdated: View {
    let date: Date
    let palette: WidgetPalette

    var body: some View {
        Text(PriceActivityText.updated(date))
            .widgetSecondaryFont(weight: .medium, monospacedDigit: true)
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
    }
}

/// «↗ +1.24% 24h» bzw. «… heute» (Veränderung gemäss %-Basis) in der Kursfarbe — dieselbe Marke wie
/// in den Widgets (`WidgetChangeLabel`); ohne Bewegung grau «0.00%», ohne Bezug grau «—».
private struct PriceActivityChange: View {
    let state: PriceActivityAttributes.ContentState
    let palette: WidgetPalette
    let size: CGFloat

    var body: some View {
        let basis = state.basis ?? .default
        WidgetChangeLabel(change: state.change24h, palette: palette, size: size, showsArrow: true, day: true,
                          suffix: A11y.changeShortLabel(basis), basis: basis)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(A11y.change(state.change24h, basis: basis))
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

    /// Quote-Währung aus «BTC/USDT» → «USDT» (für die kleine graue Währung im Kurs); ohne «/» leer,
    /// dann bleibt der Kurs ein Text (`WidgetQuoteText.split` findet nichts).
    static func quote(_ pairLabel: String) -> String {
        guard pairLabel.contains("/"), let last = pairLabel.split(separator: "/").last else { return "" }
        return last.trimmingCharacters(in: .whitespaces)
    }
}
