import SwiftUI

/// Apple-Watch-App: Kurse der gewählten Coins («Wie iPhone» = Favoriten des iPhones, oder eigene Liste) vom iPhone
/// oder direkt von den Börsen, dazu auf Wunsch der Portfolio-Wert vom iPhone. Wie die Wear-OS-App.
@main
struct CryptoCheckerWatchApp: App {
    @State private var model = WatchModel.shared

    init() {
        WatchLink.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                WatchQuoteList(model: model)
            }
        }
        // Neuer Anwendungskontext vom iPhone im Hintergrund (Kurse, Portfolio-Wert): Sitzung offen halten, bis er
        // übernommen ist (`WatchLink`), damit Komplikationen auch bei geschlossener App folgen
        .backgroundTask(.watchConnectivity) {
            await WatchLink.shared.receivePending()
        }
    }
}
