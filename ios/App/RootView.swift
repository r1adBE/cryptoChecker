import SwiftUI

/// Tabs: Merkliste · Markt · (Portfolio) · Einstellungen — wie Android (Runde 31: ohne Tab «Suchen»).
/// Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist. «Paar hinzufügen»
/// öffnet sich als Seite über der Merkliste («+» in deren Kopfzeile).
struct RootView: View {
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    /// Zyklus-Daten leben hier, damit ein Tab-Wechsel sie nicht neu lädt.
    @StateObject private var cycleViewModel = CycleViewModel()

    var body: some View {
        TabView(selection: $router.tab) {
            NavigationStack { WatchlistScreen() }
                .tabItem { Label(L("tab_watchlist"), systemImage: "checklist.unchecked") }
                .tag(AppTab.watchlist)

            NavigationStack { CycleScreen(viewModel: cycleViewModel) }
                .tabItem { Label(L("tab_market_phase"), systemImage: "waveform.path.ecg") }
                .tag(AppTab.cycle)

            if data.settings.portfolioEnabled {
                PortfolioTab()
                    .tabItem { Label(L("portfolio_title"), systemImage: "chart.pie") }
                    .tag(AppTab.portfolio)
            }

            NavigationStack { SettingsScreen() }
                .tabItem { Label(L("settings_title"), systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .background(AppColors.background)
        // Portfolio ausgeschaltet, während es offen ist: zurück zur Merkliste
        .onChange(of: data.settings.portfolioEnabled, initial: true) { _, enabled in
            if !enabled && router.tab == .portfolio { router.tab = .watchlist }
        }
        // Keine Begrüßung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl
        // der leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über.
    }
}

/// Portfolio-Tab hinter der Portfolio-Sperre: gesperrt nur der ruhige Sperr-Zustand (ohne
/// Beträge). Beim Sperren wird der ganze Stapel ersetzt — Detailansicht, Erfassen-Blätter und
/// Stichtag-Export schliessen sich damit.
private struct PortfolioTab: View {
    @ObservedObject private var lock = AppLock.shared

    var body: some View {
        switch lock.access {
        case .open:
            NavigationStack { PortfolioScreen() }
        case .locked, .pending:
            NavigationStack { PortfolioLockedView(lock: lock) }
        }
    }
}
