import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Runde 23f: Unterseiten hinter den Zeilen der Hauptseite — wie `SettingsPages.kt`.
// Inhalt und Einstellungen wie zuvor auf der langen Hauptseite, nur neu verteilt.

// MARK: - Allgemein

/// Währung: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget.
@MainActor
struct CurrencySettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_row_currency")) {
            ConversionCurrencyRow(selection: data.settings.portfolioCurrency) { data.settings.portfolioCurrency = $0 }
                .settingsAnchor("currency.conversion")
            SettingsHint(text: L("settings_conversion_currency_hint"))
        }
    }
}

/// Aktualisierung: Hintergrund und Intervall, Live-Modus.
@MainActor
struct UpdatesSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_updates")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_background_updates"),
                    subtitle: L("settings_background_updates_hint"),
                    isOn: $data.settings.backgroundUpdates
                )
                .settingsAnchor("updates.background")
                if settings.backgroundUpdates {
                    ChoiceRow(
                        title: L("settings_background_interval"),
                        options: AppSettings.backgroundIntervalChoices,
                        selection: settings.backgroundIntervalMinutes,
                        label: { L("settings_minutes", $0) },
                        onSelect: { data.settings.backgroundIntervalMinutes = $0 }
                    )
                    .settingsAnchor("updates.background_interval")
                    // iOS bestimmt den Zeitpunkt im Hintergrund; bei offener App pünktlich
                    SettingsHint(text: L("settings_ios_background_hint"))
                }
            }
            SettingsCard {
                SwitchRow(
                    title: L("settings_live_service"),
                    subtitle: L("ios_settings_live_service_hint"),
                    isOn: $data.settings.liveService
                )
                .settingsAnchor("updates.live")
                if settings.liveService {
                    ChoiceRow(
                        title: L("settings_live_interval"),
                        options: AppSettings.liveIntervalChoices,
                        selection: settings.liveIntervalSeconds,
                        label: { SettingsSummary.liveIntervalText($0) },
                        onSelect: { data.settings.liveIntervalSeconds = $0 }
                    )
                    .settingsAnchor("updates.live_interval")
                }
            }
            // Live-Kurse per WebSocket, nur bei offener Merkliste (`LivePriceStream`)
            SettingsCard {
                SwitchRow(
                    title: L("settings_live_websocket"),
                    subtitle: L("settings_live_websocket_hint"),
                    isOn: $data.settings.liveWebSocket
                )
                .settingsAnchor("updates.live_websocket")
            }
        }
    }
}

/// Merkliste: Mini-Chart und «≈ Umrechnung» in den Zeilen.
@MainActor
struct WatchlistSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_watchlist")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_watchlist_sparkline"),
                    subtitle: L("settings_watchlist_sparkline_hint"),
                    isOn: $data.settings.watchlistSparkline
                )
                .settingsAnchor("watchlist.sparkline")
                RowDivider()
                SwitchRow(
                    title: L("settings_show_converted"),
                    subtitle: L("settings_show_converted_hint", data.settings.portfolioCurrency),
                    isOn: $data.settings.showConverted
                )
                .settingsAnchor("watchlist.converted")
            }
            // Futures: welche Kontrakte in der Auswahl erscheinen und wie die Merkliste sie prüft
            Text(L("settings_row_dated_futures"))
                .font(.footnote.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
            SettingsCard {
                SwitchRow(
                    title: L("settings_rolling_futures"),
                    subtitle: L("settings_rolling_futures_hint"),
                    isOn: $data.settings.includeRollingFutures
                )
                .settingsAnchor("futures.rolling")
                RowDivider()
                SwitchRow(
                    title: L("settings_tradfi_futures"),
                    subtitle: L("settings_tradfi_futures_hint"),
                    isOn: $data.settings.includeTradFiFutures
                )
                .settingsAnchor("futures.tradfi")
            }
        }
    }
}

// MARK: - Darstellung

/// Modus: wie das System, Hell oder Dunkel (App und Widgets); dazu hoher Kontrast.
@MainActor
struct DisplayModeSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_theme_mode")) {
            SettingsCard {
                SettingsThemePicker(selection: data.settings.darkMode) { data.settings.darkMode = $0 }
            }
            SettingsCard {
                // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte
                SwitchRow(
                    title: L("settings_high_contrast"),
                    subtitle: L("settings_high_contrast_hint"),
                    isOn: $data.settings.highContrast
                )
                .settingsAnchor("display.contrast")
            }
        }
    }
}

/// Theme: Akzentfarbe der App und der Widgets.
@MainActor
struct ThemeSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_accent")) {
            SettingsAccentPicker(selection: data.settings.accentColor) { data.settings.accentColor = $0 }
            SettingsHint(text: L("ios_settings_accent_hint"), top: 2)
        }
    }
}

/// Kursfarben: Grün steigt / Rot fällt (Standard), Rot steigt / Grün fällt (Ostasien) und die
/// Fassungen für Farbsehschwäche (Blau/Orange). Gespeichert als Schema + «getauscht».
@MainActor
struct PriceColorsSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    private var current: PriceColorChoice {
        PriceColorChoice.of(scheme: data.settings.priceColorScheme, inverted: data.settings.priceColorsInverted)
    }

    var body: some View {
        SettingsCardsPage(title: L("settings_price_colors")) {
            SettingsCard {
                ForEach(Array(PriceColorChoice.allCases.enumerated()), id: \.offset) { index, choice in
                    if index > 0 { RowDivider() }
                    row(choice)
                }
            }
            SettingsHint(text: L("settings_price_colors_hint"), top: 2)
                .padding(.horizontal, 16)
        }
        .sensoryFeedback(.selection, trigger: current)
    }

    private func row(_ choice: PriceColorChoice) -> some View {
        let selected = choice == current
        return Button {
            if data.settings.priceColorScheme != choice.scheme { data.settings.priceColorScheme = choice.scheme }
            if data.settings.priceColorsInverted != choice.inverted { data.settings.priceColorsInverted = choice.inverted }
        } label: {
            HStack(spacing: 12) {
                PriceArrowsView(choice: choice)
                    .frame(minWidth: 30, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L(choice.labelKey))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    if choice == .RED_UP {
                        Text(L("price_colors_red_up_hint"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// «Basis der %-Änderung» wie Binance «Change(%) & Chart Timezone»: oben der nummerierte Hinweis,
/// darunter «Letzte 24 Std.» (Standard), die Zone des Geräts («UTC+2, 00:00 (Zeitzone des
/// Geräts)», folgt der Sommerzeit) und feste Zonen UTC+14 … UTC−12 — für Pille, Puls,
/// Aktionsblatt, Widgets und Live-Aktivität; Alarme rechnen unabhängig davon.
@MainActor
struct ChangeBasisSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    /// Zone des Geräts einmal beim Öffnen (Sommerzeit wechselt nicht, während die Seite offen ist).
    @State private var now = TimeUtils.nowMillis

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_change_basis", "%")) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                numbered(1, L("settings_change_basis_hint_1"))
                numbered(2, L("settings_change_basis_hint_2", L("change_basis_rolling")))
                numbered(3, L("settings_change_basis_hint_3"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, Spacing.sm)
            SettingsCard {
                ForEach(Array(ChangeBasis.allCases.enumerated()), id: \.offset) { index, basis in
                    if index > 0 { RowDivider() }
                    row(basis)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: data.settings.changeBasis)
    }

    /// Ein Punkt des Hinweises, Folgezeilen eingerückt («1. …»).
    private func numbered(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(LocaleNumbers.integer(number) + ".")
                .frame(minWidth: 14, alignment: .leading)
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.footnote)
        .foregroundStyle(AppColors.onSurfaceVariant)
        .accessibilityElement(children: .combine)
    }

    private func row(_ basis: ChangeBasis) -> some View {
        let selected = basis == data.settings.changeBasis
        return Button {
            if !selected { data.settings.changeBasis = basis }
        } label: {
            HStack(spacing: 12) {
                Text(A11y.changeChoiceLabel(basis, now: now))
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent.primary)
                }
            }
            .frame(minHeight: 44)
            .padding(.vertical, Spacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - Alarme

/// Alarme: Kurs-Mitteilungen, Ruhezeit, Ton, Alarm-Signal, Nachtruhe, Probe-Alarm.
@MainActor
struct AlarmSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    /// Erhöhen startet den Probe-Alarm (`AlarmTestRunner`).
    @State private var alarmTestTrigger = 0
    // Eigene Melde-Schwelle
    @State private var editingPercent = false
    @State private var percentText = ""

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsSubPage(title: L("settings_row_alarms")) {
            SwitchRow(
                title: L("settings_price_notifications"),
                subtitle: L("ios_settings_price_notifications_hint"),
                isOn: Binding(
                    get: { data.settings.priceNotifications },
                    set: { on in
                        data.settings.priceNotifications = on
                        if on { Task { _ = await Notifier.requestPermission() } }
                    }
                )
            )
            .settingsAnchor("alarms.price")
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
                .settingsAnchor("alarms.change")
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
            .settingsAnchor("alarms.cooldown")
            RowDivider()
            AlarmSoundRow(
                selection: settings.alarmSound,
                onSelect: { data.settings.alarmSound = $0 }
            )
            .settingsAnchor("alarms.sound")
            RowDivider()
            // Alarm-Signal: iOS kann nur Ton oder lautlos — Vibration nach Töne & Haptik
            ChoiceRow(
                title: L("settings_alarm_signal"),
                options: AlarmSignal.iosChoices,
                selection: settings.alarmSignal.ios,
                label: { L($0.labelKey) },
                onSelect: { data.settings.alarmSignal = $0 }
            )
            .settingsAnchor("alarms.signal")
            SettingsHint(text: L("ios_settings_alarm_signal_footer"))
            RowDivider()
            // Nachtruhe: Alarme lautlos, ohne Ansage
            SwitchRow(
                title: L("settings_quiet_hours"),
                subtitle: L("settings_quiet_hours_hint",
                            QuietHours.format(settings.quietHoursStart), QuietHours.format(settings.quietHoursEnd)),
                isOn: $data.settings.quietHoursEnabled
            )
            .settingsAnchor("alarms.quiet")
            if settings.quietHoursEnabled {
                quietHoursPicker(L("settings_quiet_hours_from"), minute: settings.quietHoursStart) {
                    data.settings.quietHoursStart = $0
                }
                quietHoursPicker(L("settings_quiet_hours_to"), minute: settings.quietHoursEnd) {
                    data.settings.quietHoursEnd = $0
                }
                .padding(.bottom, Spacing.xs)
            }
            RowDivider()
            // Probe-Alarm: gleicher Weg wie ein echter Alarm, aber ohne Nachtruhe
            Button {
                alarmTestTrigger += 1
            } label: {
                Label(L("alarm_test"), systemImage: "bell.and.waves.left.and.right.fill")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, Spacing.md)
            }
            .buttonStyle(TonalButtonStyle())
            .settingsAnchor("alarms.test")
            .padding(.top, 12)
            SettingsHint(text: L("alarm_test_hint"), top: 6)
        }
        .alarmTestRunner(trigger: alarmTestTrigger)
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
        .padding(.vertical, Spacing.sm)
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
}

/// Hinweis in der Liste, wenn Mitteilungen gebraucht werden, die Erlaubnis aber fehlt.
@MainActor
struct NotificationPermissionRow: View {
    let denied: Bool
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: denied ? "bell.slash.fill" : "bell.fill")
                .font(.body.weight(.semibold))
                .foregroundStyle(denied ? AppColors.error : accent.primary)
                .frame(width: 36, height: 36)
                .background((denied ? AppColors.error : accent.primary).opacity(0.14), in: Circle())
                .accessibilityHidden(true)
            Text(L(denied ? "ios_notifications_denied" : "ios_allow_notifications"))
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: action) {
                Text(L(denied ? "ios_open_settings" : "ios_allow_notifications"))
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
            .buttonStyle(TonalButtonStyle())
        }
        .padding(.vertical, Spacing.sm)
    }
}

// MARK: - Portfolio

/// Portfolio als eigener Tab und die Portfolio-Sperre (nur mit eingeschaltetem Portfolio).
@MainActor
struct PortfolioSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var toast: String?

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("portfolio_title")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_portfolio_tab"),
                    subtitle: L("portfolio_setting_hint"),
                    isOn: $data.settings.portfolioEnabled
                )
                .settingsAnchor("portfolio.tab")
                // Ausgeblendet ohne Portfolio; der Wert bleibt erhalten (Widget und Sicherung bleiben geschützt)
                if PortfolioLockPolicy.showSetting(portfolioEnabled: data.settings.portfolioEnabled) {
                    RowDivider()
                    SwitchRow(
                        title: L("settings_portfolio_lock"),
                        subtitle: L("settings_portfolio_lock_hint"),
                        isOn: Binding(
                            get: { data.settings.appLock },
                            set: { on in setAppLock(on) }
                        )
                    )
                    .settingsAnchor("portfolio.lock")
                    RowDivider()
                    // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                    SwitchRow(
                        title: L("portfolio_hide_amounts"),
                        subtitle: L("portfolio_hide_amounts_hint"),
                        isOn: $data.settings.hidePortfolioAmounts
                    )
                    .settingsAnchor("portfolio.hide")
                }
            }
            // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
            if PortfolioLockPolicy.showSetting(portfolioEnabled: data.settings.portfolioEnabled) {
                SettingsHint(text: L("settings_portfolio_lock_footer"), top: 2)
                    .padding(.horizontal, 16)
            }
        }
        .toast($toast)
    }

    /// Einschalten erst nach einmaligem Entsperren; ohne Displaysperre bleibt es aus.
    /// Ausschalten verlangt Entsperren, solange das Portfolio gesperrt ist (sonst wäre die
    /// Sperre hier zu umgehen).
    private func setAppLock(_ on: Bool) {
        guard on else {
            Task {
                let open = await AppLock.shared.requireUnlock(PortfolioLockPolicy.disableNeedsUnlock(locked:))
                if open { data.settings.appLock = false }
            }
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
}

// MARK: - Daten

/// Sichern & Wiederherstellen: eine Datei, die man selbst ablegt.
@MainActor
struct BackupSettingsPage: View {
    @EnvironmentObject private var data: AppData

    @State private var toast: String?
    @State private var exportDocument: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pendingRestore: Data?
    @State private var confirmRestore = false
    /// Blatt «Sichern» offen; Vorwahl «Mit Passwort schützen» (true mit Portfolio-Daten).
    @State private var exportOptions: ExportOptions?
    /// Im Blatt gewählter Schutz; die Dateiablage öffnet erst nach dem Schliessen des Blatts.
    @State private var exportChoice: ExportChoice?
    /// Verschlüsselte Sicherung gewählt: Datei bis zur Passwort-Eingabe.
    @State private var encryptedFile: EncryptedFile?

    private struct ExportOptions: Identifiable {
        let id = UUID()
        let defaultProtect: Bool
    }

    private struct ExportChoice {
        let password: String?
    }

    private struct EncryptedFile: Identifiable {
        let id = UUID()
        let data: Data
    }

    init() {}

    var body: some View {
        SettingsSubPage(title: L("backup_title")) {
            SettingsHint(text: L("backup_hint"), top: 10)
            HStack(spacing: Spacing.sm) {
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

                Button(action: startImport) {
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
                        if let file = try? Data(contentsOf: url) {
                            picked(file)
                        } else {
                            toast = L("backup_failed")
                        }
                    case .failure(let error):
                        if !Self.isCancel(error) { toast = L("backup_failed") }
                    }
                }
            }
            .settingsAnchor("backup.actions")
            .padding(.bottom, 12)
        }
        .toast($toast)
        .sheet(item: $exportOptions, onDismiss: {
            // Gleicher Weg wie bisher (Dateiablage des Systems), nur mit gewähltem Schutz
            guard let choice = exportChoice else { return }
            exportChoice = nil
            export(password: choice.password)
        }) { options in
            BackupExportSheet(defaultProtect: options.defaultProtect) { password in
                exportChoice = ExportChoice(password: password)
            }
        }
        .sheet(item: $encryptedFile, onDismiss: {
            // Passwort richtig: Rückfrage «Ersetzen?» erst nach dem Schliessen des Blatts
            if pendingRestore != nil { confirmRestore = true }
        }) { file in
            BackupImportPasswordSheet { password in
                do {
                    pendingRestore = try BackupManager.plainJSON(file.data, password: password)
                    return true
                } catch BackupCrypto.Failure.wrongPassword {
                    return false
                } catch {
                    // Unbekannte Version oder kaputte Datei: kein erneuter Versuch
                    encryptedFile = nil
                    toast = L("backup_failed")
                    return false
                }
            }
        }
        .alert(L("backup_import"), isPresented: $confirmRestore, presenting: pendingRestore) { json in
            Button(L("backup_import"), role: .destructive) { restore(json) }
            Button(L("action_cancel"), role: .cancel) { pendingRestore = nil }
        } message: { _ in
            Text(L("backup_restore_confirm"))
        }
        #if DEBUG
        // Ohne Test-Ziel: Prüfwert aus BackupCryptoTest.kt einmal nachrechnen (gleiche Bytes wie Android)
        .task {
            let ok = await Task.detached(priority: .utility) { BackupCrypto.verifyTestVector() }.value
            assert(ok, "BackupCrypto: Prüfwert weicht von Android ab")
        }
        #endif
    }

    /// Sichern: Enthält die Datei Portfolio-Daten und ist gesperrt, erst entsperren.
    private func startExport() {
        let hasPortfolio = !data.portfolio.isEmpty
        Task {
            let open = await AppLock.shared.requireUnlock { locked in
                PortfolioLockPolicy.backupExportNeedsUnlock(locked: locked, hasPortfolioData: hasPortfolio)
            }
            guard open else { return }
            exportOptions = ExportOptions(defaultProtect: hasPortfolio)
        }
    }

    /// Nach dem Blatt «Sichern»: Datei bauen (mit Passwort verschlüsselt) und ablegen lassen.
    private func export(password: String?) {
        do {
            let file = try BackupManager.exportData(data, password: password)
            exportDocument = BackupDocument(data: file)
            exporting = true
        } catch {
            toast = L("backup_failed")
        }
    }

    /// Datei gewählt: verschlüsselt → Passwort, sonst gleich die Rückfrage. Alte, lesbare
    /// Sicherungen gehen wie bisher.
    private func picked(_ file: Data) {
        do {
            if try BackupManager.needsPassword(file) {
                pendingRestore = nil
                encryptedFile = EncryptedFile(data: file)
            } else {
                pendingRestore = file
                confirmRestore = true
            }
        } catch {
            toast = L("backup_failed")
        }
    }

    /// Wiederherstellen: solange gesperrt, erst entsperren (die Sicherung kann die Sperre ausschalten).
    private func startImport() {
        Task {
            let open = await AppLock.shared.requireUnlock(PortfolioLockPolicy.restoreNeedsUnlock(locked:))
            if open { importing = true }
        }
    }

    private func restore(_ json: Data) {
        pendingRestore = nil
        do {
            let result = try BackupManager.restore(json, into: data)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("backup_restored", L("backup_restored_pairs", count: result.watches),
                      L("backup_restored_alarms", count: result.alarms))
        } catch {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("backup_failed")
        }
    }

    private static func isCancel(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }
}

// MARK: - Über

/// Über die App: Version (sieben Tipps schalten «Entwickler» frei), Zweck, Hinweis zu den Daten.
@MainActor
struct AboutSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var versionTaps = 0
    @State private var toast: String?

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_about")) {
            SettingsCard {
                AboutContent(onVersionTap: versionTapped)
                    .padding(.vertical, Spacing.sm)
            }
            // Was die App kann (früher im Begrüßungsblatt)
            SettingsCard {
                AboutFeatures()
                    .padding(.vertical, Spacing.md)
            }
        }
        .toast($toast)
    }

    /// Sieben Tipps auf die Version schalten die Entwickleroptionen frei.
    private func versionTapped() {
        guard !data.settings.developerUnlocked else { return }
        versionTaps += 1
        if versionTaps >= 4 && versionTaps < 7 {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        if versionTaps == 7 {
            data.settings.developerUnlocked = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("developer_unlocked")
        }
    }
}

/// Entwickler: HTTP-Protokoll und Bericht der letzten Aktualisierung.
@MainActor
struct DeveloperSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_section_developer")) {
            SwitchRow(
                title: L("settings_http_log"),
                subtitle: L("settings_http_log_hint"),
                isOn: $data.settings.showHttpLog
            )
            .settingsAnchor("developer.http_log")
            RowDivider()
            VStack(alignment: .leading, spacing: Spacing.xs) {
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
                // Gleiche Darstellung wie das Blatt in der Merkliste (Status, Börsen)
                if let report = data.lastRefreshReport {
                    RefreshReportSummary(report: report, now: TimeUtils.nowMillis)
                        .padding(.top, 4)
                    RefreshReportMarketList(markets: report.markets)
                } else {
                    Text(L("watchlist_refresh_report_empty"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 12)
        }
    }
}
