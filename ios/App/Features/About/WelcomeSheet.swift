import SwiftUI
import UIKit
import UserNotifications

/// Begrüßung beim ersten Start — wie `WelcomeDialog.kt`: Titel, zwei Zeilen, drei
/// Stichworte (je mit kurzer Frage darüber) und «Los geht's»: schließt und führt zur Merkliste, wo die leere Liste die
/// Starter-Auswahl (Top-Coins) zeigt. Lizenz und Kontakt stehen in den Einstellungen unter «Über».
///
/// Zusätzlich auf iOS: Mitteilungen erlauben (für Alarme), bevor das erste Paar kommt.
/// Der Aufrufer zeigt das Blatt als Sheet und schließt es in `onStart`.
@MainActor
struct WelcomeSheet: View {
    private let onStart: () -> Void

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @State private var notificationStatus: UNAuthorizationStatus = .notDetermined
    @State private var appeared = false

    init(onStart: @escaping () -> Void) {
        self.onStart = onStart
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                logo
                    .padding(.top, 48)

                VStack(spacing: 12) {
                    Text(L("welcome_title"))
                        .font(.largeTitle.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text(L("welcome_text"))
                        .font(.body)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 12)

                // Was die App kann: beobachten, alarmieren, verstehen
                VStack(alignment: .leading, spacing: 10) {
                    // Je eine kleine Frage obenauf («Was passiert?»), darunter der bisherige Text
                    welcomeLine(L("welcome_watch_q"), L("welcome_watch"))
                    welcomeLine(L("welcome_alert_q"), L("welcome_alert"))
                    welcomeLine(L("welcome_understand_q"), L("welcome_understand"))
                }
                .padding(16)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .opacity(appeared ? 1 : 0)
                .offset(y: appeared ? 0 : 14)

                notificationCard
                    .opacity(appeared ? 1 : 0)
                    .offset(y: appeared ? 0 : 16)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) { buttons }
        .background(background.ignoresSafeArea())
        .presentationDragIndicator(.hidden)
        .task {
            notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            withAnimation(.spring(duration: 0.6).delay(0.1)) { appeared = true }
        }
    }

    // MARK: Teile

    private var background: some View {
        ZStack {
            AppColors.background
            RadialGradient(colors: [accent.primary.opacity(0.28), .clear],
                           center: .top, startRadius: 10, endRadius: 420)
        }
    }

    private var logo: some View {
        ZStack {
            Circle()
                .fill(accent.primary.opacity(0.18))
                .frame(width: 150, height: 150)
                .blur(radius: 24)
            AppLogoView(size: 96)
                .shadow(color: accent.primary.opacity(0.45), radius: 18, y: 8)
        }
        .scaleEffect(appeared ? 1 : 0.85)
        .opacity(appeared ? 1 : 0)
    }

    /// Eine der drei Zeilen: kleine Frage, darunter der Text — für VoiceOver ein Element.
    private func welcomeLine(_ question: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(question)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var granted: Bool {
        notificationStatus == .authorized || notificationStatus == .provisional || notificationStatus == .ephemeral
    }

    private var notificationCard: some View {
        HStack(spacing: 14) {
            Image(systemName: granted ? "bell.badge.fill" : "bell.fill")
                .font(.title3)
                .foregroundStyle(accent.onContainer)
                .frame(width: 44, height: 44)
                .background(accent.container, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text(L(notificationStatus == .denied ? "ios_notifications_denied" : "ios_allow_notifications"))
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if granted {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(accent.primary)
                    .transition(.scale.combined(with: .opacity))
            } else if notificationStatus == .denied {
                Button {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Image(systemName: "arrow.up.forward.app")
                        .font(.headline)
                        .padding(10)
                }
                .buttonStyle(TonalButtonStyle())
                .accessibilityLabel(L("ios_open_settings"))
            } else {
                Button(action: requestNotifications) {
                    Image(systemName: "plus")
                        .font(.headline)
                        .padding(10)
                }
                .buttonStyle(TonalButtonStyle())
                .accessibilityLabel(L("ios_allow_notifications"))
            }
        }
        .padding(14)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(accent.primary.opacity(granted ? 0.5 : 0.15), lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onTapGesture { if notificationStatus == .notDetermined { requestNotifications() } }
        .animation(.spring(duration: 0.35), value: notificationStatus)
    }

    private var buttons: some View {
        VStack(spacing: 6) {
            Button {
                data.settings.aboutSeen = true
                onStart()
            } label: {
                Text(L("welcome_start"))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
            }
            .buttonStyle(AccentButtonStyle())

            Button {
                data.settings.aboutSeen = true
                dismiss()
            } label: {
                Text(L("welcome_later"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 24)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(.ultraThinMaterial)
    }

    private func requestNotifications() {
        Task {
            _ = await Notifier.requestPermission()
            notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        }
    }
}
