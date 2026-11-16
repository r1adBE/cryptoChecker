import Foundation
import os
import WatchConnectivity

/// Abgleich mit dem iPhone über WatchConnectivity (direkt zwischen iPhone und Uhr, kein Server): je Seite der
/// Anwendungskontext mit `json` = `WatchSettings` (Coin-Kürzel, Wahl, Kursquelle, Intervall); vom iPhone dazu `quotes`
/// (Kurse, die das iPhone schon hat, `WatchPhoneQuotes`) und — nur mit dem Schalter auf dem iPhone — `portfolio`
/// (`WatchPortfolio`). Gegenstück auf dem iPhone: `PhoneWatchLink`. Ohne gekoppeltes iPhone: nichts (die Uhr nutzt ihre
/// eigene Liste und fragt die Kurse selbst ab). Ist die iPhone-App nicht (mehr) installiert, vergisst die Uhr, was vom
/// iPhone stammt (`WatchModel.forgetPhone`) — wie Wear OS bei gelöschten Einträgen. Dazu im eigenen
/// Kontext `usage`: die Zähler der Uhr für «Akku & Aktivität» (`UsageLog.encodeReport`, nur Zahlen und Zeit).
final class WatchLink: NSObject, WCSessionDelegate, Sendable {
    static let shared = WatchLink()
    private static let key = "json"
    private static let quotesKey = "quotes"
    private static let portfolioKey = "portfolio"
    private static let usageKey = "usage"
    /// Bitte des iPhones «Zähler jetzt schicken» (gleich in `WatchUsageStore.requestKey`).
    private static let usageRequestKey = "usageRequest"
    /// Zähler höchstens so oft eigens ans iPhone (wie Wear OS `WatchUsage.PUBLISH_GAP_MILLIS`).
    private static let usageGapMillis: Int64 = 30 * 60_000
    private static let usageSentAt = OSAllocatedUnfairLock(initialState: Int64(0))

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
        // Uhr ohnehin wach: eigene Zähler mitgeben (höchstens alle 30 Min.)
        defer { Task { @MainActor in self.publishUsageIfDue() } }
        for _ in 0..<20 {
            guard WCSession.isSupported(), WCSession.default.hasContentPending else { return }
            try? await Task.sleep(for: .milliseconds(250))
        }
    }

    /// Zähler («Akku & Aktivität») mit dem aktuellen Stand ans iPhone, wenn die letzte Sendung `usageGapMillis` her ist.
    @MainActor
    func publishUsageIfDue() {
        let now = WatchStore.now()
        let due = Self.usageSentAt.withLock { sent -> Bool in
            guard now - sent >= Self.usageGapMillis else { return false }
            sent = now
            return true
        }
        if due { publish(WatchModel.shared.settings) }
    }

    /// Zuletzt empfangener Stand des iPhones (Anwendungskontext) für eigene Änderungen (`WatchSettings.change`); nil =
    /// keiner oder Sitzung nicht aktiv.
    var phoneSettings: WatchSettings? {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return nil }
        return WatchSettings.fromJson(WCSession.default.receivedApplicationContext[Self.key] as? String)
    }

    /// Eigenen Stand ans iPhone geben (dort gewinnt die höhere Änderungsnummer). Vor der Aktivierung geht nichts weg;
    /// `activationDidComplete` sendet den dann aktuellen Stand nach. Scheitert das Ablegen, nach `retryDelay` noch
    /// einmal mit dem dann aktuellen Stand (höchstens `maxRetries` Mal in Folge) – wie das iPhone (`PhoneWatchLink`).
    func publish(_ settings: WatchSettings, attempt: Int = 0) {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return }
        // Jeder Kontext ersetzt den vorigen: Wahl und Zähler der letzten 24 h immer zusammen
        let usage = UsageLog.encodeReport(UsageReport(at: WatchStore.now(), totals: UsageMeter.shared.totals()))
        do {
            try WCSession.default.updateApplicationContext([Self.key: settings.toJson(), Self.usageKey: usage])
        } catch {
            let nsError = error as NSError
            WatchLog.link.error("Stand nicht ans iPhone: \(nsError.domain, privacy: .public) \(nsError.code, privacy: .public)")
            guard attempt < Self.maxRetries else { return }
            Task { @MainActor in
                try? await Task.sleep(for: Self.retryDelay)
                self.publish(WatchModel.shared.settings, attempt: attempt + 1)
            }
        }
    }

    private static let maxRetries = 3
    private static let retryDelay: Duration = .seconds(30)

    // MARK: WCSessionDelegate (Aufrufe auf einem Hintergrund-Thread: nur das JSON auslesen; Zusammenführen und
    // Speichern auf dem MainActor in `WatchModel`, wo auch die Änderungen auf der Uhr laufen)

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState,
                 error: Error?) {
        guard activationState == .activated else { return }
        let context = session.receivedApplicationContext
        let json = context[Self.key] as? String
        let quotes = context[Self.quotesKey] as? String
        let portfolio = context[Self.portfolioKey] as? String
        let companion = session.isCompanionAppInstalled
        Task { @MainActor in
            if companion {
                WatchModel.shared.takeFromPhone(json, quotes: quotes, portfolio: portfolio)
            } else {
                WatchModel.shared.forgetPhone()
            }
            // Änderungen vor der Aktivierung gingen nicht weg: den aktuellen Stand jetzt senden (wie `PhoneWatchLink`)
            self.publish(WatchModel.shared.settings)
        }
    }

    /// iPhone-App entfernt (oder neu installiert): ohne App vergessen, was vom iPhone stammt.
    func sessionCompanionAppInstalledDidChange(_ session: WCSession) {
        guard !session.isCompanionAppInstalled else { return }
        Task { @MainActor in WatchModel.shared.forgetPhone() }
    }

    /// Bitte des iPhones (Seite «Akku & Aktivität»): Zähler gleich als Antwort — wie Wear OS
    /// `WatchDataListener.onMessageReceived`.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[Self.usageRequestKey] as? Bool == true else {
            replyHandler([:])
            return
        }
        replyHandler([Self.usageKey: UsageLog.encodeReport(UsageReport(at: WatchStore.now(),
                                                                      totals: UsageMeter.shared.totals()))])
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let json = applicationContext[Self.key] as? String
        let quotes = applicationContext[Self.quotesKey] as? String
        let portfolio = applicationContext[Self.portfolioKey] as? String
        Task { @MainActor in WatchModel.shared.takeFromPhone(json, quotes: quotes, portfolio: portfolio) }
    }
}
