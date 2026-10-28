import Combine
import SwiftUI
import UIKit

/// Zeigt die Rückfrage «Merkliste wird nicht gespeichert» (`WatchlistNotSavedGate`) in
/// einem eigenen Fenster über allem — wie `NotificationPrePrompt` und der Sichtschutz in `AppLock`. Vorher hing sie am
/// «obersten registrierten» Bildschirm; unter einem offenen Blatt (Alarm-Editor, Explorer) konnte sie ausbleiben und
/// `pending` hängen. Einmal je App, wie Android `WatchlistNotSavedGateHost` (einmal in der MainActivity).
/// Sicherheitsnetz: Ohne sichtbare Szene bzw. erscheint der Dialog nicht, endet die Frage mit «Abbrechen» (nichts
/// geschrieben) statt offen zu bleiben.
@MainActor
final class WatchlistNotSavedGatePresenter {
    static let shared = WatchlistNotSavedGatePresenter()

    /// So lange darf das Fenster brauchen, bis der Dialog erscheint (ms), sonst «Abbrechen».
    nonisolated static let appearTimeoutMillis: UInt64 = 2_000

    private var window: UIWindow?
    private weak var previousKeyWindow: UIWindow?
    private var observation: AnyCancellable?
    /// Zählt jedes Fenster: ein spätes Sicherheitsnetz eines alten Fensters greift nicht beim neuen.
    private var generation = 0
    private var appeared = false

    private init() {
        // `@Published` meldet vor dem Setzen: erst danach (nächster Lauf auf dem MainActor) den Stand übernehmen
        observation = WatchlistNotSavedGate.shared.$pending.removeDuplicates().sink { _ in
            Task { @MainActor in WatchlistNotSavedGatePresenter.shared.update() }
        }
    }

    /// Kann die App gerade fragen (eine Szene im Vordergrund)? Sonst fragt der Schreibweg nicht (Hintergrund nie).
    var attached: Bool { activeScene != nil }

    private var activeScene: UIWindowScene? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
    }

    private func update() {
        WatchlistNotSavedGate.shared.pending ? show() : hide()
    }

    private func show() {
        guard window == nil else { return }
        guard let scene = activeScene else {
            WatchlistNotSavedGate.shared.answer(proceed: false)
            return
        }
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert
        window.backgroundColor = .clear
        generation &+= 1
        appeared = false
        let shown = generation
        let host = UIHostingController(rootView: WatchlistNotSavedGateContent(
            data: AppData.shared,
            onAppear: { [weak self] in if self?.generation == shown { self?.appeared = true } },
            onAnswer: { proceed in WatchlistNotSavedGate.shared.answer(proceed: proceed) }))
        host.view.backgroundColor = .clear
        window.rootViewController = host
        self.window = window
        window.makeKeyAndVisible()
        // Sicherheitsnetz: erscheint der Dialog nicht, nicht hängen lassen
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.appearTimeoutMillis * 1_000_000)
            guard let self, self.generation == shown, self.window != nil, !self.appeared else { return }
            WatchlistNotSavedGate.shared.answer(proceed: false)
        }
    }

    private func hide() {
        guard let window else { return }
        window.isHidden = true
        self.window = nil
        previousKeyWindow?.makeKey()
    }
}

/// Inhalt des Fensters: nur die Rückfrage (Text, «Abbrechen» / «Trotzdem»), ohne Titel.
@MainActor
private struct WatchlistNotSavedGateContent: View {
    @ObservedObject var data: AppData
    let onAppear: () -> Void
    let onAnswer: (Bool) -> Void
    @State private var presented = false

    var body: some View {
        Color.clear
            .ignoresSafeArea()
            .onAppear {
                presented = true
                onAppear()
            }
            .alert(L("watchlist_not_saved_confirm_message"), isPresented: $presented) {
                Button(L("action_cancel"), role: .cancel) { onAnswer(false) }
                Button(L("watchlist_not_saved_confirm_action")) { onAnswer(true) }
            }
            .tint(data.settings.accentColor.primary)
            .environment(\.appAccent, data.settings.accentColor)
            .highContrastRoot(data.settings.highContrast)
            .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
    }
}
