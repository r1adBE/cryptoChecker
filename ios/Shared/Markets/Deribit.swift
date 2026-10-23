import Foundation

private let deribitAPI = "https://www.deribit.com/api/v2"

/// Abrechnungswährungen mit Perpetuals; je eine Anfrage für Paarliste und Sammelabfrage.
private let deribitSettleCurrencies = ["BTC", "ETH", "USDC"]

/// Deribit, nur Perpetuals: BTC-/ETH-PERPETUAL (gegen USD, in Coins abgerechnet = invers) und die linearen
/// USDC-Perpetuals (z. B. SOL_USDC-PERPETUAL). Eine Paarliste je Abrechnungswährung, darum kein `SimpleMarket`.
/// API v2: https://docs.deribit.com
final class Deribit: Market, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() { super.init(key: "Deribit", name: "Deribit") }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        let instrument = info.pairId ?? info.base + "-PERPETUAL"
        return deribitAPI + "/public/ticker?instrument_name=" + instrument
    }

    /// `timestamp` ist der Schnappschuss (≈ Abruf), kein Handel: Zeit der Börse unbekannt.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let result = try json.object("result")
        ticker.last = try result.double("last_price")
        ticker.bid = result.optDoubleOrMissing("best_bid_price")
        ticker.ask = result.optDoubleOrMissing("best_ask_price")
        guard let stats = result.optObject("stats") else { return }
        ticker.high = stats.optDoubleOrMissing("high")
        ticker.low = stats.optDoubleOrMissing("low")
        ticker.vol = stats.optDoubleOrMissing("volume")
        ticker.volQuote = stats.optDoubleOrMissing("volume_usd")
        // «price_change»: gleitende 24 h in Prozent
        ticker.change24hPercent = Change24h.percent(stats.optDouble("price_change"))
    }

    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.object("error").string("message")
    }

    // MARK: Sammelabfrage: Übersicht je Abrechnungswährung

    override var bulkTickersNumOfRequests: Int { deribitSettleCurrencies.count }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? {
        deribitAPI + "/public/get_book_summary_by_currency?currency=\(deribitSettleCurrencies[call])&kind=future"
    }

    /// Ohne Handel ist `last` null (ruhend); ein unlesbarer Kurs (Text statt Zahl) trifft nur sein Paar.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("result").allObjects() {
            let instrument = row.optString("instrument_name")
            if !instrument.hasSuffix("PERPETUAL") { continue }
            bulkRowIfActive(instrument, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste: eine Anfrage je Abrechnungswährung

    override var pairListCallCount: Int { deribitSettleCurrencies.count }

    override func pairListAddress(call: Int) -> String? {
        deribitAPI + "/public/get_instruments?currency=\(deribitSettleCurrencies[call])&kind=future&expired=false"
    }

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for entry in try json.array("result").allObjects() where entry.optString("settlement_period") == "perpetual" {
            let state = entry.optString("state")
            if !state.isEmpty && state != "open" { continue }
            let instrument = try entry.string("instrument_name")
            out.append(MarketPair(
                try entry.string("base_currency"),
                try entry.string("quote_currency"),
                instrument,
                isInverse(entry, instrument: instrument) ? .inversePerpetual : .perpetual
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile der Übersicht; `creation_timestamp` ist deren Erstellung, keine Handelszeit.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("bid_price")
        ticker.ask = row.optDoubleOrMissing("ask_price")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        let usd = row.optDouble("volume_usd")
        ticker.volQuote = usd.isNaN ? row.optDoubleOrMissing("volume_notional") : usd
        ticker.change24hPercent = Change24h.percent(row.optDouble("price_change"))
    }

    /// «reversed» = invers, «linear» = linear; ohne Angabe entscheidet die Kennung.
    private func isInverse(_ entry: JObject, instrument: String) -> Bool {
        switch entry.optString("instrument_type") {
        case "reversed": true
        case "linear": false
        default: FuturesContractType.isInversePerpetualId(marketKey: key, pairId: instrument)
        }
    }
}
