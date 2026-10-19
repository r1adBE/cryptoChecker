import Foundation

private let bingxAPI = "https://open-api.bingx.com/openApi"

/// «status» eines handelbaren Symbols bzw. Kontrakts (1 = online).
private let bingxOnline = 1

/// BingX Spot. API: https://bingx-api.github.io/docs/#/en-us/spot/market-api.html
final class BingX: BingXBase {
    init() {
        super.init(key: "BingX", name: "BingX", contractType: .none,
                   pairListPath: "/spot/v1/common/symbols", tickerPath: "/spot/v1/ticker/24hr")
    }
}

/// BingX USDT-Perpetuals (Swap V2). API: https://bingx-api.github.io/docs/#/en-us/swapV2/market-api.html
final class BingXFutures: BingXBase {
    init() {
        super.init(key: "BingXFutures", name: "BingX Futures", contractType: .perpetual,
                   pairListPath: "/swap/v2/quote/contracts", tickerPath: "/swap/v2/quote/ticker")
    }
}

/// Gemeinsamer Teil von Spot und Perpetuals. Antworten haben die Form {"code", "msg", "data"}; `data` ist beim
/// Einzelkurs je nach Bereich ein Objekt (Swap) oder ein Array mit einem Eintrag (Spot), bei der Sammelabfrage ein
/// Array — `dataRows` nimmt beides. Kennung beider Bereiche: «BTC-USDT».
///
/// Die Doku verlangt auch bei öffentlichen Abfragen den Parameter `timestamp` (ms); ohne ihn können Abfragen
/// abgelehnt werden. Darum kein `SimpleMarket`: jede Adresse wird beim Abruf gestempelt (`stamped`). Wie `BingX.kt`.
class BingXBase: Market {
    private let contractType: FuturesContractType
    private let pairListPath: String
    private let tickerPath: String

    init(key: String, name: String, contractType: FuturesContractType, pairListPath: String, tickerPath: String) {
        self.contractType = contractType
        self.pairListPath = pairListPath
        self.tickerPath = tickerPath
        super.init(key: key, name: name)
    }

    private var perpetuals: Bool { contractType == .perpetual }

    // MARK: Einzelkurs

    /// Ohne Kennung bleibt die Stelle leer (wie `SimpleMarket`); die Börse antwortet dann mit ihrem Fehler.
    override func tickerAddress(call: Int, info: PairQuery) -> String {
        BingXBase.stamped(bingxAPI + tickerPath + "?symbol=" + (info.pairId ?? ""))
    }

    /// `closeTime` ist das Fensterende (≈ jetzt), kein Handel → Zeit der Börse unbekannt (wie MEXC).
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let rows = try BingXBase.dataRows(json)
        guard let row = rows.first else { throw JSONError(message: JSONError.noData) }
        try copyRow(row, into: &ticker)
    }

    /// code 0 heisst Erfolg — dann ist «msg» (meist leer) kein Fehlertext; es bleibt beim Fehler des Parsers.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("code", -1) == 0 { try noErrorText() }
        return try json.string("msg")
    }

    // MARK: Sammelabfrage (ohne `symbol` alle Paare des Bereichs)

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { BingXBase.stamped(bingxAPI + tickerPath) }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try BingXBase.dataRows(JObject(string: body)) {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func pairListAddress(call: Int) -> String? { BingXBase.stamped(bingxAPI + pairListPath) }

    /// Spot: {"data": {"symbols": [{symbol, status}]}} — Basis und Kotierung nur im Symbol. Swap: {"data":
    /// [{symbol, asset, currency, status}]}. Nur «status» 1 (online); offline, Voreröffnung und ausgesetzte nicht.
    /// Swap-TradFi (`TradFi.bingx`): `asset` ist eine interne Kennung («NCSKAAPL2USD»), die Basis kommt aus
    /// `displayName`; Krypto behält `asset` (`displayName` weicht dort ab, etwa NEIROCTO → «NEIRO»).
    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        let entries = try perpetuals ? json.array("data") : json.object("data").array("symbols")
        var out: [MarketPair] = []
        for entry in try entries.allObjects() {
            guard entry.optInt("status", -1) == bingxOnline else { continue }
            let symbol = try entry.string("symbol")
            if perpetuals {
                let asset = try entry.string("asset")
                let tradFi = TradFi.bingx(asset: asset)
                let base = tradFi ? TradFi.bingxBase(displayName: entry.optString("displayName"), asset: asset) : asset
                // Immer gesetzt (auch false), wie bei Binance Futures
                out.append(MarketPair(base, try entry.string("currency"), symbol, contractType, tradFi: tradFi))
            } else {
                // Kotierung = Teil nach dem letzten «-» (Kotierungen enthalten keinen Bindestrich)
                guard let cut = symbol.lastIndex(of: "-"), cut != symbol.startIndex,
                      symbol.index(after: cut) != symbol.endIndex else { continue }
                out.append(MarketPair(String(symbol[..<cut]), String(symbol[symbol.index(after: cut)...]), symbol))
            }
        }
        return out
    }

    // MARK: Lesen

    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // Gleitende 24 h aus «openPrice»: «priceChangePercent» kommt je Bereich verschieden (Spot mit «%»)
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("openPrice"))
    }

    /// Einträge von `data`: Array → alle (jeder muss ein Objekt sein), Objekt → dieses eine.
    private static func dataRows(_ envelope: JObject) throws -> [JObject] {
        if let list = envelope.optArray("data") { return try list.allObjects() }
        if let single = envelope.optObject("data") { return [single] }
        throw JSONError(message: "data fehlt")
    }

    /// Hängt `timestamp` (jetzt, ms) an; die Börse prüft ihn gegen ihre Uhr.
    private static func stamped(_ url: String) -> String {
        url + (url.contains("?") ? "&" : "?") + "timestamp=\(TimeUtils.nowMillis)"
    }
}
