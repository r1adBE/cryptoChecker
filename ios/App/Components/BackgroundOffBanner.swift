import SwiftUI
import UIKit

/// Warnung über den Alarmen, wenn iOS bei geschlossener App gar nicht oder kaum prüft: Hintergrund-
/// aktualisierung für die App aus bzw. eingeschränkt, oder Stromsparmodus an. Ohne diese Warnung kämen dann
/// keine Alarme, und die App sagte nichts. Wie `NotificationsOffBanner` aufgebaut.
struct BackgroundOffBanner: View {
    // Gleich beim Erstellen lesen: ein leerer Group-Inhalt löst .onAppear nicht sicher aus
    @State private var state: BackgroundAvailability.State = BackgroundAvailability.current()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if let key = state.messageKey {
                HStack(spacing: 12) {
                    Image(systemName: "moon.zzz.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AppColors.warning)
                        .accessibilityHidden(true)
                    Text(L(key))
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    if state == .refreshOff {
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        } label: {
                            Text(L("alarms_notifications_turn_on"))
                                .font(.footnote.weight(.semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                        }
                        .buttonStyle(TonalButtonStyle())
                    }
                }
                .padding(12)
                .background(AppColors.warning.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityElement(children: .combine)
            }
        }
        .onAppear { state = BackgroundAvailability.current() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { state = BackgroundAvailability.current() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)) { _ in
            state = BackgroundAvailability.current()
        }
    }
}

/// Darf iOS die App im Hintergrund aktualisieren?
enum BackgroundAvailability {
    enum State: Equatable {
        case available
        /// Hintergrundaktualisierung für die App aus oder eingeschränkt (Einstellungen, Bildschirmzeit).
        case refreshOff
        /// Stromsparmodus: iOS lässt Hintergrundaufgaben kaum laufen.
        case lowPower

        var messageKey: String? {
            switch self {
            case .available: nil
            case .refreshOff: "ios_background_refresh_off"
            case .lowPower: "ios_low_power_mode"
            }
        }
    }

    @MainActor
    static func current() -> State {
        if UIApplication.shared.backgroundRefreshStatus != .available { return .refreshOff }
        if ProcessInfo.processInfo.isLowPowerModeEnabled { return .lowPower }
        return .available
    }
}
