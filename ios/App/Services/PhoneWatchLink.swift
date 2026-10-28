import Foundation
import WatchConnectivity

/// Coin-Liste der Apple-Watch-App vom iPhone aus (Einstellungen › Widgets › Uhr): Wahl «Wie iPhone»
/// (Basis-Coins der Favoriten ★, höchstens 8) oder «Eigene Liste». Übertragen wird nur `WatchSettings` als JSON im
/// Anwendungskontext von WatchConnectivity (direkt zwischen iPhone und Uhr, kein Server) — keine Kurse, Beträge oder
/// Portfolio-Daten. Gesendet wird nur, wenn eine gekoppelte Uhr die App hat. Gegenstück: `WatchLink` auf der Uhr;
/// Android: `PhoneWatchSync`.
@MainActor
final class PhoneWatchLink: NSObject, ObservableObject, WCSessionDelegate {
    static let shared = PhoneWatchLink()
    private static let storeKey = "watch_sync"
    private nonisolated static let contextKey = "json"

    @Published private(set) var settings: WatchSettings

    override private init() {
        settings = WatchSettings.fromJson(UserDefaults.standard.string(forKey: Self.storeKey)) ?? WatchSettings()
        super.init()
    }

    /// Einmal beim Start der App (lokal, ohne Netz).
    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Favoriten der Merkliste geändert (jede Übernahme in `AppData.snapshot`): Liste «Wie iPhone» nachführen.
    func favoritesChanged(_ watches: [Watch]) {
        let bases = watches.filter(\.favorite).sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }.map(\.baseAsset)
        let phone = WatchCoins.fromFavorites(bases)
        guard phone != settings.phone else { return }
        var next = settings
        next.phone = phone
        update(next, publish: true)
    }

    func setMode(_ mode: WatchSettings.Mode) {
        var next = settings
        next.mode = mode
        update(next.nextRevision(), publish: true)
    }

    func toggleOwn(_ coin: String) {
        var next = settings
        next.own = WatchCoins.toggle(settings.own, coin)
        update(next.nextRevision(), publish: true)
    }

    private func update(_ next: WatchSettings, publish: Bool) {
        guard next != settings else { return }
        settings = next
        UserDefaults.standard.set(next.toJson(), forKey: Self.storeKey)
        if publish { send() }
    }

    private func send() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        try? session.updateApplicationContext([Self.contextKey: settings.toJson()])
    }

    /// Stand der Uhr: Wahl und eigene Liste nur bei höherer Änderungsnummer (Gleichstand: eigene); die iPhone-Liste
    /// bleibt die eigene.
    private func take(_ json: String?) {
        guard let remote = WatchSettings.fromJson(json) else { return }
        update(WatchSettings.merge(settings, remote, remoteIsPhone: false), publish: false)
    }

    // MARK: WCSessionDelegate (Aufrufe auf einem Hintergrund-Thread: Werte auslesen, dann auf den MainActor)

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState,
                             error: Error?) {
        guard activationState == .activated else { return }
        let json = session.receivedApplicationContext[Self.contextKey] as? String
        Task { @MainActor in
            self.take(json)
            self.send()
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let json = applicationContext[Self.contextKey] as? String
        Task { @MainActor in self.take(json) }
    }

    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in self.send() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // Andere Uhr gewählt: neu aktivieren, damit der Stand die neue Uhr erreicht
        session.activate()
    }
}
