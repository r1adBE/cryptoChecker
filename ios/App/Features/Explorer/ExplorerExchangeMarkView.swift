import SwiftUI

/// Zeichen einer Börse in den Listen: echtes Logo (`ExchangeLogos`, vom Gerät über `CoinLogoStore` — nur mit Schalter
/// «In der App»), DexScreener und EZB-Referenzkurse ein festes Symbol (`ExchangeLogos.Symbol`), sonst und solange das
/// Logo lädt oder fehlt der farbige Kreis mit den Anfangsbuchstaben (`ExplorerExchangeMark`; Farben wie die
/// Ersatz-Kreise der Coins, `CoinLogoPlaceholder`, mindestens 4.5:1). Rein dekorativ. Wie `ExchangeMarkBadge` (Android).
struct ExplorerExchangeMarkView: View {
    let key: String
    let name: String
    let size: CGFloat
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.coinLogosEnabled) private var logosEnabled
    /// Neue Logos auf dem Gerät: fehlendes erneut versuchen.
    @ObservedObject private var revision = CoinLogoRevision.shared
    /// Geladenes Logo samt Schlüssel (Zeilen werden wiederverwendet: nie das Logo einer anderen Börse).
    @State private var loaded: (key: String, image: UIImage)?

    private var logoKey: String? { logosEnabled ? ExchangeLogos.logoKey(key) : nil }

    var body: some View {
        mark
            .frame(width: size, height: size)
            .clipShape(Circle())
            .accessibilityHidden(true)
            .task(id: "\(logoKey ?? "")|\(revision.value)") {
                guard let logoKey, loaded?.key != logoKey else { return }
                guard let image = await CoinLogoStore.shared.logo(logoKey) else { return }
                loaded = (logoKey, image)
            }
    }

    @ViewBuilder private var mark: some View {
        let dark = colorScheme == .dark
        if let symbol = ExchangeLogos.symbols[key] {
            ZStack {
                Circle().fill(AppColors.containerHigh)
                Image(systemName: symbol == .dex ? "arrow.left.arrow.right" : AssetKindIconView.tradFiSymbol)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        } else if let image = currentImage {
            ZStack {
                Circle().fill(dark ? AppColors.onSurface.opacity(0.92) : AppColors.containerHigh)
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
            }
        } else {
            let initials = ExplorerExchangeMark.initials(name)
            ZStack {
                Circle().fill(CoinLogoPlaceholder.fill(ExplorerExchangeMark.colorKey(name), dark: dark))
                Text(verbatim: initials)
                    .font(.system(size: size * (initials.count == 1 ? 0.42 : 0.36), weight: .bold))
                    .foregroundStyle(CoinLogoPlaceholder.text(dark: dark))
                    .lineLimit(1)
            }
        }
    }

    /// Logo aus dem Speicher (sofort) oder das nachgeladene; nil = Schalter aus, keins zugeordnet, (noch) nicht da.
    private var currentImage: UIImage? {
        guard let logoKey else { return nil }
        if let loaded, loaded.key == logoKey { return loaded.image }
        return CoinLogoStore.cached(logoKey)
    }
}
