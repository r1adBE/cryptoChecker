import Foundation

/// Alle SWAP-Ticker in einer Antwort (Sammelabfrage).
private let okxSwapTickers = "https://www.okx.com/api/v5/market/tickers?instType=SWAP"

/// OKX Perpetual-Swaps. Die Paarliste kommt aus `/api/v5/public/instruments`, nicht aus den Tickern: Nur dort stehen
/// `instCategory` (Krypto, Aktie, Rohstoff …) und `state` — sonst liefen NVDA-, AAPL- oder XAU-Swaps als Krypto-Token.
/// Gegenstück: `OkexFutures.kt`.
final class OkexFutures: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "OkexFutures",
            name: "OKX Futures",
            pairListURL: "https://www.okx.com/api/v5/public/instruments?instType=SWAP",
            tickerURL: "https://www.okx.com/api/v5/market/ticker?instId=%1$s"
        )
    }

    // MARK: Einzelkurs

    /// Fehler meldet OKX in code/msg statt in «data».
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let complaint = json.optString("msg")
        if !complaint.isEmpty { throw JSONError(message: complaint) }
        try copyRow(json.array("data").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage: eigener Endpunkt für alle SWAP-Ticker (nicht die Instrumentliste)

    override var bulkTickersNumOfRequests: Int { 1 }

    override func bulkTickersURL(call: Int) -> String? { okxSwapTickers }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("instId"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for inst in try json.array("data").allObjects() {
            let instId = try inst.string("instId")
            let legs = instId.components(separatedBy: "-")
            guard OkexFutures.isLive(inst.optString("state")), legs.count == 3, legs[2] == "SWAP" else { continue }
            out.append(MarketPair(
                legs[0],
                legs[1],
                instId,
                isInverse(inst, instId: instId) ? .inversePerpetual : .perpetual,
                // instCategory ≠ 1: Aktie, Rohstoff, Devisen — kein Krypto-Token
                tradFi: TradFi.okx(instCategory: inst.optString("instCategory"))
            ))
        }
        return out
    }

    // MARK: Lesen

    /// Je Paar dieselbe Struktur in Einzel- und Sammelabfrage.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.high = try row.double("high24h")
        ticker.low = try row.double("low24h")
        // SWAP: «vol24h» zählt Kontrakte, «volCcy24h» ist die Menge in der Basis. Einen Umsatz in der Kotierung
        // liefert OKX nicht — er wird auch nicht geschätzt
        ticker.vol = try row.double("volCcy24h")
        ticker.volQuote = Ticker.missing
        // Leeres Buch: "" für bidPx/askPx → ohne Geld-/Briefkurs
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        // «ts» = Erzeugung des Tickers, kein Handel. «open24h» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open24h"))
    }

    /// Ohne «state» zählt das Instrument; sonst nur «live» (nicht preopen/suspend/test).
    private static func isLive(_ state: String) -> Bool { state.isEmpty || state == "live" }

    /// «ctType» entscheidet; fehlt es, die Kennung (z. B. BTC-USD-SWAP = in Coins abgerechnet).
    private func isInverse(_ inst: JObject, instId: String) -> Bool {
        switch inst.optString("ctType") {
        case "inverse": true
        case "linear": false
        default: FuturesContractType.isInversePerpetualId(marketKey: key, pairId: instId)
        }
    }
}
