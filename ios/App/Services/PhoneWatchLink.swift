import Foundation
import WatchConnectivity

/// Apple-Watch-App vom iPhone aus: Coin-Liste (Einstellungen › Widgets › Uhr: «Wie iPhone» = Basis-Coins der
/// Favoriten ★, höchstens 8, oder «Eigene Liste»), Kursquelle der Uhr (`WatchSettings.source`) und Intervall der
/// Kurs-Aktualisierung (`WatchSettings.interval`, `WatchInterval.clamp`). Dazu im selben Anwendungskontext von
/// WatchConnectivity (direkt zwischen iPhone und Uhr, kein Server):
///  - `quotes`: für beide Listen der Uhr die Kurse, die die Merkliste schon hat (`WatchPhoneQuotes.pick`) — keine eigene
///    Abfrage für die Uhr; höchstens einmal je `minGap` (Live-Kurse ändern sich laufend); nicht bei «Immer selbst abrufen».
///  - `portfolio`: nur mit «Portfolio-Wert auf der Uhr» (Standard aus) Gesamtwert, Veränderung heute, Währung und Zeit
///    (`WatchPortfolio`); verborgen bzw. gesperrt ohne Zahlen; Schalter oder Portfolio aus → Löschen.
/// Gesendet wird nur, wenn eine gekoppelte Uhr die App hat. Gegenstück: `WatchLink` auf der Uhr; Android:
/// `PhoneWatchSync` und `PhoneWatchFeed`.
@MainActor
final class PhoneWatchLink: NSObject, ObservableObject, WCSessionDelegate {
    static let shared = PhoneWatchLink()
    private static let storeKey = "watch_sync"
    private static let portfolioKey = "watch_portfolio_on_watch"
    private nonisolated static let contextKey = "json"
    private nonisolated static let quotesKey = "quotes"
    private nonisolated static let portfolioContextKey = "portfolio"
    /// Kurse höchstens so oft (Sekunden).
    private static let minGap: TimeInterval = 60

    @Published private(set) var settings: WatchSettings
    /// Schalter «Portfolio-Wert auf der Uhr» (Einstellungen › Portfolio).
    @Published private(set) var portfolioOnWatch: Bool
    /// Zuletzt berechnete Kurse bzw. Portfolio-Stand für den Anwendungskontext (jede Sendung trägt alles).
    private var quotesJson: String?
    /// Paare der Merkliste gegen USDT (Spot), aus denen `quotesJson` entsteht — neu gewählt bei jeder anderen Liste.
    private var quoteRows: [WatchPhoneQuotes.Row] = []
    private var portfolioJson: String?
    private var lastSent = Date.distantPast
    private var pendingSend: Task<Void, Never>?

    override private init() {
        settings = WatchSettings.fromJson(UserDefaults.standard.string(forKey: Self.storeKey)) ?? WatchSettings()
        portfolioOnWatch = UserDefaults.standard.bool(forKey: Self.portfolioKey)
        super.init()
    }

    /// Einmal beim Start der App (lokal, ohne Netz).
    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
        intervalChanged(SharedStorage.loadSettings().backgroundIntervalMinutes)
        portfolioChanged()
        // Neue Momentaufnahme des Portfolios (App, Hintergrund): Wert an die Uhr, falls eingeschaltet
        _ = NotificationCenter.default.addObserver(forName: PortfolioWidgetStore.savedNotification, object: nil,
                                               queue: .main) { _ in
            Task { @MainActor in PhoneWatchLink.shared.portfolioChanged() }
        }
    }

    /// Merkliste geändert (jede Übernahme in `AppData.snapshot`): Liste «Wie iPhone» und Kurse nachführen.
    func favoritesChanged(_ watches: [Watch]) {
        let bases = watches.filter(\.favorite).sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }.map(\.baseAsset)
        let phone = WatchCoins.fromFavorites(bases)
        if phone != settings.phone {
            var next = settings
            next.phone = phone
            update(next, publish: true)
        }
        quoteRows = Self.quoteRows(watches)
        refreshQuotes()
    }

    /// Kurse für beide Listen der Uhr neu wählen; geändert → senden (höchstens einmal je `minGap`).
    private func refreshQuotes() {
        let quotes = WatchPhoneQuotes.toJson(WatchPhoneQuotes.pick(quoteRows, coins: settings.own + settings.phone))
        guard quotes != quotesJson else { return }
        quotesJson = quotes
        sendSoon()
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

    /// «Kurse vom Handy, sonst selbst» / «Immer selbst abrufen» — bestimmt nur das iPhone (keine Änderungsnummer).
    func setSource(_ source: WatchSettings.Source) {
        var next = settings
        next.source = source
        update(next, publish: true)
    }

    /// Intervall der Kurs-Aktualisierung geändert: die Uhr lädt im selben Takt (begrenzt, `WatchInterval`).
    func intervalChanged(_ minutes: Int) {
        var next = settings
        next.interval = WatchInterval.clamp(minutes)
        update(next, publish: true)
    }

    func setPortfolioOnWatch(_ on: Bool) {
        portfolioOnWatch = on
        UserDefaults.standard.set(on, forKey: Self.portfolioKey)
        portfolioChanged()
    }

    /// Portfolio-Stand für die Uhr neu bestimmen (Schalter, Einstellungen oder neue Aufnahme); `appSettings` = gerade
    /// geänderte Einstellungen, sonst gelesen (nicht lesbar zählt als gesperrt).
    func portfolioChanged(_ appSettings: AppSettings? = nil) {
        let loaded = appSettings == nil ? SharedStorage.loadSettingsChecked() : nil
        guard let s = appSettings ?? loaded?.settings else { return }
        let locked = PortfolioLockPolicy.widgetLocked(lockSetting: loaded?.appLockAssumed ?? s.appLock)
        let snapshot = portfolioOnWatch && s.portfolioEnabled ? PortfolioWidgetStore.load() : nil
        guard let message = WatchPortfolio.message(
            onWatch: portfolioOnWatch, portfolioEnabled: s.portfolioEnabled, hidden: s.hidePortfolioAmounts,
            locked: locked, total: snapshot?.total, changePercent: snapshot?.changePercent,
            currency: snapshot?.currency, time: snapshot?.updatedAt ?? 0) else { return }
        let json = WatchPortfolio.toJson(message)
        guard json != portfolioJson else { return }
        portfolioJson = json
        send()
    }

    private func update(_ next: WatchSettings, publish: Bool) {
        guard next != settings else { return }
        settings = next
        UserDefaults.standard.set(next.toJson(), forKey: Self.storeKey)
        if publish { send() }
        // Andere Listen: andere Coins für die Kurse
        refreshQuotes()
    }

    /// Nur Kurse geändert: sofort, wenn die letzte Sendung `minGap` her ist, sonst einmal danach.
    private func sendSoon() {
        let wait = Self.minGap - Date().timeIntervalSince(lastSent)
        guard wait > 0 else { return send() }
        guard pendingSend == nil else { return }
        pendingSend = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard let self, !Task.isCancelled else { return }
            self.send()
        }
    }

    private func send() {
        pendingSend?.cancel()
        pendingSend = nil
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        var context: [String: Any] = [Self.contextKey: settings.toJson()]
        if settings.source == .phone, let quotesJson { context[Self.quotesKey] = quotesJson }
        if let portfolioJson { context[Self.portfolioContextKey] = portfolioJson }
        try? session.updateApplicationContext(context)
        lastSent = Date()
    }

    /// Paare der Merkliste, aus denen die Uhr Kurse bekommen kann (gegen USDT, Spot); gesendet werden sie nur bei «Kurse
    /// vom Handy, sonst selbst» (`send`). Wie Android `PhoneWatchFeed.quotesJson`.
    static func quoteRows(_ watches: [Watch]) -> [WatchPhoneQuotes.Row] {
        watches.filter { $0.quoteAsset.uppercased() == WatchPhoneQuotes.quoteAsset }.map {
            WatchPhoneQuotes.Row(base: $0.baseAsset, quote: $0.quoteAsset, spot: $0.contractType == .none && $0.pairId == nil,
                                 disabled: $0.disabled, price: $0.lastPrice, change24h: $0.change24h,
                                 exchange: $0.exchangeName, time: $0.lastUpdate, sortOrder: $0.sortOrder)
        }
    }

    /// Stand der Uhr: Wahl und eigene Liste nur bei höherer Änderungsnummer (Gleichstand: eigene); iPhone-Liste,
    /// Kursquelle und Intervall bleiben die eigenen.
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
