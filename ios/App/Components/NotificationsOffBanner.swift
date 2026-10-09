import SwiftUI
import UIKit
import UserNotifications

/// Hinweis über der Alarm-Liste, wenn Mitteilungen für die App in den iOS-Einstellungen aus sind:
/// sonst kämen Alarme still nicht an. «Einschalten» öffnet die Mitteilungs-Einstellungen der App;
/// beim Zurückkehren in die App wird neu geprüft. Wie Android `NotificationsOffBanner`.
struct NotificationsOffBanner: View {
    @State private var denied = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if denied {
                HStack(spacing: 12) {
                    Image(systemName: "bell.slash.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AppColors.error)
                        .accessibilityHidden(true)
                    Text(L("alarms_notifications_off"))
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Button {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        Text(L("alarms_notifications_turn_on"))
                            .font(.footnote.weight(.semibold))
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(TonalButtonStyle())
                }
                .padding(12)
                .background(AppColors.error.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityElement(children: .combine)
            }
        }
        .task { await refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refresh() } }
        }
    }

    private func refresh() async {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        denied = status == .denied
    }
}
