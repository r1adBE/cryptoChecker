import Foundation

/// Marktdaten für «Crypto Pulse», die der Markt-Tab nicht schon lädt:
/// 24-h-Veränderung von BTC, ETH, SOL, BTC-Volumen-Verhältnis und BTC-Funding.
/// Fear & Greed und Gas kommen aus den Karten des Tabs. Ergebnis 5 Minuten
/// zwischengespeichert — im Speicher und im App-Group-Speicher, den auch das Widget
/// «Was gerade auffällt» liest. Wie die Pulse-Daten in Android.
struct PulseMarketData: Equatable, Sendable, Codable {
    let btc: Double?
    let eth: Double?
    let sol: Double?
    let volumeRatio: Double?
    let fundingPercent: Double?
    /// Zeitpunkt der Abfrage (Epoch-ms) — für «Stand HH:mm».
    let time: Int64
    /// Marktbreite: 24-h-Veränderung der grössten Coins (fehlt in älteren Ständen).
    var topChanges: [Double]? = nil
    /// Krypto-Markt gesamt in USD und seine Veränderung über 24 h (CoinGecko `/global`).
    var marketCapUsd: Double? = nil
    var marketCap24h: Double? = nil

    var hasMarket: Bool { btc != nil && eth != nil && sol != nil }
}

actor CryptoPulseSource {
    static let shared = CryptoPulseSource()
    static let cacheMillis: Int64 = 5 * 60_000
    static let coins = ["BTC", "ETH", "SOL"]

    private var cached: PulseMarketData?
    /// Krypto-Markt gesamt bewegt sich langsam: 30 Minuten im Speicher (schont das Abruflimit).
    private var globalCache: (at: Int64, market: GlobalMarket)?
    static let globalCacheMillis: Int64 = 30 * 60_000

    /// - Parameter force: Zwischenspeicher übergehen (Ziehen nach unten, «Erneut»).
    /// Wirft, wenn die 24-h-Veränderungen fehlen.
    func fetch(force: Bool = false) async throws -> PulseMarketData {
        if !force, let cached, cached.hasMarket, Self.isFresh(cached) {
            return cached
        }
        // App und Widget «Was gerade auffällt» teilen den letzten Stand (App Group)
        if !force, let stored = Self.stored(), stored.hasMarket, Self.isFresh(stored) {
            cached = stored
            return stored
        }
        async let changesJob = Self.changes()
        async let spikeJob = VolumeDataSource.hourlySpike(base: "BTC", quote: "USDT")
        async let fundingJob = Self.funding()
        async let topJob = Self.topChanges()
        async let globalJob = globalMarket()
        let changes = await changesJob
        let spike = await spikeJob
        let funding = await fundingJob
        let top = await topJob
        let global = await globalJob
        let data = PulseMarketData(btc: changes["BTC"], eth: changes["ETH"], sol: changes["SOL"],
                                   volumeRatio: spike?.ratio, fundingPercent: funding,
                                   time: TimeUtils.nowMillis,
                                   topChanges: top.isEmpty ? nil : top,
                                   marketCapUsd: global?.totalMarketCap["usd"],
                                   marketCap24h: global?.change24hPercent)
        guard data.hasMarket else { throw JSONError(message: "Pulse: Marktdaten fehlen") }
        cached = data
        Self.store(data)
        return data
    }

    // MARK: Gemeinsamer Stand (App Group)

    /// Schlüssel im App-Group-Speicher; Version im Namen (anderes Format = nichts gespeichert).
    static let storeKey = "pulse_market_data_v1"

    /// Jünger als die Gültigkeit (5 Min.)? Ein Zeitpunkt in der Zukunft gilt als alt.
    static func isFresh(_ data: PulseMarketData, now: Int64 = TimeUtils.nowMillis) -> Bool {
        let age = now - data.time
        return age >= 0 && age < cacheMillis
    }

    /// Letzter gespeicherter Stand (auch alt — der Aufrufer prüft das Alter); nil ohne/kaputt.
    static func stored() -> PulseMarketData? {
        guard let raw = SharedStorage.defaults.data(forKey: storeKey) else { return nil }
        return try? JSONDecoder().decode(PulseMarketData.self, from: raw)
    }

    private static func store(_ data: PulseMarketData) {
        guard let raw = try? JSONEncoder().encode(data) else { return }
        SharedStorage.defaults.set(raw, forKey: storeKey)
    }

    private func globalMarket() async -> GlobalMarket? {
        let now = TimeUtils.nowMillis
        if let globalCache, now - globalCache.at >= 0, now - globalCache.at < Self.globalCacheMillis {
            return globalCache.market
        }
        guard let market = try? await InsightsDataSource.global().market else { return nil }
        globalCache = (now, market)
        return market
    }

    /// 24-h-Veränderung der grössten Coins (CoinGecko-Rangliste), ohne Stablecoins und
    /// Doppelgänger (`CoinExclusion`), höchstens `CryptoPulse.breadthCoins`; leer bei Fehler.
    static func topChanges() async -> [Double] {
        let url = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc"
            + "&per_page=50&page=1&sparkline=false"
        guard let text = try? await MarketHTTP.call(url),
              let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]]
        else { return [] }
        var out: [Double] = []
        for item in array {
            let symbol = item["symbol"] as? String ?? ""
            let name = item["name"] as? String ?? ""
            guard !CoinExclusion.isExcluded(symbol: symbol, name: name),
                  let change = (item["price_change_percentage_24h"] as? NSNumber)?.doubleValue, change.isFinite
            else { continue }
            out.append(change)
            if out.count == CryptoPulse.breadthCoins { break }
        }
        return out
    }

    private static func funding() async -> Double? {
        guard let info = try? await FuturesDataSource.fetchForBase(baseAsset: "BTC") else { return nil }
        return info.fundingRatePercent
    }

    /// Coin → 24-h-Veränderung in %. Zuerst der Binance-Spiegel, fehlende Coins von Coinbase.
    static func changes() async -> [String: Double] {
        var out: [String: Double] = [:]
        let symbols = "[" + coins.map { "\"\($0)USDT\"" }.joined(separator: ",") + "]"
        let url = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols=" + symbols.urlQueryEncoded
        if !BlockedSources.isBlocked("data-api.binance.vision") {
            do {
                out = try parseBinance24h(try await MarketHTTP.call(url))
            } catch {
                BlockedSources.noteFailure("data-api.binance.vision", error)
            }
        }
        for coin in coins where out[coin] == nil {
            if let text = try? await MarketHTTP.call("https://api.exchange.coinbase.com/products/\(coin)-USD/stats"),
               let change = try? parseCoinbaseStats(text) {
                out[coin] = change
            }
        }
        return out
    }

    /// Binance ticker/24hr?symbols=[…] → Coin → priceChangePercent.
    static func parseBinance24h(_ text: String) throws -> [String: Double] {
        var out: [String: Double] = [:]
        for item in try JArray(string: text).objects {
            let symbol = item.optString("symbol")
            let change = item.optDouble("priceChangePercent")
            if symbol.hasSuffix("USDT"), change.isFinite { out[String(symbol.dropLast(4))] = change }
        }
        return out
    }

    /// Coinbase /products/X-USD/stats → (last / open − 1) · 100.
    static func parseCoinbaseStats(_ text: String) throws -> Double {
        let json = try JObject(string: text)
        let open = try json.double("open")
        let last = try json.double("last")
        guard open > 0, last.isFinite else { throw JSONError(message: "Coinbase-Stats ohne Eröffnungskurs") }
        return (last / open - 1) * 100
    }
}
