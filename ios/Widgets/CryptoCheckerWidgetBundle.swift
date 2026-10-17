import SwiftUI
import WidgetKit

/// Startbildschirm- und Sperrbildschirm-Widgets: Merkliste, einzelnes Paar, Portfolio und
/// «Was gerade auffällt», dazu die Live-Aktivität eines Paars.
@main
struct CryptoCheckerWidgetBundle: WidgetBundle {
    var body: some Widget {
        WatchlistWidget()
        SingleWidget()
        PortfolioWidget()
        PulseWidget()
        // Live-Aktivität «Auf dem Sperrbildschirm zeigen» (ein Paar)
        PriceLiveActivityWidget()
    }
}
