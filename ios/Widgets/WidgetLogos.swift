import UIKit

/// Coin-Logos für die Widgets «Merkliste» und «Einzelner Coin» (Schalter «Coin-Logos in Widgets»).
/// Der Timeline-Provider liest sie vor dem Zeichnen aus dem gemeinsamen Ordner (App Group) —
/// nie aus dem Netz; die App lädt alle Logos auf einmal (`CoinLogoStore.syncAll`). Fehlt eines,
/// zeigt das Widget Initialen. DEX-Pools nie (`CoinLogos.allowed(forMarket:)`).
enum WidgetLogos {

    static func load(for watches: [Watch]) async -> [String: UIImage] {
        guard SharedStorage.loadSettings().widgetCoinLogos else { return [:] }
        // Nur die Paare, die das Widget zeigen kann (`WidgetRows`), nicht alle der Gruppe
        let symbols = WidgetRows.logoKeys(for: watches)
        guard !symbols.isEmpty else { return [:] }
        return CoinLogoStore.storedLogos(symbols)
    }
}
