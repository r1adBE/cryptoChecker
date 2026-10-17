import SwiftUI

/// Tabs: Merkliste · Markt · (Portfolio) · Einstellungen — wie Android (Runde 31: ohne Tab «Suchen»).
/// Die Tableiste ist eigen (`AppTabBar`), die des Systems ausgeblendet: Symbole 24 pt, das gewählte
/// 10 % grösser und in der Themenfarbe, die Beschriftung immer gleich — das
/// lässt `TabView`/`UITabBar` nicht zu (feste Leistenhöhe 49 pt, Symbolgrösse vom System). Die
/// `TabView` bleibt für die Tabs selbst (eigener Navigationsstapel und Zustand je Tab).
/// Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist. «Paar hinzufügen»
/// öffnet sich als Seite über der Merkliste («+» in deren Kopfzeile), dort ohne Tableiste.
struct RootView: View {
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    /// Zyklus-Daten leben hier, damit ein Tab-Wechsel sie nicht neu lädt.
    @StateObject private var cycleViewModel = CycleViewModel()
    /// Tastatur sichtbar: Tableiste ausblenden (läge sonst über der Tastatur; Android: darunter).
    @State private var keyboardVisible = false

    var body: some View {
        TabView(selection: $router.tab) {
            withTabBar(NavigationStack { WatchlistScreen() }, hidden: router.showExplorer)
                .tag(AppTab.watchlist)

            withTabBar(NavigationStack { CycleScreen(viewModel: cycleViewModel) }, hidden: router.showMarketExplorer)
                .tag(AppTab.cycle)

            if data.settings.portfolioEnabled {
                withTabBar(PortfolioTab())
                    .tag(AppTab.portfolio)
            }

            withTabBar(NavigationStack { SettingsScreen() })
                .tag(AppTab.settings)
        }
        .background(AppColors.background)
        // Portfolio ausgeschaltet, während es offen ist: zurück zur Merkliste
        .onChange(of: data.settings.portfolioEnabled, initial: true) { _, enabled in
            if !enabled && router.tab == .portfolio { router.tab = .watchlist }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            keyboardVisible = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardVisible = false
        }
        // Keine Begrüßung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl
        // der leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über die App.
    }

    /// Tabs in der Reihenfolge der Leiste (Portfolio nur, wenn eingeschaltet). Eigene, kurze
    /// Beschriftungen (tab_portfolio, tab_settings); die Seitentitel bleiben lang (K-4).
    private var tabs: [AppTabItem] {
        var items: [AppTabItem] = [
            AppTabItem(tab: .watchlist, title: L("tab_watchlist"), icon: .watchlist),
            AppTabItem(tab: .cycle, title: L("tab_market_phase"), icon: .cycle),
        ]
        if data.settings.portfolioEnabled {
            items.append(AppTabItem(tab: .portfolio, title: L("tab_portfolio"), icon: .portfolio))
        }
        items.append(AppTabItem(tab: .settings, title: L("tab_settings"), icon: .settings))
        return items
    }

    /// Inhalt eines Tabs mit der eigenen Leiste unten (in jedem Tab, damit der Inhalt über ihr
    /// endet); die Systemleiste ist ausgeblendet. `hidden`: Seite ohne Leiste (z. B. «Paar hinzufügen»).
    private func withTabBar<Content: View>(_ content: Content, hidden: Bool = false) -> some View {
        content
            .toolbar(.hidden, for: .tabBar)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !hidden && !keyboardVisible {
                    AppTabBar(tabs: tabs, selection: $router.tab)
                }
            }
    }
}

/// Ein Tab der Leiste: Ziel, Name und Symbol.
struct AppTabItem: Identifiable {
    enum Icon {
        case system(String)
        case asset(String)

        /// Symbole der Tabs — auch vor den Seitentiteln (`TabTitle`), damit beide gleich bleiben.
        static let watchlist = Icon.asset("TabWatchlist")
        static let cycle = Icon.system("waveform.path.ecg")
        static let portfolio = Icon.asset("PortfolioIcon")
        static let settings = Icon.system("gearshape")
    }

    let tab: AppTab
    let title: String
    let icon: Icon
    var id: AppTab { tab }
}

/// Masse der Tableiste — wie `TAB_BAR_HEIGHT`/`TAB_ICON_SIZE` (Android).
enum AppTabBarMetrics {
    /// Höhe ohne den Bereich des Home-Indikators (Standard-Schriftgrösse); wächst mit grosser Schrift
    /// (`scaledHeight(labelLine:)`), aber nur bis `maxTypeSize`.
    static let height: CGFloat = 64
    /// Grösste Schriftstufe der Leiste (≈ 1,3 ×, wie die Deckelung in Android): darüber wächst die Leiste
    /// nicht mehr, sondern zeigt beim langen Drücken den Large Content Viewer (HIG für Leisten).
    static let maxTypeSize = DynamicTypeSize.xxxLarge
    /// Zeilenhöhe der Beschriftung (`.caption`) bei Standardgrösse; wird per `@ScaledMetric` mitskaliert.
    static let labelLineHeight: CGFloat = 16
    /// Luft oben und unten im Tab, wenn die Leiste wächst (Pille 32 + 2 + Zeile 16 + 2 × 7 = 64, wie Android).
    static let verticalPadding: CGFloat = 7

    /// Höhe der Leiste für die skalierte Zeilenhöhe der Beschriftung: nie unter `height` (Aussehen bei
    /// Standardgrösse bleibt), mit grosser Schrift so hoch, dass Symbol und Beschriftung Platz haben.
    static func scaledHeight(labelLine: CGFloat) -> CGFloat {
        max(height, max(iconSize * selectedScale, indicatorHeight) + 2 + labelLine + 2 * verticalPadding)
    }
    /// Die übliche Grösse von 24 pt.
    static let iconSize: CGFloat = 24
    /// SF Symbols in der 24-pt-Fläche etwa so gross wie die Vektor-Symbole in Android.
    static let symbolPointSize: CGFloat = 19
    /// Das Symbol des gewählten Tabs ist 10 % grösser — wie `TAB_ICON_SELECTED_SCALE` (Android).
    static let selectedScale: CGFloat = 1.1
    /// Anzeiger (Pille) hinter dem Symbol des gewählten Tabs — wie `TAB_INDICATOR_WIDTH`/`_HEIGHT` (Android).
    static let indicatorWidth: CGFloat = 64
    static let indicatorHeight: CGFloat = 32
    /// Dauer der Vergrösserung beim Wechsel des Tabs (s) — wie `TAB_ICON_SCALE_MILLIS` (Android).
    static let scaleDuration: Double = 0.15
}

/// Eigene Tableiste wie `AppTabBar` (Android): Fläche `container`, bis unter den Home-Indikator;
/// gewählt ist das Symbol in der Themenfarbe auf einer Pille (64 × 32, wie Android) und kurz animiert 10 % grösser (sonst grau), die Beschriftung bleibt immer gleich
/// (dunkel: weiss, hell: onSurface). Für VoiceOver je eine Taste mit «ausgewählt».
///
/// Ersetzt die System-Leiste (`UITabBar`) und übernimmt deren Verhalten bei grosser Schrift: Die Leiste
/// wächst nur bis `AppTabBarMetrics.maxTypeSize` (sonst fräße sie bei AX5 auf jedem Tab Inhaltshöhe);
/// darüber zeigt langes Drücken auf einen Tab Symbol und Namen im Large Content Viewer.
@MainActor
struct AppTabBar: View {
    let tabs: [AppTabItem]
    @Binding var selection: AppTab

    var body: some View {
        // Deckel aussen, damit auch `@ScaledMetric` in der Zeile die gedeckelte Grösse liest
        AppTabBarRow(tabs: tabs, selection: $selection)
            .dynamicTypeSize(...AppTabBarMetrics.maxTypeSize)
    }
}

/// Inhalt von `AppTabBar` (unter dem Schriftgrössen-Deckel).
@MainActor
private struct AppTabBarRow: View {
    let tabs: [AppTabItem]
    @Binding var selection: AppTab
    /// Wächst mit der Schriftgrösse (bis zum Deckel), sonst würde die Beschriftung abgeschnitten.
    @ScaledMetric(relativeTo: .caption) private var labelLine: CGFloat = AppTabBarMetrics.labelLineHeight

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs) { item in
                AppTabBarButton(item: item, selected: item.tab == selection) {
                    selection = item.tab
                }
            }
        }
        .frame(height: AppTabBarMetrics.scaledHeight(labelLine: labelLine))
        .background(AppColors.container.ignoresSafeArea(edges: .bottom))
    }
}

/// Ein Tab der Leiste: Symbol (24 pt, gewählt × 1,1) über der Beschriftung, ganze Fläche tippbar.
@MainActor
private struct AppTabBarButton: View {
    let item: AppTabItem
    let selected: Bool
    let action: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Beschriftung: im Dunkeln weiss, hell die normale Textfarbe (weiss wäre dort unlesbar).
    private static let labelColor = Color.dynamic(light: ColorTokens.onSurface.light, dark: 0xFFFFFF)

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                icon
                    .foregroundStyle(selected ? accent.primary : AppColors.onSurfaceVariant)
                    .frame(width: AppTabBarMetrics.iconSize, height: AppTabBarMetrics.iconSize)
                    .scaleEffect(selected ? AppTabBarMetrics.selectedScale : 1)
                    .animation(scaleAnimation, value: selected)
                    // Anzeiger hinter dem Symbol wie Android (Pille 64 × 32, Auswahlfläche des Themas; Review 18):
                    // blendet ein und wächst aus der Mitte; in schmalen Tabs höchstens so breit wie der Tab
                    .frame(minWidth: 0, maxWidth: AppTabBarMetrics.indicatorWidth)
                    .frame(height: AppTabBarMetrics.indicatorHeight)
                    .background {
                        Capsule()
                            .fill(accent.container)
                            .scaleEffect(x: selected ? 1 : 0.5, y: 1)
                            .opacity(selected ? 1 : 0)
                            .animation(scaleAnimation, value: selected)
                    }
                label
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(Self.testTag(item.tab))
        // Die Leiste wächst nur bis `maxTypeSize`: darüber Symbol und Name gross beim langen Drücken
        .accessibilityShowsLargeContentViewer {
            icon
            Text(item.title)
        }
    }

    /// Kennung für UI-Tests (`UiTestTags`), wie Android `BottomTab.testTag`.
    private static func testTag(_ tab: AppTab) -> String {
        switch tab {
        case .watchlist: return UiTestTags.tabWatchlist
        case .cycle: return UiTestTags.tabCycle
        case .portfolio: return UiTestTags.tabPortfolio
        case .settings: return UiTestTags.tabSettings
        }
    }

    /// Kurze Vergrösserung beim Wechsel; mit «Bewegung reduzieren» ohne Animation.
    private var scaleAnimation: Animation? {
        reduceMotion ? nil : .easeOut(duration: AppTabBarMetrics.scaleDuration)
    }

    private var label: some View {
        // Gewählt fett, damit der Tab nicht nur am Farbton erkennbar ist (wie Android); nicht unter ~11 pt
        Text(item.title)
            .font(.caption.weight(selected ? .bold : .medium))
            .foregroundStyle(Self.labelColor)
            .lineLimit(1)
            .minimumScaleFactor(0.85)
    }

    @ViewBuilder
    private var icon: some View {
        switch item.icon {
        case .system(let name):
            Image(systemName: name)
                .font(.system(size: AppTabBarMetrics.symbolPointSize, weight: .regular))
        case .asset(let name):
            Image(name)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
        }
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
