import Foundation

private let hashKeyAPI = "https://api-pro.hashkey.com"

/// 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle.
private let hashKeyDayTicker = hashKeyAPI + "/quote/v1/ticker/24hr"

/// `status` eines handelbaren Paars in `exchangeInfo`.
private let hashKeyStatusTrading = "TRADING"

/// HashKey Exchange (Hongkong, lizenziert; USD-, HKD- und Stablecoin-Paare), öffentliche Kurse ohne Schlüssel:
/// https://hashkeypro-apidoc.readme.io. Kennung «BTCUSD». Der Ticker kommt als Liste, auch für ein einzelnes Paar.
final class HashKey: SimpleMarket {
    init() {
        super.init(
            key: "HashKey",
            name: "HashKey Exchange",
            pairListURL: hashKeyAPI + "/api/v1/exchangeInfo",
            tickerURL: hashKeyDayTicker + "?symbol=%1$s",
            ttsName: "Hash Key Exchange",
            errorField: "msg"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    /// Liste mit einer Zeile (dokumentiert) oder, vorsichtshalber, ein einzelnes Objekt. Leere Liste = «No data».
    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let row: JObject
        if body.drop(while: { $0.isWhitespace }).hasPrefix("[") {
            let rows = try JArray(string: body)
            if rows.isEmpty { throw JSONError(message: JSONError.noData) }
            row = try rows.object(0)
        } else {
            row = try JObject(string: body)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Ob die ungefilterte Liste jedes handelbare Paar enthält, ist unbelegt: Fehlende prüft die Einzelabfrage.
    override var bulkTickersComplete: Bool { false }

    override func bulkTickersURL(call: Int) -> String? { hashKeyDayTicker }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("s"), row: row, priceField: "c", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for symbol in try json.array("symbols").allObjects() where symbol.optString("status") == hashKeyStatusTrading {
            out.append(MarketPair(symbol.optString("baseAsset"), symbol.optString("quoteAsset"),
                                  symbol.optString("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile mit Kurzfeldern (c = letzter Kurs, h/l = 24-h-Hoch/-Tief, v/qv = Volumen). `t` ist die
    /// Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("c")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("b")
        ticker.ask = row.optDoubleOrMissing("a")
        ticker.high = row.optDoubleOrMissing("h")
        ticker.low = row.optDoubleOrMissing("l")
        ticker.vol = row.optDoubleOrMissing("v")
        ticker.volQuote = row.optDoubleOrMissing("qv")
        // «o»: Eröffnung des gleitenden 24-h-Fensters
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("o"))
    }
}
