import SwiftUI
import UIKit
import UserNotifications

/// Tab «Optionen» — wie `SettingsScreen.kt` (Runde 23f): eine kurze, gruppierte Liste.
/// Je Gruppe eine Karte wie im Markt-Tab «Jetzt» (Überschrift mit Strich oben in der Karte),
/// darin Zeilen mit Titel, aktuellem Wert in Grau und Pfeil;
/// alles Ausführliche steht auf Unterseiten (`Settings…Pages.swift` je Gruppe, Markt-Meldungen, Sprachausgabe).
/// Gruppen (Build 55): Allgemein (Sprache, Währung, Merkliste, Alarme, Kurs-Aktualisierung)
/// · Darstellung (Hell/Dunkel, Akzent, Kursfarben, Widgets) · Mehr (Sicherung, Portfolio als Schalter,
/// «Erweitert», Über die App)
/// · rote Karte «App zurücksetzen». Kurs-Aktualisierung und Widgets stehen wieder auf der Hauptseite, weil
/// Alarm-Hinweis und Widget-Hilfe dorthin verweisen und beide oft gesucht werden. «Erweitert»
/// (`AdvancedSettingsPage`) trägt Token & TradFi, Portfolio, Markt-Meldungen, Sprachausgabe, %-Änderung,
/// Coin-Logos und Entwickler; Datenschutz, Börse wünschen, Quellcode und Lizenzen stehen auf «Über die App»
/// — wie Android.
/// Jede Zeile der Hauptseite trägt links ein schlichtes graues Symbol (Unterseiten ohne);
/// Merkliste wie der Tab, nur «App zurücksetzen» rot.
///
/// An iOS angepasst:
/// - Sprache: iOS stellt die App-Sprache in der Einstellungen-App ein.
/// - Hintergrund: iOS bestimmt den genauen Zeitpunkt; «Live» gilt, solange die App offen ist.
/// - Dauerhafte Mitteilungen und Akku-Optimierung gibt es auf iOS nicht.
/// - Widgets fügt man über den Startbildschirm hinzu.
///
/// Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
@MainActor
struct SettingsScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.scenePhase) private var scenePhase

    /// Erlaubnis für Mitteilungen; `.authorized` als Startwert, damit kein Hinweis aufblitzt.
    @State private var notificationStatus: UNAuthorizationStatus = .authorized
    /// Runde 31: Suche oben; bleibt nach dem Öffnen eines Treffers stehen (zurück = weitere Treffer).
    @State private var query = ""
    /// Treffer auf der Hauptseite selbst (Sprache, «App zurücksetzen»): Zeile kurz hervorheben.
    @State private var mainHighlight: String?
    /// «App zurücksetzen» — Rückfrage.
    @State private var askReset = false
    @EnvironmentObject private var router: AppRouter

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        ScrollViewReader { proxy in
            page
                // Zeile der Hauptseite aus der Suche: hinscrollen, Ziel nach kurzer Zeit wieder lösen
                .task(id: mainHighlight) {
                    guard let anchor = mainHighlight else { return }
                    try? await Task.sleep(nanoseconds: SettingsHighlightTiming.delayNanos)
                    withAnimation { proxy.scrollTo(anchor, anchor: .center) }
                    try? await Task.sleep(nanoseconds: SettingsHighlightTiming.mainNanos)
                    if mainHighlight == anchor { mainHighlight = nil }
                }
                // Tab «Einstellungen» noch einmal getippt: nach oben (bei aktiver Suche gibt es den Anker nicht)
                .onChange(of: router.scrollToTop) { _, request in
                    guard request?.tab == .settings else { return }
                    withAnimation { proxy.scrollTo(Self.topId, anchor: .top) }
                }
        }
    }

    /// Ziel für «nach oben» (erste Karte).
    private static let topId = "settings_top"

    private var page: some View {
        content
            .environment(\.settingsHighlight, mainHighlight)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("settings_search_hint"))
            .background(AppColors.background.ignoresSafeArea())
            // Einzeilig mit Tab-Symbol wie Markt und Portfolio (Android: gleiche Kopfzeile)
            .tabTitle(L("settings_title"), icon: .settings)
            // Portfolio-Widget, während das Portfolio aus ist: gleich die Seite «Portfolio» (dort der Schalter)
            .navigationDestination(isPresented: $router.showPortfolioSettings) {
                PortfolioSettingsPage()
            }
            // K-10: «Aktualisierung ändern» im Alarm-Hinweis: gleich die Seite «Aktualisierung»
            .navigationDestination(isPresented: $router.showUpdateSettings) {
                UpdatesSettingsPage()
            }
            // «Sicherung wiederherstellen» im Hinweis «Gespeicherte Daten liessen sich nicht entsperren»
            .navigationDestination(isPresented: $router.showBackupSettings) {
                BackupSettingsPage()
            }
            .animation(.spring(duration: 0.35), value: settings.developerUnlocked)
            // Auch nach der Rückkehr von einer Unterseite (Probe-Alarm) und aus den iOS-Einstellungen
            .onAppear { Task { await refreshNotificationStatus() } }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await refreshNotificationStatus() } }
            }
    }

    /// Ohne Suche die Gruppen als Karten, sonst die Treffer (Liste wie bisher).
    @ViewBuilder
    private var content: some View {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            cards
        } else {
            results
        }
    }

    /// Je Gruppe eine Karte wie im Markt-Tab «Jetzt» (Überschrift oben in der Karte), 12 pt
    /// Abstand dazwischen, keine Trennlinie zwischen den Gruppen — wie `SettingsSection` (Android).
    private var cards: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                generalSection
                    .id(Self.topId)
                appearanceSection
                moreSection
                SettingsCard {
                    resetRow
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            // iPad/Querformat: Karten höchstens 640 pt breit, mittig
            .readableContentWidth()
        }
    }

    private var results: some View {
        List {
            SettingsSearchResults(items: SettingsSearchCatalog.items(settings: settings), query: query) { anchor in
                query = ""
                mainHighlight = anchor
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .environment(\.defaultMinListRowHeight, 52)
    }

    // MARK: Allgemein

    private var generalSection: some View {
        SettingsCard(title: L("settings_group_general")) {
            // Sprache: stellt iOS in der Einstellungen-App ein
            SettingsButtonRow(
                title: L("settings_section_language"),
                value: Self.currentLanguageName,
                trailingIcon: "arrow.up.forward",
                hint: L("ios_settings_language_hint"),
                icon: .system("globe")
            ) {
                openURL(UIApplication.openSettingsURLString)
            }
            .settingsAnchor("main.language")
            RowDivider()
            SettingsNavRow(title: L("settings_row_currency"), icon: .system("arrow.left.arrow.right"), value: settings.portfolioCurrency) {
                CurrencySettingsPage()
            }
            RowDivider()
            // Gleiches Symbol wie der Tab «Merkliste»
            SettingsNavRow(title: L("settings_row_watchlist"), icon: .asset("TabWatchlist"), value: SettingsSummary.watchlistText(settings)) {
                WatchlistSettingsPage()
            }
            RowDivider()
            // Hinweis nur, wenn etwas Mitteilungen braucht und die Erlaubnis fehlt
            if needsNotificationPermission {
                notificationPermissionRow
                RowDivider()
            }
            // Glocke mit Wellen wie bei den Alarmen der Paare
            SettingsNavRow(title: L("settings_row_alarms"), icon: .system("bell.and.waves.left.and.right"), value: L(settings.alarmSignal.ios.labelKey)) {
                AlarmSettingsPage()
            }
            RowDivider()
            // Direkt nach «Alarme»: bestimmt, wie oft Alarme geprüft werden
            SettingsNavRow(title: L("settings_row_updates"), icon: .system("arrow.clockwise"), value: SettingsSummary.updatesText(settings)) {
                UpdatesSettingsPage()
            }
        }
    }

    // MARK: Darstellung

    private var appearanceSection: some View {
        SettingsCard(title: L("settings_section_appearance")) {
            SettingsNavRow(title: L("settings_theme_mode"), icon: .system("circle.lefthalf.filled"), value: SettingsSummary.displayModeText(settings)) {
                DisplayModeSettingsPage()
            }
            RowDivider()
            accentRow
            RowDivider()
            priceColorsRow
            RowDivider()
            // Widgets fügt man über den Startbildschirm hinzu: die Seite erklärt, wie
            SettingsNavRow(title: L("settings_widgets"), icon: .system("square.grid.2x2"), value: nil) {
                AddWidgetHelpSheet(portfolioEnabled: settings.portfolioEnabled, asPage: true)
            }
        }
    }

    private var priceColorsRow: some View {
        // Kerzen wie im Schalter Kerzen | Linie der Charts
        SettingsNavRow(title: L("settings_price_colors"), icon: .candles, valueDescription: SettingsSummary.priceColorsText(settings)) {
            PriceArrowsView(scheme: settings.priceColorScheme, inverted: settings.priceColorsInverted)
        } destination: {
            PriceColorsSettingsPage()
        }
    }

    private var accentRow: some View {
        let accentName = L(settings.accentColor.labelKey)
        return SettingsNavRow(title: L("settings_accent"), icon: .system("paintpalette"), valueDescription: accentName) {
            // Farbpunkt passend zum kleinen Wert unter dem Titel
            HStack(spacing: 4) {
                // K3: Farbe der Knöpfe (hell/dunkel), nicht die Grundfarbe
                Circle().fill(settings.accentColor.primary).frame(width: 10, height: 10)
                Text(accentName)
            }
        } destination: {
            ThemeSettingsPage()
        }
    }

    // MARK: Mehr

    /// Sicherung, «Erweitert» und Über die App — wie Android.
    private var moreSection: some View {
        SettingsCard(title: L("settings_group_more")) {
            SettingsNavRow(title: L("backup_title"), icon: .system("square.and.arrow.up"), value: nil) {
                BackupSettingsPage()
            }
            .accessibilityIdentifier(UiTestTags.settingsBackup)
            RowDivider()
            // Portfolio direkt einschalten — derselbe Schalter wie Erweitert › Portfolio
            SettingsMainSwitchRow(title: L("portfolio_title"), icon: .asset("PortfolioIcon"),
                                  subtitle: L("portfolio_setting_hint"), isOn: $data.settings.portfolioEnabled)
                .settingsAnchor("main.portfolio")
            RowDivider()
            SettingsNavRow(title: L("settings_row_advanced"), icon: .system("slider.horizontal.3"), value: nil) {
                AdvancedSettingsPage()
            }
            RowDivider()
            SettingsNavRow(title: L("settings_row_about"), icon: .system("info.circle"), value: AboutContent.versionText) {
                AboutSettingsPage()
            }
        }
    }

    private var notificationPermissionRow: some View {
        NotificationPermissionRow(denied: notificationStatus == .denied) {
            if notificationStatus == .denied {
                openURL(UIApplication.openNotificationSettingsURLString)
            } else {
                Task {
                    _ = await Notifier.requestPermission()
                    await refreshNotificationStatus()
                }
            }
        }
    }

    /// «App zurücksetzen»: alles löschen wie nach der Installation (mit Rückfrage); eigene Karte ganz unten.
    private var resetRow: some View {
        Button(role: .destructive) {
            askReset = true
        } label: {
            HStack(spacing: 12) {
                // Symbol links rot wie der Text (Farbe vom HStack)
                SettingsLeadingIcon(icon: .system("arrow.counterclockwise"))
                Text(L("settings_reset_app"))
                Spacer()
                Image(systemName: "trash")
                    .accessibilityHidden(true)
            }
            .foregroundStyle(AppColors.destructive)
            .frame(maxWidth: .infinity, minHeight: SettingsRowMetrics.minHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .settingsAnchor("main.reset")
        .confirmationDialog(L("settings_reset_app_title"), isPresented: $askReset, titleVisibility: .visible) {
            Button(L("settings_reset_app_action"), role: .destructive) {
                data.resetApp()
                query = ""
                router.tab = .watchlist
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_reset_app_confirm"))
        }
    }

    // MARK: Sprache

    /// Sprache, in der die App gerade läuft, in ihrer eigenen Schreibweise.
    static var currentLanguageName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        let lang = code.split(separator: "-").first.map(String.init) ?? code
        if let name = settingsLanguageNames[code] ?? settingsLanguageNames[lang] { return name }
        let locale = Locale(identifier: code)
        return locale.localizedString(forIdentifier: code)?.capitalized(with: locale) ?? code
    }

    /// Wie `AppLanguages.ALL` der Android-Fassung.
    private static let settingsLanguageNames: [String: String] = [
        "en": "English", "de": "Deutsch", "fr": "Français", "it": "Italiano", "es": "Español",
        "pt": "Português (Portugal)", "pt-BR": "Português (Brasil)", "nl": "Nederlands", "pl": "Polski", "cs": "Čeština", "hu": "Magyar",
        "ro": "Română", "sq": "Shqip", "el": "Ελληνικά", "tr": "Türkçe", "sv": "Svenska",
        "da": "Dansk", "nb": "Norsk bokmål", "fi": "Suomi", "ru": "Русский", "uk": "Українська",
        "ar": "العربية", "he": "עברית", "fa": "فارسی", "hi": "हिन्दी", "th": "ไทย",
        "vi": "Tiếng Việt", "id": "Bahasa Indonesia", "zh": "中文（简体）", "ja": "日本語", "ko": "한국어",
    ]

    // MARK: Hilfen

    private var needsNotificationPermission: Bool {
        let wanted = settings.priceNotifications || settings.activityAlerts
            || settings.macroNotifications
            || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0
            || !IndicatorAlertRules.active(settings.indicatorAlerts).isEmpty
            || data.snapshot.alarms.contains(where: \.enabled)
        return wanted && (notificationStatus == .denied || notificationStatus == .notDetermined)
    }

    private func refreshNotificationStatus() async {
        if await Notifier.isAuthorized() {
            notificationStatus = .authorized
        } else {
            notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        }
    }

    private func openURL(_ string: String) {
        guard let url = URL(string: string) else { return }
        UIApplication.shared.open(url)
    }
}
