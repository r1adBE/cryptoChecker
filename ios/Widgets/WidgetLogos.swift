import UIKit

/// Coin-Logos für die Widgets «Merkliste» und «Einzelner Coin» (Schalter «Coin-Logos in Widgets»).
/// Der Timeline-Provider liest sie vor dem Zeichnen aus dem gemeinsamen Ordner (App Group) —
/// nie aus dem Netz; die App lädt alle Logos auf einmal (`CoinLogoStore.syncAll`). Fehlt eines,
/// zeigt das Widget Initialen. DEX-Pools nie (`CoinLogos.allowed(forMarket:)`).
enum WidgetLogos {

    static func load(for watches: [Watch]) async -> [String: UIImage] {
        guard SharedStorage.loadSettings().widgetCoinLogos else { return [:] }
        // TradFi-Paare unter eigenem Schlüssel (nie das Logo eines gleichnamigen Krypto-Tokens)
        let symbols = watches.filter { CoinLogos.allowed(forMarket: $0.marketKey) }.map { CoinLogoStore.logoKey(for: $0) }
        guard !symbols.isEmpty else { return [:] }
        return CoinLogoStore.storedLogos(symbols)
    }
}
