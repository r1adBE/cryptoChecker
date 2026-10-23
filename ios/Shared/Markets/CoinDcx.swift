import Foundation

private let coinDcxAPI = "https://api.coindcx.com/exchange"

/// Alle Ticker in einer Antwort; einen Einzelabruf je Paar gibt es nicht.
private let coinDcxTicker = coinDcxAPI + "/ticker"

/// CoinDCX (Indien, INR- und USDT-Paare), öffentliche API: https://docs.coindcx.com — Kennung = `coindcx_name`
/// («BTCINR»), im Ticker `market`. Vorsicht: In der Paarliste ist `base_currency_short_name` die Gegenwährung
/// (INR/USDT) und `target_currency_short_name` der gehandelte Coin.
final class CoinDcx: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "CoinDcx",
            name: "CoinDCX",
            pairListURL: coinDcxAPI + "/v1/markets_details",
            tickerURL: coinDcxTicker,
            ttsName: "Coin D C X",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let id = pairId(info)
        guard let row = try JArray(string: body).allObjects().first(where: { $0.optString("market") == id }) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Der ungefilterte Ticker führt jeden aktiven Markt (Aufzeichnung 19.10.2026: 991 von 991 aktiven Paaren
    /// der Paarliste), ein fehlendes Paar ist nicht mehr gelistet.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { coinDcxTicker }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("market"), row: row, priceField: "last_price", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur aktive Märkte; dieselbe Kennung zählt einmal (die Liste führt Märkte mehrerer Orderbücher).
    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        var seen: Set<String> = []
        for entry in try JArray(string: body).allObjects() where entry.optString("status") == "active" {
            let id = try entry.string("coindcx_name")
            guard seen.insert(id).inserted else { continue }
            out.append(MarketPair(
                try entry.string("target_currency_short_name").uppercased(),
                try entry.string("base_currency_short_name").uppercased(),
                id
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile. `volume` zählt nach den Beispielen der Doku in der Gegenwährung (darum `volQuote`);
    /// `change_24_hour` ist die Veränderung über 24 h in Prozent. `timestamp` (s) gehört zur Momentaufnahme, nicht
    /// zum letzten Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.volQuote = row.optDoubleOrMissing("volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("change_24_hour"))
    }
}
