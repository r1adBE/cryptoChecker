import Foundation

// Futures-Börsen: Herkunft der Kennzahlen und Parser der einzelnen Börsen — wie `FuturesExchanges.kt` (Android).

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
