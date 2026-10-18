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

/// Runde Coin-Plakette im Widget, nur mit Schalter «Coin-Logos in Widgets»: echtes Logo, sonst
/// Kreis mit Initialen — nie ein kaputtes Bild; Schalter aus → nichts. Mit `accent` wie in der App
/// und Android (Akzentfarbe mit 14 %). `always` (Live-Aktivität, kein Logo-Schalter): immer der
/// Kreis mit der Farbe aus dem Symbol. `logo: false` bei DEX-Pools.
struct WidgetCoinBadge: View {
    let symbol: String
    var size: CGFloat = 26
    let dark: Bool
    var accent: Color? = nil
    var logo: Bool = true
    var always: Bool = false
    @Environment(\.widgetLogos) private var logos
    @Environment(\.widgetLogosEnabled) private var enabled

    private var hue: Double {
        var h = 5381
        for b in CoinLogos.displaySymbol(symbol).utf8 { h = (h &* 33) &+ Int(b) }
        return Double(abs(h % 360)) / 360
    }

    var body: some View {
        if always || enabled { circle }
    }

    private var circle: some View {
        ZStack {
            if !always, logo, let image = logos[symbol] {
                // Dunkel heller Grund, damit dunkle Logos sichtbar bleiben (wie Android)
                Circle().fill(dark ? Color.dynamic(ColorTokens.onSurface).opacity(0.92)
                                   : Color.dynamic(ColorTokens.onSurfaceVariant).opacity(0.11))
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .clipShape(Circle())
            } else if let accent {
                let label = CoinLogos.initials(symbol)
                Circle().fill(accent.opacity(0.14))
                Text(label)
                    .font(.system(size: size * (label.count <= 3 ? 0.33 : 0.275), weight: .bold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            } else {
                let label = CoinLogos.initials(symbol)
                Circle().fill(Color(hue: hue, saturation: 0.45, brightness: 0.85).opacity(dark ? 0.22 : 0.30))
                Circle().strokeBorder(Color(hue: hue, saturation: 0.6, brightness: 0.9).opacity(0.25), lineWidth: 0.5)
                Text(label)
                    .font(.system(size: size * (label.count > 3 ? 0.28 : 0.34), weight: .bold, design: .rounded))
                    .foregroundStyle(Color(hue: hue, saturation: dark ? 0.55 : 0.7, brightness: dark ? 0.95 : 0.7))
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
}
