import Foundation

private let lighterAPI = "https://mainnet.zklighter.elliot.ai/api/v1"

/// Kennzahlen aller Märkte (letzter Handel, 24-h-Werte), Schlüssel `symbol` — für Einzel- und Sammelkurs.
private let lighterStats = lighterAPI + "/exchangeStats"

/// Gegenwährung aller Perps: Lighter rechnet in USDC ab, das Symbol selbst ist nackt («BTC», «SPY»).
private let lighterPerpQuote = "USDC"

/// `code` einer normalen Antwort.
private let lighterOK = 200

/// Lighter — dezentrale Perpetual-Börse (zk-Rollup) mit USDC-Abrechnung, dazu einige Spot-Märkte; öffentliche
/// Schnittstelle ohne Schlüssel. Paare aus `orderBooks` + `tokenlist`, Kurse aus `exchangeStats`
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
/// Frische ab Abruf. TradFi (SPY, NATGAS, US10Y …) erkennt erst `tokenlist` (`asset_type` «RWA»), darum liest die
/// Paarliste zwei Antworten gemeinsam; `tokenlist` liefert auch die vollen Namen.
final class Lighter: Market {
    init() {
        super.init(key: "Lighter", name: "Lighter", ttsName: "Lighter")
    }

    // MARK: Einzelkurs: dieselbe Antwort wie die Sammelabfrage, daraus das gesuchte Symbol

    override func tickerAddress(call: Int, info: PairQuery) -> String { lighterStats }

    /// Unbekanntes Symbol oder Kurs 0 (nie gehandelt, abgelaufener Binärmarkt): Fehler statt Kurs 0, damit «nicht
    /// mehr gehandelt» bestätigt werden kann (NotTraded). Kurs 0 meldet `JSONError.noData` (gültige Antwort ohne Kurs):
    /// Ein eigener Text galt als Lesefehler, bestätigte nie und blieb ein Dauerfehler. Wie Android.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let symbol = Lighter.symbol(of: info)
        let rows = try json.array("order_book_stats").optObjects()
        guard let row = rows.first(where: { $0.optString("symbol") == symbol }) else {
            throw JSONError(message: "Unknown symbol: \(symbol)")
        }
        if BulkTickerRows.inactivePrice(row, "last_trade_price") {
            throw JSONError(message: JSONError.noData)
        }
        try Lighter.copyRow(row, into: &ticker)
    }

    /// Symbol des Paars: die gespeicherte Kennung, sonst wie in der Paarliste aus Basis und Gegenwährung — Perp «BTC»,
    /// Spot «ETH/USDC». Ohne Kennung suchte die Abfrage sonst das leere Symbol und bestätigte fälschlich «nicht mehr
    /// gehandelt». Wie Android.
    static func symbol(of info: PairQuery) -> String {
        info.pairId ?? (info.contractType == FuturesContractType.none ? info.base + "/" + info.quote : info.base)
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

    /// Der Einzelkurs liest dieselbe vollständige Liste (`tickerAddress`).
    override var singleTickerReadsFullList: Bool { true }

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

    // MARK: Paarliste: Märkte aus `orderBooks`, Art des Basiswerts aus `tokenlist`

    override var pairListCallCount: Int { 2 }

    /// Erst beide Antworten, dann gemeinsam lesen: `orderBooks` sagt, was handelbar ist, `tokenlist`, was TradFi ist.
    override var pairListCombined: Bool { true }

    override func pairListAddress(call: Int) -> String? {
        call == 0 ? lighterAPI + "/orderBooks" : lighterAPI + "/tokenlist"
    }

    /// Nur aktive, nicht eingefrorene Märkte. Perp «BTC» → BTC/USDC (PERPETUAL), Spot «rhQQQ/USDC» → rhQQQ/USDC;
    /// Symbole bleiben, wie Lighter sie schreibt (wie Hyperliquid «kPEPE»). Andere Marktarten werden übergangen.
    /// TradFi = `asset_type` «RWA» in `tokenlist` (gleiches Symbol, Spot mit «/USDC»). Fehlt das Symbol dort oder ist
    /// `tokenlist` unlesbar, bleibt das Paar unmarkiert wie bei Börsen ohne solches Feld: Die Liste kommt trotzdem
    /// aus `orderBooks`, und eine fehlende Angabe soll keinen Krypto-Coin unter TradFi verstecken. Ausnahme:
    /// Robinhood-Aktientoken als Spot («rhSPY/USDC», fehlen in `tokenlist`) — siehe `TradFi.lighterRobinhood`.
    override func fillPairListCombined(bodies: [String]) throws -> [MarketPair] {
        let assetTypes = Lighter.assetTypes(bodies.count > 1 ? bodies[1] : "")
        var out: [MarketPair] = []
        for book in try JObject(string: bodies[0]).array("order_books").optObjects() {
            guard book.optString("status") == "active", !book.optBool("is_frozen") else { continue }
            let symbol = book.optString("symbol")
            let type = book.optString("market_type")
            let tradFi = assetTypes[symbol].map { TradFi.lighter(assetType: $0) }
                ?? Lighter.unlistedSpotTradFi(symbol, type, assetTypes)
            if let pair = Lighter.readPair(symbol, type, tradFi: tradFi) {
                out.append(pair)
            }
        }
        return out
    }

    // MARK: Namen

    /// Voller Name je Basis aus `tokenlist` («COIN» → «Coinbase», «META» → «Meta»), damit Aktien und Rohstoffe nicht
    /// als gleichnamiger Token gelten. Spot «ETH/USDC» zählt für die Basis «ETH»; der erste Eintrag gewinnt.
    override func parseBaseNamesCombined(bodies: [String]) throws -> [String: String] {
        var names: [String: String] = [:]
        for token in try JObject(string: bodies[1]).array("tokens").optObjects() {
            let base = String(token.optString("symbol").prefix { $0 != "/" })
            let name = token.optString("name").trimmingCharacters(in: .whitespacesAndNewlines)
            if !base.isEmpty, !name.isEmpty, names[base] == nil { names[base] = name }
        }
        return names
    }

    // MARK: Lesen

    /// Eine Zeile von `exchangeStats`; `daily_price_change` ist bereits Prozent über gleitende 24 h.
    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last_trade_price")
        ticker.vol = row.optDoubleOrMissing("daily_base_token_volume")
        ticker.volQuote = row.optDoubleOrMissing("daily_quote_token_volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("daily_price_change"))
    }

    /// `asset_type` je Symbol aus `tokenlist`; leer, wenn die Antwort fehlt oder unlesbar ist.
    private static func assetTypes(_ body: String) -> [String: String] {
        guard let tokens = try? JObject(string: body).array("tokens").optObjects() else { return [:] }
        var types: [String: String] = [:]
        for token in tokens { types[token.optString("symbol")] = token.optString("asset_type") }
        return types
    }

    /// Spot ohne `tokenlist`-Eintrag: Robinhood-Aktientoken («rhSPY/USDC») sind TradFi, ausser `tokenlist` führt den
    /// Rest («SPY» oder «SPY/USDC») als Krypto. Perps ohne Eintrag bleiben unmarkiert.
    private static func unlistedSpotTradFi(_ symbol: String, _ type: String, _ assetTypes: [String: String]) -> Bool {
        guard type == "spot", let slash = symbol.firstIndex(of: "/") else { return false }
        let base = String(symbol[..<slash])
        let rest = String(base.dropFirst(2))
        let quote = String(symbol[symbol.index(after: slash)...])
        return TradFi.lighterRobinhood(base: base, restAssetType: assetTypes[rest] ?? assetTypes[rest + "/" + quote])
    }

    /// Paar aus Symbol und Marktart; nil für leere oder unpassende Symbole und unbekannte Marktarten.
    private static func readPair(_ symbol: String, _ type: String, tradFi: Bool) -> MarketPair? {
        if symbol.isEmpty { return nil }
        switch type {
        case "perp":
            return symbol.contains("/") ? nil : MarketPair(symbol, lighterPerpQuote, symbol, .perpetual, tradFi: tradFi)
        case "spot":
            guard let slash = symbol.firstIndex(of: "/"), slash != symbol.startIndex,
                  symbol.index(after: slash) != symbol.endIndex else { return nil }
            return MarketPair(String(symbol[..<slash]), String(symbol[symbol.index(after: slash)...]), symbol,
                              tradFi: tradFi)
        default:
            return nil
        }
    }
}
