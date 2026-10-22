import CoreTransferable
import SwiftUI
import UIKit
import UniformTypeIdentifiers

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

/// Vorschau des Berichts ([DiagnosticReport]) und Teilen als Textdatei (`DiagnosticsShareFile`).
/// Der Bericht entsteht nur aus Daten auf dem Gerät; Portfolio, Beträge, Adressen und Notizen liest er gar nicht.
@MainActor
struct DiagnosticsPreviewPage: View {
    @EnvironmentObject private var data: AppData
    @State private var text: String?

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
            if let text {
                ToolbarItem(placement: .primaryAction) {
                    // Erst nach der Vorschau und nur auf Tipp: nichts wird automatisch gesendet
                    ShareLink(item: DiagnosticsShareFile(text: text),
                              preview: SharePreview(DiagnosticReport.fileName)) {
                        Label(L("diagnostics_action_share"), systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .task {
            // Liegengebliebene Datei (App beendet, während geteilt wurde) zuerst weg
            DiagnosticsShareFile.remove()
            await build()
        }
        // Seite zu: die unverschlüsselte Datei bleibt nicht im temporären Ordner liegen. Das Teilen-Menü deckt die
        // Seite nur als Blatt ab (kein onDisappear), die Ziel-App hat ihre Kopie beim Schliessen schon.
        .onDisappear { DiagnosticsShareFile.remove() }
    }

    private func build() async {
        let settings = data.settings
        let activeAlarms = data.alarmsWithWatch.filter { $0.alarm.enabled }.count
        // Portfolio liest der Bericht nicht: Portfolio-Alarme zählen hier nicht mit (sonst wie «Damit Alarme ankommen»)
        let hasAlarms = AlarmReadiness.hasBackgroundAlarms(
            AlarmDeliveryScreen.alarmKinds(settings, pairAlarm: activeAlarms > 0, portfolioAlarm: false), platform: .ios)
        let readiness = AlarmReadiness.rows(await AlarmReadinessProbe.read(settings: settings, hasAlarms: hasAlarms))
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
            errorLog: nil,
            // Seit Build 103: Quell-Commit aus dem Build-Setting GIT_COMMIT
            sourceCommit: info?["CCGitCommit"] as? String ?? DiagnosticReport.localCommit)
        // Nur der Text: die Datei entsteht erst beim Teilen (`DiagnosticsShareFile`), nicht schon mit der Vorschau
        text = await Task.detached(priority: .utility) { () -> String in
            var full = input
            full.checkRuns = CheckRunStore.load()
            full.alarmEvents = AlarmLogStore.load().events
            // Seit Build 104: nur Anzahl Fehler im Hintergrund je Bereich
            full.backgroundErrors = BackgroundErrorCounter.summary()
            return DiagnosticReport.build(full)
        }.value
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

/// Bericht fürs Teilen-Menü als Textdatei im temporären Ordner (nicht im Geräte-Backup). Geschrieben wird erst, wenn
/// nach dem Tipp auf «Teilen» ein Ziel die Datei anfordert, und gelöscht beim Verlassen der Vorschau — der Bericht
/// liegt so nicht unverschlüsselt herum (wie Android `DiagnosticsFiles`, seit Build 98).
struct DiagnosticsShareFile: Transferable, Sendable {
    let text: String

    static var url: URL { FileManager.default.temporaryDirectory.appendingPathComponent(DiagnosticReport.fileName) }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .plainText) { item in
            try item.write()
            return SentTransferredFile(url)
        }
    }

    /// Schreibt den Bericht nach `url` (überschreibt eine ältere Fassung).
    func write() throws {
        try text.write(to: Self.url, atomically: true, encoding: .utf8)
    }

    /// Datei löschen; fehlt sie, ist nichts zu tun.
    static func remove() {
        try? FileManager.default.removeItem(at: url)
    }
}
