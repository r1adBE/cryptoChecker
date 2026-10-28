import SwiftUI
import WidgetKit

/// Kopfzeile des kleinen Einzelpaar-Widgets: Plakette, Paar und Veränderung.
struct SingleHeader: View {
    let watch: Watch
    let palette: WidgetPalette
    var badge: CGFloat = 20
    /// Faktor der Haupttexte (`WidgetPrimaryFit`).
    var scale: CGFloat = 1
    @Environment(\.changeView) private var changeView

    /// Volles Paar («BTC/USDT»), wenn es neben der Veränderung Platz hat (gewachsen, sonst in Grundgrösse),
    /// sonst nur die Basis («BTC» statt «BT…»). Die Veränderung wird nie gekürzt (feste Breite, Vorrang).
    var body: some View {
        HStack(spacing: 4) {
            WidgetCoinBadge(symbol: CoinLogoStore.logoKey(for: watch), size: badge, dark: palette.dark, accent: palette.accent,
                            logo: CoinLogos.allowed(forExchange: watch.exchangeKey))
                // Deaktiviert: grau (wie die Zeile in der App)
                .opacity(watch.disabled ? 0.45 : 1)
            ViewThatFits(in: .horizontal) {
                pairText(watch.displayName, scale: scale)
                    .fixedSize(horizontal: true, vertical: false)
                if scale > 1 {
                    pairText(watch.displayName, scale: 1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                pairText(watch.baseAsset, scale: scale)
                    .minimumScaleFactor(WidgetTextSize.minimumFactor(0.7, primaryScale: scale))
            }
            .layoutPriority(1)
            Spacer(minLength: 4)
            // Veränderung gemäss %-Basis als farbiger Text (keine Pille); der Chart kann einen anderen Zeitraum zeigen
            WidgetChangeLabel(change: watch.shownChange24h, palette: palette, size: 12, showsArrow: true, day: true,
                              suffix: A11y.changeShortLabel(changeView.basis), basis: changeView.basis,
                              neutral: watch.disabled)
                .fixedSize(horizontal: true, vertical: false)
                .layoutPriority(2)
                .invalidatableContent()
        }
    }

    private func pairText(_ text: String, scale: CGFloat) -> some View {
        Text(text)
            .font(.system(size: WidgetFont.body * scale, weight: .bold))
            .foregroundStyle(watch.disabled ? palette.secondary : palette.text)
            .lineLimit(1)
    }
}
