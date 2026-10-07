import Foundation

/// WOO X Spot. API v3: https://developer.woox.io
///
/// Für Spot gibt es keinen 24-h-Ticker. Der Kurs kommt deshalb aus den letzten
/// 24 Stundenkerzen: Schluss der jüngsten Kerze = letzter Kurs, Hoch/Tief und
/// Volumen über alle 24 — so entspricht es einem gleitenden 24-h-Fenster.
final class Woo: WooBase {
    init() { super.init(key: "Woo", name: "WOO X", prefix: "SPOT", contractType: .none) }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "\(WooBase.base)/v3/public/kline?symbol=\(pairId(info) ?? "")&type=1h&limit=24"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let rows = try json.object("data").array("rows").allObjects()
        guard let newest = rows.max(by: { $0.optLong("startTimestamp") < $1.optLong("startTimestamp") }) else {
            throw JSONError(message: "Keine Kerzen")
        }
        ticker.last = try newest.double("close")
        let highs = rows.map { $0.optDouble("high") }.filter { !$0.isNaN }
        let lows = rows.map { $0.optDouble("low") }.filter { !$0.isNaN }
        if let h = highs.max() { ticker.high = h }
        if let l = lows.min() { ticker.low = l }
        ticker.vol = rows.reduce(0) { $0 + $1.optDouble("volume", 0) }
        ticker.volQuote = rows.reduce(0) { $0 + $1.optDouble("amount", 0) }
        ticker.timestamp = json.optLong("timestamp")

        // Eröffnung der ältesten der 24 Stundenkerzen ≈ Kurs vor 24 h (nur mit vollem Fenster)
        let oldest = rows.min(by: { $0.optLong("startTimestamp") < $1.optLong("startTimestamp") })
        if rows.count >= 24, let oldest {
            ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: oldest.optDouble("open"))
        } else {
            ticker.change24hPercent = nil
        }
    }
}

/// WOO X USDT-Perpetuals.
final class WooFutures: WooBase {
    init() { super.init(key: "WooFutures", name: "WOO X Futures", prefix: "PERP", contractType: .perpetual) }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "\(WooBase.base)/v3/public/futures?symbol=\(pairId(info) ?? "")"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try readFutures(json.object("data").array("rows").object(0), &ticker)
        ticker.timestamp = json.optLong("timestamp")
    }

    private func readFutures(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("24hClose")
        ticker.high = json.optDoubleNoData("24hHigh")
        ticker.low = json.optDoubleNoData("24hLow")
        ticker.vol = json.optDoubleNoData("24hVolume")
        ticker.volQuote = json.optDoubleNoData("24hAmount")
        // 24hOpen = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("24hOpen"))
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(requestId: Int) -> String? { "\(WooBase.base)/v3/public/futures" }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        let json = try JObject(string: response)
        let time = json.optLong("timestamp")
        var tickers: [String: Ticker] = [:]
        for row in try json.object("data").array("rows").allObjects() {
            let symbol = row.optString("symbol")
            if symbol.isEmpty { continue }
            var ticker = Ticker()
            do { try readFutures(row, &ticker) } catch { continue }
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
        return tickers
    }

    override var bulkTickersComplete: Bool { true }
}

class WooBase: SimpleMarket {
    static let base = "https://api.woox.io"
    private let prefix: String
    private let contractType: FuturesContractType

    init(key: String, name: String, prefix: String, contractType: FuturesContractType) {
        self.prefix = prefix
        self.contractType = contractType
        super.init(key: key, name: name, pairsURL: "\(WooBase.base)/v3/public/instruments", tickerURL: "",
                   ttsName: "Woo X", errorPropertyName: "message")
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        for item in try json.object("data").array("rows").allObjects() {
            if item.optString("status") != "TRADING" { continue }
            let symbol = item.optString("symbol")
            // SPOT_BTC_USDT bzw. PERP_BTC_USDT
            let parts = symbol.split(separator: "_").map(String.init)
            if parts.count != 3 || parts[0] != prefix { continue }
            let base = item.optString("baseAsset")
            let quote = item.optString("quoteAsset")
            pairs.append(CurrencyPairInfo(base.isEmpty ? parts[1] : base, quote.isEmpty ? parts[2] : quote, symbol, contractType))
        }
        return pairs
    }

    override func pairId(_ info: CheckerInfo) -> String? {
        info.pairId ?? "\(prefix)_\(info.base)_\(info.quote)"
    }
}
