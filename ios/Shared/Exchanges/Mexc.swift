import Foundation

private let mexcSpot = "https://api.mexc.com/api/v3"
private let mexcContract = "https://contract.mexc.com/api/v1/contract"

/// Handelbar-Werte von «status» (aktuell «1», ältere Antworten «ENABLED»/«TRADING»).
private let mexcTradable: Set<String> = ["1", "ENABLED", "TRADING"]

/// MEXC Spot (Aufbau ähnlich Binance v3).
final class Mexc: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Mexc",
            name: "MEXC",
            pairListURL: mexcSpot + "/exchangeInfo",
            tickerURL: mexcSpot + "/ticker/24hr?symbol=%1$s",
            errorField: "msg"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { mexcSpot + "/ticker/24hr" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for symbol in try json.array("symbols").allObjects() {
            let tradable = mexcTradable.contains(symbol.optString("status")) && symbol.optBool("isSpotTradingAllowed", true)
            guard tradable else { continue }
            out.append(ExchangePair(try symbol.string("baseAsset"), try symbol.string("quoteAsset"),
                                        try symbol.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // «closeTime» = Fensterende (≈ jetzt), kein Handelszeitpunkt — Zeit der Börse bleibt offen.
        // Veränderung aus «openPrice» (Kurs vor 24 h); sonst «priceChangePercent» (bei MEXC ein Bruchteil)
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("openPrice"))
            ?? Change24h.fraction(row.optDouble("priceChangePercent"))
    }
}

/// MEXC USDT-Perpetuals (contract.mexc.com).
final class MexcFutures: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    override var category: ExchangeCategory { .futures }

    init() {
        super.init(
            key: "MexcFutures",
            name: "MEXC Futures",
            pairListURL: mexcContract + "/detail",
            tickerURL: mexcContract + "/ticker?symbol=%1$s",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("data"), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { mexcContract + "/ticker" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for contract in try json.array("data").allObjects() {
            // state 0 = aktiv, futureType 1 = Perpetual
            let active = contract.optInt("state", -1) == 0
            let perpetual = contract.optInt("futureType", 1) == 1
            guard active, perpetual else { continue }
            out.append(ExchangePair(
                try contract.string("baseCoin"),
                try contract.string("quoteCoin"),
                try contract.string("symbol"),
                .perpetual,
                tradFi: TradFi.mexc(conceptPlates: contract.optStrings("conceptPlate"), type: contract.optInt("type", 1))
            ))
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid1")
        ticker.ask = row.optDoubleOrMissing("ask1")
        ticker.high = row.optDoubleOrMissing("high24Price")
        ticker.low = row.optDoubleOrMissing("lower24Price")
        // «volume24» zählt Kontrakte: kein Coin-Volumen, nur der Umsatz in der Kotierung
        ticker.vol = Ticker.missing
        ticker.volQuote = row.optDoubleOrMissing("amount24")
        // «timestamp» = Momentaufnahme, kein Handel.
        // «riseFallRate» ist die Veränderung seit 00:00 UTC+8, nicht über 24 h — darum keine 24-h-Veränderung
    }
}
