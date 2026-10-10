import SwiftUI
import UIKit
import UserNotifications

/// Tab «Optionen» — wie `SettingsScreen.kt` (Runde 23f): eine kurze, gruppierte Liste.
/// Graue Gruppenüberschriften, darunter Zeilen mit Titel, aktuellem Wert in Grau und Pfeil;
/// alles Ausführliche steht auf Unterseiten (`Settings…Pages.swift` je Gruppe, Markt-Meldungen, Sprachausgabe).
/// Gruppen: Allgemein · Darstellung · Alarme & Mitteilungen · Portfolio · Daten · Über.
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
    /// Treffer auf der Hauptseite selbst (Sprache, Widgets, Links): Zeile kurz hervorheben.
    @State private var mainHighlight: String?
    /// «App zurücksetzen» — Rückfrage.
    @State private var askReset = false
    @EnvironmentObject private var router: AppRouter

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        ScrollViewReader { proxy in
            list
                // Zeile der Hauptseite aus der Suche: hinscrollen, Ziel nach kurzer Zeit wieder lösen
                .task(id: mainHighlight) {
                    guard let anchor = mainHighlight else { return }
                    try? await Task.sleep(nanoseconds: SettingsHighlightTiming.delayNanos)
                    withAnimation { proxy.scrollTo(anchor, anchor: .center) }
                    try? await Task.sleep(nanoseconds: SettingsHighlightTiming.mainNanos)
                    if mainHighlight == anchor { mainHighlight = nil }
                }
        }
    }

    private var list: some View {
        List {
            if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                generalSection
                appearanceSection
                alertsSection
                portfolioSection
                dataSection
                aboutSection
            } else {
                SettingsSearchResults(items: SettingsSearchCatalog.items(settings: settings), query: query) { anchor in
                    query = ""
                    mainHighlight = anchor
                }
            }
        }
        .environment(\.settingsHighlight, mainHighlight)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("settings_search_hint"))
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .environment(\.defaultMinListRowHeight, 52)
        .navigationTitle(L("settings_title"))
        .navigationBarTitleDisplayMode(.large)
        .animation(.spring(duration: 0.35), value: settings.developerUnlocked)
        // Auch nach der Rückkehr von einer Unterseite (Probe-Alarm) und aus den iOS-Einstellungen
        .onAppear { Task { await refreshNotificationStatus() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshNotificationStatus() } }
        }
    }

    // MARK: Allgemein

    private var generalSection: some View {
        Section {
            // Sprache: stellt iOS in der Einstellungen-App ein
            SettingsButtonRow(
                title: L("settings_section_language"),
                value: Self.currentLanguageName,
                trailingIcon: "arrow.up.forward",
                hint: L("ios_settings_language_hint")
            ) {
                openURL(UIApplication.openSettingsURLString)
            }
            .settingsAnchor("main.language")
            SettingsNavRow(title: L("settings_row_currency"), value: settings.portfolioCurrency) {
                CurrencySettingsPage()
            }
            SettingsNavRow(title: L("settings_row_updates"), value: SettingsSummary.updatesText(settings)) {
                UpdatesSettingsPage()
            }
            SettingsNavRow(title: L("settings_row_watchlist"), value: SettingsSummary.watchlistText(settings)) {
                WatchlistSettingsPage()
            }
        } header: {
            Text(L("settings_group_general"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
    }

    // MARK: Darstellung

    private var appearanceSection: some View {
        let accentName = L(settings.accentColor.labelKey)
        return Section {
            SettingsNavRow(title: L("settings_theme_mode"), value: SettingsSummary.displayModeText(settings)) {
                DisplayModeSettingsPage()
            }
            SettingsNavRow(title: L("settings_accent"), valueDescription: accentName) {
                HStack(spacing: 8) {
                    Circle().fill(settings.accentColor.seedColor).frame(width: 12, height: 12)
                    Text(accentName)
                }
            } destination: {
                ThemeSettingsPage()
            }
            SettingsNavRow(title: L("settings_price_colors"), valueDescription: SettingsSummary.priceColorsText(settings)) {
                PriceArrowsView(scheme: settings.priceColorScheme, inverted: settings.priceColorsInverted)
            } destination: {
                PriceColorsSettingsPage()
            }
            // Basis der %-Änderung (Pille, Puls, Widgets): «Letzte 24 Std.», «Seit 00:00 UTC» …
            SettingsNavRow(title: L("settings_change_basis", "%"), value: A11y.changeSummary(settings.changeBasis)) {
                ChangeBasisSettingsPage()
            }
            // Coin-Logos: je ein Schalter für App und Widgets
            SettingsNavRow(title: L("settings_coin_logos"), value: SettingsSummary.coinLogosText(settings)) {
                CoinLogosSettingsPage()
            }
            SettingsNavRow(title: L("settings_widgets"), value: nil) {
                AddWidgetHelpSheet(portfolioEnabled: settings.portfolioEnabled, asPage: true)
            }
            .settingsAnchor("main.widgets")
        } header: {
            Text(L("settings_section_appearance"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
    }

    // MARK: Alarme & Mitteilungen

    private var alertsSection: some View {
        Section {
            // Hinweis nur, wenn etwas Mitteilungen braucht und die Erlaubnis fehlt
            if needsNotificationPermission {
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
            SettingsNavRow(title: L("settings_row_alarms"), value: L(settings.alarmSignal.ios.labelKey)) {
                AlarmSettingsPage()
            }
            SettingsNavRow(title: L("settings_market_alerts"), value: SettingsSummary.marketAlertsText(settings)) {
                MarketAlertsSettingsPage()
            }
            SettingsNavRow(title: L("settings_tts"), value: SettingsSummary.speechText(settings)) {
                SpeechSettingsPage()
            }
        } header: {
            Text(L("settings_group_alerts"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
    }

    // MARK: Portfolio

    private var portfolioSection: some View {
        Section {
            SettingsNavRow(title: L("portfolio_title"), value: SettingsSummary.portfolioText(settings)) {
                PortfolioSettingsPage()
            }
        } header: {
            Text(L("portfolio_title"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
    }

    // MARK: Daten

    private var dataSection: some View {
        Section {
            SettingsNavRow(title: L("backup_title"), value: nil) {
                BackupSettingsPage()
            }
            // «App zurücksetzen»: alles löschen wie nach der Installation (mit Rückfrage)
            Button(role: .destructive) {
                askReset = true
            } label: {
                HStack {
                    Text(L("settings_reset_app"))
                    Spacer()
                    Image(systemName: "trash")
                        .accessibilityHidden(true)
                }
                .foregroundStyle(AppColors.destructive)
            }
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
        } header: {
            Text(L("settings_group_data_only"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
    }

    // MARK: Über

    private var aboutSection: some View {
        Section {
            SettingsNavRow(title: L("settings_row_about"), value: AboutContent.versionText) {
                AboutSettingsPage()
            }
            // Pflicht für den App Store: Datenschutzerklärung auch in der App erreichbar
            SettingsButtonRow(title: L("about_privacy_policy"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link")) {
                openURL(AppLinks.privacyPolicy)
            }
            .settingsAnchor("main.privacy")
            // Runde 13b: fehlende Börse direkt mit der GitHub-Vorlage wünschen
            SettingsButtonRow(title: L("about_request_exchange"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link")) {
                openURL(AppLinks.exchangeRequest)
            }
            .settingsAnchor("main.exchange")
            // Runde 15: Quellcode öffentlich auf GitHub (MIT)
            SettingsButtonRow(title: L("about_source_code"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link")) {
                openURL(AppLinks.sourceCode)
            }
            .settingsAnchor("main.source")
            // Runde 14: Lizenzhinweise
            SettingsNavRow(title: L("about_licenses"), value: nil) {
                LicensesSheet(asPage: true)
            }
            .settingsAnchor("main.licenses")
            // Entwickler erst nach sieben Tipps auf die Version (Seite «Über die App»)
            if settings.developerUnlocked {
                SettingsNavRow(title: L("settings_section_developer"), value: SettingsSummary.onOff(settings.showHttpLog)) {
                    DeveloperSettingsPage()
                }
            }
        } header: {
            Text(L("settings_section_about"))
                .sectionTitleStyle()
        }
        .listRowBackground(AppColors.container)
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
        let wanted = settings.priceNotifications || settings.zoneAlerts || settings.activityAlerts
            || settings.macroNotifications
            || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0
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
