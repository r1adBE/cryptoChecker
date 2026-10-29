import Foundation

/// Produktliste (Paare und Faktoren); bei fehlenden Faktoren auch erste Anfrage der Spot-Kurse.
private let phemexProducts = "https://api.phemex.com/public/products"

private let phemexMD = "https://api.phemex.com/md"

/// Phemex Spot. Symbole beginnen mit «s» (sBTCUSDT). Die Börse liefert ganze Zahlen (Ep/Ev), die erst mit
/// `PhemexScales` zu Kursen werden: Preise (Ep) durch 10^`priceScale` des Produkts (ohne Angabe kein Kurs),
/// Menge (volumeEv, Basis) und Umsatz (turnoverEv, Kotierung) durch 10^`valueScale` der jeweiligen Währung aus
/// `/public/products` (meist 8; SHIB 2, VPAD 4). Doku: github.com/phemex/phemex-api-docs.
/// `PhemexFutures` bekommt Dezimaltexte (Rp) und braucht keine Faktoren.
struct Phemex: PhemexBase {
    let key: String = "Phemex"
    let name: String = "Phemex"
    let tickerURL: String = phemexMD + "/spot/ticker/24hr?symbol=%1$s"
    let contractType: FuturesContractType = .none

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? "s" + info.base + info.quote }

    /// Fehlen die Faktoren, ist Anfrage 0 die Produktliste (diesmal ohne Kurs, ab der nächsten Abfrage mit — sonst
    /// hinge ein Paar ohne Sammelabfrage für immer im Fehler). Veraltete Faktoren (`PhemexScales.isStale`) frischt
    /// eine zweite Anfrage nach dem Kurs auf.
    func tickerCallCount(_ info: PairQuery) -> Int { Phemex.callsFor(PhemexScales.current) }

    func tickerAddress(call: Int, info: PairQuery) -> String {
        let products: Bool
        switch call {
        case 0: products = !PhemexScales.current.loaded
        case 1: products = true
        default: products = false
        }
        return products ? phemexProducts : simpleTickerAddress(info)
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let productData = Phemex.productDataOf(json) else {
            try phemexFillTicker(json, ticker: &ticker, info: info)
            return
        }
        // Antwort war die Produktliste: Faktoren übernehmen (und ablegen); als Anfrage 0 gibt es noch keinen Kurs
        PhemexScales.current = PhemexScales.parse(productData)
        if call == 0 { throw JSONError(message: PhemexScales.justLoaded) }
    }

    // MARK: Sammelabfrage

    /// Ohne Faktoren (auch keine gespeicherten): erst `/public/products`, dann die Kurse. Veraltete Faktoren: die
    /// Produktliste als zweite Anfrage nach den Kursen (scheitert sie, gelten die alten weiter — Gruppe 1).
    var bulkTickersNumOfRequests: Int { Phemex.callsFor(PhemexScales.current) }

    func bulkTickersURL(call: Int) -> String? {
        Phemex.isProductsCall(call) ? phemexProducts : phemexMD + "/spot/ticker/24hr/all"
    }

    /// Die Auffrischung (Anfrage 1) ist eine eigene Gruppe: Scheitert sie, bleiben die Kurse vollständig.
    func bulkTickersRequestGroup(call: Int) -> Int {
        call == 1 && Phemex.isProductsCall(1) ? 1 : 0
    }

    /// Spot ohne Faktoren: Diese Antwort ist die Produktliste — Faktoren übernehmen, keine Kurse.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        if let productData = Phemex.productDataOf(try JObject(string: body)) {
            PhemexScales.current = PhemexScales.parse(productData)
            return [:]
        }
        return try phemexParseBulkTickers(body, failed: &failed)
    }

    // MARK: Lesen

    func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?,
                 quote: String?) throws {
        // Ohne Faktoren aus der Produktliste (oder ihrer Kopie) kein Kurs: geraten läge er Zehnerpotenzen daneben
        let scales = PhemexScales.current
        guard scales.loaded else { throw JSONError(message: PhemexScales.notLoaded) }
        // Symbol ohne priceScale in der Liste (fehlt oder neu): ebenfalls kein Kurs statt geratener 10^8
        guard let priceDivisor = scales.priceDivisor(symbol) else {
            throw JSONError(message: PhemexScales.unknownScale(symbol))
        }
        let codes = scales.currencies(symbol, base: base, quote: quote)

        let last = try row.double("lastEp") / priceDivisor
        ticker.last = last
        ticker.bid = Phemex.scaled(row, "bidEp", priceDivisor)
        ticker.ask = Phemex.scaled(row, "askEp", priceDivisor)
        ticker.high = Phemex.scaled(row, "highEp", priceDivisor)
        ticker.low = Phemex.scaled(row, "lowEp", priceDivisor)
        ticker.vol = Phemex.scaled(row, "volumeEv", scales.valueDivisor(codes.base))
        ticker.volQuote = Phemex.scaled(row, "turnoverEv", scales.valueDivisor(codes.quote))
        // «timestamp» (ns) = Momentaufnahme (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
        // «openEp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: Phemex.scaled(row, "openEp", priceDivisor))
    }

    /// `data` der Produktliste, falls `json` diese ist (statt eines Tickers); sonst nil.
    private static func productDataOf(_ json: JObject) -> JObject? {
        guard let data = json.optObject("data"), data.has("products") else { return nil }
        return data
    }

    private static func isProductsCall(_ call: Int) -> Bool {
        let scales = PhemexScales.current
        return scales.loaded ? call == 1 && scales.isStale() : call == 0
    }

    private static func callsFor(_ scales: PhemexScales) -> Int { scales.isStale() ? 2 : 1 }

    /// Zahlenfeld `name` durch `divisor`; fehlt es oder ist es keine Zahl: keine Daten.
    private static func scaled(_ row: JObject, _ name: String, _ divisor: Double) -> Double {
        let raw = row.optDouble(name)
        return raw.isNaN ? Ticker.missing : raw / divisor
    }
}

/// Phemex USDT-Perpetuals: Dezimaltexte (Rp/Rq/Rv), ohne Geld-/Briefkurs.
struct PhemexFutures: PhemexBase {
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    var category: ExchangeCategory { .futures }

    let key: String = "PhemexFutures"
    let name: String = "Phemex Futures"
    let tickerURL: String = phemexMD + "/v2/ticker/24hr?symbol=%1$s"
    let contractType: FuturesContractType = .perpetual

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    // MARK: Sammelabfrage

    func bulkTickersURL(call: Int) -> String? { phemexMD + "/v2/ticker/24hr/all" }

    // MARK: Lesen

    func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?,
                 quote: String?) throws {
        let close = try row.double("closeRp")
        ticker.last = close
        ticker.high = row.optDoubleOrMissing("highRp")
        ticker.low = row.optDoubleOrMissing("lowRp")
        ticker.vol = row.optDoubleOrMissing("volumeRq")
        ticker.volQuote = row.optDoubleOrMissing("turnoverRv")
        // «timestamp» (ns): Momentaufnahme wie bei Spot. «openRp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last: close, open: row.optDouble("openRp"))
    }
}

/// Gemeinsames von Spot und Perpetuals: Paarliste aus `/public/products`, Fehlerformat, Sammelabfrage.
protocol PhemexBase: SimpleExchange {
    var contractType: FuturesContractType { get }

    /// Liest einen Ticker; `symbol` = Paar-Kennung, `base`/`quote` wenn bekannt (Einzelabfrage). Jede der beiden
    /// Börsen liefert das (in Kotlin abstrakt).
    func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?, quote: String?) throws
}

extension PhemexBase {
    var pairListURL: String { phemexProducts }
    var ttsName: String { "Phemex" }

    private var isSpot: Bool { contractType == .none }

    // MARK: Einzelkurs

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try phemexFillTicker(json, ticker: &ticker, info: info)
    }

    /// Gemeinsames Lesen des Einzelkurses (`result`); Spot nimmt es, wenn die Antwort keine Produktliste ist.
    func phemexFillTicker(_ json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let symbol = pairId(info) ?? ""
        try copyRow(json.object("result"), into: &ticker, symbol: symbol, base: info.base, quote: info.quote)
    }

    /// Fehlerformen: {"error":{…}} oder {"code":…,"msg":"…"}.
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if let nested = json.optObject("error") {
            let message = nested.optString("message")
            return message.isEmpty ? "\(nested.raw)" : message
        }
        let msg = json.optString("msg")
        if msg.isEmpty { try noErrorText() }
        return msg
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        try phemexParseBulkTickers(body, failed: &failed)
    }

    /// Eine Fehlerantwort statt Liste scheitert mit dem Text der Börse, statt still leer zu bleiben (wie Android).
    /// Einträge, die keine Objekte sind, werden übergangen. Spot nimmt es, wenn die Antwort keine Produktliste ist.
    func phemexParseBulkTickers(_ body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let root = try JObject(string: body)
        guard let rows = root.optArray("result") else { throw JSONError(message: Self.bulkFailureText(root)) }
        let priceField = isSpot ? "lastEp" : "closeRp"
        var tickers: [String: Ticker] = [:]
        for row in rows.optObjects() {
            let symbol = row.optString("symbol")
            bulkRowIfActive(symbol, row: row, priceField: priceField, tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0, symbol: symbol, base: nil, quote: nil)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        let data = try json.object("data")
        return try isSpot ? collectSpot(data) : collectPerpetuals(data)
    }

    // MARK: Lesen

    private func collectSpot(_ data: JObject) throws -> [ExchangePair] {
        // Mit der Paarliste kommen die Faktoren (Ep/Ev): gleich übernehmen
        PhemexScales.current = PhemexScales.parse(data)
        var out: [ExchangePair] = []
        for product in try data.array("products").allObjects() {
            guard product.optString("type") == "Spot", product.optString("status") == "Listed" else { continue }
            out.append(ExchangePair(
                try product.string("baseCurrency"),
                try product.string("quoteCurrency"),
                try product.string("symbol")
            ))
        }
        return out
    }

    private func collectPerpetuals(_ data: JObject) throws -> [ExchangePair] {
        guard let list = data.optArray("perpProductsV2") else { return [] }
        var out: [ExchangePair] = []
        for product in try list.allObjects() where product.optString("status") == "Listed" {
            let kind = product.optString("type")
            if !kind.isEmpty && !kind.localizedCaseInsensitiveContains("Perpetual") { continue }
            out.append(ExchangePair(
                try product.string("baseCurrency"),
                try product.string("quoteCurrency"),
                try product.string("symbol"),
                contractType,
                // perpProductSubType «TradFi» (Aktien, Rohstoffe …) wie bei Binance/Bybit markieren
                tradFi: TradFi.phemex(perpProductSubType: product.optString("perpProductSubType"))
            ))
        }
        return out
    }

    /// Text einer Fehlerantwort der Sammelabfrage: `error.message`, sonst `msg`, sonst «No result».
    private static func bulkFailureText(_ root: JObject) -> String {
        let nested = root.optObject("error")?.optString("message") ?? ""
        if !nested.isEmpty { return nested }
        let msg = root.optString("msg")
        return msg.isEmpty ? "No result" : msg
    }
}
