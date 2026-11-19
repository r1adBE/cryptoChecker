import SwiftUI

/// Zwei Spalten für einen Tab im breiten Fenster (iPad, `horizontalSizeClass == .regular`): links die Startseite des
/// Tabs, rechts was sie öffnet. Die Startseiten öffnen ihre Seiten über `navigationDestination` — in der linken Spalte
/// einer `NavigationSplitView` ersetzt das die rechte Spalte, statt die Liste zu verdecken; Seiten, die von dort weiter
/// öffnen, legen sich rechts darüber. Ohne gewählte Seite steht rechts ein ruhiger Platzhalter. Schmal (iPhone, iPad
/// in schmaler Teilung) bleibt es der eine Stapel. Wie Android `TwoPane`.
struct SplitTab<Root: View>: View {
    let root: Root
    /// Beide Spalten immer sichtbar (auch iPad hochkant): die Liste ist die Hauptsache und verschwindet nicht.
    @State private var columns: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            root
                .navigationSplitViewColumnWidth(min: SplitTabMetrics.listMin, ideal: SplitTabMetrics.listIdeal,
                                                max: SplitTabMetrics.listMax)
                .toolbar(removing: .sidebarToggle)
        } detail: {
            SplitPlaceholder()
        }
        .navigationSplitViewStyle(.balanced)
    }
}

/// Breiten der linken Spalte (pt); wie Android `TwoPanePolicy`.
enum SplitTabMetrics {
    static let listMin: CGFloat = 340
    static let listIdeal: CGFloat = 420
    static let listMax: CGFloat = 520
}

/// Rechte Spalte ohne gewählte Seite: App-Hintergrund mit dezentem Zeichen, ohne Text (nichts zu lesen, nichts zu tun).
private struct SplitPlaceholder: View {
    var body: some View {
        Image(systemName: "sidebar.left")
            .font(.system(size: 44, weight: .light))
            .foregroundStyle(AppColors.outlineVariant)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AppColors.background)
            .accessibilityHidden(true)
    }
}
