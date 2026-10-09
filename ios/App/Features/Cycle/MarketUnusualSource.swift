import Foundation

/// Daten für «Heute auffällig» — wie `UnusualDataSource.kt`, nur Quellen, die die App schon
/// nutzt, je ein Abruf:
/// - Coins: rund 30 grösste ohne Stablecoins mit …USDT auf Binance (`StarterCoins.universe`, 24 h)
/// - 24-h-Veränderung und Umsatz: Binance-Spiegel (`data-api.binance.vision`, 24-h-Ticker)
/// - Funding: Binance USDⓈ-M (`fapi.binance.com/fapi/v1/premiumIndex` ohne Symbol = alle Perpetuals);
///   fehlt die Quelle, bleibt Funding weg
/// «Üblicher» Umsatz: je Coin ein Tageswert auf dem Gerät (`MarketUnusual.updateHistory`).
/// Zwischenspeicher (10 Min.) über `CycleViewModel`.
actor MarketUnusualSource {
    static let shared = MarketUnusualSource()

    private static let tickerURL = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols="
    private static let premiumIndexURL = "https://fapi.binance.com/fapi/v1/premiumIndex"
    private static let fapiHost = "fapi.binance.com"
    private static let historyKey = "unusual_turnover_history"

    /// Frische Eingaben. Wirft ohne Coins oder Ticker.
    func fetch() async throws -> UnusualInput {
        async let fundingJob = Self.funding()
        guard var universe = await StarterCoins.universe() else {
            throw JSONError(message: "Keine Coins für «Heute auffällig»")
        }
        let tickers: [String: (change: Double, volume: Double?)]
        do {
            tickers = try await Self.tickers(universe)
        } catch let error as HttpMarketError where error.httpCode == 400 {
            // Binance kennt ein Symbol nicht mehr: Liste neu ermitteln, einmal wiederholen
            guard let fresh = await StarterCoins.universe(force: true) else { throw error }
            universe = fresh
            tickers = try await Self.tickers(universe)
        }
        let funding = await fundingJob
        let now = TimeUtils.nowMillis

        let coins: [UnusualCoin] = universe.compactMap { coin in
            guard let ticker = tickers[coin.symbol] else { return nil }
            return UnusualCoin(symbol: coin.symbol, name: coin.name, change24h: ticker.change,
                               quoteVolume: ticker.volume, marketCap: coin.marketCap,
                               fundingPercent: funding[coin.symbol])
        }
        guard !coins.isEmpty else { throw JSONError(message: "Keine Ticker für «Heute auffällig»") }

        // Tageswerte des Umsatz-Anteils fortschreiben (Ortsdatum), daraus «üblich» je Coin
        let today = Self.localDay(Date())
        let store = UserDefaults.standard
        let history = store.data(forKey: Self.historyKey)
            .flatMap { try? JSONDecoder().decode([String: [TurnoverSample]].self, from: $0) } ?? [:]
        let updated = MarketUnusual.updateHistory(history, coins: coins, day: today)
        if let data = try? JSONEncoder().encode(updated) { store.set(data, forKey: Self.historyKey) }
        var own: [String: Double] = [:]
        for (symbol, samples) in updated {
            if let typical = MarketUnusual.ownTypical(samples, today: today) { own[symbol] = typical }
        }
        return UnusualInput(coins: coins, ownTypical: own, time: now)
    }

    /// Tage seit 1970 in Ortszeit (wie `LocalDate.toEpochDay()`).
    private static func localDay(_ date: Date) -> Int64 {
        let seconds = date.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT(for: date))
        return Int64((seconds / 86_400).rounded(.down))
    }

    /// Coin → (24-h-Veränderung in %, 24-h-Umsatz in USDT).
    private static func tickers(_ universe: [UniverseCoin]) async throws -> [String: (change: Double, volume: Double?)] {
        let list = "[" + universe.map { "\"\($0.symbol)USDT\"" }.joined(separator: ",") + "]"
        let text = try await MarketHTTP.call(tickerURL + list.urlQueryEncoded)
        var out: [String: (change: Double, volume: Double?)] = [:]
        let now = TimeUtils.nowMillis
        for item in try JArray(string: text).objects {
            let symbol = item.optString("symbol").uppercased()
            let change = item.optDouble("priceChangePercent")
            guard symbol.hasSuffix("USDT"), symbol.count > 4, change.isFinite else { continue }
            // Delistetes Spot-Paar: eingefrorener Ticker — nicht als «heute auffällig» zeigen
            let closeTime: Int64? = item.has("closeTime") ? item.optLong("closeTime") : nil
            let count: Int64? = item.has("count") ? item.optLong("count") : nil
            guard MarketUnusual.isLiveTicker(closeTime: closeTime, tradeCount: count, now: now) else { continue }
            let volume = item.optDouble("quoteVolume")
            out[String(symbol.dropLast(4))] = (change: change, volume: volume.isFinite && volume >= 0 ? volume : nil)
        }
        return out
    }

    /// Coin → letzte Funding Rate in %; leer bei Fehler oder gesperrter Quelle.
    private static func funding() async -> [String: Double] {
        guard !BlockedSources.isBlocked(fapiHost) else { return [:] }
        do {
            let text = try await MarketHTTP.call(premiumIndexURL)
            var out: [String: Double] = [:]
            for item in try JArray(string: text).objects {
                let symbol = item.optString("symbol").uppercased()
                let rate = item.optDouble("lastFundingRate")
                guard symbol.hasSuffix("USDT"), symbol.count > 4, rate.isFinite else { continue }
                out[String(symbol.dropLast(4))] = rate * 100
            }
            return out
        } catch {
            BlockedSources.noteFailure(fapiHost, error)
            return [:]
        }
    }
}
