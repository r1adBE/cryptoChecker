import Foundation

/// Binance TR (Türkei), öffentliche «open API» wie Tokocrypto: https://www.binance.tr/apidocs/ — ausserhalb der
/// Türkei antwortet die Börse mit HTTP 451 (Standort gesperrt). Paare des Typs 1 laufen über `api.binance.me`.
struct BinanceTr: BinanceCloudBase {
    let key: String = "BinanceTr"
    let name: String = "Binance TR"
    let host: String = "https://www.binance.tr"
    let binanceAPI: String = "https://api.binance.me/api/v3"
}

/// Gemeinsamer Aufbau von Binance TR und Tokocrypto («Binance Cloud»): Paarliste `open/v1/common/symbols`
/// (`data.list`, Kennung «BTC_TRY»). Eigene Märkte (Typ ≠ 1) liefern den Kurs über den letzten Handel
/// (`open/v1/market/trades`, Antwort `{"code": 0, "data": [...]}`), ohne 24-h-Werte (die App rechnet mit Kerzen).
/// Märkte des Typs 1 führt das Binance-Orderbuch `binanceAPI` — ihre Kennung ist ohne «_» («BTCUSDT»), der Kurs
/// kommt vom 24-h-Ticker im Binance-Format. Keine Sammelabfrage.
protocol BinanceCloudBase: SimpleExchange {
    /// Eigene Adresse der Börse («https://www.binance.tr»).
    var host: String { get }
    /// Binance-Orderbuch der Märkte des Typs 1 («…/api/v3»).
    var binanceAPI: String { get }
}

extension BinanceCloudBase {
    var pairListURL: String { host + "/open/v1/common/symbols" }
    var tickerURL: String { host + "/open/v1/market/trades?symbol=%1$s&limit=1" }
    var errorField: String? { "msg" }

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "_" + info.quote.uppercased()
    }

    /// Eigener Markt («BTC_TRY»): letzter Handel; Typ 1 («BTCUSDT»): 24-h-Ticker des Binance-Orderbuchs.
    func tickerAddress(call: Int, info: PairQuery) -> String {
        let id = pairId(info) ?? ""
        return id.contains("_") ? simpleTickerAddress(info) : binanceAPI + "/ticker/24hr?symbol=" + id
    }

    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        if (pairId(info) ?? "").contains("_") {
            try Self.readLastTrade(body, into: &ticker)
        } else {
            try Self.readBinanceDay(try JObject(string: body), into: &ticker)
        }
    }

    /// `{"code": ≠0, "msg": …}`; Code 0 (oder ohne Code) ist kein Fehler.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("code", 0) == 0 { try noErrorText() }
        return try simpleErrorMessage(json)
    }

    // MARK: Paarliste

    /// Nur `spotTradingEnable` «1»; Typ 1 bekommt die Kennung des Binance-Orderbuchs (ohne «_»).
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        let rows = try json.optObject("data")?.array("list") ?? json.array("data")
        for market in try rows.allObjects() where market.optString("spotTradingEnable") == "1" {
            let base = try market.string("baseAsset").uppercased()
            let quote = try market.string("quoteAsset").uppercased()
            let id = market.optString("type") == "1" ? base + quote : try market.string("symbol")
            out.append(ExchangePair(base, quote, id))
        }
        return out
    }

    // MARK: Lesen

    /// Letzter Handel: `data` als Liste (oder `data.list`), der jüngste Eintrag zählt; `time` = Zeit des Handels.
    private static func readLastTrade(_ body: String, into ticker: inout Ticker) throws {
        let json = try JObject(string: body)
        let rows = try json.optObject("data")?.array("list") ?? json.array("data")
        guard rows.count > 0 else { throw JSONError(message: JSONError.noData) }
        let row = try rows.object(rows.count - 1)
        ticker.last = try row.double("price")
        ticker.timestamp = row.optLong("time")
    }

    /// 24-h-Ticker wie Binance: `priceChangePercent` gleitend über 24 h, bereits Prozent; `closeTime` ist kein Handel.
    private static func readBinanceDay(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }
}
