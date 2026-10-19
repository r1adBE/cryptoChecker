import Foundation

private let lighterAPI = "https://mainnet.zklighter.elliot.ai/api/v1"

/// Kennzahlen aller Märkte (letzter Handel, 24-h-Werte), Schlüssel `symbol` — für Einzel- und Sammelkurs.
private let lighterStats = lighterAPI + "/exchangeStats"

/// Gegenwährung aller Perps: Lighter rechnet in USDC ab, das Symbol selbst ist nackt («BTC», «SPY»).
private let lighterPerpQuote = "USDC"

/// `code` einer normalen Antwort.
private let lighterOK = 200

/// Lighter — dezentrale Perpetual-Börse (zk-Rollup) mit USDC-Abrechnung, dazu einige Spot-Märkte; öffentliche
/// Schnittstelle ohne Schlüssel. Paare aus `orderBooks`, Kurse aus `exchangeStats`
/// ({code, total, order_book_stats: [{symbol, last_trade_price, daily_…}]}).
///
/// Paar-Kennung = `symbol` («BTC» für den Perp, «rhQQQ/USDC» für Spot), nicht `market_id`: Die Sammelabfrage
/// `exchangeStats` nennt nur das Symbol, und die Sammelabfragen werden einzeln gelesen — eine Zuordnung
/// market_id ↔ symbol bräuchte einen geteilten Zustand aus der Paarliste, den der Adapter nicht halten soll.
/// Darum liest auch der Einzelkurs `exchangeStats` (wie Hyperliquid: dieselbe Antwort, daraus das gesuchte Paar);
/// gleichzeitige Einzelkurse teilen sich die Antwort (`InFlightRequests`). `orderBookDetails?market_id=` hätte
/// zusätzlich Hoch/Tief, bräuchte aber die market_id.
///
/// Kurs = letzter Handel (`last_trade_price`), kein Mark- oder Mittelkurs. Keine Zeit der Börse (Momentaufnahme),
/// Frische ab Abruf. TradFi-Perps (SPY, NATGAS, US10Y …) bleiben unmarkiert wie bei Hyperliquid: `orderBooks` hat
/// kein Feld, das sie von Krypto unterscheidet, und eine Liste von Hand veraltete bei jeder Neuzulassung.
final class Lighter: Market {
    init() {
        super.init(key: "Lighter", name: "Lighter", ttsName: "Lighter")
    }

    // MARK: Einzelkurs: dieselbe Antwort wie die Sammelabfrage, daraus das gesuchte Symbol

    override func tickerAddress(call: Int, info: PairQuery) -> String { lighterStats }

    /// Unbekanntes Symbol oder Kurs 0 (nie gehandelt, abgelaufener Binärmarkt): Fehler statt Kurs 0, damit «nicht
    /// mehr gehandelt» bestätigt werden kann (NotTraded).
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let symbol = info.pairId ?? ""
        let rows = try json.array("order_book_stats").optObjects()
        guard let row = rows.first(where: { $0.optString("symbol") == symbol }) else {
            throw JSONError(message: "Unknown symbol: \(symbol)")
        }
        if BulkTickerRows.inactivePrice(row, "last_trade_price") {
            throw JSONError(message: "No price: \(symbol)")
        }
        try Lighter.copyRow(row, into: &ticker)
    }

    /// Fehlerantwort {"code": …, "message": …} mit einem Code ausser 200; eine normale Antwort (code 200) ohne das
    /// Paar hat keinen Fehlertext der Börse.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        let code = json.optInt("code", lighterOK)
        let message = json.optString("message")
        if code == lighterOK || message.isEmpty { try noErrorText() }
        return message
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// `exchangeStats` führt alle Märkte (auch inaktive und Binärmärkte), ein fehlendes Paar ist nicht mehr gelistet.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { lighterStats }

    /// Kurs 0 (Binärmärkte «BTC-UPDOWN-15M-…», nie gehandelte Perps) wird übersprungen, nicht als Fehler gezählt;
    /// fehlt das Kursfeld (umbenannt), trifft das nur sein Paar.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("order_book_stats").optObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "last_trade_price", tickers: &tickers,
                            failed: &failed) { try Lighter.copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func pairListAddress(call: Int) -> String? { lighterAPI + "/orderBooks" }

    /// Nur aktive, nicht eingefrorene Märkte. Perp «BTC» → BTC/USDC (PERPETUAL), Spot «rhQQQ/USDC» → rhQQQ/USDC;
    /// Symbole bleiben, wie Lighter sie schreibt (wie Hyperliquid «kPEPE»). Andere Marktarten werden übergangen.
    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for book in try json.array("order_books").optObjects() {
            guard book.optString("status") == "active", !book.optBool("is_frozen") else { continue }
            if let pair = Lighter.readPair(book.optString("symbol"), book.optString("market_type")) {
                out.append(pair)
            }
        }
        return out
    }

    // MARK: Lesen

    /// Eine Zeile von `exchangeStats`; `daily_price_change` ist bereits Prozent über gleitende 24 h.
    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_trade_price")
        ticker.vol = row.optDoubleOrMissing("daily_base_token_volume")
        ticker.volQuote = row.optDoubleOrMissing("daily_quote_token_volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("daily_price_change"))
    }

    /// Paar aus Symbol und Marktart; nil für leere oder unpassende Symbole und unbekannte Marktarten.
    private static func readPair(_ symbol: String, _ type: String) -> MarketPair? {
        if symbol.isEmpty { return nil }
        switch type {
        case "perp":
            return symbol.contains("/") ? nil : MarketPair(symbol, lighterPerpQuote, symbol, .perpetual)
        case "spot":
            guard let slash = symbol.firstIndex(of: "/"), slash != symbol.startIndex,
                  symbol.index(after: slash) != symbol.endIndex else { return nil }
            return MarketPair(String(symbol[..<slash]), String(symbol[symbol.index(after: slash)...]), symbol)
        default:
            return nil
        }
    }
}
