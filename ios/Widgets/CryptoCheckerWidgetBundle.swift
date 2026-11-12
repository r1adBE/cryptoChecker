import SwiftUI
import WidgetKit

/// Startbildschirm- und Sperrbildschirm-Widgets: Merkliste, einzelnes Paar, Portfolio und
/// «Was gerade auffällt», dazu die Live-Aktivität eines Paars.
@main
struct CryptoCheckerWidgetBundle: WidgetBundle {
    init() {
        #if DEBUG
        // Debug-Schalter «Stichtag vorziehen» der App: dieselbe Uhr der Stilllegungs-Regel in den Widgets
        RetirementPreview.apply()
        #endif
    }

    var body: some Widget {
        WatchlistWidget()
        SingleWidget()
        PortfolioWidget()
        PulseWidget()
        // Live-Aktivität «Auf dem Sperrbildschirm zeigen» (ein Paar)
        PriceLiveActivityWidget()
    }
}
