import SwiftUI

// MARK: Coin-Logo

/// Runde Coin-Plakette, nur mit eingeschaltetem Schalter «Coin-Logos» (App bzw. `portfolio`):
/// echtes Logo (`CoinLogoStore`), sonst der Kreis mit den Initialen in der Akzentfarbe — nie ein
/// kaputtes Bild. Schalter aus: gar nichts (weder Logo noch Initialen, auch kein Platz). Feste
/// Grösse, nimmt also nie Breite von Kurs- oder Zahlenspalten. Dunkel: heller Grund hinter dem
/// Logo. Wie Android `CoinBadge`.
///
/// `logo: false` (DEX-Pools, deren Symbol jeder frei wählen kann): immer Initialen, damit nie das
/// Logo eines bekannten Coins neben einem gleichnamigen fremden Token steht. `favorite`: kleiner
/// Stern unten rechts (Ring in `ringColor`, der Farbe der Fläche darunter).
struct CoinBadge: View {
    let symbol: String
    var size: CGFloat = 36
    var logo: Bool = true
    /// Kennung des Paars (`Watch.logoPairKey`): Ist es TradFi, kommt das Logo aus dem
    /// TradFi-Namensraum, nie von einem gleichnamigen Krypto-Token.
    var pair: String? = nil
    /// Eigener Schalter «Im Portfolio».
    var portfolio: Bool = false
    var favorite: Bool = false
    var ringColor: Color = AppColors.container
    @Environment(\.coinLogosEnabled) private var appEnabled
    @Environment(\.appAccent) private var starAccent
    @Environment(\.rowHighlight) private var highlight
    @Environment(\.portfolioCoinLogosEnabled) private var portfolioEnabled
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme
    /// Neue Logos auf dem Gerät (Abgleich im Hintergrund): fehlende erneut versuchen.
    @ObservedObject private var revision = CoinLogoRevision.shared
    /// Geladenes Logo samt Symbol (Zeilen werden wiederverwendet: nie das Logo eines anderen Coins).
    @State private var loaded: (symbol: String, image: UIImage)?

    init(symbol: String, size: CGFloat = 36, logo: Bool = true, pair: String? = nil, portfolio: Bool = false,
         favorite: Bool = false, ringColor: Color = AppColors.container) {
        self.symbol = symbol
        self.size = size
        self.logo = logo
        self.pair = pair
        self.portfolio = portfolio
        self.favorite = favorite
        self.ringColor = ringColor
    }

    // nonisolated: reine Konstanten, auch aus den Tests lesbar (eigenes Modul, nicht auf dem MainActor)
    /// Tönung des Initialen-Kreises hell: primary darauf über `container` ≥ 4.5:1 (vorher 0.14).
    nonisolated static let initialsTintLight = 0.06
    /// Tönung des Initialen-Kreises dunkel (unverändert).
    nonisolated static let initialsTintDark = 0.14

    private var enabled: Bool { portfolio ? portfolioEnabled : appEnabled }

    /// Schlüssel des Logos (TradFi eigener Namensraum).
    private var key: String {
        CoinLogos.logoKey(symbol, tradFi: pair.map { CoinLogoStore.tradFiPairs.contains($0) } ?? false)
    }

    var body: some View {
        // Schalter aus: weder Logo noch Platzhalter
        if enabled {
            badge
                .overlay(alignment: .bottomTrailing) {
                    if favorite { star }
                }
                // Zierde: Das Kürzel steht daneben im Paar bzw. Namen
                .accessibilityHidden(true)
                .task(id: logo ? "\(key)|\(revision.value)" : "") {
                    guard logo else { loaded = nil; return }
                    let key = self.key
                    guard loaded?.symbol != key else { return }
                    if let image = await CoinLogoStore.shared.logo(key) { loaded = (key, image) }
                }
        }
    }

    private var badge: some View {
        let key = self.key
        return ZStack {
            if logo, let image = (loaded?.symbol == key ? loaded?.image : nil) ?? CoinLogoStore.cached(key) {
                Circle().fill(colorScheme == .dark ? AppColors.onSurface.opacity(0.92) : AppColors.containerHigh)
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
                    .clipShape(Circle())
            } else {
                let label = CoinLogos.initials(symbol)
                // Hell schwächer getönt: Initialen ≥ 4.5:1 auf der Tönung, wie Android
                Circle().fill(accent.tint(colorScheme == .dark ? Self.initialsTintDark : Self.initialsTintLight))
                Text(label)
                    .font(.system(size: size * (label.count <= 3 ? 0.33 : 0.275), weight: .bold))
                    .foregroundStyle(accent.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    /// Favorit: kleiner Stern, weiss (Textfarbe) bzw. in der Themenfarbe, solange der ★-Chip gewählt
    /// ist (`rowHighlight`); Ring in der Farbe der Fläche darunter.
    private var star: some View {
        let starSize = max(12, size * 0.45)
        return Image(systemName: "star.fill")
            .font(.system(size: starSize * 0.62, weight: .bold))
            .foregroundStyle(highlight.favorites ? starAccent.primary : AppColors.onSurface)
            .frame(width: starSize, height: starSize)
            .background(Circle().fill(ringColor))
            .offset(x: 3, y: 3)
    }
}
