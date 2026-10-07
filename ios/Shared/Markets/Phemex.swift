import Foundation

/// Phemex Spot. Symbole mit „s“ davor (sBTCUSDT). Kurse sind ganze Zahlen,
/// skaliert mit 10^8 (Ep/Ev). https://github.com/phemex/phemex-api-docs
final class Phemex: PhemexBase {
    private static let scale = 100_000_000.0

    init() {
        super.init(key: "Phemex", name: "Phemex",
                   tickerURL: "https://api.phemex.com/md/spot/ticker/24hr?symbol=%1$s", contractType: .none)
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "s\(info.base)\(info.quote)" }

    override func read(_ json: JObject, _ ticker: inout Ticker) throws {
        func scaled(_ name: String) -> Double {
            let v = json.optDouble(name)
            return v.isNaN ? Ticker.noData : v / Phemex.scale
        }
        ticker.last = try json.double("lastEp") / Phemex.scale
        ticker.bid = scaled("bidEp")
        ticker.ask = scaled("askEp")
        ticker.high = scaled("highEp")
        ticker.low = scaled("lowEp")
        ticker.vol = scaled("volumeEv")
        ticker.volQuote = scaled("turnoverEv")
        ticker.timestamp = PhemexBase.nanosToMillis(json)
        // 24-h-Ticker: openEp = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: scaled("openEp"))
    }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.phemex.com/md/spot/ticker/24hr/all" }
}

/// Phemex USDT-Perpetuals. Werte als Dezimal-Strings (Rp/Rq/Rv), ohne Geld-/Briefkurs.
final class PhemexFutures: PhemexBase {
    init() {
        super.init(key: "PhemexFutures", name: "Phemex Futures",
                   tickerURL: "https://api.phemex.com/md/v2/ticker/24hr?symbol=%1$s", contractType: .perpetual)
    }

    override func pairId(_ info: CheckerInfo) -> String? { info.pairId ?? "\(info.base)\(info.quote)" }

    override func read(_ json: JObject, _ ticker: inout Ticker) throws {
        ticker.last = try json.double("closeRp")
        ticker.high = json.optDoubleNoData("highRp")
        ticker.low = json.optDoubleNoData("lowRp")
        ticker.vol = json.optDoubleNoData("volumeRq")
        ticker.volQuote = json.optDoubleNoData("turnoverRv")
        ticker.timestamp = PhemexBase.nanosToMillis(json)
        // 24-h-Ticker: openRp = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: ticker.last, open: json.optDouble("openRp"))
    }

    override func bulkTickersURL(requestId: Int) -> String? { "https://api.phemex.com/md/v2/ticker/24hr/all" }
}

class PhemexBase: SimpleMarket {
    private let contractType: FuturesContractType

    init(key: String, name: String, tickerURL: String, contractType: FuturesContractType) {
        self.contractType = contractType
        super.init(key: key, name: name, pairsURL: "https://api.phemex.com/public/products", tickerURL: tickerURL, ttsName: "Phemex")
    }

    override func parseCurrencyPairs(requestId: Int, json: JObject) throws -> [CurrencyPairInfo] {
        let data = try json.object("data")
        var pairs: [CurrencyPairInfo] = []
        if contractType == .none {
            for item in try data.array("products").allObjects() {
                if item.optString("type") != "Spot" || item.optString("status") != "Listed" { continue }
                try pairs.append(CurrencyPairInfo(item.string("baseCurrency"), item.string("quoteCurrency"), item.string("symbol")))
            }
        } else {
            for item in try data.optArray("perpProductsV2")?.allObjects() ?? [] {
                if item.optString("status") != "Listed" { continue }
                let type = item.optString("type")
                if !type.isEmpty && !type.localizedCaseInsensitiveContains("Perpetual") { continue }
                try pairs.append(CurrencyPairInfo(item.string("baseCurrency"), item.string("quoteCurrency"),
                                                  item.string("symbol"), contractType))
            }
        }
        return pairs
    }

    func read(_ json: JObject, _ ticker: inout Ticker) throws {}

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        try read(json.object("result"), &ticker)
    }

    /// Phemex meldet Fehler als {"error":{…}} oder {"code":…,"msg":"…"}.
    override func parseError(requestId: Int, json: JObject, info: CheckerInfo) throws -> String? {
        if let error = json.optObject("error") {
            let message = error.optString("message")
            return message.isEmpty ? "\(error.raw)" : message
        }
        let msg = json.optString("msg")
        if msg.isEmpty { throw JSONError(message: "Kein Fehlertext") }
        return msg
    }

    override var bulkTickersNumOfRequests: Int { 1 }

    override func parseBulkTickers(requestId: Int, response: String) throws -> [String: Ticker] {
        guard let list = try JObject(string: response).optArray("result") else { return [:] }
        var tickers: [String: Ticker] = [:]
        for item in list.objects {
            let symbol = item.optString("symbol")
            if symbol.isEmpty { continue }
            var ticker = Ticker()
            do { try read(item, &ticker) } catch { continue }
            tickers[symbol] = ticker
        }
        return tickers
    }

    /// Zeitstempel in Nanosekunden (teils als String).
    static func nanosToMillis(_ json: JObject) -> Int64 {
        let raw = json.optString("timestamp")
        if let n = Int64(raw) { return n / 1_000_000 }
        if let d = Double(raw) { return Int64(d / 1_000_000) }
        return 0
    }
}
