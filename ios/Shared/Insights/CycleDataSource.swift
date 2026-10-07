import Foundation

/// Holt die Daten für das Zyklus-Modell — wie `CycleDataSource.kt`:
///  - Tages- und Wochenkerzen BTC/USDT (Kurs, Durchschnitte, Allzeithoch) über die
///    Ausweich-Kette Binance → Binance.US → Coinbase (siehe `CandleDataSource`);
///    Coinbase liefert nur rund 300 Tage, das reicht notfalls für das Modell
///  - Coin Metrics Community API: MVRV, Mining-Einnahmen, Hashrate
///    (frei, ohne Schlüssel, Lizenz CC BY-NC 4.0 — Quellenangabe in der App)
///
/// Fehlen die On-Chain-Daten, rechnet das Modell mit den Kursdaten weiter.
enum CycleDataSource {

    /// Rund 14 Monate Tageswerte — genug für den 365-Tage-Schnitt des Puell Multiple.
    static func coinMetricsURL() -> String {
        let start = LocalDay.todayUTC().plusDays(-430)
        return "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d" +
            "&start_time=\(start.description)&page_size=1000"
    }

    /// - Parameter forceOnChain: gespeicherte On-Chain-Werte übergehen (Ziehen nach unten).
    static func fetch(forceOnChain: Bool = false) async throws -> CycleInputs {
        // On-Chain-Werte ändern sich höchstens täglich: 12 h aus dem Speicher
        let cachedOnChain: OnChain? = forceOnChain ? nil : storedOnChain(now: TimeUtils.nowMillis)
        let skipOnChain = cachedOnChain != nil
        async let dailyJob = CandleDataSource.candles(base: "BTC", quote: "USDT", interval: .d1, limit: 1000)
        async let weeklyJob = CandleDataSource.candles(base: "BTC", quote: "USDT", interval: .w1, limit: 1000)
        async let onChainJob = loadOnChain(skip: skipOnChain)

        guard let dailyCandles = await dailyJob else { throw JSONError(message: "Keine BTC-Tageskerzen verfügbar") }
        let daily = dailyCandles.map(toInsights)
        // Wochenkerzen nur für 200-Wochen-Schnitt und Allzeithoch; fehlen sie, entfällt das
        let weekly = (await weeklyJob)?.map(toInsights) ?? []
        var onChain = cachedOnChain
        if onChain == nil {
            onChain = await onChainJob
        }

        guard daily.count >= 200 else { throw JSONError(message: "Zu wenige Tageskerzen: \(daily.count)") }
        let closes = daily.map(\.close)

        // Allzeithoch: Wochenkerzen reichen bis 2017 zurück, Tageskerzen geben das genaue Datum.
        let athCandle = (weekly + daily).max(by: { $0.high < $1.high })

        return CycleInputs(
            price: closes[closes.count - 1],
            sma200d: Indicators.smaOfLast(closes, 200),
            sma111d: Indicators.smaOfLast(closes, 111),
            sma350d: Indicators.smaOfLast(closes, 350),
            price30dAgo: closes.count >= 31 ? closes[closes.count - 31] : nil,
            sma200w: Indicators.smaOfLast(weekly.map(\.close), 200),
            ath: athCandle?.high,
            athDate: athCandle?.date,
            mvrv: onChain?.mvrv,
            puell: onChain?.puell,
            hash30d: onChain?.hash30d,
            hash60d: onChain?.hash60d
        )
    }

    private static func toInsights(_ c: MarketCandle) -> InsightsCandle {
        InsightsCandle(date: LocalDay(epochMillisUTC: c.openTime), high: c.high, close: c.close)
    }

    private static func optionalCall(_ url: String) async -> String? {
        try? await MarketHTTP.call(url, session: MarketHTTP.bulkSession)
    }

    private struct OnChain: Codable {
        let mvrv: Double?
        let puell: Double?
        let hash30d: Double?
        let hash60d: Double?

        var isEmpty: Bool { mvrv == nil && puell == nil && hash30d == nil && hash60d == nil }
    }

    // MARK: On-Chain-Zwischenspeicher (12 h)

    /// Versionierter Schlüssel: ein altes oder kaputtes Format wird einfach übergangen.
    private static let onChainKey = "cycle_onchain_v1"
    static let onChainMaxAgeMillis: Int64 = 12 * 60 * 60_000

    private struct StoredOnChain: Codable {
        let savedAt: Int64
        let value: OnChain
    }

    /// Gespeicherte On-Chain-Werte, wenn jünger als 12 h; sonst nil.
    private static func storedOnChain(now: Int64) -> OnChain? {
        guard let data = UserDefaults.standard.data(forKey: onChainKey),
              let stored = try? JSONDecoder().decode(StoredOnChain.self, from: data),
              !stored.value.isEmpty,
              now - stored.savedAt >= 0, now - stored.savedAt < onChainMaxAgeMillis else { return nil }
        return stored.value
    }

    /// Coin Metrics abfragen und bei Erfolg speichern; `skip` = gespeicherte Werte gelten noch.
    private static func loadOnChain(skip: Bool) async -> OnChain? {
        if skip { return nil }
        guard let text = await optionalCall(coinMetricsURL()),
              let onChain = try? parseCoinMetrics(text), !onChain.isEmpty else { return nil }
        if let data = try? JSONEncoder().encode(StoredOnChain(savedAt: TimeUtils.nowMillis, value: onChain)) {
            UserDefaults.standard.set(data, forKey: onChainKey)
        }
        return onChain
    }

    private static func parseCoinMetrics(_ json: String) throws -> OnChain {
        let rows = try JObject(string: json).array("data")
        var mvrv: [Double] = []
        var issuance: [Double] = []
        var hash: [Double] = []
        for r in rows.objects {
            if let v = Double(r.optString("CapMVRVCur")) { mvrv.append(v) }
            if let v = Double(r.optString("IssTotUSD")) { issuance.append(v) }
            if let v = Double(r.optString("HashRate")) { hash.append(v) }
        }

        // Puell Multiple: Wert der täglich neu geschürften Coins / 365-Tage-Schnitt.
        var puell: Double? = nil
        if issuance.count >= 365, let avg = Indicators.smaOfLast(issuance, 365), avg > 0, let last = issuance.last {
            puell = last / avg
        }

        return OnChain(
            mvrv: mvrv.last,
            puell: puell,
            hash30d: Indicators.smaOfLast(hash, 30),
            hash60d: Indicators.smaOfLast(hash, 60)
        )
    }
}
