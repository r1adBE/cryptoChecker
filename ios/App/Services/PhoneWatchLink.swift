import Foundation
import WatchConnectivity

/// Apple-Watch-App vom iPhone aus: Coin-Liste (Einstellungen › Widgets › Uhr: «Wie iPhone» = Basis-Coins der
/// Favoriten ★, höchstens 8, je mit dem Paar des Favoriten, oder «Eigene Liste»), Kursquelle der Uhr (`WatchSettings.source`) und Intervall der
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
    /// Paare der Merkliste, aus denen `quotesJson` entsteht — neu gewählt bei jeder anderen Liste.
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
        let (phone, pairs) = Self.favorites(watches)
        if phone != settings.phone || pairs != settings.pairs {
            var next = settings
            next.phone = phone
            next.pairs = pairs
            update(next, publish: true)
        }
        quoteRows = Self.quoteRows(watches)
        refreshQuotes()
    }

    /// Kurse für beide Listen der Uhr neu wählen; geändert → senden (höchstens einmal je `minGap`).
    private func refreshQuotes() {
        let quotes = WatchPhoneQuotes.toJson(WatchPhoneQuotes.pick(quoteRows, coins: settings.kept))
        guard quotes != quotesJson else { return }
        quotesJson = quotes
        sendSoon()
    }

    func setMode(_ mode: WatchSettings.Mode) {
        change { $0.mode = mode }
    }

    func toggleOwn(_ coin: String) {
        change { $0.own = WatchCoins.toggle($0.own, coin) }
    }

    /// Eigene Änderung von Wahl oder eigener Liste: zuerst den zuletzt empfangenen Stand der Uhr zusammenführen, dann
    /// mit max(iPhone, Uhr) + 1 speichern und senden (`WatchSettings.change`) — sonst bliebe die Uhr bei ihrer Wahl, wenn
    /// sie schon weiter gezählt hatte. Wie Android `PhoneWatchSync.change`.
    private func change(_ edit: (inout WatchSettings) -> Void) {
        update(WatchSettings.change(settings, remote: Self.watchSettings(), isPhone: true, edit), publish: true)
    }

    /// Zuletzt empfangener Stand der Uhr (Anwendungskontext); nil = keiner oder Sitzung nicht aktiv.
    private static func watchSettings() -> WatchSettings? {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated else { return nil }
        return WatchSettings.fromJson(WCSession.default.receivedApplicationContext[contextKey] as? String)
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

    /// Paare der Merkliste, aus denen die Uhr Kurse bekommen kann (eigene Liste: gegen USDT, Spot; «Wie iPhone»: genau
    /// das Paar des Favoriten, auch Futures/Perp und andere Börsen); gesendet werden sie nur bei «Kurse vom Handy, sonst
    /// selbst» (`send`). Wie Android `PhoneWatchFeed.quotesJson`.
    static func quoteRows(_ watches: [Watch]) -> [WatchPhoneQuotes.Row] {
        watches.map { w -> WatchPhoneQuotes.Row in
            let contract = watchContract(w)
            // Spot = kein Kontrakt und kein DexScreener-Pool; die Börsen-Kennung («BTCUSDT») ändert daran nichts
            return WatchPhoneQuotes.Row(base: w.baseAsset, quote: w.quoteAsset, spot: w.contractType == .none && contract.isEmpty,
                                        // Börse stillgelegt (`ExchangeRetirement`): kein alter Kurs an die Uhr
                                        disabled: w.disabled,
                                        price: ExchangeRetirement.isMarker(w.lastError) ? nil : w.lastPrice,
                                        change24h: w.change24h,
                                        exchange: w.exchangeName, time: w.lastUpdate, sortOrder: w.sortOrder,
                                        contract: contract)
        }
    }

    /// Kontrakt-Marke der Uhr (`WatchPair.contract(short:pool:)`): Kurzform des Kontrakts, Pool nur bei DexScreener
    /// (`WatchIdentity.pool`, mit der Kennung des Pools), sonst Spot. Wie Android `PhoneWatchFeed.watchContract`.
    static func watchContract(_ w: Watch) -> String {
        WatchPair.contract(short: w.contractShortName, pool: WatchIdentity.pool(exchangeKey: w.exchangeKey, pairId: w.pairId))
    }

    /// «Wie iPhone»: Basis-Coins der Favoriten (`WatchCoins.fromFavorites`) und je Coin das Paar des ersten Favoriten
    /// (`WatchPair`: Börse und Kontrakt wie in der Merkliste, z. B. BANK/USDT Perp bei Binance Futures). Wie Android
    /// `PhoneWatchSync.favorites`.
    static func favorites(_ watches: [Watch]) -> (phone: [String], pairs: [WatchPair]) {
        let favorites = watches.filter(\.favorite).sorted { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }
        let bases = favorites.map(\.baseAsset)
        let pairs = favorites.map {
            WatchPair.of(base: $0.baseAsset, quote: $0.quoteAsset, exchange: $0.exchangeName,
                         contract: watchContract($0))
        }
        return (WatchCoins.fromFavorites(bases), WatchCoins.favoritePairs(bases, pairs: pairs))
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
