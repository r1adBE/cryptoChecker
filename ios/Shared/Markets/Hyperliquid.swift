import Foundation

/// Die eine Adresse für Paare, Einzel- und Sammelkurse (POST).
private let hyperliquidInfo = "https://api.hyperliquid.xyz/info"

/// Höchstlänge der Rohantwort als Fehlertext.
private let hyperliquidErrorChars = 200

/// Hyperliquid — dezentrale Perpetual-Börse mit USDC-Abrechnung. Eine POST-Abfrage («metaAndAssetCtxs») liefert Liste
/// und Kurse aller Perps, darum kein `SimpleMarket`:
/// [ {universe: [{name, …}, …]}, [{markPx, midPx, prevDayPx, dayNtlVlm, dayBaseVlm, …}, …] ],
/// beide Teile über den Index verbunden.
///
/// Kurs = `midPx` (Mitte Geld/Brief, ersatzweise `markPx`): kein letzter Handel, keine Zeit der Börse — daher
/// `priceIsMid` («Mittelkurs»), Frische ab Abruf. Die 24-h-Veränderung ist Mark gegen Mark (`markChange`). Letzte
/// Handel gäbe es nur je Coin (`recentTrades`) — für alle Perps zu teuer.
final class Hyperliquid: Market {
    init() {
        super.init(key: "Hyperliquid", name: "Hyperliquid", ttsName: "Hyperliquid")
    }

    override var priceIsMid: Bool { true }

    /// Veränderung Mark gegen Mark (`markChange`) — als «Mark» benannt.
    override var changeIsMark: Bool { true }

    // MARK: Einzelkurs: dieselbe Antwort, daraus der gesuchte Coin

    override func tickerAddress(call: Int, info: PairQuery) -> String { hyperliquidInfo }

    // Für jedes Paar dieselbe Abfrage (~100 KB): gleichzeitige Einzelkurse teilen sich eine Antwort (`InFlightRequests`)
    override func tickerPostBody(call: Int, info: PairQuery) -> PostRequestInfo? { Hyperliquid.metaRequest }

    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        var ignored = Set<String>()
        let byCoin = try readUniverse(body, failed: &ignored)
        guard let coin = info.pairId, let snapshot = byCoin[coin] else {
            throw JSONError(message: "Unknown coin: \(info.pairId ?? "null")")
        }
        ticker.last = snapshot.last
        ticker.vol = snapshot.vol
        ticker.volQuote = snapshot.volQuote
        ticker.change24hPercent = snapshot.change24hPercent
        ticker.markFallback = snapshot.markFallback
    }

    /// Hyperliquid antwortet bei Fehlern mit blossem Text: dessen Anfang ist der Fehlertext.
    override func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        String(body.prefix(hyperliquidErrorChars))
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { hyperliquidInfo }

    override func bulkTickersPostRequestInfo(call: Int) -> PostRequestInfo? { Hyperliquid.metaRequest }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        try readUniverse(body, failed: &failed)
    }

    // MARK: Paarliste

    override func pairListAddress(call: Int) -> String? { hyperliquidInfo }

    override func pairListPostBody(call: Int) -> PostRequestInfo? { Hyperliquid.metaRequest }

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for coin in try JArray(string: body).object(0).array("universe").allObjects() where !coin.optBool("isDelisted") {
            let name = try coin.string("name")
            out.append(MarketPair(name, "USDC", name, .perpetual))
        }
        return out
    }

    // MARK: Lesen

    /// Ein Kontext; bei dünnem Orderbuch fehlt `midPx` → Mark-Preis, als solcher markiert (`Ticker.markFallback`).
    private static func copyRow(_ context: JObject, into ticker: inout Ticker) {
        let mid = context.optDouble("midPx", .nan)
        ticker.markFallback = mid.isNaN
        ticker.last = mid.isNaN ? context.optDouble("markPx", .nan) : mid
        ticker.vol = context.optDoubleOrMissing("dayBaseVlm")
        ticker.volQuote = context.optDoubleOrMissing("dayNtlVlm")
        ticker.change24hPercent = markChange(context)
    }

    /// Die eine Abfrage für Paare, Einzel- und Sammelkurse.
    private static var metaRequest: PostRequestInfo {
        PostRequestInfo(body: #"{"type":"metaAndAssetCtxs"}"#, headers: ["Content-Type": "application/json"])
    }

    /// Alle gelisteten Coins mit Kurs; `failed` sammelt Coins, deren Kontext keinen lesbaren Preis hat. Universum und
    /// Kontexte hängen über den Index zusammen, darum hier eine Zählschleife statt `allObjects()`.
    private func readUniverse(_ body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let root = try JArray(string: body)
        let universe = try root.object(0).array("universe")
        let contexts = try root.array(1)
        var byCoin: [String: Ticker] = [:]
        for index in 0..<min(universe.count, contexts.count) {
            let coin = try universe.object(index)
            if coin.optBool("isDelisted") { continue }
            guard let context = contexts.optObject(index), !Hyperliquid.withoutPrice(context, coin) else { continue }
            let name = try coin.string("name")
            bulkRow(name, tickers: &byCoin, failed: &failed) { Hyperliquid.copyRow(context, into: &$0) }
        }
        return byCoin
    }

    /// Kontext ohne Preis auslassen statt als Fehler zählen: midPx/markPx vorhanden, aber leer (ruhend), oder beide
    /// fehlen bei einem Coin ohne Namen. Fehlen beide bei benanntem Coin → Fehler über `bulkRow`.
    private static func withoutPrice(_ context: JObject, _ coin: JObject) -> Bool {
        let hasMid = context.raw["midPx"] != nil
        let hasMark = context.raw["markPx"] != nil
        let usableMid = hasMid && !BulkTickerRows.inactivePrice(context, "midPx")
        let usableMark = hasMark && !BulkTickerRows.inactivePrice(context, "markPx")
        if usableMid || usableMark { return false }
        if hasMid || hasMark { return true }
        return coin.optString("name").isEmpty
    }

    /// 24-h-Veränderung Mark gegen Mark: `prevDayPx` ist ein Mark-Preis von vor 24 h, einen damaligen Mittelkurs gibt
    /// es nicht. Mittelkurs gegen Mark vermischte den Abstand Mid/Mark mit der Bewegung. Ohne `markPx` keine Angabe
    /// (dann Kerzen).
    private static func markChange(_ context: JObject) -> Double? {
        Change24h.fromOpen(last: context.optDouble("markPx", .nan), open: context.optDouble("prevDayPx"))
    }
}
