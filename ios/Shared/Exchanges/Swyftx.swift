import Foundation

private let swyftxInfo = "https://api.swyftx.com.au/markets/info/basic/"

/// Swyftx nennt Kauf- und Verkaufspreis in AUD.
private let swyftxQuote = "AUD"

/// Swyftx (Australien/Neuseeland, Broker), öffentliche API: https://docs.swyftx.com.au/ — nur Kauf- (`buy`) und
/// Verkaufspreis (`sell`) in AUD je Coin, kein letzter Handel: der Kurs ist der Mittelkurs (`priceIsMid`). Ohne
/// 24-h-Veränderung (Kerzen), ohne Zeit; `volume24H` ist das weltweite Volumen, nicht das von Swyftx (nicht
/// übernommen). Kennung = Kürzel («BTC»); Paarliste und Sammelabfrage lesen dieselbe Gesamtliste.
struct Swyftx: SimpleExchange {
    let key: String = "Swyftx"
    let name: String = "Swyftx"
    let pairListURL: String = swyftxInfo
    let tickerURL: String = swyftxInfo + "%1$s/"

    var priceIsMid: Bool { true }

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base.uppercased() }

    /// Liste mit dem einen Coin; leer oder ohne Preise = kein Kurs.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        guard let row = try JArray(string: body).optObject(0), Swyftx.tradable(row) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    /// `{"error": {"error", "message"}}`.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let error = json.optObject("error") else { try noErrorText() }
        return try error.string("message")
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    /// Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt.
    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { swyftxInfo }

    /// Coins ohne Preise (nicht handelbar) fallen still weg.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            let code = row.optString("code")
            guard !code.isEmpty, Swyftx.tradable(row) else { continue }
            bulkRow(code, tickers: &tickers, failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Je handelbarem Coin ein Paar gegen AUD. Ein Kürzel steht teils zweimal (alter und neuer Token, gleiche Preise,
    /// z. B. «DATA»): nur das erste zählt.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        var seen = Set<String>()
        for row in try JArray(string: body).allObjects() where Swyftx.tradable(row) {
            let code = try row.string("code")
            if !seen.insert(code).inserted { continue }
            out.append(ExchangePair(code.uppercased(), swyftxQuote, code))
        }
        return out
    }

    // MARK: Lesen

    /// Verkaufspreis = Gebot, Kaufpreis = Angebot, Kurs = Mitte; fehlt ein Preis, ist die Zeile unlesbar.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let bid = try row.double("sell")
        let ask = try row.double("buy")
        ticker.bid = bid
        ticker.ask = ask
        ticker.last = bid > 0 && ask > 0 ? (bid + ask) / 2 : Ticker.missing
    }

    /// Beide Preise vorhanden und nicht leer oder 0.
    private static func tradable(_ row: JObject) -> Bool {
        row.raw["buy"] != nil && row.raw["sell"] != nil
            && !BulkTickerRows.inactivePrice(row, "buy") && !BulkTickerRows.inactivePrice(row, "sell")
    }
}
