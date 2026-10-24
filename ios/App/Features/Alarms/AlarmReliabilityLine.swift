import SwiftUI
import UIKit

/// Eine Zeile über der Merkliste, nur wenn bei den Alarmen etwas nicht stimmt (`AlarmReliability`, `isShown`): meist
/// Bernstein mit dem wichtigsten Grund («Letzte Prüfung vor 7 Std. – …»), grau nur «noch keine Prüfung»; Tipp öffnet
/// «Damit Alarme ankommen» als Blatt. Liest denselben Zustand wie die Seite (`AlarmReadinessProbe`), beim Zurückkehren
/// in die App neu. Ohne Alarme oder im Normalfall nichts. Wie `AlarmReliabilityLine.kt`.
@MainActor
struct AlarmReliabilityLine: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var input: AlarmReadiness.Input?
    @State private var showDelivery = false

    var body: some View {
        TimelineView(.everyMinute) { context in
            if let line = line(now: Int64(context.date.timeIntervalSince1970 * 1000)) {
                Button { showDelivery = true } label: { label(line) }
                    .buttonStyle(.plain)
                    .accessibilityHint(L("delivery_title"))
                    .accessibilityIdentifier("alarm_reliability_line")
            }
        }
        .task { await refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refresh() } }
        }
        .onChange(of: data.settings) { _, _ in Task { await refresh() } }
        .onChange(of: data.alarmsWithWatch.filter { $0.alarm.enabled }.count) { _, _ in Task { await refresh() } }
        .sheet(isPresented: $showDelivery, onDismiss: { Task { await refresh() } }) {
            NavigationStack {
                AlarmDeliveryScreen()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L("ios_action_ok")) { showDelivery = false }
                        }
                    }
            }
        }
    }

    /// Zeigt die Zeile den Stand `kind`? Im Normalfall (letzte Prüfung im Hintergrund rechtzeitig, `.recent`) nicht —
    /// erst wenn die Prüfung im Verzug ist (älter als `AlarmReliability.staleMillis`, `.stale`) oder etwas anderes
    /// fehlt. Wie Android `alarmReliabilityLineShown`.
    nonisolated static func isShown(_ kind: AlarmReliability.Kind) -> Bool {
        kind != .hidden && kind != .recent
    }

    private struct Line {
        let text: String
        let warn: Bool
    }

    /// Satz und Farbe zur Uhr `now`; nil = keine Zeile (keine Alarme oder noch nicht gelesen).
    private func line(now: Int64) -> Line? {
        guard var current = input else { return nil }
        current.now = now
        let status = AlarmReliability.status(current)
        guard Self.isShown(status.kind) else { return nil }
        // Alle gespeicherten Läufe gescheitert: Zeit des letzten Versuchs (wie Android)
        let checkedAt = current.lastBackgroundCheck ?? current.lastFailedBackgroundCheck
        let ago = checkedAt.map { WatchlistTime.ago($0, now: now) } ?? ""
        let text: String
        switch status.kind {
        case .hidden, .recent: return nil
        case .stale: text = L("alarm_reliability_stale", ago)
        case .never: text = L("alarm_reliability_never")
        case .notificationsOff: text = L("alarm_reliability_notifications_off")
        // Nur Android (Merkliste ohne Schlüssel); iOS setzt `storageNoKey` nie
        case .storageNoKey: text = L("alarm_reliability_storage_no_key")
        case .battery: text = L("alarm_reliability_battery")
        case .backgroundRefresh: text = L("alarm_reliability_background_refresh")
        }
        return Line(text: text, warn: status.warn)
    }

    private func label(_ line: Line) -> some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: line.warn ? "exclamationmark.triangle.fill" : "bell")
                .font(.caption)
                .foregroundStyle(line.warn ? AppColors.warning : AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
            Text(line.text)
                .font(.footnote)
                .foregroundStyle(line.warn ? AppColors.warningText : AppColors.onSurfaceVariant)
                // Grosse Schrift (Bedienungshilfen): ganzer Satz statt abgeschnitten
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.forward")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    private func refresh() async {
        // Wie «Damit Alarme ankommen»: alles, was nur eine Prüfung im Hintergrund meldet
        let hasAlarms = AlarmReadiness.hasBackgroundAlarms(
            AlarmDeliveryScreen.alarmKinds(data.settings, pairAlarm: data.alarmsWithWatch.contains { $0.alarm.enabled },
                                           portfolioAlarm: data.portfolioAlarms.contains(where: \.enabled)),
            platform: .ios)
        input = await AlarmReadinessProbe.read(settings: data.settings, hasAlarms: hasAlarms)
    }
}
