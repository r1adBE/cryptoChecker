import SwiftUI
import UIKit

/// «Diagnose teilen» (Build 81) unter Einstellungen › Über die App: Zeile mit Hinweis; öffnet die Vorschau, erst dort
/// geht es über das Teilen-Menü weiter. Wie `DiagnosticsCard` (Android).
@MainActor
struct DiagnosticsCard: View {
    var body: some View {
        SettingsCard {
            SettingsNavRow(title: L("diagnostics_share"), icon: .system("stethoscope"), value: nil) {
                DiagnosticsPreviewPage()
            }
            .settingsAnchor("about.diagnostics")
            SettingsHint(text: L("diagnostics_share_hint"))
                .padding(.bottom, Spacing.sm)
        }
    }
}

/// Vorschau des Berichts ([DiagnosticReport]) und Teilen als Textdatei (temporärer Ordner, nicht im Geräte-Backup).
/// Der Bericht entsteht nur aus Daten auf dem Gerät; Portfolio, Beträge, Adressen und Notizen liest er gar nicht.
@MainActor
struct DiagnosticsPreviewPage: View {
    @EnvironmentObject private var data: AppData
    @State private var text: String?
    @State private var file: URL?

    var body: some View {
        ScrollView {
            if let text {
                Text(verbatim: text)
                    .font(.caption.monospaced())
                    .foregroundStyle(AppColors.onSurface)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
            }
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("diagnostics_preview_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let file {
                ToolbarItem(placement: .primaryAction) {
                    // Erst nach der Vorschau und nur auf Tipp: nichts wird automatisch gesendet
                    ShareLink(item: file) {
                        Label(L("diagnostics_action_share"), systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .task { await build() }
    }

    private func build() async {
        let settings = data.settings
        let activeAlarms = data.alarmsWithWatch.filter { $0.alarm.enabled }.count
        let readiness = AlarmReadiness.rows(await AlarmReadinessProbe.read(settings: settings, hasAlarms: activeAlarms > 0))
        let info = Bundle.main.infoDictionary
        let input = DiagnosticInput(
            createdAt: TimeUtils.nowMillis,
            zone: TimeZone.current.identifier,
            appVersion: info?["CFBundleShortVersionString"] as? String ?? "-",
            appBuild: info?["CFBundleVersion"] as? String ?? "-",
            system: "iOS \(UIDevice.current.systemVersion)",
            device: Self.deviceModel(),
            locale: Locale.current.identifier(.bcp47),
            settings: DiagnosticSettings(
                updateMode: settings.updateMode.rawValue,
                backgroundIntervalMinutes: settings.backgroundIntervalMinutes,
                exactChecks: nil,
                priceNotifications: settings.priceNotifications,
                alarmSignal: settings.alarmSignal.ios.rawValue,
                alarmCooldownMinutes: settings.alarmCooldownMinutes,
                quietHoursEnabled: settings.quietHoursEnabled,
                quietHoursStart: settings.quietHoursStart,
                quietHoursEnd: settings.quietHoursEnd,
                activeAlarms: activeAlarms),
            readiness: readiness,
            checkRuns: [],
            alarmEvents: [],
            exchangePauses: SharedStorage.exchangeBackoff,
            // Der Release-Build führt kein Fehlerprotokoll
            errorLog: nil)
        let report = await Task.detached(priority: .utility) { () -> (String, URL?) in
            var full = input
            full.checkRuns = CheckRunStore.load()
            full.alarmEvents = AlarmLogStore.load().events
            let text = DiagnosticReport.build(full)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(DiagnosticReport.fileName)
            do {
                try text.write(to: url, atomically: true, encoding: .utf8)
                return (text, url)
            } catch {
                return (text, nil)
            }
        }.value
        text = report.0
        file = report.1
    }

    /// Gerätekennung wie «iPhone16,1» (der Name des Geräts bleibt draussen — er enthält oft einen Vornamen).
    private static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return machine.isEmpty ? UIDevice.current.model : machine
    }
}
