import SwiftUI

/// Apple-Watch-App (Build 155): Kurse der gewählten Coins («Wie iPhone» = Favoriten des iPhones, oder eigene Liste),
/// direkt von den Börsen. Wie die Wear-OS-App.
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
    }
}
