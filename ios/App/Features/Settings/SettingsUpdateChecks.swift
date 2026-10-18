import SwiftUI

/// «Letzte Prüfungen» (Build 46) in «Aktualisierung»: ehrlicher Satz, dann je Lauf Zeit, Auslöser,
/// Ergebnis und Dauer (neueste zuerst, aus `CheckRunStore`). Wie `CheckRunsCard` (Android,
/// SettingsUpdateChecks.kt); «Pünktlich prüfen» und Akku-Hinweis gibt es nur dort.
@MainActor
struct CheckRunsCard: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var runs: [CheckRun] = []

    var body: some View {
        SettingsCard(title: L("settings_check_runs_title")) {
            SettingsHint(text: L("settings_check_runs_honest_ios"), top: 4)
            // Ohne App Group sehen Widgets und Hintergrund-Aufgabe die Daten der App nicht (Fehler der Installation)
            if !SharedStorage.isAppGroupAvailable {
                SettingsHint(text: L("ios_storage_app_group_missing"))
            }
            if runs.isEmpty {
                SettingsHint(text: L("settings_check_runs_empty"))
            }
            ForEach(Array(runs.enumerated()), id: \.offset) { _, run in
                CheckRunRow(run: run)
            }
            // Daneben das Alarm-Protokoll: nicht nur wann, sondern was die Prüfung bei jedem Alarm sah
            SettingsNavRow(title: L("alarm_log_open"), valueDescription: nil, value: { EmptyView() },
                           destination: { AlarmLogScreen() })
        }
        .onAppear { runs = CheckRunStore.load() }
        // Zurück in der App (Hintergrund-Aufgabe oder Widget lief inzwischen): neu lesen
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { runs = CheckRunStore.load() }
        }
    }
}

@MainActor
private struct CheckRunRow: View {
    let run: CheckRun

    private var time: String {
        let date = Date(timeIntervalSince1970: TimeInterval(run.at) / 1000)
        // Heute nur die Uhrzeit, sonst mit Datum
        if Calendar.current.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private var duration: String {
        guard run.result != .skipped else { return "" }
        let seconds: Int = Int((run.durationMillis + 500) / 1000)
        return L("settings_seconds", seconds)
    }

    private var detail: String {
        [Self.resultLabel(run.result), duration].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var ok: Bool { run.result == .ok || run.result == .skipped }

    var body: some View {
        let trigger: String = Self.triggerLabel(run.trigger)
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(time)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(trigger)
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(ok ? AppColors.onSurfaceVariant : AppColors.warningText)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([time, trigger, detail].filter { !$0.isEmpty }.joined(separator: ", "))
    }

    /// iOS-Widget: prüft Kurse und Kursalarme, aber keine Alarme mit Zusatzdaten (Volumen, Funding/OI,
    /// Hoch/Tief …; siehe `WidgetData`) — darum sagt der Auslöser gleich mit, was dieser Lauf abdeckt.
    static func triggerLabel(_ trigger: CheckTrigger) -> String {
        switch trigger {
        case .worker: return L("check_trigger_worker")
        case .exact: return L("check_trigger_exact")
        case .bgTask: return L("check_trigger_bgtask")
        case .widget: return L("check_trigger_widget_ios")
        case .foreground: return L("check_trigger_foreground")
        }
    }

    static func resultLabel(_ result: CheckResult) -> String {
        switch result {
        case .ok: return L("check_result_ok")
        case .partial: return L("check_result_partial")
        case .failed: return L("check_result_failed")
        case .skipped: return L("check_result_skipped")
        }
    }
}
