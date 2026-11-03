import Foundation
import WatchConnectivity

/// Abgleich mit dem iPhone über WatchConnectivity (direkt zwischen iPhone und Uhr, kein Server): je Seite der
/// Anwendungskontext mit `json` = `WatchSettings` (Coin-Kürzel, Wahl, Kursquelle, Intervall); vom iPhone dazu `quotes`
/// (Kurse, die das iPhone schon hat, `WatchPhoneQuotes`) und — nur mit dem Schalter auf dem iPhone — `portfolio`
/// (`WatchPortfolio`). Gegenstück auf dem iPhone: `PhoneWatchLink`. Ohne gekoppeltes iPhone: nichts (die Uhr nutzt ihre
/// eigene Liste und fragt die Kurse selbst ab).
final class WatchLink: NSObject, WCSessionDelegate, Sendable {
    static let shared = WatchLink()
    private static let key = "json"
    private static let quotesKey = "quotes"
    private static let portfolioKey = "portfolio"

    /// iPhone gerade erreichbar? Sonst fragt die Uhr selbst ab (`WatchPhoneQuotes.selfFetch`); ohne Sitzung: nein.
    var phoneReachable: Bool {
        WCSession.isSupported() && WCSession.default.activationState == .activated && WCSession.default.isReachable
    }

    /// Einmal beim Start der App.
    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Hintergrund-Aufgabe von WatchConnectivity: aktivieren und warten, bis ausstehende Inhalte zugestellt sind
    /// (höchstens 5 s); die Übernahme läuft über die Delegate-Aufrufe.
    func receivePending() async {
        activate()
        for _ in 0..<20 {
            guard WCSession.isSupported(), WCSession.default.hasContentPending else { return }
            try? await Task.sleep(for: .milliseconds(250))
        }
    }

    /// Zuletzt empfangener Stand des iPhones (Anwendungskontext) für eigene Änderungen (`WatchSettings.change`); nil =
    /// keiner oder Sitzung nicht aktiv.
    var phoneSettings: WatchSettings? {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return nil }
        return WatchSettings.fromJson(WCSession.default.receivedApplicationContext[Self.key] as? String)
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
        let context = session.receivedApplicationContext
        let json = context[Self.key] as? String
        let quotes = context[Self.quotesKey] as? String
        let portfolio = context[Self.portfolioKey] as? String
        Task { @MainActor in
            WatchModel.shared.takeFromPhone(json, quotes: quotes, portfolio: portfolio)
            // Änderungen vor der Aktivierung gingen nicht weg: den aktuellen Stand jetzt senden (wie `PhoneWatchLink`)
            self.publish(WatchModel.shared.settings)
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let json = applicationContext[Self.key] as? String
        let quotes = applicationContext[Self.quotesKey] as? String
        let portfolio = applicationContext[Self.portfolioKey] as? String
        Task { @MainActor in WatchModel.shared.takeFromPhone(json, quotes: quotes, portfolio: portfolio) }
    }
}
