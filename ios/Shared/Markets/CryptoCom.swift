import Foundation

private let cryptoComAPI = "https://api.crypto.com/exchange/v1/public"

/// Gegenwährungen, bei denen `vv` (USD) als Volumen in der Gegenwährung gilt.
private let cryptoComDollarQuotes: Set<String> = ["USD", "USDT", "USDC"]

/// Crypto.com Exchange Spot (API v1). Ticker-Felder sind Einzelbuchstaben: a = letzter Kurs, b/k = Geld/Brief,
/// h/l = Hoch/Tief, v = Menge, vv = Umsatz in USD, c = Veränderung, i = Instrument, t = Zeit.
final class CryptoCom: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "CryptoCom",
            name: "Crypto.com",
            pairListURL: cryptoComAPI + "/get-instruments",
            tickerURL: cryptoComAPI + "/get-tickers?instrument_name=%1$s",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(CryptoCom.dataOf(json).object(0), into: &ticker, counter: info.quote)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { cryptoComAPI + "/get-tickers" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try CryptoCom.dataOf(JObject(string: body)).allObjects() {
            bulkRowIfActive(row.optString("i"), row: row, priceField: "a", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for entry in try CryptoCom.dataOf(json).allObjects() {
            let spotPair = entry.optString("inst_type") == "CCY_PAIR"
            guard spotPair, entry.optBool("tradable") else { continue }
            out.append(MarketPair(try entry.string("base_ccy"), try entry.string("quote_ccy"),
                                        try entry.string("symbol")))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile. `counter`: Gegenwährung; ohne Angabe aus dem Instrument `i` («BTC_EUR» → EUR). `t` ist
    /// die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker, counter: String? = nil) throws {
        ticker.last = try row.double("a")
        ticker.bid = row.optDoubleOrMissing("b")
        ticker.ask = row.optDoubleOrMissing("k")
        ticker.high = row.optDoubleOrMissing("h")
        ticker.low = row.optDoubleOrMissing("l")
        ticker.vol = row.optDoubleOrMissing("v")
        // «vv» ist USD-Umsatz: nur bei USD-artiger Gegenwährung das Volumen in der Gegenwährung, sonst keines
        // (die Oberfläche schätzt dann Menge × Kurs, QuickFilters)
        let quote = (counter ?? CryptoCom.quoteOfInstrument(row.optString("i"))).uppercased()
        ticker.volQuote = cryptoComDollarQuotes.contains(quote) ? row.optDoubleOrMissing("vv") : Ticker.missing
        // «c»: gleitende 24 h als Bruchteil (0.0583 = +5,83 %), null ohne Handel
        ticker.change24hPercent = Change24h.fraction(row.optDouble("c"))
    }

    /// Teil nach dem letzten Unterstrich («BTC_EUR» → «EUR»); ohne Unterstrich leer.
    private static func quoteOfInstrument(_ instrument: String) -> String {
        guard let cut = instrument.lastIndex(of: "_") else { return "" }
        return String(instrument[instrument.index(after: cut)...])
    }

    /// `result.data` einer Antwort.
    private static func dataOf(_ envelope: JObject) throws -> JArray {
        try envelope.object("result").array("data")
    }
}
