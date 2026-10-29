import Foundation

private let bitunixFuturesAPI = "https://fapi.bitunix.com/api/v1/futures/market"

/// Bitunix, nur Perpetuals (öffentliche Futures-Schnittstelle ohne Schlüssel). Antworten {"code", "data", "msg"};
/// code 0 = Erfolg. Paarliste `trading_pairs`, Kurse `tickers` (mit `symbols` gefiltert, ohne alle Paare).
/// Kennung = `symbol` («BTCUSDT»). Die Spot-Schnittstelle kennt keinen 24-h-Ticker, darum nur Futures.
/// Die API nennt keine Art je Kontrakt: Aktien-Kontrakte (AAPL, TSLA) erscheinen wie Krypto (`tradFi` false).
/// Wie `BitunixFutures.kt`.
struct BitunixFutures: Exchange {
    let key: String = "BitunixFutures"
    let name: String = "Bitunix Futures"

    var category: ExchangeCategory { .futures }

    // MARK: Einzelkurs

    func tickerAddress(call: Int, info: PairQuery) -> String {
        bitunixFuturesAPI + "/tickers?symbols=" + (info.pairId ?? "")
    }

    /// Nur die Zeile des gefragten Symbols zählt; fehlt sie, «keine Daten».
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try json.array("data").allObjects()
        guard let row = rows.first(where: { $0.optString("symbol") == info.pairId }) else {
            throw JSONError(message: JSONError.noData)
        }
        try BitunixFutures.copyRow(row, into: &ticker)
    }

    /// code 0 heisst Erfolg — dann ist «msg» («Success») kein Fehlertext. Sonst z. B. 20015 «this Futures is not
    /// allowed to trade.».
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("code", -1) == 0 { try noErrorText() }
        return try json.string("msg")
    }

    // MARK: Sammelabfrage (ohne `symbols` alle Paare)

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bitunixFuturesAPI + "/tickers" }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try BitunixFutures.copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    func pairListAddress(call: Int) -> String? { bitunixFuturesAPI + "/trading_pairs" }

    /// {"data": [{symbol, base, quote, symbolStatus}]}; nur «OPEN» (nicht PREVIEW, CANCEL_ONLY, STOP).
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("data").allObjects() {
            guard entry.optString("symbolStatus") == "OPEN" else { continue }
            let symbol = try entry.string("symbol")
            let quote = try entry.string("quote")
            let base = BitunixFutures.base(symbol: symbol, base: entry.optString("base"), quote: quote)
            out.append(ExchangePair(base, quote, symbol, .perpetual, tradFi: false))
        }
        return out
    }

    /// Basis aus dem Symbol ohne die Endung `quote` (1000PEPEUSDT → «1000PEPE»: der Kurs gilt für 1000 Stück, die
    /// Antwort nennt aber «PEPE»). Endet das Symbol nicht auf `quote` oder bliebe nichts übrig: `base`. Wie Android.
    static func base(symbol: String, base: String, quote: String) -> String {
        guard !quote.isEmpty, symbol.hasSuffix(quote), symbol.count > quote.count else { return base }
        return String(symbol.dropLast(quote.count))
    }

    // MARK: Lesen

    /// Letzter Kurs, 24-h-Hoch/-Tief und -Volumen; Veränderung aus «open» (Eröffnung der letzten 24 h laut Doku).
    /// Keine Zeit der Börse.
    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVol")
        ticker.volQuote = row.optDoubleOrMissing("quoteVol")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }
}
