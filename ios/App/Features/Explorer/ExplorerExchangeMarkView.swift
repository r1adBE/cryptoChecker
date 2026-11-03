import SwiftUI

/// Zeichen einer Börse in den Listen: echtes Logo (`ExchangeLogos`, mitgeliefert in `ExchangeLogoPack` — nur mit
/// Schalter «In der App»), DexScreener und EZB-Referenzkurse ein festes Symbol (`ExchangeLogos.Symbol`), sonst (Schalter
/// aus, Paket unlesbar) der farbige Kreis mit den Anfangsbuchstaben (`ExplorerExchangeMark`; Farben wie die
/// Ersatz-Kreise der Coins, `CoinLogoPlaceholder`, mindestens 4.5:1). Rein dekorativ. Wie `ExchangeMarkBadge` (Android).
struct ExplorerExchangeMarkView: View {
    let key: String
    let name: String
    let size: CGFloat
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        mark
            .frame(width: size, height: size)
            .clipShape(Circle())
            .accessibilityHidden(true)
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

    /// Mitgeliefertes Logo (sofort); nil = keins zugeordnet oder Paket unlesbar.
    private var currentImage: UIImage? {
        guard let name = ExchangeLogos.logoName(key) else { return nil }
        return ExchangeLogoPack.image(name)
    }
}
