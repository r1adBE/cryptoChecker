import Combine
import SwiftUI
import UIKit
import UserNotifications

/// «Damit Alarme ankommen»: der echte Zustand je Punkt (✓ / ⚠ / Hinweis) mit Knopf zur passenden Seite.
/// Erreichbar aus «Alle Alarme», dem Hinweis im Alarm-Editor und Einstellungen › Alarme. Wie `AlarmDeliveryScreen.kt`.
/// Liest nur (Mitteilungs-Einstellungen, Hintergrundaktualisierung, Stromsparmodus, «Letzte Prüfungen»); beim
/// Zurückkehren in die App neu gelesen.
@MainActor
struct AlarmDeliveryScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.scenePhase) private var scenePhase
    @State private var items: [AlarmReadiness.Item] = []
    @State private var lastCheck: Int64?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.md) {
                // Derselbe ehrliche Satz wie unter jedem Alarm
                Text(AlarmTimingHint.text)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                VStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        if index > 0 { Divider().overlay(AppColors.outlineVariant) }
                        row(item)
                    }
                }
                .appCard(padding: .none)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            .readableContentWidth()
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("delivery_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refresh() } }
        }
        .onChange(of: data.settings) { _, _ in Task { await refresh() } }
        // Stromsparmodus: iOS meldet die Änderung nicht unbedingt auf dem Haupt-Thread
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)
            .receive(on: DispatchQueue.main)) { _ in
            Task { await refresh() }
        }
    }

    private func row(_ item: AlarmReadiness.Item) -> some View {
        let title = Self.title(item.row)
        let detail = detailText(item)
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: Spacing.md) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AppColors.onSurface)
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(item.state == .warn ? AppColors.warningText : AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: Self.symbol(item.state))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Self.tint(item.state))
                    .accessibilityHidden(true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel([title, Self.stateLabel(item.state), detail].joined(separator: ", "))
            actionButton(item.action)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private func actionButton(_ action: AlarmReadiness.Action) -> some View {
        switch action {
        case .none, .channelSettings, .exactPermission, .batterySettings:
            // Die drei letzten gibt es nur auf Android
            EmptyView()
        case .notificationSettings:
            link(L("alarm_test_open_settings")) { NotificationsOffBanner.openSettings() }
        case .appSettings:
            link(L("alarm_test_open_settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        case .requestPermission:
            link(L("delivery_action_allow")) {
                Task {
                    _ = await Notifier.requestPermission()
                    await refresh()
                }
            }
        case .alarmSettings:
            NavigationLink { AlarmSettingsPage() } label: { linkLabel(L("delivery_action_change")) }
                .buttonStyle(.plain)
        case .updateSettings:
            NavigationLink { UpdatesSettingsPage() } label: { linkLabel(L("alarm_change_updates")) }
                .buttonStyle(.plain)
        case .checkRuns:
            NavigationLink { UpdatesSettingsPage() } label: { linkLabel(L("delivery_action_show")) }
                .buttonStyle(.plain)
        }
    }

    private func link(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { linkLabel(title) }
            .buttonStyle(.plain)
    }

    private func linkLabel(_ title: String) -> some View {
        Text(title)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(accent.primary)
            .frame(minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
    }

    private func refresh() async {
        // Alles, was nur eine Prüfung im Hintergrund meldet (`AlarmReadiness.hasBackgroundAlarms`), nicht nur Paar-Alarme
        let hasAlarms = AlarmReadiness.hasBackgroundAlarms(
            Self.alarmKinds(data.settings, pairAlarm: data.alarmsWithWatch.contains { $0.alarm.enabled },
                            portfolioAlarm: data.portfolioAlarms.contains(where: \.enabled)),
            platform: .ios)
        let input = await AlarmReadinessProbe.read(settings: data.settings, hasAlarms: hasAlarms)
        lastCheck = input.lastBackgroundCheck
        items = AlarmReadiness.rows(input)
    }

    /// Alarm-Arten aus Einstellungen und Alarmen (`AlarmReadiness.hasBackgroundAlarms`); auch für «Diagnose teilen».
    static func alarmKinds(_ settings: AppSettings, pairAlarm: Bool, portfolioAlarm: Bool) -> AlarmReadiness.AlarmKinds {
        var k = AlarmReadiness.AlarmKinds()
        k.pairAlarm = pairAlarm
        k.portfolioEnabled = settings.portfolioEnabled
        k.portfolioAlarm = portfolioAlarm
        k.gasEthTenths = settings.gasAlertEthTenths
        k.gasBtc = settings.gasAlertBtc
        k.depeg = settings.depegAlarm
        k.fearGreedBelow = settings.fearGreedBelow
        k.fearGreedAbove = settings.fearGreedAbove
        k.indicatorAlert = settings.indicatorAlerts.values.contains { $0 > 0 }
        k.activityAlerts = settings.activityAlerts
        k.macroNotices = settings.macroNotifications
        return k
    }

    private func detailText(_ item: AlarmReadiness.Item) -> String {
        let settings = data.settings
        switch item.detail {
        case .allowed: return L("delivery_allowed")
        case .on:
            switch item.row {
            case .lowPower: return L("ios_low_power_mode")
            case .dnd: return L("delivery_dnd_on")
            default: return L("settings_summary_on")
            }
        case .off:
            switch item.row {
            case .notifications: return L("delivery_notifications_off")
            case .timeSensitive: return L("delivery_time_sensitive_off")
            case .backgroundRefresh: return L("ios_background_refresh_off")
            default: return L("option_off")
            }
        case .notAsked: return L("delivery_notifications_not_asked")
        case .alertOff: return L("delivery_alert_off")
        case .soundOff: return L("delivery_sound_off")
        case .quietNow: return L("delivery_quiet_now", QuietHours.format(settings.quietHoursEnd))
        case .quietSet:
            return L("delivery_quiet_set", QuietHours.format(settings.quietHoursStart), QuietHours.format(settings.quietHoursEnd))
        case .checkRecent: return lastCheck.map { AlarmLastCheckedLine.when($0, now: Date()) } ?? ""
        case .checkOld: return L("delivery_last_check_old", lastCheck.map { AlarmLastCheckedLine.when($0, now: Date()) } ?? "")
        case .checkNever: return L("delivery_last_check_never")
        case .note: return L(item.row == .swipeAway ? "delivery_swipe_away_note" : "delivery_live_activity_note")
        // Nur auf Android
        case .channelHigh: return L("delivery_channel_high")
        case .channelLow: return L("delivery_channel_low")
        case .channelOff: return L("delivery_channel_off")
        case .exactOn: return L("delivery_exact_on")
        case .exactOff: return L("delivery_exact_off")
        case .exactNoPermission: return L("settings_exact_checks_permission_missing")
        case .batteryUnrestricted: return L("delivery_battery_unrestricted")
        case .batteryOptimized: return L("delivery_battery_optimized")
        case .restricted: return L("delivery_background_restricted")
        }
    }

    static func title(_ row: AlarmReadiness.Row) -> String {
        switch row {
        case .notifications: return L("delivery_notifications")
        case .alertSound: return L("delivery_alert_sound")
        case .timeSensitive: return L("delivery_time_sensitive")
        case .backgroundRefresh: return L("delivery_background_refresh")
        case .lowPower: return L("delivery_low_power")
        case .quietHours: return L("settings_quiet_hours")
        case .lastCheck: return L("delivery_last_check")
        case .swipeAway: return L("delivery_swipe_away")
        case .liveActivity: return L("delivery_live_activity")
        // Nur auf Android
        case .channel: return L("delivery_channel")
        case .exact: return L("settings_exact_checks")
        case .battery: return L("delivery_battery")
        case .background: return L("delivery_background")
        case .dnd: return L("delivery_dnd")
        }
    }

    private static func symbol(_ state: AlarmReadiness.State) -> String {
        switch state {
        case .ok: return "checkmark.circle.fill"
        case .warn: return "exclamationmark.triangle.fill"
        case .info: return "info.circle"
        }
    }

    private static func tint(_ state: AlarmReadiness.State) -> Color {
        switch state {
        case .ok: return PriceColors.ok
        case .warn: return AppColors.warning
        case .info: return AppColors.onSurfaceVariant
        }
    }

    private static func stateLabel(_ state: AlarmReadiness.State) -> String {
        switch state {
        case .ok: return L("delivery_state_ok")
        case .warn: return L("delivery_state_warn")
        case .info: return L("delivery_state_info")
        }
    }
}

/// Liest den echten Zustand für «Damit Alarme ankommen» (auch für «Diagnose teilen») — nur lesen: keine Erlaubnis
/// wird erfragt. Wie `AlarmReadinessProbe.kt`.
@MainActor
enum AlarmReadinessProbe {
    static func read(settings: AppSettings, hasAlarms: Bool) async -> AlarmReadiness.Input {
        let center = await UNUserNotificationCenter.current().notificationSettings()
        let runs = await Task.detached(priority: .utility) { CheckRunStore.load() }.value
        var input = AlarmReadiness.Input(platform: .ios, notifications: access(center.authorizationStatus),
                                         now: TimeUtils.nowMillis, minuteOfDay: QuietHours.minuteOfDay())
        input.quietEnabled = settings.quietHoursEnabled
        input.quietStart = settings.quietHoursStart
        input.quietEnd = settings.quietHoursEnd
        // Nur vom System geplante Prüfungen (BGAppRefreshTask) — App, Widget und gescheiterte Läufe zählen nicht
        input.lastBackgroundCheck = CheckRunLog.lastBackgroundCheck(runs)
        // Alle gespeicherten geplanten Läufe gescheitert: Zuverlässigkeitszeile warnt
        input.lastFailedBackgroundCheck = CheckRunLog.lastFailedBackgroundCheck(runs)
        input.hasAlarms = hasAlarms
        input.alertEnabled = center.alertSetting != .disabled
        input.soundEnabled = center.soundSetting != .disabled
        input.wantsSound = settings.alarmSignal.ios != .SILENT
        // Alarme melden sich als «dringlich» (`interruptionLevel = .timeSensitive`)
        switch center.timeSensitiveSetting {
        case .enabled: input.timeSensitive = true
        case .disabled: input.timeSensitive = false
        default: input.timeSensitive = nil
        }
        input.backgroundRefresh = UIApplication.shared.backgroundRefreshStatus == .available
        input.lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        return input
    }

    static func access(_ status: UNAuthorizationStatus) -> AlarmReadiness.Access {
        switch status {
        case .authorized, .provisional, .ephemeral: return .granted
        case .notDetermined: return .notDetermined
        default: return .denied
        }
    }
}
