import LocalAuthentication
import SwiftUI
import UIKit

/// App-Sperre — wie `AppLock.kt`. Gesperrt wird beim Kaltstart und nach mehr als
/// 60 Sekunden im Hintergrund, sofern die Einstellung an ist. Entsperrt wird mit
/// Face ID / Touch ID oder dem Gerätecode (`.deviceOwnerAuthentication`).
/// Widgets und Mitteilungen sind nicht gesperrt (ausser dem Portfolio-Widget).
@MainActor
final class AppLock: ObservableObject {
    static let shared = AppLock()

    /// Länger im Hintergrund → beim Zurückkehren sperren.
    static let graceSeconds: TimeInterval = 60

    @Published private(set) var locked: Bool {
        didSet { updateCoverWindow() }
    }
    @Published private(set) var authenticating = false

    private var backgroundedAt: Date?
    /// Szene aktiv (nicht im App-Umschalter, Kontrollzentrum o. ä.).
    private var sceneActive = true
    /// Eigenes Fenster über allem: Ein Overlay der Hauptansicht verdeckt keine
    /// offenen Blätter (Sheets) — diese lägen sonst über der Sperre bzw. wären im
    /// App-Umschalter sichtbar.
    private var coverWindow: UIWindow?
    /// Beim nächsten Aktivwerden einmal von selbst nach Face ID fragen.
    private var autoPromptPending: Bool

    private init() {
        // Kaltstart der App-Oberfläche: gesperrt, wenn eingeschaltet
        let enabled = SharedStorage.loadSettings().appLock
        autoPromptPending = enabled
        locked = enabled
    }

    private var enabled: Bool { AppData.shared.settings.appLock }

    /// Kann das Gerät überhaupt entsperren (Code, Face ID oder Touch ID eingerichtet)?
    nonisolated static func canAuthenticate() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Einmal entsperren lassen (Face ID / Touch ID, sonst Gerätecode).
    nonisolated static func authenticate() async -> Bool {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return false }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: L("app_lock_reason"))
        } catch {
            return false
        }
    }

    // MARK: Lebenszyklus

    /// Jeder Wechsel der Szenen-Phase (auch `.inactive`, z. B. durch die Face-ID-Abfrage
    /// selbst — die sperrt nicht neu, da nur `.background` die Zeit misst).
    func scenePhaseChanged(_ phase: ScenePhase) {
        sceneActive = phase == .active
        switch phase {
        case .active: didBecomeActive()
        case .background: didEnterBackground()
        default: break
        }
        updateCoverWindow()
    }

    func didEnterBackground() {
        backgroundedAt = Date()
    }

    func didBecomeActive() {
        if let since = backgroundedAt {
            backgroundedAt = nil
            if enabled && !locked && Date().timeIntervalSince(since) > Self.graceSeconds {
                locked = true
                autoPromptPending = true
            }
        }
        // Ausgeschaltet (z. B. durch eine Wiederherstellung): nicht mehr sperren
        if !enabled && locked {
            locked = false
            autoPromptPending = false
        }
        if locked && autoPromptPending {
            autoPromptPending = false
            Task { await unlock() }
        }
    }

    /// Knopf «Entsperren» bzw. automatische Abfrage.
    func unlock() async {
        guard locked, !authenticating else { return }
        // Keine Displaysperre mehr eingerichtet: Sperre wäre nicht aufzuheben
        guard Self.canAuthenticate() else {
            locked = false
            return
        }
        authenticating = true
        let ok = await Self.authenticate()
        authenticating = false
        if ok { locked = false }
    }

    /// Einstellung ausgeschaltet: sofort frei.
    func disabled() {
        locked = false
        autoPromptPending = false
        updateCoverWindow()
    }

    // MARK: Fenster über allem

    /// Gesperrt: Sperransicht; Sperre an und Szene nicht aktiv: Sichtschutz.
    private func updateCoverWindow() {
        let show = locked || (enabled && !sceneActive)
        guard show else {
            coverWindow?.isHidden = true
            return
        }
        if coverWindow == nil {
            guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState != .unattached })
            else { return }
            let window = UIWindow(windowScene: scene)
            window.windowLevel = .alert + 1
            window.backgroundColor = .clear
            let host = UIHostingController(rootView: AppLockWindowContent(lock: self, data: AppData.shared))
            host.view.backgroundColor = .clear
            window.rootViewController = host
            coverWindow = window
        }
        // Gesperrt: offene Tastatur (eigenes Fenster) schliessen, damit sie nicht über der Sperre liegt
        if locked {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        coverWindow?.isHidden = false
    }
}

/// Inhalt des Sperr-Fensters: dieselben Ansichten wie in der Hauptansicht.
@MainActor
private struct AppLockWindowContent: View {
    @ObservedObject var lock: AppLock
    @ObservedObject var data: AppData

    var body: some View {
        Group {
            if lock.locked {
                AppLockView(lock: lock)
            } else {
                AppPrivacyCover()
            }
        }
        .environment(\.appAccent, data.settings.accentColor)
        .highContrastRoot(data.settings.highContrast)
        .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
    }
}

// MARK: Ansichten

/// Vollbild über der App, solange gesperrt: Logo, «Crypto Checker ist gesperrt», «Entsperren».
@MainActor
struct AppLockView: View {
    @ObservedObject var lock: AppLock
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            WatchlistLogo(size: 72)
                .padding(18)
                .background(accent.container.opacity(0.5), in: Circle())
                .accessibilityHidden(true)
            Text(L("app_lock_title"))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Button {
                Task { await lock.unlock() }
            } label: {
                Label(L("app_lock_unlock"), systemImage: "lock.open.fill")
                    .font(.headline)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(lock.authenticating)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.background.ignoresSafeArea())
    }
}

/// Sichtschutz im App-Umschalter (Szene nicht aktiv), solange die Sperre an ist.
@MainActor
struct AppPrivacyCover: View {
    @Environment(\.appAccent) private var accent

    var body: some View {
        WatchlistLogo(size: 72)
            .padding(18)
            .background(accent.container.opacity(0.5), in: Circle())
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AppColors.background.ignoresSafeArea())
            .accessibilityHidden(true)
    }
}
