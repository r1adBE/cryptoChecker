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
    /// Erneutes Tippen auf den gewählten Tab mit einer Seite darüber: neue Kennung = neuer Navigationsstapel,
    /// also zurück zur Startseite des Tabs (die Seiten öffnen sich über Zustände der Bildschirme und
    /// `NavigationLink`s ohne gemeinsamen Pfad — anders liessen sie sich nicht alle auf einmal schliessen).
    @State private var stackIds: [AppTab: Int] = [:]
    /// Tabs, deren Startseite gerade sichtbar ist (keine Seite darüber).
    @State private var rootVisible: Set<AppTab> = []

    var body: some View {
        TabView(selection: $router.tab) {
            withTabBar(NavigationStack { rootMarked(WatchlistScreen(), .watchlist) }.id(stackId(.watchlist)),
                       hidden: router.showExplorer)
                .tag(AppTab.watchlist)

            withTabBar(NavigationStack { rootMarked(CycleScreen(viewModel: cycleViewModel), .cycle) }.id(stackId(.cycle)),
                       hidden: router.showMarketExplorer)
                .tag(AppTab.cycle)

            if data.settings.portfolioEnabled {
                withTabBar(PortfolioTab(rootVisible: $rootVisible).id(stackId(.portfolio)))
                    .tag(AppTab.portfolio)
            }

            withTabBar(NavigationStack { rootMarked(SettingsScreen(), .settings) }.id(stackId(.settings)))
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
        // Keine Begrüssung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl
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

    private func stackId(_ tab: AppTab) -> Int { stackIds[tab, default: 0] }

    /// Merkt, ob die Startseite eines Tabs sichtbar ist (eine Seite darüber lässt sie verschwinden).
    private func rootMarked<Content: View>(_ content: Content, _ tab: AppTab) -> some View {
        content.modifier(TabRootMarker(tab: tab, rootVisible: $rootVisible))
    }

    /// Gewählten Tab noch einmal getippt (wie die System-Tableiste): Seite darüber → zurück zur Startseite;
    /// schon auf der Startseite → nach oben scrollen (die Bildschirme hören auf `AppRouter.scrollToTop`).
    private func reselect(_ tab: AppTab) {
        if rootVisible.contains(tab) {
            router.requestScrollToTop(tab)
            return
        }
        router.resetPages(of: tab)
        stackIds[tab, default: 0] += 1
    }

    /// Inhalt eines Tabs mit der eigenen Leiste unten (in jedem Tab, damit der Inhalt über ihr
    /// endet); die Systemleiste ist ausgeblendet. `hidden`: Seite ohne Leiste (z. B. «Paar hinzufügen»).
    private func withTabBar<Content: View>(_ content: Content, hidden: Bool = false) -> some View {
        content
            .toolbar(.hidden, for: .tabBar)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !hidden && !keyboardVisible {
                    AppTabBar(tabs: tabs, selection: $router.tab, onReselect: reselect)
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
    static let height: CGFloat = 66
    /// Grösste Schriftstufe der Leiste (≈ 1,5 ×, `.caption` 18 pt, wie die Deckelung in Android): darüber wächst
    /// die Leiste nicht mehr, sondern zeigt beim langen Drücken den Large Content Viewer (HIG für Leisten).
    static let maxTypeSize = DynamicTypeSize.accessibility1
    /// Zeilenhöhe der Beschriftung (`.caption`) bei Standardgrösse; wird per `@ScaledMetric` mitskaliert.
    static let labelLineHeight: CGFloat = 16
    /// Luft oben und unten im Tab, wenn die Leiste wächst (Pille 32 + 2 + Zeile 16 + 2 × 8 = 66, wie Android).
    static let verticalPadding: CGFloat = 8
    /// Seitlicher Rand je Tab — wie `TAB_HORIZONTAL_PADDING` (Android).
    static let horizontalPadding: CGFloat = 4

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
    /// Pille in der vollen Akzentfarbe, Symbol darauf onAccent: hebt sich mit ≥ 3:1 von der Leiste ab (vorher
    /// Akzent 30 % bzw. #404040: nur 1.5–1.6:1). Hell primary auf #F1EEE9 → 5.04–5.09:1, Weiss darauf 5.84–5.89:1;
    /// dunkel primary auf #1F1F1F → 6.17–7.05:1, onPrimary darauf 6.15–6.70:1 (wie Android, TabIndicatorContrastTest).
    /// Gewählt zudem an fetter Beschriftung erkennbar.
    /// Dauer der Vergrösserung beim Wechsel des Tabs (s) — wie `TAB_ICON_SCALE_MILLIS` (Android).
    static let scaleDuration: Double = 0.15
}

/// Eigene Tableiste wie `AppTabBar` (Android): Fläche `container`, bis unter den Home-Indikator;
/// gewählt ist das Symbol (onAccent) auf einer Pille in der Themenfarbe (64 × 32, wie Android) und kurz animiert 10 % grösser (sonst grau), die Beschriftung bleibt immer gleich
/// (dunkel: weiss, hell: onSurface). Für VoiceOver je eine Taste mit «ausgewählt».
///
/// Ersetzt die System-Leiste (`UITabBar`) und übernimmt deren Verhalten bei grosser Schrift: Die Leiste
/// wächst nur bis `AppTabBarMetrics.maxTypeSize` (sonst frässe sie bei AX5 auf jedem Tab Inhaltshöhe);
/// darüber zeigt langes Drücken auf einen Tab Symbol und Namen im Large Content Viewer.
@MainActor
struct AppTabBar: View {
    let tabs: [AppTabItem]
    @Binding var selection: AppTab
    /// Erneutes Tippen auf den schon gewählten Tab.
    var onReselect: (AppTab) -> Void = { _ in }

    var body: some View {
        // Deckel aussen, damit auch `@ScaledMetric` in der Zeile die gedeckelte Grösse liest
        AppTabBarRow(tabs: tabs, selection: $selection, onReselect: onReselect)
            .dynamicTypeSize(...AppTabBarMetrics.maxTypeSize)
    }
}

/// Inhalt von `AppTabBar` (unter dem Schriftgrössen-Deckel).
@MainActor
private struct AppTabBarRow: View {
    let tabs: [AppTabItem]
    @Binding var selection: AppTab
    let onReselect: (AppTab) -> Void
    /// Wächst mit der Schriftgrösse (bis zum Deckel), sonst würde die Beschriftung abgeschnitten.
    @ScaledMetric(relativeTo: .caption) private var labelLine: CGFloat = AppTabBarMetrics.labelLineHeight

    var body: some View {
        // Oben ausgerichtet: bricht eine Beschriftung auf zwei Zeilen um, bleiben alle Symbole auf einer Höhe
        HStack(alignment: .top, spacing: 0) {
            ForEach(tabs) { item in
                AppTabBarButton(item: item, selected: item.tab == selection) {
                    if selection == item.tab {
                        onReselect(item.tab)
                    } else {
                        selection = item.tab
                    }
                }
            }
        }
        .padding(.vertical, AppTabBarMetrics.verticalPadding)
        // Höhe nach Inhalt (zweizeilige Beschriftung), aber nie unter der Standardhöhe
        .fixedSize(horizontal: false, vertical: true)
        .frame(minHeight: AppTabBarMetrics.scaledHeight(labelLine: labelLine))
        .background(AppColors.container.ignoresSafeArea(edges: .bottom))
        // VoiceOver: als Tableiste ansagen («Tab 2 von 4»), die Tasten bleiben einzeln mit «ausgewählt»
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isTabBar)
    }
}

/// Ein Tab der Leiste: Symbol (24 pt, gewählt × 1,1) über der Beschriftung, ganze Fläche tippbar.
@MainActor
private struct AppTabBarButton: View {
    let item: AppTabItem
    let selected: Bool
    let action: () -> Void

    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Beschriftung: im Dunkeln weiss, hell die normale Textfarbe (weiss wäre dort unlesbar).
    private static let labelColor = Color.dynamic(light: ColorTokens.onSurface.light, dark: 0xFFFFFF)

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                icon
                    .foregroundStyle(selected ? onAccent : AppColors.onSurfaceVariant)
                    .frame(width: AppTabBarMetrics.iconSize, height: AppTabBarMetrics.iconSize)
                    .scaleEffect(selected ? AppTabBarMetrics.selectedScale : 1)
                    .animation(scaleAnimation, value: selected)
                    // Anzeiger hinter dem Symbol wie Android (Pille 64 × 32, Auswahlfläche des Themas):
                    // blendet ein und wächst aus der Mitte; in schmalen Tabs höchstens so breit wie der Tab
                    .frame(minWidth: 0, maxWidth: AppTabBarMetrics.indicatorWidth)
                    .frame(height: AppTabBarMetrics.indicatorHeight)
                    .background {
                        Capsule()
                            .fill(accent.primary)
                            .scaleEffect(x: selected ? 1 : 0.5, y: 1)
                            .opacity(selected ? 1 : 0)
                            .animation(scaleAnimation, value: selected)
                    }
                label
            }
            .padding(.horizontal, AppTabBarMetrics.horizontalPadding)
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
        // Gewählt fett, damit der Tab nicht nur am Farbton erkennbar ist (wie Android). Lange Namen brechen
        // auf zwei Zeilen um statt zu schrumpfen (`.caption`, nie unter 12 pt)
        Text(item.title)
            .font(.caption.weight(selected ? .bold : .medium))
            .foregroundStyle(Self.labelColor)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
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
    @Binding var rootVisible: Set<AppTab>

    var body: some View {
        switch lock.access {
        case .open:
            NavigationStack { PortfolioScreen().modifier(TabRootMarker(tab: .portfolio, rootVisible: $rootVisible)) }
        case .locked, .pending:
            NavigationStack { PortfolioLockedView(lock: lock).modifier(TabRootMarker(tab: .portfolio, rootVisible: $rootVisible)) }
        }
    }
}

/// Startseite eines Tabs sichtbar oder nicht: verschwindet, sobald eine Seite darüber liegt (oder ein anderer
/// Tab gewählt ist), und erscheint wieder beim Zurückgehen.
private struct TabRootMarker: ViewModifier {
    let tab: AppTab
    @Binding var rootVisible: Set<AppTab>

    func body(content: Content) -> some View {
        content
            .onAppear { rootVisible.insert(tab) }
            .onDisappear { rootVisible.remove(tab) }
    }
}
