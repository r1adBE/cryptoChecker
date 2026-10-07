import ActivityKit
import Foundation

/// Live-Aktivität «Auf dem Sperrbildschirm zeigen» für EIN Paar — nur iOS.
/// Liegt in Shared/, weil App (Starten, Aktualisieren, Beenden) und
/// Widget-Erweiterung (Darstellung) denselben Typ brauchen. Kein Push, kein Server:
/// aktualisiert wird, wenn die App Kurse prüft (Vordergrund, Live, Hintergrund).
struct PriceActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Fertig formatierter Kurs mit Quote, z. B. «97’512.4 USDT».
        var priceText: String
        /// Veränderung über 24 Stunden in % wie die Pille der Merkliste (`Watch.change24h`);
        /// nil = kein 24-h-Bezug («—»).
        var change24h: Double?
        /// Stand des Kurses.
        var updatedAt: Date
    }

    /// Id des Paars in der Merkliste.
    var watchId: Int64
    /// z. B. «BTC/USDT».
    var pairLabel: String
    /// z. B. «Binance».
    var exchangeName: String
    /// Basiswährung für die Dynamic Island, z. B. «BTC».
    var symbol: String
}

extension PriceActivityAttributes {
    /// Nach so langer Zeit ohne Aktualisierung gilt der Inhalt als veraltet.
    static let staleSeconds: TimeInterval = 30 * 60

    init(watch: Watch) {
        self.init(watchId: watch.id, pairLabel: watch.displayName,
                  exchangeName: watch.marketName, symbol: watch.baseAsset)
    }

    static func state(_ watch: Watch) -> ContentState {
        let updated = watch.lastUpdate > 0 ? Date(millis: watch.lastUpdate) : Date()
        return ContentState(priceText: PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                            change24h: watch.change24h,
                            updatedAt: updated)
    }

    static func content(_ watch: Watch, now: Date = Date()) -> ActivityContent<ContentState> {
        ActivityContent(state: state(watch), staleDate: now.addingTimeInterval(staleSeconds))
    }
}
