import Foundation

private let asterFapi = "https://fapi.asterdex.com/fapi/v1"

/// Aster — dezentrale Perpetual-Börse mit öffentlicher Schnittstelle im Aufbau von Binance USDⓈ-M (fapi):
/// `exchangeInfo` und `ticker/24hr` (mit `symbol` ein Objekt, ohne alle als Array). Der Kurs ist der letzte Handel
/// (`lastPrice`), kein Mark-Preis — darum weder Mittelkurs- noch Mark-Kennzeichnung wie bei Hyperliquid.
struct Aster: SimpleExchange {
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    var category: ExchangeCategory { .futures }

    let key: String = "Aster"
    let name: String = "Aster"
    let pairListURL: String = asterFapi + "/exchangeInfo"
    let tickerURL: String = asterFapi + "/ticker/24hr?symbol=%1$s"
    let errorField: String? = "msg"

    // MARK: Einzelkurs

    /// Unbekanntes Symbol: {"code":…,"msg":…} — ohne `lastPrice` scheitert das Lesen, «msg» wird Fehlertext.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Ungefiltert enthält die Antwort alle gehandelten Kontrakte.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { asterFapi + "/ticker/24hr" }

    /// Einträge, die keine Objekte sind, übergehen; unlesbare Zahlen treffen nur ihr Paar.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).optObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur handelbare Perpetuals; fehlen «contractType» oder «status», gilt der Eintrag als handelbares Perpetual
    /// (Aster führt nur solche). Aktien, ETFs und Rohstoffe erkennt `TradFi.aster` an `underlyingSubType`.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("symbols").allObjects() {
            let kind = entry.optString("contractType")
            let state = entry.optString("status")
            let perpetual = (kind.isEmpty ? "PERPETUAL" : kind) == "PERPETUAL"
            let trading = (state.isEmpty ? "TRADING" : state) == "TRADING"
            guard perpetual, trading else { continue }
            out.append(ExchangePair(
                try entry.string("baseAsset"),
                try entry.string("quoteAsset"),
                try entry.string("symbol"),
                .perpetual,
                // Immer gesetzt (auch false), wie bei Binance Futures
                tradFi: TradFi.aster(subTypes: entry.optStrings("underlyingSubType"))
            ))
        }
        return out
    }

    // MARK: Lesen

    /// `closeTime` ist nur das Fensterende, kein Handel: die Zeit der Börse bleibt unbekannt (wie Binance Futures).
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // gleitende 24 h, bereits Prozent (wie Binance)
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }
}
