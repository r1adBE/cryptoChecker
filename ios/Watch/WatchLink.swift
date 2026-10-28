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

    /// Eigenen Stand ans iPhone geben (dort gewinnt die höhere Änderungsnummer). Vor der Aktivierung geht nichts weg;
    /// `activationDidComplete` sendet den dann aktuellen Stand nach.
    func publish(_ settings: WatchSettings) {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return }
        try? WCSession.default.updateApplicationContext([Self.key: settings.toJson()])
    }

    // MARK: WCSessionDelegate (Aufrufe auf einem Hintergrund-Thread: nur das JSON auslesen; Zusammenführen und
    // Speichern auf dem MainActor in `WatchModel`, wo auch die Änderungen auf der Uhr laufen)

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {
        guard activationState == .activated else { return }
        let json = session.receivedApplicationContext[Self.key] as? String
        Task { @MainActor in
            WatchModel.shared.takeFromPhone(json)
            // Änderungen vor der Aktivierung gingen nicht weg: den aktuellen Stand jetzt senden (wie `PhoneWatchLink`)
            self.publish(WatchModel.shared.settings)
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let json = applicationContext[Self.key] as? String
        Task { @MainActor in WatchModel.shared.takeFromPhone(json) }
    }
}
