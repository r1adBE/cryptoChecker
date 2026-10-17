import Foundation

/// Binance Alpha: frühe Token, die Binance nicht regulär am Spot führt. Öffentliche Endpunkte ohne
/// Schlüssel auf www.binance.com (developers.binance.com/docs/alpha, «Market Data»: Token List,
/// Ticker, Klines; Stand Oktober 2026). Die App führt sie als Paare «SYMBOL/USDT» der Börse «Binance»
/// mit der Kennung «ALPHA_<id>USDT» (`pairId`, wie das `symbol` der Alpha-Endpunkte) — Kurse aus der
/// Token-Liste (eine Anfrage für alle, `readListRow`; bis `maxSingleTickers` Paare je Paar der Ticker,
/// `bulkRequestURLs`), Einzelkurs vom Ticker (`readTicker`), Kerzen
/// von den Klines (`klinesURL`). Wie `BinanceAlpha.kt`.
enum BinanceAlpha {
    /// Ganze Token-Liste (auch Kurs, 24-h-Veränderung und -Umsatz je Token).
    static let tokenListURL =
        "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"

    /// 24-h-Ticker eines Paars, Kennung anhängen («…?symbol=ALPHA_175USDT»).
    static let tickerURL = "https://www.binance.com/bapi/defi/v1/public/alpha-trade/ticker?symbol="

    /// Kerzen (`symbol`, `interval`, `limit`).
    static let klinesBaseURL = "https://www.binance.com/bapi/defi/v1/public/alpha-trade/klines"

    /// Börse, unter der die Alpha-Paare stehen (`Market.key`).
    static let marketKey = "Binance"

    /// Herkunft der Kerzen («Chart: Binance Alpha»).
    static let provider = "Binance Alpha"

    /// Gegenwährung der Alpha-Paare.
    static let quote = "USDT"

    /// Bis so viele beobachtete Alpha-Paare: je Paar ein Ticker (rund 0,4 KB) statt der ganzen
    /// Token-Liste (rund 1 KB je Token, alle Token); darüber die Liste (`bulkRequestURLs`).
    static let maxSingleTickers = 10

    /// So lange teilen alle Aufrufer (Merkliste, Widgets, Alarme) eine geladene Token-Liste (`listCache`).
    static let listCacheMillis: Int64 = 60_000

    /// Erfolgs-Code der Alpha-Antworten (`{"code":"000000","data":…}`).
    private static let okCode = "000000"

    /// Token der Alpha-Liste für die Paarliste: Kürzel, Name (für «gleicher Token»), Kennung `alphaId`
    /// («ALPHA_175»; nil = unbekannt, z. B. aus der Ersatzdatei — dann kein Paar).
    struct Token: Codable, Equatable, Sendable {
        var symbol: String
        var name: String?
        var alphaId: String?
    }

    /// «ALPHA_» und 1–9 Ziffern, danach (bei `suffix`) 2–10 Grossbuchstaben A–Z.
    private static func matches(_ s: String, suffix: Bool) -> Bool {
        guard s.hasPrefix("ALPHA_") else { return false }
        let digits: ClosedRange<Unicode.Scalar> = "0"..."9"
        let letters: ClosedRange<Unicode.Scalar> = "A"..."Z"
        let rest: [Unicode.Scalar] = Array(s.unicodeScalars.dropFirst(6))
        var i = 0
        while i < rest.count && i < 9 && digits.contains(rest[i]) { i += 1 }
        if i == 0 { return false }
        let tail: ArraySlice<Unicode.Scalar> = rest[i...]
        if !suffix { return tail.isEmpty }
        return tail.count >= 2 && tail.count <= 10 && tail.allSatisfy { letters.contains($0) }
    }

    /// Kennung eines Alpha-Paars («ALPHA_175USDT»)?
    static func isPairId(_ id: String?) -> Bool {
        guard let id else { return false }
        return matches(id, suffix: true)
    }

    /// Alpha-Paar unter der Börse Binance (`marketKey`)?
    static func isAlphaPair(marketKey: String, pairId: String?) -> Bool {
        marketKey == Self.marketKey && isPairId(pairId)
    }

    /// Gültige `alphaId` («ALPHA_175»), gross; sonst nil.
    static func alphaId(_ raw: String?) -> String? {
        guard let s = raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              matches(s, suffix: false) else { return nil }
        return s
    }

    /// Kennung des Paars zu `alphaId` («ALPHA_175» → «ALPHA_175USDT»); ungültig → nil.
    static func pairId(alphaId raw: String?, quote: String = BinanceAlpha.quote) -> String? {
        alphaId(raw).map { $0 + quote }
    }

    /// Kürzel wie in der Paarliste: ohne Leer- und unsichtbare Formatzeichen, gross.
    static func cleanSymbol(_ raw: String?) -> String {
        CoinLogos.cleanSymbol(raw).uppercased()
    }

    /// Paare «SYMBOL/USDT» der `tokens` (schon gefiltert: handelbar und nicht regulär gelistet,
    /// Reihenfolge = Vorrang), je `alphaId` eins. Spot-Paare (`spotPairs`) bleiben normale Spot-Paare.
    /// Ist das Kürzel schon vergeben (Spot oder ein früheres Alpha-Token), ist es ein anderer Token
    /// gleichen Kürzels (sonst wäre er `listingCex`): er fällt nicht weg, sondern heisst «SYMBOL_<Nummer>»
    /// (`distinctBase`, Nummer der `alphaId`) — eindeutig in Paarliste, Logo-/Namen-Schlüsseln und
    /// Ausweich-Kerzen, die sonst den anderen Token träfen. Kennung bleibt «ALPHA_<id>USDT». Wie Android.
    static func pairs(tokens: [Token], spotPairs: [MarketPair]) -> [MarketPair] {
        var taken = Set<String>()
        for p in spotPairs where !isPairId(p.pairId) { taken.insert(p.base.uppercased()) }
        var ids = Set<String>()
        var out: [MarketPair] = []
        for t in tokens {
            guard let id = pairId(alphaId: t.alphaId) else { continue }
            let symbol = cleanSymbol(t.symbol)
            if symbol.isEmpty || !ids.insert(id).inserted { continue }
            var base = symbol
            if !taken.insert(symbol).inserted {
                base = distinctBase(symbol: symbol, pairId: id)
                taken.insert(base)
            }
            out.append(MarketPair(base, quote, id))
        }
        return out
    }

    /// Kürzel eines zweiten Tokens gleichen Kürzels: «PEPE» + «ALPHA_500USDT» → «PEPE_500».
    static func distinctBase(symbol: String, pairId: String) -> String {
        let rest = pairId.hasPrefix("ALPHA_") ? String(pairId.dropFirst("ALPHA_".count)) : pairId
        return symbol + "_" + String(rest.prefix { $0 >= "0" && $0 <= "9" })
    }

    /// Alpha-Anfragen der Sammelabfrage von Binance für `pairIds`: an erster Stelle die Token-Liste
    /// (nur bei mehr als `maxSingleTickers` Alpha-Paaren, sonst nil), danach je Alpha-Paar ein
    /// Ticker (bis `maxSingleTickers`, sortiert, je Kennung einmal). Keine Alpha-Paare → leer.
    static func bulkRequestURLs(pairIds: [String]) -> [String?] {
        let ids: [String] = Array(Set(pairIds.filter { isPairId($0) })).sorted()
        if ids.isEmpty { return [] }
        if ids.count > maxSingleTickers { return [tokenListURL] }
        let tickers: [String?] = ids.map { tickerURL + $0 }
        return [nil] + tickers
    }

    /// Zuletzt geladene Token-Liste (Antworttext) für höchstens `listCacheMillis`: eine
    /// Aktualisierung lädt sie nie doppelt. Nur im Speicher.
    final class ListCache: @unchecked Sendable {
        private let maxAgeMillis: Int64
        private let lock = NSLock()
        private var body: String?
        private var at: Int64 = 0

        init(maxAgeMillis: Int64 = BinanceAlpha.listCacheMillis) { self.maxAgeMillis = maxAgeMillis }

        /// Gespeicherter Text, wenn jünger als `maxAgeMillis`; sonst nil.
        func get(now: Int64) -> String? {
            lock.lock(); defer { lock.unlock() }
            guard let body, now >= at, now - at < maxAgeMillis else { return nil }
            return body
        }

        func put(_ text: String, now: Int64) {
            lock.lock(); defer { lock.unlock() }
            body = text
            at = now
        }
    }

    /// Gemeinsamer Speicher der Token-Liste für die Kursabfragen.
    static let listCache = ListCache()

    /// Antwort des Tickers in der Sammelabfrage → Kennung (`data.symbol`) und Kurs in `ticker`. Anderer
    /// Code (Paar unbekannt, z. B. abgesetzt) → nil: das Paar fehlt einfach, wie ein Token ohne Kurs
    /// in der Liste. Ohne gültige Kennung oder Kurs: Fehler.
    static func readBulkTicker(_ json: String, _ ticker: inout Ticker) throws -> String? {
        let root = try JObject(string: json)
        let code = root.optString("code")
        if (!code.isEmpty && code != okCode) || root.isNull("data") { return nil }
        let d = try root.object("data")
        let id = d.optString("symbol").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard isPairId(id) else { throw JSONError(message: "Alpha ticker without symbol") }
        try readTicker(json, &ticker)
        return id
    }

    /// Wurzel einer Alpha-Antwort; anderer Code oder kein `data` → Fehler.
    private static func root(_ json: String) throws -> JObject {
        let root = try JObject(string: json)
        let code = root.optString("code")
        if !code.isEmpty && code != okCode { throw JSONError(message: "Alpha code \(code)") }
        if root.isNull("data") { throw JSONError(message: "Alpha without data") }
        return root
    }

    /// Zeilen der Token-Liste (`data`-Array).
    static func listRows(_ json: String) throws -> JArray {
        try root(json).array("data")
    }

    /// Kennung des Paars einer Zeile der Token-Liste (`alphaId` + USDT); nil = keine.
    static func listRowPairId(_ row: JObject) -> String? {
        pairId(alphaId: row.optString("alphaId"))
    }

    /// Kurs aus einer Zeile der Token-Liste; false = kein Kurs (`price` ≤ 0: nicht gehandelt). Felder:
    /// `price`, `percentChange24h` (schon in Prozent), `volume24h` (Umsatz in USD), `priceHigh24h` /
    /// `priceLow24h` (0 = unbekannt). Keine Handelszeit in der Liste: die Zeit der Börse bleibt
    /// unbekannt. Fehlt `price` (Format geändert): Fehler.
    static func readListRow(_ row: JObject, _ ticker: inout Ticker) throws -> Bool {
        let price = try row.double("price")
        guard price.isFinite, price > 0 else { return false }
        ticker.last = price
        if let high = above(row, "priceHigh24h", floor: 0, inclusive: false) { ticker.high = high }
        if let low = above(row, "priceLow24h", floor: 0, inclusive: false) { ticker.low = low }
        if let volume = above(row, "volume24h", floor: 0, inclusive: true) { ticker.volQuote = volume }
        ticker.change24hPercent = Change24h.percent(row.optDouble("percentChange24h"))
        return true
    }

    /// Antwort des Tickers (`tickerURL`) → Kurs (`data`: `lastPrice`, `priceChangePercent` in Prozent,
    /// `highPrice`, `lowPrice`, `volume`, `quoteVolume`). `closeTime` ist das Ende des 24-h-Fensters,
    /// kein Handel — die Zeit der Börse bleibt unbekannt (wie Binance Spot).
    static func readTicker(_ json: String, _ ticker: inout Ticker) throws {
        let d = try root(json).object("data")
        let last = try d.double("lastPrice")
        guard last.isFinite, last > 0 else { throw JSONError(message: "Alpha ticker without price") }
        ticker.last = last
        if let high = above(d, "highPrice", floor: 0, inclusive: false) { ticker.high = high }
        if let low = above(d, "lowPrice", floor: 0, inclusive: false) { ticker.low = low }
        if let volume = above(d, "volume", floor: 0, inclusive: true) { ticker.vol = volume }
        if let quoteVolume = above(d, "quoteVolume", floor: 0, inclusive: true) { ticker.volQuote = quoteVolume }
        ticker.change24hPercent = Change24h.percent(d.optDouble("priceChangePercent"))
    }

    /// Fehlertext einer Alpha-Antwort (`message`, sonst `messageDetail`); nil = keiner.
    static func errorText(_ json: JObject) -> String? {
        var text = json.optString("message")
        if text.isEmpty { text = json.optString("messageDetail") }
        return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
    }

    /// Kerzen-Adresse; `interval` wie bei Binance («1h», «4h», «1d»).
    static func klinesURL(pairId: String, interval: String, limit: Int) -> String {
        "\(klinesBaseURL)?symbol=\(pairId)&interval=\(interval)&limit=\(min(max(limit, 1), 1000))"
    }

    /// Kerzen der Antwort (`klinesURL`, `data` wie Binance-Klines: [openTime, open, high, low, close,
    /// volume, closeTime, quoteVolume, …]).
    static func klines(_ json: String) throws -> JArray {
        try root(json).array("data")
    }

    /// Endliche Zahl über `floor` (bei `inclusive` auch gleich), sonst nil.
    private static func above(_ source: JObject, _ field: String, floor: Double, inclusive: Bool) -> Double? {
        let value = source.optDouble(field)
        guard value.isFinite else { return nil }
        let fits = inclusive ? value >= floor : value > floor
        return fits ? value : nil
    }
}
