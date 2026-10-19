import Foundation

private let mercadoAPI = "https://api.mercadobitcoin.net/api/v4"

/// Ticker immer mit Auswahl: «?symbols=BTC-BRL,ETH-BRL» (ohne Auswahl gibt es keine Gesamtliste).
private let mercadoTickers = mercadoAPI + "/tickers?symbols="

/// Mercado Bitcoin (Brasilien, BRL-Paare), öffentliche API v4: https://api.mercadobitcoin.net/api/v4/docs —
/// Kennung wie «BTC-BRL». Die Paarliste kommt spaltenweise (je Feld eine Liste, gleiche Stelle = gleiches Paar).
final class MercadoBitcoin: SimpleMarket {
    init() {
        super.init(
            key: "MercadoBitcoin",
            name: "Mercado Bitcoin",
            pairListURL: mercadoAPI + "/symbols",
            tickerURL: mercadoTickers + "%1$s",
            errorField: "message"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + "-" + info.quote }

    /// Antwort ist eine Liste; nur die Zeile des angefragten Paars zählt.
    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let id = pairId(info)
        guard let row = try JArray(string: body).allObjects().first(where: { $0.optString("pair") == id }) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage: nur gefiltert

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Alle beobachteten Paare in einer Anfrage. Lehnt die Börse die Auswahl wegen eines unbekannten Paars ab,
    /// lädt die App die Paarliste, lässt Unbekannte weg und fragt neu (`bulkKnownPairsURL`, wie bei Bithumb).
    /// Keine Aufteilung auf mehrere Adressen: Die Wiederholung teilte die Liste neu auf und träfe andere Gruppen.
    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        guard call == 0, !pairIds.isEmpty else { return nil }
        return mercadoTickers + pairIds.sorted().joined(separator: ",")
    }

    override func bulkKnownPairsURL(call: Int) -> String? { pairListAddress(call: call) }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("pair"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Spalten «symbol», «base-currency», «currency»; gelistet und handelbar laut «exchange-listed/-traded». Nur
    /// «type» CRYPTO oder UTILITY_TOKEN: die Börse führt daneben über tausend tokenisierte Forderungen und Anteile
    /// (DIGITAL_ASSET, DIGITAL_VARIABLE_INCOME), die keine Kryptowährungen sind (Aufzeichnung 19.10.2026).
    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        let symbols = try json.array("symbol")
        let bases = try json.array("base-currency")
        let quotes = try json.array("currency")
        let listed = json.optArray("exchange-listed")
        let traded = json.optArray("exchange-traded")
        let types = json.optArray("type")
        var out: [MarketPair] = []
        for i in 0..<symbols.count {
            let id = symbols.optString(i)
            if id.isEmpty || !MercadoBitcoin.flag(listed, i) || !MercadoBitcoin.flag(traded, i)
            || !MercadoBitcoin.isCrypto(types, i) { continue }
            out.append(MarketPair(bases.optString(i).uppercased(), quotes.optString(i).uppercased(), id))
        }
        return out
    }

    /// Werte der Spalte «type», die als Kryptowährung gelten.
    private static let cryptoTypes: Set<String> = ["CRYPTO", "UTILITY_TOKEN"]

    /// Art an Stelle `i` ist eine Kryptowährung; fehlende Spalte gilt als ja, leerer Wert als nein.
    private static func isCrypto(_ column: JArray?, _ i: Int) -> Bool {
        guard let column else { return true }
        return cryptoTypes.contains(column.optString(i).uppercased())
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile (Zahlen als Text). `buy`/`sell` = bestes Gebot/Angebot, `vol` in der Basiswährung, `open`
    /// = Kurs zu Beginn der letzten 24 h. `date` (s) ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse
    /// bleibt unbekannt.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }

    /// Schalter an Stelle `i`; fehlende Spalte oder unklarer Wert gilt als gesetzt (wie org.json `optBoolean`).
    private static func flag(_ column: JArray?, _ i: Int) -> Bool {
        guard let column, i < column.count else { return true }
        let value = column.raw[i]
        if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() { return number.boolValue }
        if let text = value as? String {
            if text.lowercased() == "true" { return true }
            if text.lowercased() == "false" { return false }
        }
        return true
    }
}
