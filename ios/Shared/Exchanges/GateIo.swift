import Foundation

private let gateIoAPI = "https://api.gateio.ws/api/v4/spot"

/// Ticker: mit `currency_pair` ein Paar, ohne Parameter alle.
private let gateIoTickers = gateIoAPI + "/tickers"

/// Gate.io Spot (API v4). Kennung = `id` der Paarliste («BTC_USDT»).
final class GateIo: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "GateIo",
            name: "Gate.io",
            pairListURL: gateIoAPI + "/currency_pairs",
            tickerURL: gateIoTickers + "?currency_pair=%1$s",
            ttsName: "Gate io",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try JArray(string: body)
        if rows.isEmpty { throw JSONError(message: JSONError.noData) }
        try copyRow(rows.object(0), into: &ticker)
    }

    // MARK: Sammelabfrage: derselbe Endpunkt ohne currency_pair

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { gateIoTickers }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("currency_pair"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try JArray(string: body).allObjects() {
            // «trade_status» muss da sein; nur «tradable» kommt in die Liste
            guard try entry.string("trade_status") == "tradable" else { continue }
            out.append(ExchangePair(try entry.string("base"), try entry.string("quote"), try entry.string("id")))
        }
        return out
    }

    // MARK: Namen

    /// Volle Namen aus derselben Liste (`base_name`, `quote_name`), etwa «QTUM» → «Quantum».
    override func parseBaseNames(call: Int, body: String) throws -> [String: String] {
        var names: [String: String] = [:]
        for entry in try JArray(string: body).allObjects() where entry.optString("trade_status") == "tradable" {
            GateIo.remember(&names, entry, codeField: "base", nameField: "base_name")
            GateIo.remember(&names, entry, codeField: "quote", nameField: "quote_name")
        }
        return names
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Einzel- wie Sammelabruf).
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.high = try row.double("high_24h")
        ticker.low = try row.double("low_24h")
        ticker.vol = try row.double("base_volume")
        ticker.volQuote = try row.double("quote_volume")
        // ohne Orderbuch schickt Gate "" für Geld/Brief: dann fehlen sie, statt die Zeile zu verwerfen
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        // «change_percentage»: gleitende 24 h in Prozent (change_utc0/utc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.percent(row.optDouble("change_percentage"))
    }

    /// Erster Name je Kürzel gewinnt; leere Angaben (auch JSON null) zählen nicht.
    private static func remember(_ names: inout [String: String], _ entry: JObject, codeField: String,
                                 nameField: String) {
        let code = entry.optString(codeField)
        let name = entry.optString(nameField)
        if code.isEmpty || name.isEmpty || names[code] != nil { return }
        names[code] = name
    }
}
