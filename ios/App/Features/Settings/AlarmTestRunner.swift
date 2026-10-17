import SwiftUI
import UIKit

/// Führt den Probe-Alarm aus, sobald `trigger` sich ändert, und zeigt das Ergebnis:
/// Hinweis bei Nachtruhe (`alarm_test_quiet`) bzw. bei fehlender Erlaubnis eine
/// Rückfrage mit «Einstellungen öffnen». Für Einstellungen und Erst-Alarm-Dialog.
@MainActor
struct AlarmTestRunner: ViewModifier {
    let trigger: Int

    @EnvironmentObject private var data: AppData
    @State private var toast: String?
    @State private var denied = false

    func body(content: Content) -> some View {
        content
            .toast($toast)
            .alert(L("alarm_test_denied"), isPresented: $denied) {
                Button(L("alarm_test_open_settings")) {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                Button(L("action_cancel"), role: .cancel) {}
            }
            .onChange(of: trigger) { _, _ in
                Task { await run() }
            }
    }

    private func run() async {
        switch await data.sendTestAlarm() {
        case .sent:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .sentDuringQuietHours:
            toast = L("alarm_test_quiet")
        case .denied:
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            denied = true
        }
    }
}

extension View {
    /// Probe-Alarm bei jeder Änderung von `trigger` (z. B. Zähler erhöhen).
    func alarmTestRunner(trigger: Int) -> some View { modifier(AlarmTestRunner(trigger: trigger)) }
}
