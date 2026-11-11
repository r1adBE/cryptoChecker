import AppIntents
import SwiftUI
import UIKit
import WidgetKit

// Logo und Coin-Plakette der Widgets. Farben und Schrift stehen in `WidgetStyle.swift`.

// MARK: Bausteine

/// App-Logo aus dem Asset-Katalog; fehlt es im Widget-Ziel, ein Ersatzzeichen.
struct WidgetLogo: View {
    let accent: AccentColor
    let dark: Bool
    var size: CGFloat = 16

    var body: some View {
        if let image = UIImage(named: accent.logoName(dark: dark)) {
            Image(uiImage: image)
                .resizable()
                .interpolation(.high)
                .aspectRatio(contentMode: .fit)
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                    .fill(accent.primary(dark: dark))
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.system(size: size * 0.55, weight: .bold))
                    .foregroundStyle(AppColors.onVivid)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        }
    }
}

/// Coin-Logos der Widgets je Basis-Symbol (vom Timeline-Provider geladen, `WidgetLogos`).
/// Leer: Schalter «Coin-Logos in Widgets» aus, Vorschau oder noch nicht geladen → Initialen.
private struct WidgetLogosKey: EnvironmentKey {
    static let defaultValue: [String: UIImage] = [:]
}

/// Schalter «Coin-Logos in Widgets»; aus → keine Plakette (weder Logo noch Initialen).
private struct WidgetLogosEnabledKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var widgetLogos: [String: UIImage] {
        get { self[WidgetLogosKey.self] }
        set { self[WidgetLogosKey.self] = newValue }
    }

    var widgetLogosEnabled: Bool {
        get { self[WidgetLogosEnabledKey.self] }
        set { self[WidgetLogosEnabledKey.self] = newValue }
    }
}

/// Runde Coin-Plakette im Widget, nur mit Schalter «Coin-Logos in Widgets»: echtes Logo, sonst der farbige
/// Ersatz-Kreis mit den Initialen (`CoinLogoPlaceholder`, Farbe fest aus dem Kürzel, wie in der App und Android) — nie
/// ein kaputtes Bild; Schalter aus → nichts. `always` (Live-Aktivität, kein Logo-Schalter): immer der Ersatz-Kreis.
/// `logo: false` bei DEX-Pools. `accent` bleibt für die Aufrufer, der Kreis hängt nicht mehr von ihr ab.
/// Getönter Home-Bildschirm (iOS 18, `widgetRenderingMode` ≠ `.fullColor`): das System färbt alles Deckende gleich ein —
/// das Logo bleibt darum in seinen Farben (`widgetAccentedRenderingMode(.fullColor)`), und der Ersatz-Kreis wird zu einer
/// schwachen Scheibe mit deckenden Initialen (sonst verschmelzen Kreis und Schrift zu einer einfarbigen Fläche).
struct WidgetCoinBadge: View {
    let symbol: String
    var size: CGFloat = 26
    let dark: Bool
    var accent: Color? = nil
    var logo: Bool = true
    var always: Bool = false
    @Environment(\.widgetLogos) private var logos
    @Environment(\.widgetLogosEnabled) private var enabled
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        if always || enabled { circle }
    }

    private var circle: some View {
        ZStack {
            if !always, logo, let image = logos[symbol] {
                // Dunkel heller Grund, damit dunkle Logos sichtbar bleiben (wie Android)
                Circle().fill(dark ? Color.dynamic(ColorTokens.onSurface).opacity(0.92)
                                   : Color.dynamic(ColorTokens.onSurfaceVariant).opacity(0.11))
                logoImage(image)
                    .scaledToFit()
                    .clipShape(Circle())
            } else {
                let label = CoinLogos.initials(symbol)
                let tinted = renderingMode != .fullColor
                // Getönt zählt nur die Deckkraft: Scheibe schwach, Initialen voll
                if tinted {
                    Circle().fill(Color.primary.opacity(0.18))
                } else {
                    Circle().fill(CoinLogoPlaceholder.fill(symbol, dark: dark))
                }
                Text(label)
                    .font(.system(size: size * (label.count <= 3 ? 0.33 : 0.275), weight: .bold))
                    .foregroundStyle(tinted ? Color.primary : CoinLogoPlaceholder.text(dark: dark))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        // Zierde: Das Kürzel steht schon im Paar
        .accessibilityHidden(true)
    }

    /// Coin-Logo; ab iOS 18 auch im getönten Modus in seinen eigenen Farben statt als einfarbige Scheibe.
    @ViewBuilder
    private func logoImage(_ image: UIImage) -> some View {
        if #available(iOS 18.0, *) {
            Image(uiImage: image)
                .resizable()
                .interpolation(.high)
                .widgetAccentedRenderingMode(.fullColor)
        } else {
            Image(uiImage: image)
                .resizable()
                .interpolation(.high)
        }
    }
}
