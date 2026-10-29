import Foundation

private let bitfinexAPI = "https://api-pub.bitfinex.com/v2"

/// Sammel-Ticker; dahinter «ALL» oder die Symbole mit Komma.
private let bitfinexTickers = bitfinexAPI + "/tickers?symbols="

/// Bitfinex-Kürzel → gebräuchliches Kürzel, damit die Suche («ATOM») trifft. Nur Einträge, die Bitfinex selbst so
/// zuordnet (`/v2/conf/pub:map:currency:sym`). Absichtlich nicht: XCH (dort CryptoFranc, sonst Chia), DOG und andere
/// unklare. Wie `Bitfinex.kt`.
private let bitfinexAliases: [String: String] = [
    "UST": "USDT",
    "UDC": "USDC",
    "TSD": "TUSD",
    "EUS": "EURS",
    "ALG": "ALGO",
    // Ampleforth, nicht der Token «AMP»
    "AMP": "AMPL",
    "ATO": "ATOM",
    "DAT": "DATA",
    "DSH": "DASH",
    "IOT": "IOTA",
    "MNA": "MANA",
    // Optimism (Bitfinex ordnet OPX selbst OP zu)
    "OPX": "OP",
    "QSH": "QASH",
    "QTM": "QTUM",
    "SNG": "SNGLS",
    "STJ": "STORJ",
    "WBT": "WBTC",
    "YYW": "YOYOW",
]

/// Bitfinex Spot (API v2, öffentlich). Antworten sind Arrays; ein Ticker lautet
/// [BID, BID_SIZE, ASK, ASK_SIZE, DAILY_CHANGE, DAILY_CHANGE_RELATIVE, LAST_PRICE, VOLUME, HIGH, LOW],
/// in der Sammelabfrage mit dem Symbol davor. Bitfinex' eigene Kürzel (UST = USDT, UDC = USDC, ATO = ATOM …) werden
/// für die Anzeige übersetzt (`bitfinexAliases`); die Kennung bleibt die der Börse.
struct Bitfinex: SimpleExchange {
    let key: String = "Bitfinex"
    let name: String = "Bitfinex"
    let pairListURL: String = bitfinexAPI + "/conf/pub:list:pair:exchange"
    let tickerURL: String = bitfinexAPI + "/ticker/%1$s"

    // MARK: Einzelkurs

    /// Ohne Kennung steht hier wie auf Android «null» in der Adresse (die Börse antwortet mit ihrem Fehler).
    func pairId(_ info: PairQuery) -> String? { info.pairId ?? "null" }

    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let fields = try JArray(string: body)
        // Fehlerform: ["error", Code, "Meldung"]
        if fields.optString(0) == "error" { throw JSONError(message: fields.optString(2)) }
        try copyRow(fields, into: &ticker, shift: 0)
    }

    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        let text = try JArray(string: body).optString(2)
        return text.isEmpty ? nil : text
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { bitfinexTickers + "ALL" }

    /// Nur die beobachteten Paare («symbols=tBTCUSD,tETHUSD»), aufgeteilt, damit jede URL kurz bleibt.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        guard let parts = BulkPairChunks.chunks(prefix: bitfinexTickers, pairIds: pairIds) else {
            return bulkTickersURL(call: call)
        }
        return call < parts.count ? BulkPairChunks.url(prefix: bitfinexTickers, chunk: parts[call]) : nil
    }

    func bulkTickersRequestCount(pairIds: [String]) -> Int {
        BulkPairChunks.chunks(prefix: bitfinexTickers, pairIds: pairIds)?.count ?? bulkTickersNumOfRequests
    }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allArrays() {
            let symbol = row.optString(0)
            // «f…» sind Funding-Märkte, keine Handelspaare; leerer Kurs: ruhend, kein Fehler
            if !symbol.hasPrefix("t") || BulkTickerRows.inactivePrice(row, 7) { continue }
            bulkRow(symbol, tickers: &tickers, failed: &failed) { try copyRow(row, into: &$0, shift: 1) }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for raw in try JArray(string: body).array(0).allStrings() {
            guard !raw.hasPrefix("TEST"), let sides = Bitfinex.sidesOf(raw) else { continue }
            out.append(ExchangePair(Bitfinex.readable(sides.base), Bitfinex.readable(sides.quote), "t" + raw))
        }
        return out
    }

    // MARK: Lesen

    /// Liest die Ticker-Felder ab Position `shift` (0 = Einzelabruf, 1 = Sammelzeile mit Symbol).
    private func copyRow(_ row: JArray, into ticker: inout Ticker, shift: Int) throws {
        ticker.last = try row.double(shift + 6)
        ticker.bid = try row.double(shift)
        ticker.ask = try row.double(shift + 2)
        ticker.high = try row.double(shift + 8)
        ticker.low = try row.double(shift + 9)
        ticker.vol = try row.double(shift + 7)
        // relative Tagesveränderung = gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(row.optDouble(shift + 5))
    }

    /// «BTCUSD» oder «DOGE:USD» → (Basis, Kotierung); andere Form → nil.
    private static func sidesOf(_ raw: String) -> (base: String, quote: String)? {
        if let colon = raw.firstIndex(of: ":") {
            return (String(raw[..<colon]), String(raw[raw.index(after: colon)...]))
        }
        guard raw.count == 6 else { return nil }
        return (String(raw.prefix(3)), String(raw.dropFirst(3)))
    }

    private static func readable(_ code: String) -> String { bitfinexAliases[code] ?? code }
}
