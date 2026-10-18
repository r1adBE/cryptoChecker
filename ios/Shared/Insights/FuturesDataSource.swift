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

/// Welche Börse ihre Perpetuals mit eigenen Funding-/Open-Interest-Daten versorgt und wie die
/// Herkunft der Kennzahlen zum beobachteten Paar steht — wie `FuturesSources` (Android).
enum FuturesSources {
    static let binance = "Binance Futures"
    static let bybit = "Bybit"
    static let okx = "OKX"
    static let woo = "WOO X Futures"
    static let bitget = "Bitget Futures"
    static let mexc = "MEXC Futures"
    static let htx = "HTX Futures"
    static let hyperliquid = "Hyperliquid"
    static let deribit = "Deribit"
    /// Binance COIN-M (Paare «2:BTCUSD_PERP», `dapi.binance.com`) — eigener Kontrakt, nicht der USDT-Kontrakt.
    static let binanceCoinM = "Binance COIN-M"
    static let coinMPrefix = "2:"

    /// Marktschlüssel → Name der eigenen Quelle (= `FuturesInfo.source`, wenn die Börse selbst antwortet).
    private static let ownSources: [String: String] = [
        "BinanceFutures": binance,
        "BybitFutures": bybit,
        "OkexFutures": okx,
        "WooFutures": woo,
        "BitgetFutures": bitget,
        "MexcFutures": mexc,
        "HtxFutures": htx,
        "Hyperliquid": hyperliquid,
        "Deribit": deribit,
    ]

    /// Börsen, die auch als Ausweichquelle für andere Paare dienen (Kette Binance → Bybit → OKX).
    private static let chain: Set<String> = [binance, bybit, okx]

    /// Eigene Quelle der Börse oder nil, wenn sie keine eigenen Kennzahlen liefert (dann nur Richtwert).
    static func own(_ marketKey: String) -> String? { ownSources[marketKey] }

    /// Eigene Quelle für dieses Paar: bei Binance COIN-M der COIN-M-Kontrakt, sonst `own(_:)`.
    static func own(_ marketKey: String, pairId: String?) -> String? {
        marketKey == "BinanceFutures" && isCoinM(pairId) ? binanceCoinM : own(marketKey)
    }

    /// Binance-COIN-M-Paar (Präfix «2:» wie im Adapter).
    static func isCoinM(_ pairId: String?) -> Bool { pairId?.hasPrefix(coinMPrefix) == true }

    /// Quellen, deren Intervall je Kontrakt verschieden ist und in der Antwort steht.
    private static let perContract: Set<String> = [binance, bybit, okx, bitget, mexc]

    /// Börsen mit eigenen Funding-/Open-Interest-Daten (für die Alarme FUNDING_* / OI_*).
    static var markets: Set<String> { Set(ownSources.keys) }

    /// Abrechnungsintervall der Quelle in Stunden, wenn die Antwort keines nennt: Hyperliquid stündlich,
    /// Deribit, WOO X, HTX und Binance COIN-M 8. Binance, Bybit, OKX, Bitget und MEXC haben keinen Standard
    /// (je Kontrakt verschieden): fehlt die Angabe, ist es unbekannt (nil), nie still 8 — wie Android.
    static func fundingIntervalHours(_ source: String?) -> Int? {
        if source == hyperliquid { return 1 }
        if let source, perContract.contains(source) { return nil }
        return 8
    }

    /// Wie `source` zum Paar steht: nil = Daten der beobachteten Börse; `.FALLBACK` = eigene Börse
    /// (Binance/Bybit/OKX) nicht erreichbar, eine andere sprang ein; `.REFERENCE` = Spot-Paar, Börse
    /// ohne eigene Daten oder eigene Quelle ausgefallen — Kennzahlen einer anderen Börse als Richtwert.
    static func note(perpetual: Bool, marketKey: String, source: String, pairId: String? = nil) -> FuturesSourceNote? {
        guard perpetual, let own = own(marketKey, pairId: pairId) else { return .REFERENCE }
        if source == own { return nil }
        return chain.contains(own) ? .FALLBACK : .REFERENCE
    }

    static func note(_ watch: Watch, source: String) -> FuturesSourceNote? {
        note(perpetual: watch.contractType.isPerpetual, marketKey: watch.marketKey, source: source, pairId: watch.pairId)
    }
}

/// Antworten der Börsen-APIs → `FuturesInfo` — wie `FuturesParsers` (Android), gleiche Testfälle
/// (`futures_derivatives.json`). Funding in Prozent (Antwort × 100), Open Interest in USD bzw.
/// Quote-Währung; ohne Funding und Open Interest wirft der Parser (die Kette nimmt die nächste Quelle).
enum FuturesParsers {

    /// WOO X `GET /v3/public/futures?symbol=PERP_BTC_USDT`: «estFundingRate» (sonst «lastFundingRate»),
    /// «nextFundingTime», «openInterest» in Coins × «markPrice».
    static func woo(_ body: String) throws -> FuturesInfo {
        let row = try JObject(string: body).object("data").array("rows").object(0)
        let funding = num(row, "estFundingRate") ?? num(row, "lastFundingRate")
        return try info(funding, time(row, "nextFundingTime"),
                        product(num(row, "openInterest"), num(row, "markPrice")), FuturesSources.woo)
    }

    /// Bitget-Ticker: «fundingRate», «holdingAmount» (Coins) × «markPrice»; `fundingTime` optional
    /// (`/api/v2/mix/market/funding-time`, «nextFundingTime»).
    static func bitget(ticker: String, fundingTime: String?) throws -> FuturesInfo {
        let t = try JObject(string: ticker).array("data").object(0)
        let mark = num(t, "markPrice") ?? num(t, "lastPr")
        let next = fundingTime.flatMap { text in
            (try? JObject(string: text).array("data").object(0)).flatMap { time($0, "nextFundingTime") }
        }
        let period = fundingTime.flatMap { text in
            (try? JObject(string: text).array("data").object(0)).flatMap { num($0, "ratePeriod") }
        }
        return try info(num(t, "fundingRate"), next, product(num(t, "holdingAmount"), mark), FuturesSources.bitget,
                        intervalHours: hours(period))
    }

    /// MEXC-Ticker: «fundingRate», «holdVol» (Kontrakte) × «contractSize» (`detail`) × «fairPrice»;
    /// `fundingRate` optional (`/api/v1/contract/funding_rate/{symbol}`, «nextSettleTime»).
    static func mexc(ticker: String, detail: String?, fundingRate: String?, symbol: String) throws -> FuturesInfo {
        let t = try JObject(string: ticker).object("data")
        let size = detail.flatMap { mexcContractSize($0, symbol: symbol) }
        let price = num(t, "fairPrice") ?? num(t, "lastPrice")
        var oi: Double? = nil
        if let contracts = num(t, "holdVol"), let size { oi = product(contracts * size, price) }
        let f = fundingRate.flatMap { try? JObject(string: $0).object("data") }
        let funding = num(t, "fundingRate") ?? f.flatMap { num($0, "fundingRate") }
        return try info(funding, f.flatMap { time($0, "nextSettleTime") }, oi, FuturesSources.mexc,
                        intervalHours: hours(f.flatMap { num($0, "collectCycle") }))
    }

    private static func mexcContractSize(_ text: String, symbol: String) -> Double? {
        guard let root = try? JObject(string: text) else { return nil }
        let item = root.optObject("data") ?? root.optArray("data")?.objects.first(where: { $0.optString("symbol") == symbol })
        return item.flatMap { num($0, "contractSize") }.flatMap { $0 > 0 ? $0 : nil }
    }

    /// HTX `swap_funding_rate` («funding_rate») und `swap_open_interest` («value» = Open Interest in USDT).
    static func htx(funding: String, openInterest: String?) throws -> FuturesInfo {
        let root = try JObject(string: funding)
        guard root.optString("status") == "ok" else {
            throw JSONError(message: "HTX-Fehler: \(root.optString("err_msg"))")
        }
        let data = try root.object("data")
        let rate = num(data, "funding_rate")
        let value = openInterest.flatMap { text -> Double? in
            guard let oiRoot = try? JObject(string: text), oiRoot.optString("status") == "ok",
                  let first = try? oiRoot.array("data").object(0) else { return nil }
            return num(first, "value")
        }
        return try info(rate, nil, value, FuturesSources.htx)
    }

    /// Hyperliquid `metaAndAssetCtxs`: Eintrag `coin` in «universe», gleicher Index in den Kontexten:
    /// «funding» (stündliche Rate), «openInterest» in Coins × «markPx».
    static func hyperliquid(_ body: String, coin: String) throws -> FuturesInfo {
        let root = try JArray(string: body)
        let universe = try root.object(0).array("universe")
        let contexts = try root.array(1)
        for i in 0..<min(universe.count, contexts.count) where (try? universe.object(i))?.optString("name") == coin {
            let ctx = try contexts.object(i)
            return try info(num(ctx, "funding"), nil, product(num(ctx, "openInterest"), num(ctx, "markPx")),
                            FuturesSources.hyperliquid)
        }
        throw JSONError(message: "Hyperliquid ohne \(coin)")
    }

    /// Deribit-Ticker: «funding_8h»; «open_interest» bei inversen Perpetuals in USD, bei linearen
    /// USDC-Perpetuals (Name mit «_») in Coins × «mark_price».
    static func deribit(_ body: String) throws -> FuturesInfo {
        let r = try JObject(string: body).object("result")
        let oi = num(r, "open_interest")
        let linear = r.optString("instrument_name").contains("_")
        let usd = linear ? product(oi, num(r, "mark_price")) : oi
        return try info(num(r, "funding_8h"), nil, usd, FuturesSources.deribit)
    }

    private static func info(_ rate: Double?, _ next: Int64?, _ oiUsd: Double?, _ source: String,
                             intervalHours: Int? = nil) throws -> FuturesInfo {
        let funding = rate.map { $0 * 100.0 }
        let oi = oiUsd.flatMap { $0 > 0 ? $0 : nil }
        guard funding != nil || oi != nil else { throw JSONError(message: "\(source) ohne Futures-Daten") }
        return FuturesInfo(fundingRatePercent: funding, nextFundingTime: next, openInterestUsd: oi, source: source,
                           fundingIntervalHours: intervalHours)
    }

    /// Abrechnungsintervall in ganzen Stunden (1 … 24); alles andere = unbekannt — wie Android.
    static func hours(_ value: Double?) -> Int? {
        guard let v = value, v.isFinite else { return nil }
        let rounded = v.rounded()
        guard abs(v - rounded) <= 0.01, rounded >= 1, rounded <= 24 else { return nil }
        return Int(rounded)
    }

    /// Binance `GET /fapi/v1/fundingInfo`: Kontrakte mit angepasstem Intervall. Symbol → Stunden.
    static func binanceFundingInfo(_ body: String) throws -> [String: Int] {
        var map: [String: Int] = [:]
        for item in try JArray(string: body).allObjects() {
            let symbol = item.optString("symbol")
            guard !symbol.isEmpty, let h = hours(num(item, "fundingIntervalHours")) else { continue }
            map[symbol] = h
        }
        return map
    }

    /// Intervall eines Binance-Kontrakts: aus `fundingInfo`, nicht gelistet = 8 Stunden (Standard).
    static func binanceIntervalHours(_ fundingInfo: [String: Int], symbol: String) -> Int { fundingInfo[symbol] ?? 8 }

    /// Binance COIN-M `GET /dapi/v1/premiumIndex?symbol=BTCUSD_PERP` (Liste) und optional
    /// `/dapi/v1/openInterest` (Kontrakte × Kontraktwert in USD, `coinMContractUsd` aus `contractSizes`
    /// der exchangeInfo) — wie Android.
    static func binanceCoinM(premium: String, openInterest: String?, symbol: String,
                             contractSizes: [String: Double]? = nil) throws -> FuturesInfo {
        let rows = try JArray(string: premium).allObjects()
        guard let row = rows.first(where: { $0.optString("symbol") == symbol }) ?? (rows.count == 1 ? rows.first : nil) else {
            throw JSONError(message: "COIN-M ohne \(symbol)")
        }
        let contracts = openInterest.flatMap { try? JObject(string: $0) }.flatMap { num($0, "openInterest") }
        return try info(num(row, "lastFundingRate"), time(row, "nextFundingTime"),
                        product(contracts, coinMContractUsd(symbol, contractSizes: contractSizes)), FuturesSources.binanceCoinM)
    }

    /// Binance COIN-M `GET /dapi/v1/exchangeInfo`: Kontraktwert in USD («contractSize») je Symbol
    /// (Stand der Doku: BTCUSD 100, alle anderen 10). Einträge ohne gültige Grösse fehlen. Wie Android.
    static func binanceCoinMContractSizes(_ body: String) throws -> [String: Double] {
        guard let symbols = try JObject(string: body).optArray("symbols") else { return [:] }
        var map: [String: Double] = [:]
        for item in try symbols.allObjects() {
            let symbol = item.optString("symbol")
            guard !symbol.isEmpty, let size = num(item, "contractSize"), size > 0 else { continue }
            map[symbol] = size
        }
        return map
    }

    /// Kontraktwert eines COIN-M-Kontrakts in USD: «contractSize» aus `contractSizes` (exchangeInfo),
    /// sonst nach der Binance-Kontraktspezifikation BTC 100, alle anderen 10.
    static func coinMContractUsd(_ symbol: String, contractSizes: [String: Double]? = nil) -> Double {
        if let size = contractSizes?[symbol], size.isFinite, size > 0 { return size }
        return symbol.hasPrefix("BTCUSD") ? 100 : 10
    }

    /// OKX: Abstand zwischen nächster und übernächster Abrechnung in Stunden; fehlt eine Zeit = unbekannt.
    static func okxIntervalHours(fundingTime: Int64?, nextFundingTime: Int64?) -> Int? {
        guard let a = fundingTime, let b = nextFundingTime, a > 0, b > a else { return nil }
        return hours(Double(b - a) / 3_600_000)
    }

    private static func product(_ a: Double?, _ b: Double?) -> Double? {
        guard let a, let b else { return nil }
        return a * b
    }

    /// Zahl als JSON-Zahl oder Text; null, NaN und ±∞ = kein Wert (wie Android).
    private static func num(_ json: JObject, _ key: String) -> Double? {
        let d = json.optDouble(key)
        return d.isFinite ? d : nil
    }

    /// Zeitpunkt in Epoch-ms; 0 oder negativ = keiner.
    private static func time(_ json: JObject, _ key: String) -> Int64? {
        guard let d = num(json, key) else { return nil }
        let t = JSONValue.int64(d)
        return t > 0 ? t : nil
    }
}

/// Futures-Kennzahlen — wie `FuturesDataSource.kt`. Perpetuals zuerst von der beobachteten Börse,
/// wenn sie eigene Daten liefert (`FuturesSources.own`), alles andere zuerst von Binance USDⓈ-M — bei
/// anderen Börsen als Richtwert für denselben Coin (`FuturesSources.note`). Ist die erste Quelle gesperrt (Binance in den
/// USA: 451/403) oder liefert nichts, folgen Bybit bzw. Binance und zuletzt OKX.
/// Gesperrte Hosts werden wie bei den Kerzen 6 h übersprungen (`BlockedSources`).
enum FuturesDataSource {

    /// Hostnamen = Schlüssel in `BlockedSources`.
    private static let binanceHost = "fapi.binance.com"
    private static let coinMHost = "dapi.binance.com"
    private static let bybitHost = "api.bybit.com"
    private static let okxHost = "www.okx.com"

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

    // MARK: Funding-Verlauf («Warum?»)

    private typealias HistoryAttempt = (host: String, run: () async throws -> FundingHistory)

    private static let historyLock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `historyLock` (NSLock)
    nonisolated(unsafe) private static var historyCache: [String: (time: Int64, value: FundingHistory)] = [:]
    private static let historyCacheMillis: Int64 = 10 * 60_000

    /// Die letzten `FundingTrendRules.limit` abgerechneten Funding Rates — gleiche Ausweich-Kette
    /// und Symbole wie `fetch` (Perpetual) bzw. `fetchForBase` (sonst), gesperrte Hosts übersprungen.
    /// Einmal beim Öffnen von «Warum?»; Ergebnisse ~10 Min. im Speicher. nil = keine Quelle (wie Android).
    ///  - Binance: `GET https://fapi.binance.com/fapi/v1/fundingRate?symbol=…&limit=4`
    ///  - Bybit: `GET https://api.bybit.com/v5/market/funding/history?category=linear&symbol=…&limit=4`
    ///  - OKX: `GET https://www.okx.com/api/v5/public/funding-rate-history?instId=…&limit=4`
    static func fundingHistory(watch: Watch) async -> FundingHistory? {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let perpetual = watch.contractType.isPerpetual
        let key = perpetual ? "\(watch.marketKey)|\(watch.pairId ?? "")|\(base)" : "SPOT|\(base)"
        let now = TimeUtils.nowMillis
        if let hit = cachedHistory(key, now: now) { return hit }
        let usdt = "\(base)USDT"
        let swap = "\(base)-USDT-SWAP"
        let attempts: [HistoryAttempt]
        switch perpetual ? watch.marketKey : "" {
        case "BybitFutures":
            let symbol = watch.pairId ?? usdt
            attempts = [
                (host: bybitHost, run: { try await bybitHistory(symbol: symbol) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "BinanceFutures" where FuturesSources.isCoinM(watch.pairId):
            let coinM = String((watch.pairId ?? "").dropFirst(FuturesSources.coinMPrefix.count))
            attempts = [
                (host: coinMHost, run: { try await binanceCoinMHistory(symbol: coinM) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "BinanceFutures":
            let symbol: String
            if let pairId = watch.pairId, pairId.hasSuffix("USDT") || pairId.hasSuffix("USDC") {
                symbol = pairId
            } else {
                symbol = usdt
            }
            attempts = [
                (host: binanceHost, run: { try await binanceHistory(symbol: symbol) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "OkexFutures":
            let instId = watch.pairId.flatMap { $0.hasSuffix("-SWAP") ? $0 : nil } ?? swap
            attempts = [
                (host: okxHost, run: { try await okxHistory(instId: instId) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
            ]
        default:
            attempts = [
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        }
        for attempt in attempts {
            if BlockedSources.isBlocked(attempt.host) { continue }
            do {
                let result = try await attempt.run()
                storeHistory(key, result)
                return result
            } catch {
                // Abbruch (Blatt zu): nichts merken, nicht weiter fragen
                if error is CancellationError || Task.isCancelled { return nil }
                BlockedSources.noteFailure(attempt.host, error)
            }
        }
        // Fehler nicht merken: beim nächsten Öffnen neu versuchen
        return nil
    }

    private static func binanceHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://fapi.binance.com/fapi/v1/fundingRate?symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBinance(text), source: "Binance Futures")
    }

    private static func binanceCoinMHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://dapi.binance.com/dapi/v1/fundingRate?symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBinance(text), source: FuturesSources.binanceCoinM)
    }

    private static func bybitHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBybit(text), source: "Bybit")
    }

    private static func okxHistory(instId: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://www.okx.com/api/v5/public/funding-rate-history?instId=\(instId)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseOkx(text), source: "OKX")
    }

    /// Leere Antwort zählt als Fehler, damit die Kette mit der nächsten Quelle weitermacht.
    private static func history(_ points: [FundingPoint], source: String) throws -> FundingHistory {
        guard !points.isEmpty else { throw JSONError(message: "Kein Funding-Verlauf von \(source)") }
        return FundingHistory(points: points, source: source)
    }

    private static func cachedHistory(_ key: String, now: Int64) -> FundingHistory? {
        historyLock.lock(); defer { historyLock.unlock() }
        guard let entry = historyCache[key] else { return nil }
        let age = now - entry.time
        return age >= 0 && age < historyCacheMillis ? entry.value : nil
    }

    private static func storeHistory(_ key: String, _ value: FundingHistory) {
        historyLock.lock(); defer { historyLock.unlock() }
        historyCache[key] = (time: TimeUtils.nowMillis, value: value)
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
