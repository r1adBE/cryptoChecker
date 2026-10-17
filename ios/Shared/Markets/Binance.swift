import Foundation

/// Ab so vielen Symbolen kostet die Auswahl bei Binance gleich viel wie die ganze Liste.
private let binanceSymbolLimit = 100

/// Erste Alpha-Teilanfrage der Sammelabfrage (davor: Spot); zugleich die Gruppe der Alpha-Paare.
private let binanceFirstAlphaCall = 1

/// Paarliste von binance.com nur mit handelbaren Paaren und ohne die Berechtigungslisten je Paar — ein Bruchteil der
/// vollen Antwort. Binance.US dokumentiert beide Parameter nicht, dort bleibt die volle Liste.
private let binanceComPairListQuery = "?symbolStatus=TRADING&showPermissionSets=false"

/// Binance Spot (binance.com).
final class Binance: BinanceBase {
    init() { super.init(key: "Binance", name: "Binance", domain: "com", pairListQuery: binanceComPairListQuery) }
}

/// Binance.US — gleiche API unter eigener Domain.
final class BinanceUs: BinanceBase {
    init() { super.init(key: "BinanceUs", name: "Binance.US", domain: "us") }
}

/// Gemeinsamer Teil von Binance und Binance.US (API v3). Unter «Binance» stehen zusätzlich die Alpha-Paare
/// (`BinanceAlpha`, Kennung «ALPHA_<id>USDT») mit eigenen Endpunkten. `pairListQuery` hängt an die Paarliste; der
/// Status wird beim Lesen trotzdem geprüft (Binance.US filtert nicht).
class BinanceBase: SimpleMarket {
    /// 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle.
    private let dayTicker: String

    init(key: String, name: String, domain: String, pairListQuery: String = "") {
        dayTicker = "https://api.binance.\(domain)/api/v3/ticker/24hr"
        super.init(
            key: key,
            name: name,
            pairListURL: "https://api.binance.\(domain)/api/v3/exchangeInfo" + pairListQuery,
            tickerURL: "https://api.binance.\(domain)/api/v3/ticker/24hr?symbol=%1$s",
            errorField: "msg"
        )
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        guard let pairId = info.pairId, BinanceAlpha.isPairId(pairId) else {
            return super.tickerAddress(call: call, info: info)
        }
        return BinanceAlpha.tickerURL + pairId
    }

    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        if BinanceAlpha.isPairId(info.pairId) {
            try BinanceAlpha.readTicker(body, &ticker)
        } else {
            try super.fillTicker(call: call, body: body, ticker: &ticker, info: info)
        }
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json, into: &ticker)
    }

    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if BinanceAlpha.isPairId(info.pairId) { return BinanceAlpha.errorText(json) }
        return try super.errorMessage(call: call, json: json, info: info)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Der ungefilterte Spot-Ticker enthält jedes gehandelte Paar.
    override var bulkTickersComplete: Bool { true }

    /// Ohne Auswahl: alle Spot-Paare; Alpha-Teilanfragen gibt es nur mit Auswahl (ein gescheiterter Alpha-Ticker
    /// lädt nicht ersatzweise die ganze Token-Liste).
    override func bulkTickersURL(call: Int) -> String? {
        call < binanceFirstAlphaCall ? dayTicker : nil
    }

    /// Anfrage 0: Spot, mit `symbols=[…]` nur die beobachteten Paare (wenige KB statt rund 2 MB). Ab
    /// `binanceSymbolLimit` Paaren wäre die Auswahl gleich teuer wie alles. Gibt es nur Alpha-Paare, entfällt die
    /// Spot-Anfrage. Ab Anfrage 1: Alpha (Token-Liste bzw. Einzel-Ticker).
    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        if call >= binanceFirstAlphaCall {
            let alphaCalls = BinanceAlpha.bulkRequestURLs(pairIds: pairIds)
            let slot = call - binanceFirstAlphaCall
            return slot < alphaCalls.count ? alphaCalls[slot] : nil
        }
        let spot = pairIds.filter { !BinanceAlpha.isPairId($0) }
        if spot.isEmpty && !pairIds.isEmpty { return nil }
        if spot.isEmpty || spot.count > binanceSymbolLimit { return dayTicker }
        let list = "[" + spot.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        return dayTicker + "?symbols=" + list.urlQueryEncoded
    }

    /// Spot-Anfrage plus die Alpha-Anfragen (`BinanceAlpha.bulkRequestURLs`).
    override func bulkTickersRequestCount(pairIds: [String]) -> Int {
        BinanceAlpha.bulkRequestURLs(pairIds: pairIds).count + 1
    }

    /// Spot und Alpha zählen getrennt als «vollständig» — eine gescheiterte Alpha-Anfrage trübt Spot nicht.
    override func bulkTickersRequestGroup(call: Int) -> Int {
        call < binanceFirstAlphaCall ? 0 : binanceFirstAlphaCall
    }

    override func bulkTickersPairGroup(pairId: String) -> Int {
        BinanceAlpha.isPairId(pairId) ? binanceFirstAlphaCall : 0
    }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        if call == binanceFirstAlphaCall { return try readAlphaList(body, failed: &failed) }
        if call > binanceFirstAlphaCall { return try readAlphaTicker(body) }
        return try readSpotList(body, failed: &failed)
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for entry in try json.array("symbols").allObjects() {
            guard try entry.string("status") == "TRADING" else { continue }
            let symbol = try entry.string("symbol")
            out.append(MarketPair(try entry.string("baseAsset"), try entry.string("quoteAsset"), symbol))
        }
        return out
    }

    // MARK: Lesen

    /// Ein Eintrag des 24-h-Tickers (Einzel- wie Sammelabruf). `closeTime` ist bloss das Fensterende, kein Handel:
    /// die Zeit der Börse bleibt unbekannt, sonst wirkte ein ruhiges Paar frisch.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("lastPrice")
        ticker.high = try row.double("highPrice")
        ticker.low = try row.double("lowPrice")
        ticker.bid = try row.double("bidPrice")
        ticker.ask = try row.double("askPrice")
        ticker.vol = try row.double("volume")
        ticker.volQuote = try row.double("quoteVolume")
        // «priceChangePercent»: gleitende 24 h, bereits Prozent
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }

    private func readSpotList(_ body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "lastPrice", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    /// Alpha-Token-Liste: Token ohne Kurs (0 bzw. leer) werden nicht gehandelt und fallen still weg; fehlt `price`
    /// ganz oder ist er unlesbar, gilt die Zeile als Fehler (`bulkRow`).
    private func readAlphaList(_ body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try BinanceAlpha.listRows(body).optObjects() {
            guard let pairId = BinanceAlpha.listRowPairId(row) else { continue }
            let price = row.optDouble("price")
            let tradable = price.isFinite && price > 0
            if !tradable && !row.isNull("price") { continue }
            bulkRow(pairId, tickers: &tickers, failed: &failed) { target in
                guard try BinanceAlpha.readListRow(row, &target) else {
                    throw JSONError(message: "Alpha row without price")
                }
            }
        }
        return tickers
    }

    /// Einzel-Ticker eines Alpha-Paars; ein unbekanntes Paar fehlt einfach.
    private func readAlphaTicker(_ body: String) throws -> [String: Ticker] {
        var single = Ticker()
        guard let pairId = try BinanceAlpha.readBulkTicker(body, &single) else { return [:] }
        return [pairId: single]
    }
}
