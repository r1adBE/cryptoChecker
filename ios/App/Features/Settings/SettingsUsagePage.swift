import BackgroundTasks
import SwiftUI
import UIKit

/// «Akku & Aktivität»: ehrlicher Satz (Apps sehen ihren Akkuverbrauch nicht), je eine Karte «Handy» (eigene Zähler der
/// letzten 24 h, `UsageMeter`) und «Uhr» (Stand der Apple Watch, `WatchUsageStore`; ohne «Keine Uhr verbunden»), dann der
/// Weg in die iOS-Einstellungen — die Liste je App zeigt iOS unter Einstellungen › Batterie. Wie Android `UsagePage`
/// (dort mit «Pünktlich prüfen» im Satz zum Treiber).
@MainActor
struct UsageSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.scenePhase) private var scenePhase
    @State private var phone = UsageTotals.zero
    @State private var watch: UsageReport?
    @State private var planned = false

    init() {}

    private var driver: String {
        let interval: String = L("settings_minutes", data.settings.backgroundIntervalMinutes)
        switch UsageLog.driver(backgroundPlanned: planned, exactActive: false) {
        case .none: return L("usage_driver_none")
        case .interval: return L("usage_driver_interval", interval)
        case .intervalExact: return L("usage_driver_exact", interval, L("settings_exact_checks"))
        }
    }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_usage")) {
            SettingsHint(text: L("usage_intro"), top: Spacing.xs)
            UsageCard(title: L("usage_phone"), totals: phone, driver: driver, asOf: nil)
                .settingsAnchor("usage.phone")
            Group {
                if let watch {
                    UsageCard(title: L("usage_watch"), totals: watch.totals, driver: L("usage_driver_watch"), asOf: watch.at)
                } else {
                    SettingsCard(title: L("usage_watch")) {
                        SettingsHint(text: L("usage_watch_none"))
                    }
                }
            }
            .settingsAnchor("usage.watch")
            SettingsCard {
                SettingsHint(text: L("ios_usage_battery_hint"), top: Spacing.sm)
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                } label: {
                    Text(L("ios_open_settings"))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                }
                .buttonStyle(TonalButtonStyle())
                .padding(.bottom, Spacing.sm)
            }
            .settingsAnchor("usage.battery")
        }
        .task { await reload() }
        // Zurück in der App (Hintergrund-Aufgabe, Widget oder Uhr lief inzwischen): neu lesen
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await reload() } }
        }
    }

    /// Zähler abseits des Haupt-Threads lesen (Datei-Abstimmung mit dem Widget), dazu: ist die Aufgabe vorgemerkt?
    private func reload() async {
        phone = await Task.detached(priority: .utility) { UsageMeter.shared.totals() }.value
        watch = await Task.detached(priority: .utility) { WatchUsageStore.load() }.value
        planned = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            BGTaskScheduler.shared.getPendingTaskRequests { requests in
                continuation.resume(returning: requests.contains { $0.identifier == BackgroundRefresh.refreshId })
            }
        }
    }
}

/// Eine Karte: Einstufung mit Punkt, die drei Zahlen der letzten 24 h, der Treiber und bei der Uhr der Stand.
@MainActor
private struct UsageCard: View {
    let title: String
    let totals: UsageTotals
    let driver: String
    let asOf: Int64?

    private var level: UsageLevel { UsageLog.level(totals) }

    var body: some View {
        SettingsCard(title: title) {
            HStack(spacing: Spacing.sm) {
                Circle()
                    .fill(color)
                    .frame(width: 10, height: 10)
                    .accessibilityHidden(true)
                Text(L(labelKey))
                    .font(.body.weight(.semibold))
            }
            .padding(.vertical, Spacing.xs)
            Text(L("usage_runs", count: totals.runs))
                .font(.subheadline)
            Text(L("usage_requests", count: totals.requests))
                .font(.subheadline)
            Text(L("usage_received", ByteCountFormatter.string(fromByteCount: totals.bytes, countStyle: .file)))
                .font(.subheadline)
                .padding(.bottom, Spacing.xs)
            SettingsHint(text: driver)
            if let asOf {
                SettingsHint(text: L("usage_as_of", Self.time(asOf)))
            }
        }
    }

    private var labelKey: String {
        switch level {
        case .low: return "usage_level_low"
        case .medium: return "usage_level_medium"
        case .high: return "usage_level_high"
        }
    }

    private var color: Color {
        switch level {
        case .low: return PriceColors.ok
        case .medium: return AppColors.warning
        case .high: return AppColors.error
        }
    }

    /// Heute nur die Uhrzeit, sonst mit Datum (wie «Letzte Prüfungen»).
    private static func time(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        if Calendar.current.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
