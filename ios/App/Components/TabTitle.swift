import SwiftUI

// MARK: Seitentitel mit Tab-Symbol

/// Titel einer Tab-Seite (Markt, Portfolio, Einstellungen) in der Navigationsleiste: davor
/// dasselbe Symbol wie im Tab (`AppTabItem.Icon`), in der Titelfarbe und so gross wie die
/// Titelschrift (wächst mit der Schriftgrösse). Das Symbol ist für VoiceOver nur dekorativ.
/// Wie `TabTitle` (Android). Einsatz: `.tabTitle(_:icon:)`.
struct TabTitle: View {
    let title: String
    let icon: AppTabItem.Icon
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 20

    var body: some View {
        HStack(spacing: 8) {
            symbol
                .frame(width: iconSize, height: iconSize)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
                .lineLimit(1)
        }
        .foregroundStyle(AppColors.onSurface)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        // Die Leiste deckelt die Schrift: langes Drücken zeigt den Titel gross (HIG, wie die Tableiste)
        .accessibilityShowsLargeContentViewer {
            symbol
            Text(title)
        }
    }

    @ViewBuilder
    private var symbol: some View {
        switch icon {
        case .system(let name):
            Image(systemName: name)
                .font(.headline.weight(.regular))
        case .asset(let name):
            Image(name)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
        }
    }
}

extension View {
    /// Seitentitel mit Tab-Symbol (siehe `TabTitle`): einzeilige Leiste wie Android, der Titel
    /// bleibt zusätzlich als `navigationTitle` (Zurück-Taste, Umschalter).
    func tabTitle(_ title: String, icon: AppTabItem.Icon) -> some View {
        self
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { TabTitle(title: title, icon: icon) }
            }
    }
}
