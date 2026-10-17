import Foundation
import SwiftUI

// Laden der Markt-Daten (Teil von `CycleViewModel`): Halving-Schätzung, Puls, Auffälliges, Termine, Gas,
// Bitcoin-Modell, Angst & Gier, Gesamtmarkt, Altcoin-Saison, Zyklus-Verlauf, Coin-Karte und Coin-Liste.
// Eigene Datei, weil hier nur festgelegt ist, WAS je Karte geladen wird; WIE (Zwischenspeicher, Zeitlimit)
// steht in `MarketDataSync.swift` (wie `MarketDataLoaders.kt`).

extension CycleViewModel {
    /// Blockhöhe höchstens einmal je Stunde abfragen — wie Android.
    func loadHalvingEstimate(force: Bool) {
        let now = TimeUtils.nowMillis
        if !force, halvingEstimate != nil, now - halvingEstimateAt >= 0, now - halvingEstimateAt < 3_600_000 { return }
        Task { [weak self] in
            guard let blocks = await HalvingMath.fetchBlocks(),
                  let estimate = HalvingMath.estimate(tipHeight: blocks.height, avgBlockSeconds: blocks.avgBlockSeconds,
                                                      nowMillis: TimeUtils.nowMillis) else { return }
            await MainActor.run {
                self?.halvingEstimate = estimate
                self?.halvingEstimateAt = TimeUtils.nowMillis
            }
        }
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
    func loadMacro() {
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

    func loadMarket(force: Bool) {
        load(Self.marketKey, ttl: CycleCachePolicy.market, timeout: Self.longTimeout, force: force,
             minForce: CycleCachePolicy.manualMinInterval,
             shown: market.value != nil,
             apply: { [weak self] (state: CycleLoad<BitcoinReport>) in self?.market = state }) {
            let sourced = try await CycleDataSource.fetchSourced(forceOnChain: force)
            return Sourced(value: BitcoinIndicatorModel.evaluate(sourced.value), provider: sourced.provider)
        }
    }

    // MARK: Fear & Greed, Dominanz, Altcoin-Saison, Zyklus-Vergleich

    func retryInsights() { loadInsights(force: true) }

    func loadInsights(force: Bool) {
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

    /// Altcoin-Saison: 3 h zwischengespeichert, von Hand frühestens alle 5 Min. neu.
    func loadAltSeason(force: Bool) {
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

    /// «Erneut» lädt sofort.
    func loadCoin() { loadCoin(force: true) }

    /// Je Coin eigener Stand (15 Min.); ein anderer Coin zeigt sofort seinen gespeicherten Stand.
    func loadCoin(force: Bool) {
        let symbol = selectedCoin
        load(Self.coinKey(symbol), ttl: CycleCachePolicy.coin, timeout: Self.longTimeout, force: force,
             shown: coin.value?.symbol == symbol, apply: { [weak self] (state: CycleLoad<CoinReport>) in
                 // Inzwischen anderer Coin gewählt: Ergebnis nicht zeigen
                 guard let self, self.selectedCoin == symbol else { return }
                 self.coin = state
             }) {
            let sourced = try await InsightsDataSource.fetchCoin(symbol: symbol)
            return Sourced(value: CoinIndicatorModel.evaluate(symbol: symbol, input: sourced.value), provider: sourced.provider)
        }
    }

    /// Coins mit USDT-Spotpaar auf Binance. Zwischengespeichert, weil die Liste gross ist.
    func loadCoinList() {
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
