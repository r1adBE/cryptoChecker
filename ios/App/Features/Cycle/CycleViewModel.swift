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
    @Published var market: CycleLoad<BitcoinReport> = .loading
    @Published var fearGreed: CycleLoad<FearGreed> = .loading
    @Published var dominance: CycleLoad<Dominance> = .loading
    /// Gesamtmarkt (Marktkapitalisierung, Volumen) — aus derselben CoinGecko-Abfrage wie die Dominanz.
    @Published var globalMarket: CycleLoad<GlobalMarket> = .loading
    @Published var altSeason: CycleLoad<AltSeason> = .loading
    @Published var history: CycleLoad<CycleHistory> = .loading
    @Published var coin: CycleLoad<CoinReport> = .loading
    /// Coins mit USDT-Paar auf Binance (sonst eine kurze Standardliste).
    @Published var coins: [String] = CycleViewModel.fallbackCoins
    @Published private(set) var selectedCoin: String = "ETH"
    @Published private(set) var favoriteCoins: Set<String> = []
    /// Netzwerkgebühren (#167).
    @Published var gas: CycleLoad<GasReport> = .loading
    /// «Crypto Pulse»: 24-h-Veränderungen, Volumen-Verhältnis, Funding.
    @Published var pulse: CycleLoad<PulseMarketData> = .loading
    /// «Heute auffällig» (marktweit ungewöhnliche Bewegungen).
    @Published var unusual: CycleLoad<UnusualReport> = .loading
    /// Termine wichtiger US-Wirtschaftsdaten (Hinweis über dem Pulse); die Ansicht wählt, was heute gilt.
    @Published var macroEvents: [MacroEvent] = []

    /// Letztes und geschätzt nächstes Halving: reines Datum nach Kalender, auch ohne Internet.
    @Published private(set) var halving: HalvingInfo = BitcoinHalving.info()
    /// Nächstes Halving rechnerisch aus der Blockhöhe (`HalvingMath`); nil = noch nicht geladen (dann Kalender).
    @Published var halvingEstimate: HalvingEstimate?
    var halvingEstimateAt: Int64 = 0

    /// Stand (Epoch-ms) der gezeigten Daten je Bereich.
    @Published var stamps: [String: Int64] = [:]
    /// Anbieter der gezeigten Daten je Bereich (z. B. «CoinGecko»), sofern bekannt.
    @Published var providers: [String: String] = [:]
    /// Bereiche, die gerade neu laden.
    @Published var refreshingKeys: Set<String> = []

    /// Welche Teile des Tabs (`CycleRevealSlot`, je Register von oben) sichtbar sind — einmal je
    /// App-Sitzung schrittweise (`CycleReveal`), danach bleibt alles stehen.
    @Published private(set) var revealed: Set<CycleRevealSlot> = []
    /// Diese Teile erscheinen ohne Animation (beim Öffnen schon bereit).
    private(set) var revealInstant: Set<CycleRevealSlot> = []
    private var revealStart: Int64?
    private var revealReadyAt = [Int64?](repeating: nil, count: CycleReveal.count)
    private var revealTask: Task<Void, Never>?

    var tasks: [String: Task<Void, Never>] = [:]
    /// Je Bereich hochgezählt; nur die jüngste Abfrage darf ihr Ergebnis setzen.
    var generations: [String: Int] = [:]
    /// Coin-Liste nur einmal je Sitzung prüfen (der Abruf ist mehrere MB gross).
    private var coinListRequested = false

    static let fallbackCoins: [String] = [
        "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
        "TON", "LTC", "BCH", "UNI", "NEAR", "APT", "ETC", "XLM", "SUI", "PEPE",
    ].sorted()

    static let coinListKey = "cycle_coin_list"
    static let coinListDateKey = "cycle_coin_list_at"
    /// Binance-Paarliste höchstens einmal pro Woche neu laden (mehrere MB).
    static let coinListMaxAge: TimeInterval = 7 * 24 * 3600

    // Schlüssel der Bereiche (Dateinamen im Zwischenspeicher)
    static let pulseKey = "pulse"
    static let unusualKey = "unusual"
    /// «Heute auffällig» 10 Min. (wie `CycleSource.UNUSUAL` in Android).
    static let unusualTTL: Int64 = 10 * CycleCachePolicy.minute
    /// Eigener Schlüssel seit Runde 35 (neutrale Kennzahlen statt Zonen-Bericht)
    static let marketKey = "btc_indicators"
    static let fearGreedKey = "fng"
    static let globalKey = "global"
    static let altSeasonKey = "altseason"
    static let historyKey = "history"
    static let gasKey = "gas"
    static func coinKey(_ symbol: String) -> String { "coinind_" + symbol }

    /// Harte Zeitgrenze je Quelle (alle Teilanfragen zusammen). Grosse bzw.
    /// mehrteilige Abfragen etwas länger — sie sind dafür lange zwischengespeichert.
    static let timeout: Double = 12
    static let longTimeout: Double = 25

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
        halving = BitcoinHalving.info()
        loadHalvingEstimate(force: false)
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

    /// Bezugspunkt der monotonen Zeit (nur Abstände zählen).
    private static let clockOrigin = ContinuousClock.now

    /// Monotone Millisekunden seit `clockOrigin`. `ContinuousClock` läuft auch im Ruhezustand weiter
    /// (anders als `DispatchTime.uptimeNanoseconds`): nach dem Aufwecken gelten die Fristen der Einblendung
    /// als abgelaufen, statt um die Schlafdauer verschoben zu sein.
    private static var monotonicMillis: Int64 {
        let parts = clockOrigin.duration(to: ContinuousClock.now).components
        return parts.seconds * 1_000 + parts.attoseconds / 1_000_000_000_000_000
    }

    private func startReveal() {
        guard revealStart == nil else { return }
        let start = Self.monotonicMillis
        revealStart = start
        noteRevealReadiness(at: start)
        revealInstant = CycleReveal.instantSlots(readyAt: revealReadyAt, start: start)
        advanceReveal()
    }

    private func noteRevealReadiness(at time: Int64) {
        for (index, ready) in revealReadiness.enumerated() where ready && revealReadyAt[index] == nil {
            revealReadyAt[index] = time
        }
    }

    /// Neu rechnen (nach neuen Daten oder zur Frist) und die nächste Prüfung planen.
    func advanceReveal() {
        guard let start = revealStart, revealed.count < CycleReveal.count else { return }
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
        halving = BitcoinHalving.info()
        loadHalvingEstimate(force: true)
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

    /// Stand der gezeigten Altcoin-Saison («Stand 14:05»); nil = noch nichts gezeigt.
    var altSeasonAsOf: Int64? { stamps[Self.altSeasonKey] }

    /// Altcoin-Saison lädt gerade neu.
    var altSeasonRefreshing: Bool { refreshingKeys.contains(Self.altSeasonKey) }

    // MARK: Herkunft und Stand (Nebenzeilen von «Einordnung» und «Daten»)

    private func stamp(_ key: String, ttl: Int64) -> DataStamp? {
        stamps[key].map { DataStamp(provider: providers[key], savedAt: $0, ttl: ttl) }
    }

    /// Puls und «Heute auffällig»: für «Kurz gesagt» (kein Satz auf veralteten Daten).
    var pulseStamp: DataStamp? { stamp(Self.pulseKey, ttl: CycleCachePolicy.pulse) }
    var unusualStamp: DataStamp? { stamp(Self.unusualKey, ttl: Self.unusualTTL) }
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

    // MARK: Coin

    func selectCoin(_ symbol: String) {
        selectedCoin = symbol
        loadCoin(force: false)
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

}
