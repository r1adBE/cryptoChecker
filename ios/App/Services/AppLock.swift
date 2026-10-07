import LocalAuthentication
import SwiftUI
import UIKit

/// Portfolio-Sperre — wie `AppLock.kt` / `PortfolioLockPolicy`. Gesperrt ist nur das Portfolio
/// (Tab, Detailansicht, Erfassen-Blätter, Stichtag-Export, Sicherung mit Portfolio-Daten,
/// Portfolio-Widget); die übrige App ist nie gesperrt. Gesperrt wird beim Kaltstart und nach
/// mehr als 60 Sekunden im Hintergrund, sofern die Einstellung an ist. Entsperrt wird einmal je
/// Sitzung mit Face ID / Touch ID oder dem Gerätecode (`.deviceOwnerAuthentication`).
@MainActor
final class AppLock: ObservableObject {
    static let shared = AppLock()

    /// Portfolio gerade gesperrt (Einstellung an und in dieser Sitzung nicht entsperrt)?
    @Published private(set) var locked: Bool {
        didSet { updateCoverWindow() }
    }
    @Published private(set) var authenticating = false

    private var backgroundedAt: Date?
    /// Szene aktiv (nicht im App-Umschalter, Kontrollzentrum o. ä.).
    private(set) var sceneActive = true
    /// Eigenes Fenster über allem für den Sichtschutz im App-Umschalter: Ein Overlay der
    /// Hauptansicht verdeckt keine offenen Blätter (Sheets).
    private var coverWindow: UIWindow?

    private init() {
        // Kaltstart der App-Oberfläche: Portfolio gesperrt, wenn eingeschaltet
        locked = SharedStorage.loadSettings().appLock
    }

    private var enabled: Bool { AppData.shared.settings.appLock }

    /// Zustand des Portfolio-Bereichs (die Einstellungen sind auf iOS immer schon gelesen).
    var access: PortfolioAccess {
        PortfolioLockPolicy.access(lockSetting: enabled, lockRequested: locked)
    }

    /// Kann das Gerät überhaupt entsperren (Code, Face ID oder Touch ID eingerichtet)?
    nonisolated static func canAuthenticate() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Einmal entsperren lassen (Face ID / Touch ID, sonst Gerätecode).
    nonisolated static func authenticate() async -> Bool {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return false }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: L("portfolio_lock_reason"))
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
        let since = backgroundedAt
        backgroundedAt = nil
        if enabled && !locked && PortfolioLockPolicy.relockAfterBackground(backgroundSince: since, now: Date()) {
            locked = true
        }
        // Ausgeschaltet (z. B. durch eine Wiederherstellung): nicht mehr sperren
        if !enabled && locked { locked = false }
    }

    /// Knopf «Entsperren» bzw. automatische Abfrage beim Öffnen des Portfolio-Tabs.
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

    /// Vor einer Portfolio-Aktion ausserhalb des Tabs: true sofort, wenn `needsUnlock` für den
    /// aktuellen Zustand false ist; sonst nach der Abfrage, ob entsperrt wurde.
    func requireUnlock(_ needsUnlock: (Bool) -> Bool) async -> Bool {
        guard needsUnlock(PortfolioLockPolicy.isLocked(lockSetting: enabled, lockRequested: locked)) else { return true }
        await unlock()
        return !locked
    }

    /// Einstellung ausgeschaltet: sofort frei.
    func disabled() {
        locked = false
        updateCoverWindow()
    }

    // MARK: Fenster über allem

    /// Sperre an und Szene nicht aktiv: Sichtschutz (App-Umschalter, auch über offenen Blättern).
    private func updateCoverWindow() {
        let show = enabled && !sceneActive
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
            let host = UIHostingController(rootView: AppLockWindowContent(data: AppData.shared))
            host.view.backgroundColor = .clear
            window.rootViewController = host
            coverWindow = window
        }
        coverWindow?.isHidden = false
    }
}

/// Inhalt des Sichtschutz-Fensters.
@MainActor
private struct AppLockWindowContent: View {
    @ObservedObject var data: AppData

    var body: some View {
        AppPrivacyCover()
            .environment(\.appAccent, data.settings.accentColor)
            .highContrastRoot(data.settings.highContrast)
            .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
    }
}

// MARK: Ansichten

/// Ruhiger Sperr-Zustand im Portfolio-Tab: Schloss, kurzer Text, «Entsperren» — keine Beträge,
/// keine Coins. Fragt beim Erscheinen (Szene aktiv) einmal von selbst nach; danach per Knopf.
@MainActor
struct PortfolioLockedView: View {
    @ObservedObject var lock: AppLock
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "lock.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(accent.primary)
                .frame(width: 72, height: 72)
                .background(accent.container.opacity(0.5), in: Circle())
                .accessibilityHidden(true)
            Text(L("portfolio_locked_title"))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(L("portfolio_locked_text"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
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
            .padding(.top, 6)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("portfolio_title"))
        .onAppear {
            guard lock.sceneActive else { return }
            Task { await lock.unlock() }
        }
    }
}

/// Sichtschutz im App-Umschalter (Szene nicht aktiv), solange die Portfolio-Sperre an ist.
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
