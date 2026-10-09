import SwiftUI
import UIKit

/// Gleicher Anfang im ⋯-Menü von Merkliste, Markt und Portfolio: App-Logo und Name (öffnet
/// «Über»), Trennlinie, «Aktualisieren». Danach folgen die eigenen Einträge des Tabs — wie
/// `AppMenuHead` (Android).
struct AppMenuHead: View {
    let refreshing: Bool
    let onOpenAbout: () -> Void
    let onRefresh: () -> Void
    @Environment(\.appAccent) var accent
    @Environment(\.colorScheme) var colorScheme

    var body: some View {
        Button(action: onOpenAbout) {
            // Menüs zeigen nur Bilder aus dem Katalog bzw. Symbole, keine eigenen Ansichten
            let logo = accent.logoName(dark: colorScheme == .dark)
            if UIImage(named: logo) != nil {
                Label(L("app_name"), image: logo)
            } else {
                Label(L("app_name"), systemImage: "chart.line.uptrend.xyaxis.circle.fill")
            }
        }
        Divider()
        Button(action: onRefresh) {
            Label(L("action_refresh"), systemImage: "arrow.clockwise")
        }
        .disabled(refreshing)
    }
}
