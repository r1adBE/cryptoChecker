import Foundation

private let btcMarketsAPI = "https://api.btcmarkets.net/v3"

/// Sammelabfrage: hinter dem Präfix die Kennungen, je Markt ein eigener Parameter «marketId».
private let btcMarketsTickersFor = btcMarketsAPI + "/markets/tickers?marketId="

/// Trenner zwischen zwei Kennungen der Sammelabfrage.
private let btcMarketsNextId = "&marketId="

/// `status` eines normal handelbaren Markts; «Post Only», «Limit Only», «Cancel Only», «Offline» fallen weg.
private let btcMarketsStatusOnline = "Online"

/// Zeichen, die ohne Kodierung in der Adresse stehen dürfen («BTC-AUD»).
private let btcMarketsSafeCharacters = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-")

/// BTC Markets (Australien, AUD-Paare), API v3: https://api.btcmarkets.net/doc/v3. Kennung = `marketId` («BTC-AUD»).
/// Die Sammelabfrage verlangt die Märkte als Auswahl; eine ungefilterte Form ist nicht belegt.
struct BtcMarkets: SimpleExchange {
    let key: String = "BtcMarkets"
    let name: String = "BTC Markets"
    let pairListURL: String = btcMarketsAPI + "/markets"
    let tickerURL: String = btcMarketsAPI + "/markets/%1$s/ticker"
    let ttsName: String = "B T C Markets"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    // MARK: Sammelabfrage: nur gefiltert, alle beobachteten Märkte in einer Anfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Ob die Börse unbekannte Märkte ablehnt oder still weglässt, ist unbelegt: Fehlende prüft die Einzelabfrage.
    var bulkTickersComplete: Bool { false }

    /// Sortiert und ohne Doppelte; Kennungen, die kodiert werden müssten, bleiben draussen (Einzelabfrage). Ohne
    /// Kennung keine Sammelabfrage.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        guard call == 0 else { return nil }
        let safe = pairIds.filter { id in !id.isEmpty && id.allSatisfy { btcMarketsSafeCharacters.contains($0) } }
        let ids = Set(safe).sorted()
        if ids.isEmpty { return nil }
        return btcMarketsTickersFor + ids.joined(separator: btcMarketsNextId)
    }

    /// Lehnt die Börse die Auswahl wegen eines unbekannten Markts ab: Paarliste holen und ohne ihn neu fragen.
    func bulkKnownPairsURL(call: Int) -> String? { pairListAddress(call: call) }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("marketId"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur «Online»; ohne `status` (ältere Antworten) bleibt der Markt.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for market in try JArray(string: body).allObjects() {
            let status = market.optString("status")
            if !status.isEmpty && status != btcMarketsStatusOnline { continue }
            out.append(ExchangePair(market.optString("baseAssetName"), market.optString("quoteAssetName"),
                                  market.optString("marketId")))
        }
        return out
    }

    // MARK: Lesen

    /// Ein Ticker. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bestBid")
        ticker.ask = row.optDoubleOrMissing("bestAsk")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("volume24h")
        ticker.volQuote = row.optDoubleOrMissing("volumeQte24h")
        // «price24h» = absolute Veränderung über 24 h (mehr Stellen); «pricePct24h» (Prozent) nur ersatzweise
        ticker.change24hPercent = Change24h.fromAbsolute(last: last, change: row.optDouble("price24h"))
            ?? Change24h.percent(row.optDouble("pricePct24h"))
    }
}
