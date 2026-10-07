import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications

/// Tab «Optionen» — wie `SettingsScreen.kt`, an iOS angepasst:
/// - Sprache: iOS stellt die App-Sprache in der Einstellungen-App ein.
/// - Hintergrund: iOS bestimmt den genauen Zeitpunkt; «Live» gilt, solange die App offen ist.
/// - Dauerhafte Mitteilungen und Akku-Optimierung gibt es auf iOS nicht.
/// - Widgets fügt man über den Startbildschirm hinzu.
///
/// Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
@MainActor
struct SettingsScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.scenePhase) private var scenePhase

    @State private var toast: String?
    @State private var advanced = false
    @State private var versionTaps = 0
    @State private var showLicenses = false
    /// Erhöhen startet den Probe-Alarm (`AlarmTestRunner`).
    @State private var alarmTestTrigger = 0

    /// Erlaubnis für Mitteilungen; `.authorized` als Startwert, damit kein Hinweis aufblitzt.
    @State private var notificationStatus: UNAuthorizationStatus = .authorized

    // Eigene Melde-Schwelle
    @State private var editingPercent = false
    @State private var percentText = ""

    // Sichern & Wiederherstellen
    @State private var exportDocument: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pendingRestore: Data?
    @State private var confirmRestore = false

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Gruppen wie Android (Runde 9): 1 Darstellung · 2 Währung & Umrechnung ·
                // 3 Alarme & Benachrichtigungen · 4 Daten & Aktualisierung · 5 Portfolio ·
                // 6 Sicherheit & Backup · 7 Erweitert (eingeklappt) · 8 Über. Akku gibt es auf iOS nicht.
                appearanceSection
                currencySection
                alertsSection
                updatesSection
                portfolioSection
                securitySection
                advancedSection
                aboutSection
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
        }
        .scrollDismissesKeyboard(.interactively)
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("settings_title"))
        .toast($toast)
        .alarmTestRunner(trigger: alarmTestTrigger)
        .task { await refreshNotificationStatus() }
        .onChange(of: scenePhase) { _, phase in
            // Nach der Rückkehr aus den iOS-Einstellungen neu prüfen
            if phase == .active { Task { await refreshNotificationStatus() } }
        }
        .animation(.spring(duration: 0.35), value: settings.developerUnlocked)
    }

    // MARK: Erscheinungsbild

    private var appearanceSection: some View {
        SettingsGroup(L("settings_section_appearance"), icon: "paintpalette.fill") {
            languageRow
            RowDivider()
            SettingsThemePicker(selection: settings.darkMode) { data.settings.darkMode = $0 }
            RowDivider()
            SettingsAccentPicker(selection: settings.accentColor) { data.settings.accentColor = $0 }
            SettingsHint(text: L("ios_settings_accent_hint"), top: 2)
            RowDivider()
            // Kursfarben: Blau/Orange für Rot-Grün-Sehschwäche
            SettingsPriceColorPicker(selection: settings.priceColorScheme) { data.settings.priceColorScheme = $0 }
            SettingsHint(text: L("settings_price_colors_hint"), top: 2)
            RowDivider()
            // Farben tauschen: Rot steigend, Grün fallend (Standard nach Region)
            SwitchRow(
                title: L("settings_price_colors_inverted"),
                subtitle: L("settings_price_colors_inverted_hint"),
                isOn: $data.settings.priceColorsInverted
            )
            RowDivider()
            // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte
            SwitchRow(
                title: L("settings_high_contrast"),
                subtitle: L("settings_high_contrast_hint"),
                isOn: $data.settings.highContrast
            )
            RowDivider()
            // Mini-Chart (24 h) in den Zeilen der Merkliste
            SwitchRow(
                title: L("settings_watchlist_sparkline"),
                subtitle: L("settings_watchlist_sparkline_hint"),
                isOn: $data.settings.watchlistSparkline
            )
            RowDivider()
            // Widgets: iOS lässt Apps keine Widgets anlegen — drei Schritte (Runde 13b)
            WidgetsSettingsRow(portfolioEnabled: settings.portfolioEnabled)
        }
        .padding(.bottom, 20)
    }

    // MARK: Währung & Umrechnung

    /// Die Währung ist die Einheit der App, kein Aussehen — eigene Gruppe.
    private var currencySection: some View {
        SettingsGroup(L("settings_group_currency"), icon: "dollarsign.circle.fill") {
            // Währung zuerst: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget
            ConversionCurrencyRow(selection: settings.portfolioCurrency) { data.settings.portfolioCurrency = $0 }
            Text(L("settings_conversion_currency_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 8)
            RowDivider()
            // «≈ Umrechnung»: Kurse der Merkliste zusätzlich in der Umrechnungswährung
            SwitchRow(
                title: L("settings_show_converted"),
                subtitle: L("settings_show_converted_hint", settings.portfolioCurrency),
                isOn: $data.settings.showConverted
            )
        }
        .padding(.bottom, 20)
    }

    // MARK: Sprache

    /// Erste Zeile der Darstellung: nennt «Sprache» selbst, die aktuelle rechts.
    private var languageRow: some View {
        SettingsLinkRow(
            icon: "character.bubble",
            title: L("settings_section_language"),
            subtitle: L("ios_settings_language_hint"),
            value: Self.currentLanguageName
        ) {
            openURL(UIApplication.openSettingsURLString)
        }
    }

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

    // MARK: Aktualisierung

    private var updatesSection: some View {
        SettingsGroup(L("settings_group_data"), icon: "arrow.triangle.2.circlepath") {
            SwitchRow(
                title: L("settings_background_updates"),
                subtitle: L("settings_background_updates_hint"),
                isOn: $data.settings.backgroundUpdates
            )
            if settings.backgroundUpdates {
                ChoiceRow(
                    title: L("settings_background_interval"),
                    options: AppSettings.backgroundIntervalChoices,
                    selection: settings.backgroundIntervalMinutes,
                    label: { L("settings_minutes", $0) },
                    onSelect: { data.settings.backgroundIntervalMinutes = $0 }
                )
                // iOS bestimmt den Zeitpunkt im Hintergrund; bei offener App pünktlich
                SettingsHint(text: L("settings_ios_background_hint"))
            }
        }
        .padding(.bottom, 20)
    }

    // MARK: Erweitert

    /// Eingeklappt (Zustand nicht gespeichert): Live-Modus, Laufzeit-Futures und —
    /// sofern freigeschaltet — die Entwickleroptionen.
    private var advancedSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionCard(nil) {
                Button {
                    withAnimation(.spring(duration: 0.3)) { advanced.toggle() }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.body.weight(.medium))
                            .foregroundStyle(accent.primary)
                            .frame(width: 26)
                        Text(L("settings_section_advanced"))
                            .font(.body)
                            .foregroundStyle(AppColors.onSurface)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .rotationEffect(.degrees(advanced ? 90 : 0))
                    }
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(.isHeader)
            }

            if advanced {
                SectionCard(nil) {
                    SwitchRow(
                        title: L("settings_live_service"),
                        subtitle: L("ios_settings_live_service_hint"),
                        isOn: $data.settings.liveService
                    )
                    if settings.liveService {
                        ChoiceRow(
                            title: L("settings_live_interval"),
                            options: AppSettings.liveIntervalChoices,
                            selection: settings.liveIntervalSeconds,
                            // Kurz: «15 s», «5 min» statt «300 Sekunden»
                            label: { $0 >= 60 && $0 % 60 == 0 ? L("settings_minutes", $0 / 60) : L("settings_seconds", $0) },
                            onSelect: { data.settings.liveIntervalSeconds = $0 }
                        )
                    }
                    RowDivider()
                    SwitchRow(
                        title: L("settings_rolling_futures"),
                        subtitle: L("settings_rolling_futures_hint"),
                        isOn: $data.settings.includeRollingFutures
                    )
                }
                .transition(.move(edge: .top).combined(with: .opacity))

                if settings.developerUnlocked {
                    developerSection
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: Mitteilungen

    /// Gruppe 3: Alarme in einer Karte; Markt-Meldungen und Sprachausgabe (Runde 13b)
    /// auf eigenen Unterseiten, hier je eine Zeile mit Kurzwert («2 aktiv», «Aus»).
    private var alertsSection: some View {
        SettingsSection(L("settings_group_alerts"), icon: "bell.badge.fill") {
            SettingsCard { alarmRows }
            SettingsCard {
                SettingsSubPageLink(
                    icon: "chart.line.uptrend.xyaxis",
                    title: L("settings_market_alerts"),
                    subtitle: L("settings_market_alerts_hint"),
                    value: SettingsSummary.marketAlertsText(settings)
                ) {
                    MarketAlertsSettingsPage()
                }
                RowDivider()
                SettingsSubPageLink(
                    icon: "speaker.wave.2.fill",
                    title: L("settings_tts"),
                    subtitle: L("settings_tts_row_hint"),
                    value: SettingsSummary.speechText(settings)
                ) {
                    SpeechSettingsPage()
                }
            }
        }
        .padding(.bottom, 20)
        .alert(L("settings_notification_change"), isPresented: $editingPercent) {
            TextField("0", text: $percentText)
                .keyboardType(.decimalPad)
            Button(L("action_save")) {
                if let v = SettingsPercentOption.parse(percentText) {
                    data.settings.notificationChangePercent = v
                }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text("% · 0 = " + L("settings_notification_change_always"))
        }
    }

    /// Kurs in der Mitteilung, Ruhezeit zwischen Alarmen, Ton, Nachtruhe, Probe-Alarm.
    @ViewBuilder
    private var alarmRows: some View {
        if needsNotificationPermission {
            notificationPermissionRow
            RowDivider()
        }

        SwitchRow(
            title: L("settings_price_notifications"),
            subtitle: L("ios_settings_price_notifications_hint"),
            isOn: Binding(
                get: { data.settings.priceNotifications },
                set: { on in
                    data.settings.priceNotifications = on
                    if on { requestNotifications() }
                }
            )
        )
        if settings.priceNotifications {
            ChoiceRow(
                title: L("settings_notification_change"),
                options: AppSettings.notificationChangeChoices.map { SettingsPercentOption.value($0) } + [.custom],
                selection: percentSelection,
                label: percentLabel,
                onSelect: { option in
                    switch option {
                    case .value(let v): data.settings.notificationChangePercent = v
                    case .custom:
                        percentText = isCustomPercent ? SettingsPercentOption.format(settings.notificationChangePercent) : ""
                        editingPercent = true
                    }
                }
            )
            SettingsHint(text: L("settings_notification_change_hint"))
        }

        RowDivider()
        ChoiceRow(
            title: L("settings_alarm_cooldown"),
            options: AppSettings.alarmCooldownChoices,
            selection: settings.alarmCooldownMinutes,
            label: { L("settings_minutes", $0) },
            onSelect: { data.settings.alarmCooldownMinutes = $0 }
        )
        RowDivider()
        AlarmSoundRow(
            selection: settings.alarmSound,
            onSelect: { data.settings.alarmSound = $0 }
        )
        RowDivider()
        // Nachtruhe: Alarme lautlos, ohne Ansage
        SwitchRow(
            title: L("settings_quiet_hours"),
            subtitle: L("settings_quiet_hours_hint",
                        QuietHours.format(settings.quietHoursStart), QuietHours.format(settings.quietHoursEnd)),
            isOn: $data.settings.quietHoursEnabled
        )
        if settings.quietHoursEnabled {
            quietHoursPicker(L("settings_quiet_hours_from"), minute: settings.quietHoursStart) {
                data.settings.quietHoursStart = $0
            }
            quietHoursPicker(L("settings_quiet_hours_to"), minute: settings.quietHoursEnd) {
                data.settings.quietHoursEnd = $0
            }
            .padding(.bottom, 6)
        }
        RowDivider()
        // Probe-Alarm: gleicher Weg wie ein echter Alarm, aber ohne Nachtruhe
        Button {
            alarmTestTrigger += 1
            Task { await refreshNotificationStatus() }
        } label: {
            Label(L("alarm_test"), systemImage: "bell.and.waves.left.and.right.fill")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
        }
        .buttonStyle(TonalButtonStyle())
        .padding(.top, 12)
        SettingsHint(text: L("alarm_test_hint"), top: 6)
    }

    /// Uhrzeit der Nachtruhe (Minuten seit Mitternacht) im Format der Region.
    private func quietHoursPicker(_ title: String, minute: Int, onChange: @escaping (Int) -> Void) -> some View {
        DatePicker(
            title,
            selection: Binding(
                get: { QuietHours.date(for: minute) },
                set: { onChange(QuietHours.minuteOfDay($0)) }
            ),
            displayedComponents: .hourAndMinute
        )
        .font(.body)
        .tint(accent.primary)
        .padding(.vertical, 6)
    }

    private var isCustomPercent: Bool {
        !AppSettings.notificationChangeChoices.contains(settings.notificationChangePercent)
    }

    private var percentSelection: SettingsPercentOption {
        isCustomPercent ? .custom : .value(settings.notificationChangePercent)
    }

    private func percentLabel(_ option: SettingsPercentOption) -> String {
        switch option {
        case .value(let v):
            return SettingsPercentOption.format(v) + "%"
        case .custom:
            return isCustomPercent
                ? SettingsPercentOption.format(settings.notificationChangePercent) + "%"
                : L("settings_custom_value")
        }
    }

    /// Hinweis nur, wenn etwas Mitteilungen braucht und die Erlaubnis fehlt.
    private var needsNotificationPermission: Bool {
        let wanted = settings.priceNotifications || settings.zoneAlerts || settings.activityAlerts
            || settings.macroNotifications
            || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0
            || data.snapshot.alarms.contains(where: \.enabled)
        return wanted && (notificationStatus == .denied || notificationStatus == .notDetermined)
    }

    private var notificationPermissionRow: some View {
        let denied = notificationStatus == .denied
        return HStack(spacing: 12) {
            Image(systemName: denied ? "bell.slash.fill" : "bell.fill")
                .font(.body.weight(.semibold))
                .foregroundStyle(denied ? AppColors.error : accent.primary)
                .frame(width: 36, height: 36)
                .background((denied ? AppColors.error : accent.primary).opacity(0.14), in: Circle())
            Text(L(denied ? "ios_notifications_denied" : "ios_allow_notifications"))
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if denied {
                    openURL(UIApplication.openNotificationSettingsURLString)
                } else {
                    requestNotifications()
                }
            } label: {
                Text(L(denied ? "ios_open_settings" : "ios_allow_notifications"))
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
            .buttonStyle(TonalButtonStyle())
        }
        .padding(.vertical, 12)
    }

    // MARK: Portfolio

    /// Optionaler Bereich: Portfolio als eigener Tab (wie Android).
    /// Die Daten bleiben beim Ausschalten erhalten.
    private var portfolioSection: some View {
        SettingsGroup(L("portfolio_title"), icon: "chart.pie.fill") {
            SwitchRow(
                title: L("settings_portfolio_tab"),
                subtitle: L("portfolio_setting_hint"),
                isOn: $data.settings.portfolioEnabled
            )
        }
        .padding(.bottom, 20)
    }

    // MARK: Sicherheit & Backup

    /// App-Sperre, darunter Sichern & Wiederherstellen — eine Karte wie Android.
    private var securitySection: some View {
        SettingsGroup(L("settings_group_security"), icon: "lock.fill") {
            SwitchRow(
                title: L("settings_app_lock"),
                subtitle: L("settings_app_lock_hint"),
                isOn: Binding(
                    get: { data.settings.appLock },
                    set: { on in setAppLock(on) }
                )
            )
            RowDivider()
            // Sichern & Wiederherstellen: eine Datei, die man selbst ablegt
            Text(L("backup_title"))
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .padding(.top, 12)
            SettingsHint(text: L("backup_hint"), top: 2)
            HStack(spacing: 10) {
                Button(action: startExport) {
                    Label(L("backup_export"), systemImage: "square.and.arrow.up")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .fileExporter(
                    isPresented: $exporting,
                    document: exportDocument,
                    contentType: .json,
                    defaultFilename: BackupManager.defaultFilename()
                ) { result in
                    exportDocument = nil
                    switch result {
                    case .success:
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        toast = L("backup_exported")
                    case .failure(let error):
                        if !Self.isCancel(error) { toast = L("backup_failed") }
                    }
                }

                Button { importing = true } label: {
                    Label(L("backup_import"), systemImage: "square.and.arrow.down")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(TonalButtonStyle())
                .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
                    switch result {
                    case .success(let url):
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        if let json = try? Data(contentsOf: url) {
                            pendingRestore = json
                            confirmRestore = true
                        } else {
                            toast = L("backup_failed")
                        }
                    case .failure(let error):
                        if !Self.isCancel(error) { toast = L("backup_failed") }
                    }
                }
            }
            .padding(.bottom, 12)
        }
        .padding(.bottom, 20)
        .alert(L("backup_import"), isPresented: $confirmRestore, presenting: pendingRestore) { json in
            Button(L("backup_import"), role: .destructive) { restore(json) }
            Button(L("action_cancel"), role: .cancel) { pendingRestore = nil }
        } message: { _ in
            Text(L("backup_restore_confirm"))
        }
    }

    /// Einschalten erst nach einmaligem Entsperren; ohne Displaysperre bleibt es aus.
    private func setAppLock(_ on: Bool) {
        guard on else {
            data.settings.appLock = false
            return
        }
        guard AppLock.canAuthenticate() else {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("app_lock_unavailable")
            return
        }
        Task {
            let ok = await AppLock.authenticate()
            if ok { data.settings.appLock = true }
        }
    }

    private func startExport() {
        do {
            exportDocument = BackupDocument(data: try BackupManager.exportData(data))
            exporting = true
        } catch {
            toast = L("backup_failed")
        }
    }

    private func restore(_ json: Data) {
        pendingRestore = nil
        do {
            let result = try BackupManager.restore(json, into: data)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("backup_restored", L("backup_restored_pairs", count: result.watches),
                      L("backup_restored_alarms", count: result.alarms))
            Task { await refreshNotificationStatus() }
        } catch {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("backup_failed")
        }
    }

    private static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }

    // MARK: Entwickler

    private var developerSection: some View {
        SettingsGroup(L("settings_section_developer"), icon: "hammer.fill") {
            SwitchRow(
                title: L("settings_http_log"),
                subtitle: L("settings_http_log_hint"),
                isOn: $data.settings.showHttpLog
            )
            RowDivider()
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(L("watchlist_refresh_report")).font(.body)
                    Spacer()
                    if data.lastRefreshMillis > 0 {
                        Text(PriceFormat.duration(data.lastRefreshMillis))
                            .font(.footnote.weight(.semibold).monospacedDigit())
                            .foregroundStyle(accent.onContainer)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(accent.container, in: Capsule())
                    }
                }
                Text(data.lastRefreshReport.isEmpty ? L("watchlist_refresh_report_empty") : data.lastRefreshReport)
                    .font(data.lastRefreshReport.isEmpty ? .footnote : .caption.monospaced())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 12)
        }
        .padding(.bottom, 20)
    }

    // MARK: Über

    private var aboutSection: some View {
        SettingsGroup(L("settings_section_about"), icon: "info.circle.fill") {
            AboutContent(onVersionTap: versionTapped)
            .padding(.vertical, 6)
            RowDivider()
            // Pflicht für den App Store: Datenschutzerklärung auch in der App erreichbar
            SettingsLinkRow(icon: "hand.raised.fill", title: L("about_privacy_policy")) {
                openURL(AppLinks.privacyPolicy)
            }
            RowDivider()
            // Runde 13b: fehlende Börse direkt mit der GitHub-Vorlage wünschen
            SettingsLinkRow(icon: "building.columns", title: L("about_request_exchange")) {
                openURL(AppLinks.exchangeRequest)
            }
            RowDivider()
            // Runde 15: Quellcode öffentlich auf GitHub (MIT)
            SettingsLinkRow(icon: "chevron.left.forwardslash.chevron.right", title: L("about_source_code")) {
                openURL(AppLinks.sourceCode)
            }
            RowDivider()
            // Runde 14: Lizenzhinweise, bewusst unauffällig ganz unten
            Button { showLicenses = true } label: {
                HStack(spacing: 6) {
                    Text(L("about_licenses"))
                        .font(.footnote)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .accessibilityHidden(true)
                }
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .sheet(isPresented: $showLicenses) {
            LicensesSheet()
        }
    }

    /// Sieben Tipps auf die Version schalten die Entwickleroptionen frei.
    private func versionTapped() {
        guard !settings.developerUnlocked else { return }
        versionTaps += 1
        if versionTaps >= 4 && versionTaps < 7 {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        if versionTaps == 7 {
            data.settings.developerUnlocked = true
            // Entwickleroptionen stehen unter «Erweitert»: gleich aufklappen
            withAnimation(.spring(duration: 0.35)) { advanced = true }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("developer_unlocked")
        }
    }

    // MARK: Hilfen

    private func requestNotifications() {
        Task {
            _ = await Notifier.requestPermission()
            await refreshNotificationStatus()
        }
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

/// Umrechnungswährung als Menü — gilt für die umgerechneten Kurse der Merkliste,
/// Alarme in eigener Währung und das Portfolio (wie `ConversionCurrencyRow` in Android).
@MainActor
struct ConversionCurrencyRow: View {
    let selection: String
    let onSelect: (String) -> Void

    @Environment(\.appAccent) private var accent

    /// Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen.
    private var codes: [String] {
        FxRateSource.currencies.contains(selection) ? FxRateSource.currencies : FxRateSource.currencies + [selection]
    }

    var body: some View {
        Menu {
            Picker(L("settings_conversion_currency"), selection: Binding(
                get: { selection },
                set: { code in if code != selection { onSelect(code) } }
            )) {
                ForEach(codes, id: \.self) { code in
                    Text(code).tag(code)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "dollarsign.arrow.circlepath")
                    .font(.body.weight(.medium))
                    .foregroundStyle(accent.primary)
                    .frame(width: 26)
                Text(L("settings_conversion_currency"))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                Text(selection)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("settings_conversion_currency"))
        .accessibilityValue(selection)
    }
}
