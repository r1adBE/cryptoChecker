import Foundation
import SwiftUI

/// Ladezustand eines Bereichs im Markt-Tab (Android: `LoadState` / `MarketState`).
enum CycleLoad<Value> {
    case loading
    case loaded(Value)
    case failed

    var value: Value? {
        if case .loaded(let v) = self { return v }
        return nil
    }

    var isLoading: Bool {
        if case .loading = self { return true }
        return false
    }

    /// Nur die Art des Zustands (0 lädt, 1 geladen, 2 gescheitert) — als Auslöser für Animationen.
    var phase: Int {
        switch self {
        case .loading: 0
        case .loaded: 1
        case .failed: 2
        }
    }
}

/// Daten des Markt-Tabs — wie `InfoViewModel.kt`.
///
/// Sofort-Anzeige: Jeder Bereich liegt mit Zeitstempel auf der Platte (`CycleCache`).
/// Beim Öffnen erscheint der gespeicherte Stand sofort (ohne Kreisel); neu geladen
/// wird nur, was älter als seine Zeitgrenze ist (`CycleCachePolicy`), und das still
/// im Hintergrund. Ziehen nach unten lädt alles. Jede Quelle hat eine harte
/// Zeitgrenze und läuft unabhängig — eine langsame hält die anderen nicht auf.
///
/// Lebt in `RootView` (nicht im Tab), damit ein Tab-Wechsel nichts neu lädt.
@MainActor
final class CycleViewModel: ObservableObject {

    /// Zyklus-Modell (Top-/Bottom-Score) für Bitcoin.
    @Published private(set) var market: CycleLoad<CycleReport> = .loading
    @Published private(set) var fearGreed: CycleLoad<FearGreed> = .loading
    @Published private(set) var dominance: CycleLoad<Dominance> = .loading
    /// Gesamtmarkt (Marktkapitalisierung, Volumen) — aus derselben CoinGecko-Abfrage wie die Dominanz.
    @Published private(set) var globalMarket: CycleLoad<GlobalMarket> = .loading
    @Published private(set) var altSeason: CycleLoad<AltSeason> = .loading
    @Published private(set) var history: CycleLoad<CycleHistory> = .loading
    @Published private(set) var coin: CycleLoad<CoinReport> = .loading
    /// Coins mit USDT-Paar auf Binance (sonst eine kurze Standardliste).
    @Published private(set) var coins: [String] = CycleViewModel.fallbackCoins
    @Published private(set) var selectedCoin: String = "ETH"
    @Published private(set) var favoriteCoins: Set<String> = []
    /// Netzwerkgebühren (#167).
    @Published private(set) var gas: CycleLoad<GasReport> = .loading
    /// «Crypto Pulse»: 24-h-Veränderungen, Volumen-Verhältnis, Funding.
    @Published private(set) var pulse: CycleLoad<PulseMarketData> = .loading
    /// «Heute auffällig» (marktweit ungewöhnliche Bewegungen).
    @Published private(set) var unusual: CycleLoad<UnusualReport> = .loading
    /// Termine wichtiger US-Wirtschaftsdaten (Hinweis über dem Pulse); die Ansicht wählt, was heute gilt.
    @Published private(set) var macroEvents: [MacroEvent] = []

    /// Halving-Phase: rein nach Kalender, auch ohne Internet verfügbar.
    @Published private(set) var cycle: CycleInfo = BitcoinCycle.info()

    /// Stand (Epoch-ms) der gezeigten Daten je Bereich.
    @Published private(set) var stamps: [String: Int64] = [:]
    /// Anbieter der gezeigten Daten je Bereich (z. B. «CoinGecko»), sofern bekannt.
    @Published private(set) var providers: [String: String] = [:]
    /// Bereiche, die gerade neu laden.
    @Published private(set) var refreshingKeys: Set<String> = []

    /// Wie viele Teile des Tabs (von oben, `CycleRevealSlot`) sichtbar sind — einmal je
    /// App-Sitzung schrittweise (`CycleReveal`), danach bleibt alles stehen.
    @Published private(set) var revealed = 0
    /// In dieser App-Sitzung gewählter Zustand je Abschnitt («Einordnung», «Daten»); fehlt = nie
    /// getippt (`MarketSections`). Lebt hier, damit er Tab-Wechsel übersteht.
    @Published var sectionChoice: [MarketSection: Bool] = [:]
    /// Die ersten so vielen Teile erscheinen ohne Animation (beim Öffnen schon bereit).
    private(set) var revealInstant = 0
    private var revealStart: Int64?
    private var revealReadyAt = [Int64?](repeating: nil, count: CycleReveal.count)
    private var revealTask: Task<Void, Never>?

    private var tasks: [String: Task<Void, Never>] = [:]
    /// Je Bereich hochgezählt; nur die jüngste Abfrage darf ihr Ergebnis setzen.
    private var generations: [String: Int] = [:]
    /// Coin-Liste nur einmal je Sitzung prüfen (der Abruf ist mehrere MB gross).
    private var coinListRequested = false

    static let fallbackCoins: [String] = [
        "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
        "TON", "LTC", "BCH", "UNI", "NEAR", "APT", "ETC", "XLM", "SUI", "PEPE",
    ].sorted()

    private static let coinListKey = "cycle_coin_list"
    private static let coinListDateKey = "cycle_coin_list_at"
    /// Binance-Paarliste höchstens einmal pro Woche neu laden (mehrere MB).
    private static let coinListMaxAge: TimeInterval = 7 * 24 * 3600

    // Schlüssel der Bereiche (Dateinamen im Zwischenspeicher)
    private static let pulseKey = "pulse"
    private static let unusualKey = "unusual"
    /// «Heute auffällig» 10 Min. (wie `CycleSource.UNUSUAL` in Android).
    private static let unusualTTL: Int64 = 10 * CycleCachePolicy.minute
    private static let marketKey = "market"
    private static let fearGreedKey = "fng"
    private static let globalKey = "global"
    private static let altSeasonKey = "altseason"
    private static let historyKey = "history"
    private static let gasKey = "gas"
    private static func coinKey(_ symbol: String) -> String { "coin_" + symbol }

    /// Harte Zeitgrenze je Quelle (alle Teilanfragen zusammen). Grosse bzw.
    /// mehrteilige Abfragen etwas länger — sie sind dafür lange zwischengespeichert.
    private static let timeout: Double = 12
    private static let longTimeout: Double = 25

    init() {}

    /// Ältester gezeigter Stand, solange ein Bereich mit gezeigten Daten neu lädt; sonst nil.
    var refreshingSince: Int64? {
        let shown = [Self.pulseKey, Self.unusualKey, Self.marketKey, Self.fearGreedKey, Self.globalKey, Self.altSeasonKey,
                     Self.historyKey, Self.coinKey(selectedCoin), Self.gasKey]
        return CycleCachePolicy.asOf(stamps: stamps, shown: shown, refreshing: refreshingKeys)
    }

    /// Beim Erscheinen des Tabs: Favoriten auffrischen, Gespeichertes sofort zeigen und
    /// nur Abgelaufenes neu laden (auch nach einem Tab-Wechsel).
    func onAppear() {
        favoriteCoins = SharedStorage.favorites(.COIN)
        cycle = BitcoinCycle.info()
        loadAll(force: false)
        if !coinListRequested {
            coinListRequested = true
            loadCoinList()
        }
        // Gespeichertes liegt jetzt vor (synchron gelesen): Abfolge einmal je Sitzung starten
        startReveal()
    }

    // MARK: Schrittweises Erscheinen

    /// Je Teil: Daten bereit (geladen, gescheitert oder aus dem Zwischenspeicher)?
    private var revealReadiness: [Bool] {
        CycleRevealSlot.allCases.map { slot in
            switch slot {
            case .pulse: !pulse.isLoading
            case .unusual: !unusual.isLoading
            case .fearGreed: !fearGreed.isLoading
            case .marketTotals: !globalMarket.isLoading
            case .phase: !market.isLoading
            case .dominance: !dominance.isLoading && !altSeason.isLoading
            case .halving: !history.isLoading
            case .coin: !coin.isLoading
            case .gas: !gas.isLoading
            case .headerContext, .headerData: true
            }
        }
    }

    private static var monotonicMillis: Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    private func startReveal() {
        guard revealStart == nil else { return }
        let start = Self.monotonicMillis
        revealStart = start
        noteRevealReadiness(at: start)
        revealInstant = CycleReveal.instantCount(readyAt: revealReadyAt, start: start)
        advanceReveal()
    }

    private func noteRevealReadiness(at time: Int64) {
        for (index, ready) in revealReadiness.enumerated() where ready && revealReadyAt[index] == nil {
            revealReadyAt[index] = time
        }
    }

    /// Neu rechnen (nach neuen Daten oder zur Frist) und die nächste Prüfung planen.
    private func advanceReveal() {
        guard let start = revealStart, revealed < CycleReveal.count else { return }
        let now = Self.monotonicMillis
        noteRevealReadiness(at: now)
        let plan = CycleReveal.plan(readyAt: revealReadyAt, start: start, now: now)
        if plan.revealed != revealed { revealed = plan.revealed }
        revealTask?.cancel()
        revealTask = nil
        guard let nextAt = plan.nextAt else { return }
        let delay = UInt64(max(1, nextAt - now)) * 1_000_000
        revealTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            self?.advanceReveal()
        }
    }

    /// Alles neu laden (Ziehen nach unten). Wartet auf das Bitcoin-Modell.
    func refreshAll() async {
        cycle = BitcoinCycle.info()
        loadAll(force: true)
        await tasks[Self.marketKey]?.value
    }

    private func loadAll(force: Bool) {
        loadMacro()
        loadPulse(force: force)
        loadUnusual(force: force)
        loadMarket(force: force)
        loadInsights(force: force)
        loadCoin(force: force)
        loadGas(force: force)
    }

    // MARK: Crypto Pulse

    /// - Parameter force: Zwischenspeicher übergehen («Erneut», Ziehen nach unten).
    func loadPulse(force: Bool = false) {
        load(Self.pulseKey, ttl: CycleCachePolicy.pulse, timeout: Self.timeout, force: force,
             shown: pulse.value != nil, apply: { [weak self] in self?.pulse = $0 }) {
            Sourced(value: try await CryptoPulseSource.shared.fetch(force: force), provider: nil)
        }
    }

    // MARK: Heute auffällig

    /// - Parameter force: Zwischenspeicher (10 Min.) übergehen («Erneut», Ziehen nach unten).
    func loadUnusual(force: Bool = false) {
        load(Self.unusualKey, ttl: Self.unusualTTL, timeout: Self.longTimeout, force: force,
             shown: unusual.value != nil,
             apply: { [weak self] (state: CycleLoad<UnusualInput>) in
                 guard let self else { return }
                 switch state {
                 case .loading: self.unusual = .loading
                 case .failed: self.unusual = .failed
                 case .loaded(let input):
                     self.unusual = MarketUnusual.evaluate(input).map { CycleLoad.loaded($0) } ?? .failed
                 }
             }) {
            Sourced(value: try await MarketUnusualSource.shared.fetch(), provider: nil)
        }
    }

    // MARK: Wirtschaftsdaten

    /// Höchstens einmal am Tag aus dem Netz (`MacroCalendarSource`); plant danach die Morgen-Mitteilungen neu.
    private func loadMacro() {
        Task { [weak self] in
            let events = await MacroCalendarSource.shared.events()
            guard let self else { return }
            if self.macroEvents != events { self.macroEvents = events }
            await MacroNotifications.reschedule(settings: SharedStorage.loadSettings(), events: events)
        }
    }

    // MARK: Netzwerkgebühren

    /// - Parameter force: Zwischenspeicher übergehen (Ziehen nach unten, «Erneut»).
    func loadGas(force: Bool = false) {
        let maxAge: Int64 = force ? 0 : CycleCachePolicy.gas
        load(Self.gasKey, ttl: CycleCachePolicy.gas, timeout: Self.timeout, force: force,
             shown: gas.value != nil, apply: { [weak self] in self?.gas = $0 }) {
            try await GasDataSource.shared.fetchSourced(maxAge: maxAge)
        }
    }

    // MARK: Bitcoin-Modell

    /// «Erneut» lädt sofort (ohne Zeitgrenze des Zwischenspeichers).
    func loadMarket() { loadMarket(force: true) }

    private func loadMarket(force: Bool) {
        load(Self.marketKey, ttl: CycleCachePolicy.market, timeout: Self.longTimeout, force: force,
             minForce: CycleCachePolicy.manualMinInterval,
             shown: market.value != nil,
             apply: { [weak self] (state: CycleLoad<CycleReport>) in self?.market = state }) {
            let sourced = try await CycleDataSource.fetchSourced(forceOnChain: force)
            return Sourced(value: CycleModel.evaluate(sourced.value), provider: sourced.provider)
        }
    }

    // MARK: Fear & Greed, Dominanz, Altcoin-Saison, Zyklus-Vergleich

    func retryInsights() { loadInsights(force: true) }

    private func loadInsights(force: Bool) {
        load(Self.fearGreedKey, ttl: CycleCachePolicy.fearGreed, timeout: Self.timeout, force: force,
             shown: fearGreed.value != nil, apply: { [weak self] in self?.fearGreed = $0 }) {
            Sourced(value: try await InsightsDataSource.fearGreed(), provider: DataFreshness.alternativeMe)
        }
        loadGlobal(force: force)
        loadAltSeason(force: force)
        load(Self.historyKey, ttl: CycleCachePolicy.history, timeout: Self.longTimeout, force: force,
             minForce: CycleCachePolicy.manualMinInterval,
             shown: history.value != nil, apply: { [weak self] in self?.history = $0 }) {
            Sourced(value: try await InsightsDataSource.cycleHistory(), provider: nil)
        }
    }

    /// Stand der gezeigten Altcoin-Saison («Stand 14:05»); nil = noch nichts gezeigt.
    var altSeasonAsOf: Int64? { stamps[Self.altSeasonKey] }

    /// Altcoin-Saison lädt gerade neu.
    var altSeasonRefreshing: Bool { refreshingKeys.contains(Self.altSeasonKey) }

    // MARK: Herkunft und Stand (Nebenzeilen von «Einordnung» und «Daten»)

    private func stamp(_ key: String, ttl: Int64) -> DataStamp? {
        stamps[key].map { DataStamp(provider: providers[key], savedAt: $0, ttl: ttl) }
    }

    var fearGreedStamp: DataStamp? { stamp(Self.fearGreedKey, ttl: CycleCachePolicy.fearGreed) }
    var marketStamp: DataStamp? { stamp(Self.marketKey, ttl: CycleCachePolicy.market) }
    /// Dominanz und Gesamtmarkt (eine CoinGecko-Abfrage).
    var globalStamp: DataStamp? { stamp(Self.globalKey, ttl: CycleCachePolicy.global) }
    var altSeasonStamp: DataStamp? { stamp(Self.altSeasonKey, ttl: CycleCachePolicy.altSeason) }
    var gasStamp: DataStamp? { stamp(Self.gasKey, ttl: CycleCachePolicy.gas) }
    /// Stand des gezeigten Coins (nur, wenn er zum gewählten Coin gehört).
    var coinStamp: DataStamp? {
        guard coin.value?.symbol == selectedCoin else { return nil }
        return stamp(Self.coinKey(selectedCoin), ttl: CycleCachePolicy.coin)
    }

    /// «Aktualisieren» an der Altcoin-Saison: höchstens alle 5 Min. ein neuer Abruf.
    func refreshAltSeason() { loadAltSeason(force: true) }

    /// Altcoin-Saison: 3 h zwischengespeichert, von Hand frühestens alle 5 Min. neu.
    private func loadAltSeason(force: Bool) {
        load(Self.altSeasonKey, ttl: CycleCachePolicy.altSeason, timeout: Self.longTimeout, force: force,
             minForce: CycleCachePolicy.manualMinInterval,
             shown: altSeason.value != nil, apply: { [weak self] in self?.altSeason = $0 }) {
            try await InsightsDataSource.altSeason()
        }
    }

    /// Dominanz und Gesamtmarkt aus einer einzigen CoinGecko-Abfrage (`/global`).
    /// Fehlt ein Teil in der Antwort, gilt nur er als gescheitert.
    private func loadGlobal(force: Bool) {
        load(Self.globalKey, ttl: CycleCachePolicy.global, timeout: Self.timeout, force: force,
             shown: dominance.value != nil || globalMarket.value != nil,
             apply: { [weak self] (state: CycleLoad<CoinGeckoGlobal>) in
                 guard let self else { return }
                 switch state {
                 case .loading:
                     self.dominance = .loading
                     self.globalMarket = .loading
                 case .failed:
                     self.dominance = .failed
                     self.globalMarket = .failed
                 case .loaded(let result):
                     self.dominance = result.dominance.map { CycleLoad.loaded($0) } ?? .failed
                     self.globalMarket = result.market.map { CycleLoad.loaded($0) } ?? .failed
                 }
             }) {
            Sourced(value: try await InsightsDataSource.global(), provider: DataFreshness.coinGecko)
        }
    }

    /// Gemeinsamer Ablauf je Bereich:
    /// 1. Noch nichts gezeigt → gespeicherten Stand von der Platte sofort zeigen.
    /// 2. Stand jünger als `ttl` (und nicht erzwungen) → fertig.
    /// 3. Sonst laden: Kreisel nur, wenn nichts gezeigt wird; mit Daten still im Hintergrund.
    ///    Erfolg → zeigen und speichern; Fehler → Gezeigtes bleibt, sonst «gescheitert».
    /// Läuft die Abfrage eines Bereichs schon, startet nur ein erzwungenes Laden neu.
    private func load<T: Codable & Sendable>(
        _ key: String,
        ttl: Int64,
        timeout: Double,
        force: Bool,
        minForce: Int64 = 0,
        shown: Bool,
        apply: @escaping @MainActor (CycleLoad<T>) -> Void,
        _ fetch: @escaping @Sendable () async throws -> Sourced<T>
    ) {
        var hasValue = shown
        if !hasValue, let entry = CycleCache.read(key, as: T.self) {
            apply(.loaded(entry.value))
            stamps[key] = entry.savedAt
            providers[key] = entry.provider
            hasValue = true
        }
        // Gezeigt, aber ohne Stand (z. B. Coin gewechselt) zählt wie «nichts bekannt»
        let savedAt = hasValue ? stamps[key] : nil
        guard CycleCachePolicy.needsRefresh(savedAt: savedAt, now: TimeUtils.nowMillis, ttl: ttl, force: force,
                                            minForce: minForce)
        else { return }
        if tasks[key] != nil && !force {
            // Läuft schon (z. B. Coin hin und zurück gewechselt): nur den Ladezustand zeigen
            if !hasValue { apply(.loading) }
            return
        }

        tasks[key]?.cancel()
        let generation = (generations[key] ?? 0) + 1
        generations[key] = generation
        if !hasValue { apply(.loading) }
        refreshingKeys.insert(key)
        let keepShown = hasValue
        tasks[key] = Task { [weak self] in
            let value = try? await AsyncTimeout.run(seconds: timeout, fetch)
            guard let self, self.generations[key] == generation else { return }
            self.tasks[key] = nil
            self.refreshingKeys.remove(key)
            if let value {
                let now = TimeUtils.nowMillis
                apply(.loaded(value.value))
                self.stamps[key] = now
                self.providers[key] = value.provider
                CycleCache.write(key, value.value, savedAt: now, provider: value.provider)
            } else if !keepShown {
                apply(.failed)
            }
            // Daten bereit: vielleicht darf die nächste Karte erscheinen
            self.advanceReveal()
        }
    }

    // MARK: Coin

    func selectCoin(_ symbol: String) {
        selectedCoin = symbol
        loadCoin(force: false)
    }

    /// «Erneut» lädt sofort.
    func loadCoin() { loadCoin(force: true) }

    /// Je Coin eigener Stand (15 Min.); ein anderer Coin zeigt sofort seinen gespeicherten Stand.
    private func loadCoin(force: Bool) {
        let symbol = selectedCoin
        load(Self.coinKey(symbol), ttl: CycleCachePolicy.coin, timeout: Self.longTimeout, force: force,
             shown: coin.value?.symbol == symbol, apply: { [weak self] (state: CycleLoad<CoinReport>) in
                 // Inzwischen anderer Coin gewählt: Ergebnis nicht zeigen
                 guard let self, self.selectedCoin == symbol else { return }
                 self.coin = state
             }) {
            let sourced = try await InsightsDataSource.fetchCoin(symbol: symbol)
            return Sourced(value: CoinCycleModel.evaluate(symbol: symbol, input: sourced.value), provider: sourced.provider)
        }
    }

    func toggleFavoriteCoin(_ symbol: String) {
        var favorites = SharedStorage.favorites(.COIN)
        if favorites.contains(symbol) {
            favorites.remove(symbol)
        } else {
            favorites.insert(symbol)
        }
        SharedStorage.setFavorites(.COIN, favorites)
        favoriteCoins = favorites
    }

    /// Coins mit USDT-Spotpaar auf Binance. Zwischengespeichert, weil die Liste gross ist.
    private func loadCoinList() {
        let store = UserDefaults.standard
        if let cached = store.stringArray(forKey: Self.coinListKey), !cached.isEmpty {
            coins = cached
            let age = Date().timeIntervalSince1970 - store.double(forKey: Self.coinListDateKey)
            if age < Self.coinListMaxAge { return }
        }
        Task { [weak self] in
            guard let binance = MarketsConfig.market("Binance"),
                  let pairs = try? await MarketService.fetchCurrencyPairs(market: binance) else { return }
            let list = Array(Set(
                pairs.filter { $0.quote == "USDT" && $0.contractType == .none }.map(\.base)
            )).sorted()
            guard !list.isEmpty else { return }
            store.set(list, forKey: Self.coinListKey)
            store.set(Date().timeIntervalSince1970, forKey: Self.coinListDateKey)
            self?.coins = list
        }
    }
}
