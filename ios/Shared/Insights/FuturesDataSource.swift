import Foundation

/// Funding Rate und Open Interest eines Perpetual-Kontrakts.
struct FuturesInfo: Equatable, Sendable {
    /// Aktuelle Funding Rate in Prozent (z. B. 0.01 = 0,01 %).
    let fundingRatePercent: Double?
    /// Zeitpunkt der nächsten Funding-Zahlung (Epoch-ms).
    let nextFundingTime: Int64?
    /// Offene Positionen in USD.
    let openInterestUsd: Double?
    /// Woher die Daten stammen (`FuturesSources`: «Binance Futures», «Bybit», «OKX», «WOO X Futures» …).
    let source: String
    /// Abrechnungsintervall dieses Kontrakts in Stunden, wie die Börse es meldet (Binance 1/4/8, Bybit,
    /// OKX, Bitget, MEXC); nil = die Antwort nennt es nicht — wie Android.
    var fundingIntervalHours: Int? = nil

    /// Wirksames Intervall: gemeldet, sonst das feste der Quelle (`FuturesSources.fundingIntervalHours`);
    /// nil = unbekannt — dann keine Umrechnung auf 8 Std. und kein Funding-Alarm (wie Android).
    var intervalHours: Int? { fundingIntervalHours ?? FuturesSources.fundingIntervalHours(source) }
}

/// Futures-Kennzahlen — wie `FuturesDataSource.kt`. Perpetuals zuerst von der beobachteten Börse,
/// wenn sie eigene Daten liefert (`FuturesSources.own`), alles andere zuerst von Binance USDⓈ-M — bei
/// anderen Börsen als Richtwert für denselben Coin (`FuturesSources.note`). Ist die erste Quelle gesperrt (Binance in den
/// USA: 451/403) oder liefert nichts, folgen Bybit bzw. Binance und zuletzt OKX.
/// Gesperrte Hosts werden wie bei den Kerzen 6 h übersprungen (`BlockedSources`).
enum FuturesDataSource {

    /// Hostnamen = Schlüssel in `BlockedSources`.
    static let binanceHost = "fapi.binance.com"
    static let coinMHost = "dapi.binance.com"
    static let bybitHost = "api.bybit.com"
    static let okxHost = "www.okx.com"

    private typealias Attempt = (host: String, run: () async throws -> FuturesInfo)

    /// nil, wenn das Paar kein (USDT-)Perpetual ist.
    static func fetch(watch: Watch) async throws -> FuturesInfo? {
        // Wie Android: nur Perpetuals (linear und invers, z. B. COIN-M), keine Laufzeit-Kontrakte.
        guard watch.contractType.isPerpetual else { return nil }
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        switch watch.marketKey {
        case "BybitFutures":
            let symbol = watch.pairId ?? "\(base)USDT"
            return try await firstOf([
                (host: bybitHost, run: { try await bybit(symbol: symbol) }),
                (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        case "BinanceFutures":
            // COIN-M (2:BTCUSD_PERP): eigene Kennzahlen von dapi; USDT-Kontrakt nur als Richtwert
            if FuturesSources.isCoinM(watch.pairId), let pairId = watch.pairId {
                let coinM = String(pairId.dropFirst(FuturesSources.coinMPrefix.count))
                return try await firstOf([
                    (host: coinMHost, run: { try await binanceCoinM(symbol: coinM) }),
                    (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                    (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
                    (host: okxHost, run: { try await okx(base: base) }),
                ])
            }
            let symbol: String
            if let pairId = watch.pairId, pairId.hasSuffix("USDT") || pairId.hasSuffix("USDC") {
                symbol = pairId
            } else {
                symbol = "\(base)USDT"
            }
            return try await firstOf([
                (host: binanceHost, run: { try await binance(symbol: symbol, source: "Binance Futures") }),
                (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        case "OkexFutures":
            // OKX-Perpetual: eigener Kontrakt (auch BASE-USD-SWAP), sonst Binance/Bybit als Richtwert
            let instId = watch.pairId.flatMap { $0.hasSuffix("-SWAP") ? $0 : nil }
            return try await firstOf([
                (host: okxHost, run: { try await okx(base: base, instId: instId) }),
                (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
            ])
        default:
            // Weitere Börsen mit eigener Quelle: zuerst ihre eigenen Kennzahlen, sonst Richtwert der Kette
            guard let own = ownAttempt(watch, base: base) else { return try await fetchForBase(baseAsset: base) }
            return try await firstOf([
                own,
                (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
                (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
                (host: okxHost, run: { try await okx(base: base) }),
            ])
        }
    }

    /// Eigene Quelle der beobachteten Börse — wie `ownSource` (Android); nil = nur Richtwert (z. B. Phemex).
    /// Abfragen gehen nur an die Börse, die das Paar ohnehin liefert:
    ///  - WOO X: `GET https://api.woox.io/v3/public/futures?symbol=PERP_BTC_USDT`
    ///  - Bitget: `GET https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES`
    ///    und `…/api/v2/mix/market/funding-time?symbol=…&productType=USDT-FUTURES`
    ///  - MEXC: `GET https://contract.mexc.com/api/v1/contract/ticker?symbol=BTC_USDT`,
    ///    `…/api/v1/contract/detail?symbol=…` und `…/api/v1/contract/funding_rate/{symbol}`
    ///  - HTX: `GET https://api.hbdm.com/linear-swap-api/v1/swap_funding_rate?contract_code=BTC-USDT`
    ///    und `…/linear-swap-api/v1/swap_open_interest?contract_code=…&business_type=swap`
    ///  - Hyperliquid: `POST https://api.hyperliquid.xyz/info {"type":"metaAndAssetCtxs"}`
    ///  - Deribit: `GET https://www.deribit.com/api/v2/public/ticker?instrument_name=…`
    private static func ownAttempt(_ watch: Watch, base: String) -> Attempt? {
        let quote = watch.quoteAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let id = watch.pairId
        switch watch.marketKey {
        case "WooFutures":
            let symbol = id ?? "PERP_\(base)_\(quote)"
            return (host: "api.woox.io", run: { try await woo(symbol: symbol) })
        case "BitgetFutures":
            let symbol = id ?? "\(base)\(quote)"
            return (host: "api.bitget.com", run: { try await bitget(symbol: symbol) })
        case "MexcFutures":
            let symbol = id ?? "\(base)_\(quote)"
            return (host: "contract.mexc.com", run: { try await mexc(symbol: symbol) })
        case "HtxFutures":
            let code = id ?? "\(base)-\(quote)"
            return (host: "api.hbdm.com", run: { try await htx(code: code) })
        case "Hyperliquid":
            let coin = id ?? watch.baseAsset.trimmingCharacters(in: .whitespaces)
            return (host: "api.hyperliquid.xyz", run: { try await hyperliquid(coin: coin) })
        case "Deribit":
            let instrument = id ?? "\(base)-PERPETUAL"
            return (host: "www.deribit.com", run: { try await deribit(instrument: instrument) })
        default:
            return nil
        }
    }

    private static func woo(symbol: String) async throws -> FuturesInfo {
        let text = try await MarketHTTP.call("https://api.woox.io/v3/public/futures?symbol=\(symbol)")
        return try FuturesParsers.woo(text)
    }

    private static func bitget(symbol: String) async throws -> FuturesInfo {
        async let ticker = MarketHTTP.call(
            "https://api.bitget.com/api/v2/mix/market/ticker?symbol=\(symbol)&productType=USDT-FUTURES")
        async let time = optionalCall(
            "https://api.bitget.com/api/v2/mix/market/funding-time?symbol=\(symbol)&productType=USDT-FUTURES")
        let tickerText = try await ticker
        let timeText = await time
        return try FuturesParsers.bitget(ticker: tickerText, fundingTime: timeText)
    }

    private static func mexc(symbol: String) async throws -> FuturesInfo {
        async let ticker = MarketHTTP.call("https://contract.mexc.com/api/v1/contract/ticker?symbol=\(symbol)")
        async let detail = optionalCall("https://contract.mexc.com/api/v1/contract/detail?symbol=\(symbol)")
        async let funding = optionalCall("https://contract.mexc.com/api/v1/contract/funding_rate/\(symbol)")
        let tickerText = try await ticker
        let detailText = await detail
        let fundingText = await funding
        return try FuturesParsers.mexc(ticker: tickerText, detail: detailText, fundingRate: fundingText, symbol: symbol)
    }

    private static func htx(code: String) async throws -> FuturesInfo {
        async let funding = MarketHTTP.call(
            "https://api.hbdm.com/linear-swap-api/v1/swap_funding_rate?contract_code=\(code)")
        async let oi = optionalCall(
            "https://api.hbdm.com/linear-swap-api/v1/swap_open_interest?contract_code=\(code)&business_type=swap")
        let fundingText = try await funding
        let oiText = await oi
        return try FuturesParsers.htx(funding: fundingText, openInterest: oiText)
    }

    private static func hyperliquid(coin: String) async throws -> FuturesInfo {
        let post = PostRequestInfo(body: #"{"type":"metaAndAssetCtxs"}"#, headers: ["Content-Type": "application/json"])
        let text = try await MarketHTTP.call("https://api.hyperliquid.xyz/info", post: post)
        return try FuturesParsers.hyperliquid(text, coin: coin)
    }

    private static func deribit(instrument: String) async throws -> FuturesInfo {
        let text = try await MarketHTTP.call("https://www.deribit.com/api/v2/public/ticker?instrument_name=\(instrument)")
        return try FuturesParsers.deribit(text)
    }

    /// Kennzahlen des USDT-Perpetuals zu einem Coin, unabhängig vom beobachteten Paar
    /// (z. B. Spot) — für «Warum bewegt sich das?». Wie `fetchForBase` in Android.
    static func fetchForBase(baseAsset: String) async throws -> FuturesInfo {
        let base = baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        return try await firstOf([
            (host: binanceHost, run: { try await binance(symbol: "\(base)USDT", source: "Binance Futures") }),
            (host: bybitHost, run: { try await bybit(symbol: "\(base)USDT") }),
            (host: okxHost, run: { try await okx(base: base) }),
        ])
    }

    /// Fragt die Quellen der Reihe nach (gesperrte Hosts übersprungen); die erste
    /// Antwort gewinnt. Wirft den letzten Fehler, wenn alle scheitern.
    private static func firstOf(_ attempts: [Attempt]) async throws -> FuturesInfo {
        var lastError: Error = JSONError(message: "Alle Futures-Quellen gesperrt")
        for attempt in attempts {
            if BlockedSources.isBlocked(attempt.host) { continue }
            do {
                return try await attempt.run()
            } catch {
                if error is CancellationError || Task.isCancelled { throw error }
                BlockedSources.noteFailure(attempt.host, error)
                lastError = error
            }
        }
        throw lastError
    }

    private static func binance(symbol: String, source: String) async throws -> FuturesInfo {
        async let premiumJob = MarketHTTP.call("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=\(symbol)")
        async let oiJob = optionalCall("https://fapi.binance.com/fapi/v1/openInterest?symbol=\(symbol)")

        let p = try JObject(string: try await premiumJob)
        let oiText = await oiJob
        let oi = oiText.flatMap { try? JObject(string: $0) }

        let mark = Double(p.optString("markPrice"))
        let contracts = oi.flatMap { Double($0.optString("openInterest")) }
        let next = p.optLong("nextFundingTime")
        var openInterest: Double? = nil
        if let contracts, let mark { openInterest = contracts * mark }
        // Nicht gelistet = Standard 8 Std.; Liste nicht ladbar = unbekannt (keine Umrechnung, kein Alarm)
        let intervals = await binanceIntervals()
        return FuturesInfo(
            fundingRatePercent: Double(p.optString("lastFundingRate")).map { $0 * 100.0 },
            nextFundingTime: next > 0 ? next : nil,
            openInterestUsd: openInterest,
            source: source,
            fundingIntervalHours: intervals.map { FuturesParsers.binanceIntervalHours($0, symbol: symbol) }
        )
    }

    /// Binance `GET /fapi/v1/fundingInfo` (alle Kontrakte mit abweichendem Intervall), 6 Std. im Speicher;
    /// scheitert das Neuladen, gilt die letzte Liste bis 7 Tage weiter, sonst nil = unbekannt (wie Android).
    private static func binanceIntervals() async -> [String: Int]? {
        let now = TimeUtils.nowMillis
        let cached = BinanceIntervalCache.value
        if let cached, now - cached.time >= 0, now - cached.time < 6 * 3_600_000 {
            return cached.map
        }
        guard let text = await optionalCall("https://fapi.binance.com/fapi/v1/fundingInfo"),
              let map = try? FuturesParsers.binanceFundingInfo(text) else {
            guard let cached, now - cached.time >= 0, now - cached.time < 7 * 24 * 3_600_000 else { return nil }
            return cached.map
        }
        BinanceIntervalCache.value = (now, map)
        return map
    }

    /// Binance COIN-M: `GET https://dapi.binance.com/dapi/v1/premiumIndex?symbol=BTCUSD_PERP` und
    /// `…/dapi/v1/openInterest?symbol=…`.
    private static func binanceCoinM(symbol: String) async throws -> FuturesInfo {
        async let premiumJob = MarketHTTP.call("https://dapi.binance.com/dapi/v1/premiumIndex?symbol=\(symbol)")
        async let oiJob = optionalCall("https://dapi.binance.com/dapi/v1/openInterest?symbol=\(symbol)")
        async let sizesJob = binanceCoinMSizes()
        let premium = try await premiumJob
        let oi = await oiJob
        return try FuturesParsers.binanceCoinM(premium: premium, openInterest: oi, symbol: symbol,
                                               contractSizes: await sizesJob)
    }

    /// Binance COIN-M `GET /dapi/v1/exchangeInfo`: Kontraktwert je Symbol, wie die Intervall-Liste 6 Std.
    /// im Speicher und bei Fehlschlag bis 7 Tage weiter; sonst nil (dann BTC 100 / sonst 10 USD). Wie Android.
    private static func binanceCoinMSizes() async -> [String: Double]? {
        let now = TimeUtils.nowMillis
        let cached = BinanceCoinMSizeCache.value
        if let cached, now - cached.time >= 0, now - cached.time < 6 * 3_600_000 {
            return cached.map
        }
        guard let text = await optionalCall("https://dapi.binance.com/dapi/v1/exchangeInfo"),
              let map = try? FuturesParsers.binanceCoinMContractSizes(text), !map.isEmpty else {
            guard let cached, now - cached.time >= 0, now - cached.time < 7 * 24 * 3_600_000 else { return nil }
            return cached.map
        }
        BinanceCoinMSizeCache.value = (now, map)
        return map
    }

    private static func bybit(symbol: String) async throws -> FuturesInfo {
        let url = "https://api.bybit.com/v5/market/tickers?category=linear&symbol=\(symbol)"
        let t = try JObject(string: try await MarketHTTP.call(url))
            .object("result").array("list").object(0)
        let next = Int64(t.optString("nextFundingTime"))
        let funding = Double(t.optString("fundingRate")).map { $0 * 100.0 }
        let oiUsd = Double(t.optString("openInterestValue"))
        // Leerer Ticker (weder Funding noch Open Interest): Fehler, damit die Kette mit der
        // nächsten Quelle weitermacht — wie `require` in Android statt «Bybit» ohne Zahlen
        guard funding != nil || oiUsd != nil else {
            throw JSONError(message: "Bybit ohne Futures-Daten für \(symbol)")
        }
        return FuturesInfo(
            fundingRatePercent: funding,
            nextFundingTime: (next ?? 0) > 0 ? next : nil,
            openInterestUsd: oiUsd,
            source: "Bybit",
            fundingIntervalHours: FuturesParsers.hours(Double(t.optString("fundingIntervalHour")))
        )
    }

    /// OKX-Perpetual `instId` (Standard BASE-USDT-SWAP). Funding: «fundingTime» ist die nächste
    /// Abrechnung. Open Interest in USD aus «oiUsd», sonst Coins × Mark-Preis.
    private static func okx(base: String, instId explicitId: String? = nil) async throws -> FuturesInfo {
        let instId = explicitId ?? "\(base)-USDT-SWAP"
        async let fundingJob = MarketHTTP.call("https://www.okx.com/api/v5/public/funding-rate?instId=\(instId)")
        async let oiJob = optionalCall("https://www.okx.com/api/v5/public/open-interest?instType=SWAP&instId=\(instId)")

        let f = try okxFirst(try await fundingJob)
        let oiText = await oiJob
        let oi = oiText.flatMap { try? okxFirst($0) }

        var openInterest: Double? = oi.flatMap { Double($0.optString("oiUsd")) }
        if openInterest == nil, let coins = oi.flatMap({ Double($0.optString("oiCcy")) }) {
            let markText = await optionalCall("https://www.okx.com/api/v5/public/mark-price?instType=SWAP&instId=\(instId)")
            if let mark = markText.flatMap({ try? okxFirst($0) }).flatMap({ Double($0.optString("markPx")) }) {
                openInterest = coins * mark
            }
        }
        let next = Int64(f.optString("fundingTime")) ?? Int64(f.optString("nextFundingTime"))
        return FuturesInfo(
            fundingRatePercent: Double(f.optString("fundingRate")).map { $0 * 100.0 },
            nextFundingTime: (next ?? 0) > 0 ? next : nil,
            openInterestUsd: openInterest,
            source: "OKX",
            // «fundingTime» = nächste, «nextFundingTime» = übernächste Abrechnung: der Abstand ist das Intervall
            fundingIntervalHours: FuturesParsers.okxIntervalHours(
                fundingTime: Int64(f.optString("fundingTime")), nextFundingTime: Int64(f.optString("nextFundingTime")))
        )
    }

    /// OKX-Antwort {"code":"0","data":[{…}]}: erstes Datenobjekt, sonst Fehler.
    private static func okxFirst(_ text: String) throws -> JObject {
        let root = try JObject(string: text)
        guard root.optString("code") == "0" else {
            throw JSONError(message: "OKX-Fehler: \(root.optString("msg"))")
        }
        return try root.array("data").object(0)
    }

    private static func optionalCall(_ url: String) async -> String? {
        try? await MarketHTTP.call(url)
    }
}

/// Abrechnungsintervalle von Binance (Symbol → Stunden), prozessweit; mit Lock wie die anderen Caches
/// (mehrere Abfragen können gleichzeitig lesen und schreiben).
private enum BinanceIntervalCache {
    private static let lock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `lock` (NSLock)
    nonisolated(unsafe) private static var stored: (time: Int64, map: [String: Int])?

    static var value: (time: Int64, map: [String: Int])? {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }
}

/// Kontraktwerte von Binance COIN-M (Symbol → USD je Kontrakt), prozessweit; mit Lock wie
/// `BinanceIntervalCache`.
private enum BinanceCoinMSizeCache {
    private static let lock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `lock` (NSLock)
    nonisolated(unsafe) private static var stored: (time: Int64, map: [String: Double])?

    static var value: (time: Int64, map: [String: Double])? {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }
}
