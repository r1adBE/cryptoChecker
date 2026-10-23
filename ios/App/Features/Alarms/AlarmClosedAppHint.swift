import SwiftUI

// «Wann Alarme geprüft werden»: Zeile «Zuletzt geprüft», Hinweis bei geschlossener App und die Meldung nach dem
// Speichern — genutzt im Alarm-Blatt (`AlarmEditorSheet`) und im Portfolio-Alarm.

/// «Zuletzt geprüft 14:32»: wann die Alarme zuletzt wirklich geprüft wurden (App, Live-Strom oder
/// Hintergrund-Aufgabe, `SharedStorage.lastAlarmCheckAt` — das Widget zählt nicht). Noch nie: keine Zeile.
/// Liest den Stand jede Minute neu (UserDefaults meldet keine Änderung).
@MainActor
struct AlarmLastCheckedLine: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            let at = SharedStorage.lastAlarmCheckAt
            if at > 0 {
                Text(L("alarm_last_checked", Self.when(at, now: context.date)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    /// Heute nur die Uhrzeit, sonst mit Datum (z. B. «15. Okt., 22:10»).
    static func when(_ millis: Int64, now: Date) -> String {
        let date = Date(millis: millis)
        if Calendar.current.isDate(date, inSameDayAs: now) { return PriceFormat.shortTime(millis) }
        return date.formatted(.dateTime.day().month(.abbreviated).hour().minute())
    }
}

/// Graue Zeile mit dem einen Satz «Wann Alarme geprüft werden» (`AlarmTimingHint`) — vollständig sichtbar,
/// darum ohne (i), das ihn nur wiederholen würde; wie `ClosedAppHint` (Android). Darunter
/// (K-10) «Aktualisierung ändern»: schliesst das Blatt und öffnet Einstellungen › Kurs-Aktualisierung; und
/// «Damit Alarme ankommen» als Blatt darüber.
@MainActor
struct ClosedAppHintRow: View {
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    /// «Damit Alarme ankommen» als Blatt über dem Editor (der Entwurf bleibt dabei offen).
    @State private var showDelivery = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(AlarmTimingHint.text)
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            AlarmLastCheckedLine()
                .padding(.top, 2)
            Button(L("alarm_change_updates"), action: openUpdates)
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.plain)
                .frame(minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            // Was gerade im Weg stehen könnte (Mitteilungen, Hintergrundaktualisierung …): echter Zustand
            Button(L("delivery_title")) { showDelivery = true }
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.plain)
                .frame(minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .padding(.horizontal, 4)
        .sheet(isPresented: $showDelivery) {
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

    /// Blatt schliessen, dann den Tab «Einstellungen» mit «Aktualisierung» zeigen.
    private func openUpdates() {
        dismiss()
        AppRouter.shared.openUpdateSettings()
    }
}

extension View {
    /// Hinweis «Wann Alarme geprüft werden» als Meldung, einmal nach dem Speichern: der eine Satz und
    /// darunter der Weg zur Wahl «Wie aktuell?» — wie `AlarmTimingDialog` (Android).
    func alarmTimingAlert(isPresented: Binding<Bool>) -> some View {
        alert(L("alarm_timing_title"), isPresented: isPresented) {
            Button(L("ios_action_ok"), role: .cancel) {}
        } message: {
            Text(AlarmTimingHint.text + "\n\n" + AlarmTimingHint.whereText)
        }
    }
}
