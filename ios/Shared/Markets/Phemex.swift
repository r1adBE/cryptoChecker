import Foundation

/// Produktliste (Paare und Faktoren); bei fehlenden Faktoren auch erste Anfrage der Spot-Kurse.
private let phemexProducts = "https://api.phemex.com/public/products"

private let phemexMD = "https://api.phemex.com/md"

/// Phemex Spot. Symbole beginnen mit «s» (sBTCUSDT). Die Börse liefert ganze Zahlen (Ep/Ev), die erst mit
/// `PhemexScales` zu Kursen werden: Preise (Ep) durch 10^`priceScale` des Produkts (ohne Angabe kein Kurs),
/// Menge (volumeEv, Basis) und Umsatz (turnoverEv, Kotierung) durch 10^`valueScale` der jeweiligen Währung aus
/// `/public/products` (meist 8; SHIB 2, VPAD 4). Doku: github.com/phemex/phemex-api-docs.
/// `PhemexFutures` bekommt Dezimaltexte (Rp) und braucht keine Faktoren.
final class Phemex: PhemexBase {
    init() {
        super.init(key: "Phemex", name: "Phemex", tickerURL: phemexMD + "/spot/ticker/24hr?symbol=%1$s",
                   contractType: .none)
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? "s" + info.base + info.quote }

    /// Fehlen die Faktoren, ist Anfrage 0 die Produktliste (diesmal ohne Kurs, ab der nächsten Abfrage mit — sonst
    /// hinge ein Paar ohne Sammelabfrage für immer im Fehler). Veraltete Faktoren (`PhemexScales.isStale`) frischt
    /// eine zweite Anfrage nach dem Kurs auf.
    override func tickerCallCount(_ info: PairQuery) -> Int { Phemex.callsFor(PhemexScales.current) }

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        let products: Bool
        switch call {
        case 0: products = !PhemexScales.current.loaded
        case 1: products = true
        default: products = false
        }
        return products ? phemexProducts : super.tickerAddress(call: call, info: info)
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        guard let productData = Phemex.productDataOf(json) else {
            try super.fillTicker(call: call, json: json, ticker: &ticker, info: info)
            return
        }
        // Antwort war die Produktliste: Faktoren übernehmen (und ablegen); als Anfrage 0 gibt es noch keinen Kurs
        PhemexScales.current = PhemexScales.parse(productData)
        if call == 0 { throw JSONError(message: PhemexScales.justLoaded) }
    }

    // MARK: Sammelabfrage

    /// Ohne Faktoren (auch keine gespeicherten): erst `/public/products`, dann die Kurse. Veraltete Faktoren: die
    /// Produktliste als zweite Anfrage nach den Kursen (scheitert sie, gelten die alten weiter — Gruppe 1).
    override var bulkTickersNumOfRequests: Int { Phemex.callsFor(PhemexScales.current) }

    override func bulkTickersURL(call: Int) -> String? {
        Phemex.isProductsCall(call) ? phemexProducts : phemexMD + "/spot/ticker/24hr/all"
    }

    /// Die Auffrischung (Anfrage 1) ist eine eigene Gruppe: Scheitert sie, bleiben die Kurse vollständig.
    override func bulkTickersRequestGroup(call: Int) -> Int {
        call == 1 && Phemex.isProductsCall(1) ? 1 : 0
    }

    /// Spot ohne Faktoren: Diese Antwort ist die Produktliste — Faktoren übernehmen, keine Kurse.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        if let productData = Phemex.productDataOf(try JObject(string: body)) {
            PhemexScales.current = PhemexScales.parse(productData)
            return [:]
        }
        return try super.parseBulkTickers(call: call, body: body, failed: &failed)
    }

    // MARK: Lesen

    override func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?,
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
final class PhemexFutures: PhemexBase {
    init() {
        super.init(key: "PhemexFutures", name: "Phemex Futures", tickerURL: phemexMD + "/v2/ticker/24hr?symbol=%1$s",
                   contractType: .perpetual)
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    // MARK: Sammelabfrage

    override func bulkTickersURL(call: Int) -> String? { phemexMD + "/v2/ticker/24hr/all" }

    // MARK: Lesen

    override func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?,
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
class PhemexBase: SimpleMarket {
    private let contractType: FuturesContractType

    init(key: String, name: String, tickerURL: String, contractType: FuturesContractType) {
        self.contractType = contractType
        super.init(key: key, name: name, pairListURL: phemexProducts, tickerURL: tickerURL, ttsName: "Phemex")
    }

    private var isSpot: Bool { contractType == .none }

    /// Liest einen Ticker; `symbol` = Paar-Kennung, `base`/`quote` wenn bekannt (Einzelabfrage). Jede Unterklasse
    /// muss das liefern (in Kotlin abstrakt).
    func copyRow(_ row: JObject, into ticker: inout Ticker, symbol: String, base: String?, quote: String?) throws {
        fatalError("\(key): copyRow fehlt")
    }

    // MARK: Einzelkurs

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let symbol = pairId(info) ?? ""
        try copyRow(json.object("result"), into: &ticker, symbol: symbol, base: info.base, quote: info.quote)
    }

    /// Fehlerformen: {"error":{…}} oder {"code":…,"msg":"…"}.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if let nested = json.optObject("error") {
            let message = nested.optString("message")
            return message.isEmpty ? "\(nested.raw)" : message
        }
        let msg = json.optString("msg")
        if msg.isEmpty { try noErrorText() }
        return msg
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Eine Fehlerantwort statt Liste scheitert mit dem Text der Börse, statt still leer zu bleiben (wie Android).
    /// Einträge, die keine Objekte sind, werden übergangen.
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let root = try JObject(string: body)
        guard let rows = root.optArray("result") else { throw JSONError(message: PhemexBase.bulkFailureText(root)) }
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

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        let data = try json.object("data")
        return try isSpot ? collectSpot(data) : collectPerpetuals(data)
    }

    // MARK: Lesen

    private func collectSpot(_ data: JObject) throws -> [MarketPair] {
        // Mit der Paarliste kommen die Faktoren (Ep/Ev): gleich übernehmen
        PhemexScales.current = PhemexScales.parse(data)
        var out: [MarketPair] = []
        for product in try data.array("products").allObjects() {
            guard product.optString("type") == "Spot", product.optString("status") == "Listed" else { continue }
            out.append(MarketPair(
                try product.string("baseCurrency"),
                try product.string("quoteCurrency"),
                try product.string("symbol")
            ))
        }
        return out
    }

    private func collectPerpetuals(_ data: JObject) throws -> [MarketPair] {
        guard let list = data.optArray("perpProductsV2") else { return [] }
        var out: [MarketPair] = []
        for product in try list.allObjects() where product.optString("status") == "Listed" {
            let kind = product.optString("type")
            if !kind.isEmpty && !kind.localizedCaseInsensitiveContains("Perpetual") { continue }
            out.append(MarketPair(
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

/// Faktoren der Phemex-Spot-Produkte aus `/public/products` — mit der Paarliste geladen, im Speicher gehalten und als
/// Datei in der App Group abgelegt (`storeURL`). Wie `PhemexScales` (Android): `priceScale` je Symbol (Ep; ohne Feld kein
/// Kurs — nie geraten), `valueScale` je Währung (Ev, Liste `currencies`), `pairs` Symbol → Basis/Kotierung. Ohne
/// Produktliste (`loaded` false) zeigt Phemex keinen Kurs; die Sammelabfrage holt dann zuerst die Liste — nie ein
/// geratener Faktor. `documentedValueScales` gelten nur für Währungen, die die Liste nicht nennt.
struct PhemexScales: Sendable {
    var priceScale: [String: Int] = [:]
    var valueScale: [String: Int] = [:]
    var pairs: [String: (base: String, quote: String)] = [:]
    /// Aus der Produktliste (oder ihrer gespeicherten Kopie); false = nie geladen.
    var loaded = false
    /// Zeitpunkt der Produktliste in ms; 0 = unbekannt (ältere Datei) → gilt als veraltet.
    var loadedAt: Int64 = 0

    static let defaultScale = 8
    static let formatVersion = 1
    /// Fehlertext, solange die Produktliste fehlt (lieber kein Kurs als einer um Zehnerpotenzen daneben).
    static let notLoaded = "Phemex: product list not loaded yet"
    /// Fehlertext für ein Symbol ohne `priceScale` in der Produktliste. Wie Android `UNKNOWN_SCALE`.
    static func unknownScale(_ symbol: String) -> String { "Phemex: no price scale for \(symbol) in the product list" }
    /// Fehlertext der Einzelabfrage, deren erste Anfrage eben die fehlende Produktliste geladen hat.
    static let justLoaded = "Phemex: product list loaded, price follows with the next update"
    /// Faktoren einmal täglich neu laden.
    static let refreshMillis: Int64 = 24 * 60 * 60 * 1000
    /// Public-Spot-API, Tabelle «All Spot trading currencies»: Wertfaktoren ungleich 8.
    static let documentedValueScales: [String: Int] = ["SHIB": 2, "VPAD": 4]
    /// Kotierungen, an denen ein unbekanntes Symbol getrennt wird.
    private static let quoteSuffixes = ["USDT", "USDC", "BTC", "ETH", "TRY", "BRZ"]

    /// Fehlt oder älter als `refreshMillis` (oder Zeitpunkt in der Zukunft): neu laden. Wie Android.
    func isStale(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        guard loaded else { return true }
        let age = now - loadedAt
        return age < 0 || age >= Self.refreshMillis
    }

    // MARK: Geteilter Stand

    private static let guardLock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `guardLock` (NSLock)
    nonisolated(unsafe) private static var shared = PhemexScales()
    nonisolated(unsafe) private static var diskChecked = false
    nonisolated(unsafe) private static var diskFile: URL? = SharedStorage.directory.appendingPathComponent("phemex_scales.json")

    /// Ablage auf dem Gerät (App Group, auch für die Widgets); nil = nur im Speicher (Tests). Neu setzen erlaubt
    /// einen neuen Ladeversuch. Wie Android `PhemexScales.store`.
    static var storeURL: URL? {
        get { guardLock.withLock { diskFile } }
        set {
            guardLock.withLock {
                diskFile = newValue
                diskChecked = false
            }
        }
    }

    /// Zuletzt aus der Produktliste gelesene Faktoren (für alle). Noch nichts geladen: einmal die Datei versuchen.
    /// Neue Faktoren aus der Produktliste werden sofort abgelegt.
    static var current: PhemexScales {
        get {
            guardLock.lock()
            defer { guardLock.unlock() }
            guard !shared.loaded, !diskChecked else { return shared }
            diskChecked = true
            if let file = diskFile, let bytes = try? Data(contentsOf: file),
               let fromDisk = decode(String(decoding: bytes, as: UTF8.self)) {
                shared = fromDisk
            }
            return shared
        }
        set {
            let target: URL? = guardLock.withLock {
                shared = newValue
                return diskFile
            }
            guard newValue.loaded, let file = target, let bytes = newValue.encode().data(using: .utf8) else { return }
            try? bytes.write(to: file, options: .atomic)
        }
    }

    // MARK: Datei

    /// Dateiform `{"v":1,"t":…,"price":{…},"value":{…},"pairs":{"sBTCUSDT":["BTC","USDT"]}}` — wie Android.
    func encode() -> String {
        let pairLists = pairs.mapValues { [$0.base, $0.quote] }
        let root: [String: Any] = ["v": Self.formatVersion, "t": loadedAt, "price": priceScale, "value": valueScale,
                                   "pairs": pairLists]
        guard let bytes = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) else { return "" }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Gegenstück zu `encode`; andere Version, unlesbar oder ganz ohne Angaben → nil.
    static func decode(_ text: String?) -> PhemexScales? {
        guard let text, !text.isEmpty, let root = try? JObject(string: text), root.optInt("v") == formatVersion else {
            return nil
        }
        /// Ganzzahlen eines Unterobjekts; null-Werte fallen weg.
        func scales(_ field: String) -> [String: Int] {
            guard let table = root.optObject(field) else { return [:] }
            var out: [String: Int] = [:]
            for key in table.keys where table.has(key) { out[key] = table.optInt(key, defaultScale) }
            return out
        }
        var result = PhemexScales()
        result.priceScale = scales("price")
        result.valueScale = scales("value")
        if let table = root.optObject("pairs") {
            for symbol in table.keys {
                guard let legs = table.optArray(symbol) else { continue }
                let base = legs.optString(0)
                let quote = legs.optString(1)
                if !base.isEmpty && !quote.isEmpty { result.pairs[symbol] = (base: base, quote: quote) }
            }
        }
        guard !(result.priceScale.isEmpty && result.valueScale.isEmpty && result.pairs.isEmpty) else { return nil }
        result.loaded = true
        result.loadedAt = root.optLong("t", 0)
        return result
    }

    // MARK: Abfragen

    /// Teiler der Preise (Ep) von `symbol`; nil, wenn die Produktliste für das Symbol keinen `priceScale` nennt
    /// (fehlt in der Liste, neu gelistet). Dann zeigt das Paar einen Fehler: 8 zu raten läge bei anderen Faktoren
    /// Zehnerpotenzen daneben. Wie Android.
    func priceDivisor(_ symbol: String) -> Double? { priceScale[symbol].map { Self.tenToThe($0) } }

    /// Teiler der Werte (Ev) in `currency`; unbekannt: dokumentierte Ausnahme, sonst 8.
    func valueDivisor(_ currency: String?) -> Double {
        guard let code = currency?.uppercased() else { return Self.tenToThe(Self.defaultScale) }
        return Self.tenToThe(valueScale[code] ?? Self.documentedValueScales[code] ?? Self.defaultScale)
    }

    /// Basis und Kotierung von `symbol`: aus der Produktliste, sonst `base`/`quote`, sonst am Symbol geschätzt.
    func currencies(_ symbol: String, base: String?, quote: String?) -> (base: String?, quote: String?) {
        if let listed = pairs[symbol] { return (listed.base, listed.quote) }
        if let base, let quote, !base.isEmpty, !quote.isEmpty { return (base, quote) }
        let bare = symbol.hasPrefix("s") ? String(symbol.dropFirst()) : symbol
        for suffix in Self.quoteSuffixes where bare.count > suffix.count && bare.hasSuffix(suffix) {
            return (String(bare.dropLast(suffix.count)), suffix)
        }
        return (nil, nil)
    }

    /// Faktoren aus dem Objekt `data` von `/public/products`; fehlende Felder bleiben weg (dann Standard).
    static func parse(_ data: JObject, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> PhemexScales {
        var result = PhemexScales()
        result.loaded = true
        result.loadedAt = now
        for currency in data.optArray("currencies")?.optObjects() ?? [] {
            let code = currency.optString("currency").uppercased()
            guard !code.isEmpty, currency.has("valueScale") else { continue }
            result.valueScale[code] = currency.optInt("valueScale", defaultScale)
        }
        for product in data.optArray("products")?.optObjects() ?? [] where product.optString("type") == "Spot" {
            let symbol = product.optString("symbol")
            if symbol.isEmpty { continue }
            if product.has("priceScale") { result.priceScale[symbol] = product.optInt("priceScale", defaultScale) }
            let base = product.optString("baseCurrency")
            let quote = product.optString("quoteCurrency")
            if !base.isEmpty && !quote.isEmpty { result.pairs[symbol] = (base.uppercased(), quote.uppercased()) }
        }
        return result
    }

    /// 10 hoch `scale`, begrenzt auf 0…18.
    private static func tenToThe(_ scale: Int) -> Double { pow(10, Double(min(max(scale, 0), 18))) }
}
