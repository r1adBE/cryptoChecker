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
/// `priceIsMid` («Mittelkurs»), Frische ab Abruf. Die 24-h-Veränderung rechnet mit demselben Kurs gegen `prevDayPx`
/// (`shownChange`), damit Kurs, Änderung und Alarme auf einer Basis stehen. Letzte Handel gäbe es nur je Coin
/// (`recentTrades`) — für alle Perps zu teuer.
final class Hyperliquid: Market, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(key: "Hyperliquid", name: "Hyperliquid", ttsName: "Hyperliquid")
    }

    override var priceIsMid: Bool { true }

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

    /// Hyperliquid antwortet bei Fehlern mit blossem Text: dessen Anfang ist der Fehlertext. Eine gültige Antwort
    /// (JSON-Liste) ohne den Coin hat keinen — sonst verdeckte ihr Anfang «Unknown coin» (`NotTraded.isMissingEntry`).
    override func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        if body.drop(while: { $0.isWhitespace }).hasPrefix("[") { try noErrorText() }
        return String(body.prefix(hyperliquidErrorChars))
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    /// Der Einzelkurs liest dieselbe vollständige Liste.
    override var singleTickerReadsFullList: Bool { true }

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

    /// Ein Kontext; bei dünnem Orderbuch fehlt `midPx` oder ist «0» → Mark-Preis, als solcher markiert
    /// (`Ticker.markFallback`). «0» ist kein Mittelkurs (`optDouble` liest ihn aber als 0, nicht als fehlend). Wie Android.
    private static func copyRow(_ context: JObject, into ticker: inout Ticker) {
        let rawMid = context.optDouble("midPx", .nan)
        let mid = rawMid > 0 && rawMid.isFinite ? rawMid : .nan
        ticker.markFallback = mid.isNaN
        ticker.last = mid.isNaN ? context.optDouble("markPx", .nan) : mid
        ticker.vol = context.optDoubleOrMissing("dayBaseVlm")
        ticker.volQuote = context.optDoubleOrMissing("dayNtlVlm")
        ticker.change24hPercent = shownChange(last: ticker.last, context)
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

    /// 24-h-Veränderung des gezeigten Kurses `last` (Mittelkurs, ersatzweise Mark) gegen `prevDayPx`. Mark gegen Mark
    /// wich vom gezeigten Kurs ab: Der Mittelkurs bewegt sich auch ohne Handel, und Alarme auf Kurs und Prozent
    /// stünden auf zwei Basen. Der Abstand Mid/Mark ist klein gegen die Tagesbewegung. Ohne Kurs oder `prevDayPx`
    /// keine Angabe (dann Kerzen). Wie Android.
    private static func shownChange(last: Double, _ context: JObject) -> Double? {
        Change24h.fromOpen(last: last, open: context.optDouble("prevDayPx"))
    }
}
