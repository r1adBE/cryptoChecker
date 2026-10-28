import Foundation
import WatchConnectivity

/// Abgleich der Wahl mit dem iPhone über WatchConnectivity (direkt zwischen iPhone und Uhr, kein Server):
/// je Seite der Anwendungskontext `["json": WatchSettings]` — nur Coin-Kürzel und Wahl, keine Kurse oder Beträge.
/// Gegenstück auf dem iPhone: `PhoneWatchLink`. Ohne gekoppeltes iPhone: nichts (die Uhr nutzt ihre eigene Liste).
final class WatchLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchLink()
    private static let key = "json"

    /// Einmal beim Start der App.
    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Eigene Änderung ans iPhone geben (dort gewinnt die höhere Änderungsnummer).
    func publish(_ settings: WatchSettings) {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return }
        try? WCSession.default.updateApplicationContext([Self.key: settings.toJson()])
    }

    private func take(_ context: [String: Any]) {
        guard let remote = WatchSettings.fromJson(context[Self.key] as? String) else { return }
        let merged = WatchSettings.merge(WatchStore.settings, remote, remoteIsPhone: true)
        guard merged != WatchStore.settings else { return }
        WatchStore.settings = merged
        Task { @MainActor in
            WatchModel.shared.reloadFromStore()
            WatchComplications.reload()
        }
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {
        if activationState == .activated { take(session.receivedApplicationContext) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        take(applicationContext)
    }
}
