import SwiftUI

/// Tabs: Merkliste · Hinzufügen · Zyklus · (Portfolio) · Optionen.
/// Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist.
struct RootView: View {
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    @State private var showWelcome = false
    /// Zyklus-Daten leben hier, damit ein Tab-Wechsel sie nicht neu lädt.
    @StateObject private var cycleViewModel = CycleViewModel()

    var body: some View {
        TabView(selection: $router.tab) {
            NavigationStack { WatchlistScreen() }
                .tabItem { Label(L("tab_watchlist"), systemImage: "list.bullet.rectangle") }
                .tag(AppTab.watchlist)

            NavigationStack { ExplorerScreen() }
                .tabItem { Label(L("tab_markets"), systemImage: "plus.circle") }
                .tag(AppTab.add)

            NavigationStack { CycleScreen(viewModel: cycleViewModel) }
                .tabItem { Label(L("tab_market_phase"), systemImage: "arrow.triangle.2.circlepath") }
                .tag(AppTab.cycle)

            if data.settings.portfolioEnabled {
                NavigationStack { PortfolioScreen() }
                    .tabItem { Label(L("portfolio_title"), systemImage: "chart.pie") }
                    .tag(AppTab.portfolio)
            }

            NavigationStack { SettingsScreen() }
                .tabItem { Label(L("tab_settings"), systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .background(AppColors.background)
        // Portfolio ausgeschaltet, während es offen ist: zurück zur Merkliste
        .onChange(of: data.settings.portfolioEnabled, initial: true) { _, enabled in
            if !enabled && router.tab == .portfolio { router.tab = .watchlist }
        }
        .onAppear {
            if !data.settings.aboutSeen { showWelcome = true }
        }
        .sheet(isPresented: $showWelcome) {
            WelcomeSheet(onStart: {
                showWelcome = false
                router.tab = .watchlist
            })
            .environmentObject(data)
            .environment(\.appAccent, data.settings.accentColor)
            .environment(\.priceColorScheme, data.settings.priceColorScheme)
            .environment(\.priceColorsInverted, data.settings.priceColorsInverted)
            .interactiveDismissDisabled()
        }
    }
}
