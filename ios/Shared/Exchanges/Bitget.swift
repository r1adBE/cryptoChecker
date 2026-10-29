import Foundation

/// Gemeinsame API-Wurzel von Spot und Futures.
private let bitgetAPI = "https://api.bitget.com/api/v2"

/// Produktart der USDT-Perpetuals, Teil jeder Futures-Adresse.
private let bitgetUsdtFutures = "productType=USDT-FUTURES"

/// Bitget Spot (API v2).
struct Bitget: BitgetBase {
    let key: String = "Bitget"
    let name: String = "Bitget"
    let pairListURL: String = bitgetAPI + "/spot/public/symbols"
    let tickerURL: String = bitgetAPI + "/spot/market/tickers?symbol=%1$s"
    let allTickersURL: String = bitgetAPI + "/spot/market/tickers"

    func pairOf(_ entry: JObject) throws -> ExchangePair? {
        guard entry.optString("status") == "online" else { return nil }
        return ExchangePair(try entry.string("baseCoin"), try entry.string("quoteCoin"), try entry.string("symbol"))
    }
}

/// Bitget USDT-Perpetuals.
struct BitgetFutures: BitgetBase {
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    var category: ExchangeCategory { .futures }

    let key: String = "BitgetFutures"
    let name: String = "Bitget Futures"
    let pairListURL: String = bitgetAPI + "/mix/market/contracts?" + bitgetUsdtFutures
    let tickerURL: String = bitgetAPI + "/mix/market/ticker?symbol=%1$s&" + bitgetUsdtFutures
    let allTickersURL: String = bitgetAPI + "/mix/market/tickers?" + bitgetUsdtFutures

    func pairOf(_ entry: JObject) throws -> ExchangePair? {
        let perpetual = entry.optString("symbolType") == "perpetual"
        let normal = entry.optString("symbolStatus") == "normal"
        guard perpetual, normal else { return nil }
        return ExchangePair(
            try entry.string("baseCoin"),
            try entry.string("quoteCoin"),
            try entry.string("symbol"),
            .perpetual,
            tradFi: TradFi.bitget(isRwa: entry.optString("isRwa"))
        )
    }
}

/// Spot und Futures unterscheiden sich nur in Adressen und Paarfilter (`pairOf`); Antworten haben die Form
/// {"code", "msg", "data": [...]}, die Ticker-Felder heissen gleich.
protocol BitgetBase: SimpleExchange {
    /// Sammelabfrage (alle Paare).
    var allTickersURL: String { get }

    /// Paar aus einem Eintrag der Paarliste; nil = nicht wählbar. Jede der beiden Börsen liefert das (in Kotlin abstrakt).
    func pairOf(_ entry: JObject) throws -> ExchangePair?
}

extension BitgetBase {
    var errorField: String? { "msg" }

    // MARK: Einzelkurs

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.array("data").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { allTickersURL }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPr", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        try json.array("data").allObjects().compactMap { entry in try pairOf(entry) }
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPr")
        ticker.bid = row.optDoubleOrMissing("bidPr")
        ticker.ask = row.optDoubleOrMissing("askPr")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // `ts` = Zeitpunkt der Momentaufnahme, kein Handel → Zeit der Börse unbekannt.
        // «change24h» ist gleitend (Bruchteil); «changeUtc24h» (ab 0 Uhr UTC) passt nicht.
        ticker.change24hPercent = Change24h.fraction(row.optDouble("change24h"))
    }
}
