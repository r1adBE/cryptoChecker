import SwiftUI

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
