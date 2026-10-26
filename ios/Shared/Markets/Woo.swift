import Foundation

private let wooAPI = "https://api.woox.io"

/// Spot-Kerzen je Abruf: die laufende Stunde plus 24 volle (Fenster bis jetzt).
private let wooHours = 25

/// Grund, wenn die Kerzenliste leer ist.
private let wooNoCandles = "Keine Kerzen"

/// Grund, wenn die Liste der letzten Abschlüsse leer oder unbrauchbar ist (zweite Anfrage, darf scheitern).
private let wooNoTrades = "Keine Abschlüsse"

/// WOO X Spot (API v3, developer.woox.io).
///
/// Spot hat keinen 24-h-Ticker; Grundlage sind die letzten `wooHours` Stundenkerzen. Letzter Kurs = Schluss der
/// jüngsten Kerze. Das 24-h-Fenster endet jetzt (Antwortzeit `timestamp` der Hülle):
///  - jüngste Kerze ist die laufende Stunde → 24 volle Stunden plus die angebrochene (alle 25);
///  - jüngste Kerze schon abgeschlossen (laufende Stunde fehlt noch) → genau die 24 vollen davor;
///  - ohne Antwortzeit gilt die jüngste Kerze als laufende Stunde.
/// Hoch, Tief und Volumen über dieses Fenster; Veränderung gegen die Eröffnung seiner ältesten Kerze, aber nur bei
/// lückenlosem Fenster. Liegt die jüngste Kerze mehr als eine Stunde zurück, endet kein Fenster jetzt: keine
/// Veränderung (dann rechnet die App mit eigenen Kerzen). Zeit der Börse = Ende der Information der jüngsten Kerze
/// (laufend: Antwortzeit, sonst höchstens jetzt; abgeschlossen: Stundenende).
///
/// Eine zweite Anfrage (`/v1/public/market_trades`, letzter Abschluss): Sie setzt den letzten Kurs auf
/// den echten letzten Abschluss und die Zeit der Börse auf dessen Zeit; die Veränderung wird gegen dieselbe
/// Eröffnung neu gerechnet. Scheitert sie, bleibt der Schluss der Stundenkerze (der laufenden Kerze = letzter
/// Abschluss bis zur Antwortzeit). Darum kein «Stundenkurs» mehr. Gegenstück: `Woo.kt`.
final class Woo: WooBase, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(key: "Woo", name: "WOO X", symbolPrefix: "SPOT", contractType: .none,
                   tickerURL: wooAPI + "/v3/public/kline?symbol=%1$s&type=1h&limit=\(wooHours)")
    }

    // MARK: Einzelkurs

    /// Anfrage 0: Stundenkerzen (muss gelingen); Anfrage 1: letzter Abschluss (ergänzt, darf scheitern).
    override func tickerCallCount(_ info: PairQuery) -> Int { 2 }

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        guard call == 1 else { return super.tickerAddress(call: call, info: info) }
        return wooAPI + "/v1/public/market_trades?symbol=\(pairId(info) ?? "")&limit=1"
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        if call == 1 {
            try Woo.fillLastTrade(json, into: &ticker)
            return
        }
        let candles = try json.object("data").array("rows").allObjects()
        // Neueste zuerst, je Stundenbeginn nur eine Kerze
        var seenStarts = Set<Int64>()
        let hourly = candles
            .sorted { Woo.start($0) > Woo.start($1) }
            .filter { seenStarts.insert(Woo.start($0)).inserted }
        guard let newest = hourly.first else { throw JSONError(message: wooNoCandles) }
        let last = try newest.double("close")
        ticker.last = last

        // Antwortzeit der Hülle — für die Lage des Fensters und die Zeit der Börse
        let answeredAt = json.optLong("timestamp")
        let newestAge: Int64? = answeredAt > 0 ? answeredAt - Woo.start(newest) : nil
        let newestIsRunning = newestAge.map { $0 < Change24h.hourMillis } ?? true
        // Zeit der Börse = Ende der Information im Kurs: laufende Kerze bis zur Antwortzeit (ohne diese bis
        // höchstens jetzt), abgeschlossene bis zu ihrem Stundenende. So greifen «veraltet» und die 2-h-Regel der
        // Alarme, wenn die Börse nur noch eine alte Kerze liefert.
        let candleEnd = Woo.start(newest) + Change24h.hourMillis
        if !newestIsRunning {
            ticker.timestamp = candleEnd
        } else if answeredAt > 0 {
            ticker.timestamp = answeredAt
        } else {
            ticker.timestamp = min(candleEnd, TimeUtils.nowMillis)
        }
        let span = newestIsRunning ? wooHours : wooHours - 1
        let window = Array(hourly.prefix(span))

        if let top = window.map({ $0.optDouble("high") }).filter({ !$0.isNaN }).max() { ticker.high = top }
        if let bottom = window.map({ $0.optDouble("low") }).filter({ !$0.isNaN }).min() { ticker.low = bottom }
        ticker.vol = window.reduce(0) { sum, candle in sum + candle.optDouble("volume", 0) }
        ticker.volQuote = window.reduce(0) { sum, candle in sum + candle.optDouble("amount", 0) }

        // Eröffnung der ältesten Kerze ≈ Kurs vor 24 h — nur lückenlos und wenn das Fenster jetzt endet
        let endsNow = newestAge.map { $0 < 2 * Change24h.hourMillis } ?? true
        let firstHour = endsNow ? Change24h.hourlyWindowStart(window.map(Woo.start), hours: span) : nil
        let opening = firstHour.flatMap { hour in window.first(where: { Woo.start($0) == hour }) }
        ticker.change24hPercent = opening.flatMap { Change24h.fromOpen(last: last, open: $0.optDouble("open")) }
    }

    /// Letzter Abschluss: Kurs und Zeit (Sekunden mit Bruchteil als Text). Erst alles lesen, dann schreiben — so
    /// bleibt der Kurs aus den Kerzen unverändert, wenn die Antwort unbrauchbar ist. Die Veränderung behält ihre
    /// Eröffnung (aus Kurs und Prozent der Kerzen zurückgerechnet).
    private static func fillLastTrade(_ json: JObject, into ticker: inout Ticker) throws {
        guard let rows = json.optArray("rows"), let row = try? rows.object(0) else {
            throw JSONError(message: wooNoTrades)
        }
        let price = row.optDouble("executed_price")
        guard price.isFinite, price > 0 else { throw JSONError(message: wooNoTrades) }
        let seconds = Double(row.optString("executed_timestamp"))
        let opening = ticker.change24hPercent.map { pct in ticker.last / (1 + pct / 100) }
        ticker.last = price
        // Ohne lesbare Zeit bleibt die der Kerzen (sonst gälte die Abrufzeit als Zeit der Börse). «inf» oder riesige
        // Werte gelten als unlesbar: `Int64(…)` stürzte sonst ab (wie Android `wooTradeMillis`)
        if let millis = Woo.tradeMillis(seconds) {
            ticker.timestamp = millis
        } else {
            ticker.timestamp = ticker.exchangeTime ?? 0
        }
        if let opening { ticker.change24hPercent = Change24h.fromOpen(last: price, open: opening) }
    }

    // MARK: Lesen

    /// Zeit des Abschlusses in ms aus Sekunden; nil = keine, nicht endlich, ≤ 0 oder ausserhalb von Int64.
    static func tradeMillis(_ seconds: Double?) -> Int64? {
        guard let seconds, seconds.isFinite, seconds > 0 else { return nil }
        let millis = seconds * 1000
        // 2^63 ist als Double exakt darstellbar; ab dort passt der Wert nicht mehr in Int64
        guard millis.isFinite, millis < 9_223_372_036_854_775_808.0 else { return nil }
        return JSONValue.int64(millis.rounded())
    }

    private static func start(_ candle: JObject) -> Int64 { candle.optLong("startTimestamp") }
}

/// WOO X USDT-Perpetuals.
final class WooFutures: WooBase, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    /// Abschnitt «Futures» der Börsenauswahl (`MarketCategory`). Android gleich.
    override var category: MarketCategory { .futures }

    init() {
        super.init(key: "WooFutures", name: "WOO X Futures", symbolPrefix: "PERP", contractType: .perpetual,
                   tickerURL: wooAPI + "/v3/public/futures?symbol=%1$s")
    }

    // MARK: Einzelkurs

    /// Hüllenzeit = Antwortzeit, kein Handel: Zeit der Börse bleibt offen.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try WooFutures.copyRow(json.object("data").array("rows").object(0), into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { wooAPI + "/v3/public/futures" }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).object("data").array("rows").allObjects() {
            bulkRowIfActive(row.optString("symbol"), row: row, priceField: "24hClose", tickers: &tickers,
                            failed: &failed) { try WooFutures.copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Lesen

    private static func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let close = try row.double("24hClose")
        ticker.last = close
        ticker.high = row.optDoubleOrMissing("24hHigh")
        ticker.low = row.optDoubleOrMissing("24hLow")
        ticker.vol = row.optDoubleOrMissing("24hVolume")
        ticker.volQuote = row.optDoubleOrMissing("24hAmount")
        // «24hOpen» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: close, open: row.optDouble("24hOpen"))
    }
}

/// Gemeinsam: Instrumentliste, Kennung «PRÄFIX_BASIS_QUOTE», Fehlertext in «message».
class WooBase: SimpleMarket, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    private let symbolPrefix: String
    private let contractType: FuturesContractType

    init(key: String, name: String, symbolPrefix: String, contractType: FuturesContractType, tickerURL: String) {
        self.symbolPrefix = symbolPrefix
        self.contractType = contractType
        super.init(key: key, name: name, pairListURL: wooAPI + "/v3/public/instruments", tickerURL: tickerURL,
                   ttsName: "Woo X", errorField: "message")
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? [symbolPrefix, info.base, info.quote].joined(separator: "_")
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for inst in try json.object("data").array("rows").allObjects() where inst.optString("status") == "TRADING" {
            let symbol = inst.optString("symbol")
            // SPOT_BTC_USDT bzw. PERP_BTC_USDT
            let parts = symbol.split(separator: "_").map(String.init)
            guard parts.count == 3, parts[0] == symbolPrefix else { continue }
            let base = inst.optString("baseAsset")
            let quote = inst.optString("quoteAsset")
            out.append(MarketPair(base.isEmpty ? parts[1] : base, quote.isEmpty ? parts[2] : quote, symbol,
                                        contractType))
        }
        return out
    }
}
