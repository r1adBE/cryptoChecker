import Foundation

// Live-Kurse per WebSocket (Runde 31): reine Logik — Börsen, Abo-Nachrichten, Parser, Planung
// der Verbindungen, Puffer und Regeln. Wie `LiveFeed.kt`; gemeinsame Testfälle in
// `Tests/Parity/live_feed.json`. Die Verbindungen hält `App/Services/LivePriceStream.swift`
// (nur App, nie Widgets). Liegt in Shared, weil `PriceRefresher` die Regeln braucht.

/// Ein Kurs aus dem Datenstrom. `price` / `change24h` nil = in dieser Nachricht nicht enthalten
/// (Bybit-Futures schicken nach dem ersten Stand nur geänderte Felder). `change24h` in Prozent,
/// nur gleitende 24 h. `time` in ms seit 1970; nil = Empfangszeit nehmen.
struct LiveTick: Equatable, Sendable {
    var symbol: String
    var price: Double?
    var change24h: Double?
    var time: Int64?
}

/// Letzter bekannter Live-Stand eines Paars (Watch-Id).
struct LiveQuote: Equatable, Sendable {
    var price: Double
    var change24h: Double?
    var time: Int64
}

/// Paar der sichtbaren Merkliste, soweit der Datenstrom es braucht.
struct LivePair: Equatable, Hashable, Sendable {
    var watchId: Int64
    var marketKey: String
    var marketName: String
    var pairId: String?
    var base: String
    var quote: String
    /// «NONE», «PERPETUAL», … (wie der Name von `FuturesContractType` in Kotlin).
    var contract: String
    /// Wird nicht mehr gehandelt: kein Abo.
    var notTraded = false
}

/// Börsen mit öffentlichem WebSocket-Ticker. `marketKey` = Schlüssel des REST-Adapters,
/// `rollingChange` = der Strom liefert dieselbe gleitende 24-h-Veränderung wie REST (Kraken nicht).
enum LiveExchange: String, CaseIterable, Sendable {
    case BINANCE, BINANCE_FUTURES, BYBIT, BYBIT_FUTURES, OKX, OKX_SWAP, COINBASE, KRAKEN

    var marketKey: String {
        switch self {
        case .BINANCE: return "Binance"
        case .BINANCE_FUTURES: return "BinanceFutures"
        case .BYBIT: return "Bybit"
        case .BYBIT_FUTURES: return "BybitFutures"
        case .OKX: return "Okex"
        case .OKX_SWAP: return "OkexFutures"
        case .COINBASE: return "Coinbase"
        case .KRAKEN: return "Kraken"
        }
    }

    var url: String {
        switch self {
        case .BINANCE: return "wss://stream.binance.com:9443/ws"
        case .BINANCE_FUTURES: return "wss://fstream.binance.com/ws"
        case .BYBIT: return "wss://stream.bybit.com/v5/public/spot"
        case .BYBIT_FUTURES: return "wss://stream.bybit.com/v5/public/linear"
        case .OKX, .OKX_SWAP: return "wss://ws.okx.com:8443/ws/v5/public"
        case .COINBASE: return "wss://ws-feed.exchange.coinbase.com"
        case .KRAKEN: return "wss://ws.kraken.com/v2"
        }
    }

    /// Höchstens so viele Symbole je Verbindung; weitere bekommen eine eigene.
    var maxSymbolsPerConnection: Int { 200 }

    /// Höchstens so viele Symbole je Abo-Nachricht (Bybit Spot: 10).
    var maxSymbolsPerMessage: Int {
        switch self {
        case .BYBIT, .BYBIT_FUTURES: return 10
        case .OKX, .OKX_SWAP: return 50
        default: return 200
        }
    }

    /// Ping als Textnachricht (Protokoll der Börse); nil = nur WebSocket-Ping-Frames.
    var pingText: String? {
        switch self {
        case .BYBIT, .BYBIT_FUTURES: return "{\"op\":\"ping\"}"
        case .OKX, .OKX_SWAP: return "ping"
        case .KRAKEN: return "{\"method\":\"ping\"}"
        default: return nil
        }
    }

    var pingIntervalMillis: Int64 {
        switch self {
        case .BYBIT, .BYBIT_FUTURES: return 20_000
        case .OKX, .OKX_SWAP: return 25_000
        case .KRAKEN: return 30_000
        default: return 0
        }
    }

    var rollingChange: Bool { self != .KRAKEN }

    static func from(marketKey: String) -> LiveExchange? {
        allCases.first { $0.marketKey == marketKey }
    }

    /// Symbol im Datenstrom für ein Paar; nil = dieses Paar nicht live (z. B. Laufzeit-Futures).
    func symbol(for pair: LivePair) -> String? {
        let trimmed = pair.pairId?.trimmingCharacters(in: .whitespaces) ?? ""
        let id: String? = trimmed.isEmpty ? nil : trimmed
        let perpetual = pair.contract == "PERPETUAL"
        switch self {
        case .BINANCE:
            return id?.uppercased()
        case .BINANCE_FUTURES:
            // Nur USDⓈ-M-Perpetuals; COIN-M («2:…») läuft über einen anderen Server
            guard let id, perpetual, !id.hasPrefix("2:") else { return nil }
            return id.uppercased()
        case .BYBIT:
            return id
        case .BYBIT_FUTURES:
            return perpetual ? id : nil
        case .OKX:
            return id ?? "\(pair.base)-\(pair.quote)".uppercased()
        case .OKX_SWAP:
            guard let id, id.hasSuffix("-SWAP") else { return nil }
            return id
        case .COINBASE:
            return id ?? "\(pair.base)-\(pair.quote)".uppercased()
        case .KRAKEN:
            // v2 nennt Paare «BTC/USD» (nicht die REST-Kennung XXBTZUSD)
            let base = pair.base.trimmingCharacters(in: .whitespaces).uppercased()
            let quote = pair.quote.trimmingCharacters(in: .whitespaces).uppercased()
            guard !base.isEmpty, !quote.isEmpty else { return nil }
            return "\(base)/\(quote)"
        }
    }

    /// Abo-Nachrichten für `symbols`, je höchstens `maxSymbolsPerMessage`.
    func subscribeMessages(_ symbols: [String]) -> [String] {
        var messages: [String] = []
        var start = 0
        var index = 0
        while start < symbols.count {
            let chunk = Array(symbols[start..<min(start + maxSymbolsPerMessage, symbols.count)])
            index += 1
            start += maxSymbolsPerMessage
            switch self {
            case .BINANCE, .BINANCE_FUTURES:
                let params = chunk.map { LiveExchange.quoted($0.lowercased() + "@ticker") }.joined(separator: ",")
                messages.append("{\"method\":\"SUBSCRIBE\",\"params\":[\(params)],\"id\":\(index)}")
            case .BYBIT, .BYBIT_FUTURES:
                let args = chunk.map { LiveExchange.quoted("tickers.\($0)") }.joined(separator: ",")
                messages.append("{\"op\":\"subscribe\",\"args\":[\(args)]}")
            case .OKX, .OKX_SWAP:
                let args = chunk.map { "{\"channel\":\"tickers\",\"instId\":\(LiveExchange.quoted($0))}" }.joined(separator: ",")
                messages.append("{\"op\":\"subscribe\",\"args\":[\(args)]}")
            case .COINBASE:
                let ids = chunk.map { LiveExchange.quoted($0) }.joined(separator: ",")
                messages.append("{\"type\":\"subscribe\",\"product_ids\":[\(ids)],\"channels\":[\"ticker\"]}")
            case .KRAKEN:
                let ids = chunk.map { LiveExchange.quoted($0) }.joined(separator: ",")
                messages.append("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ticker\",\"symbol\":[\(ids)]}}")
            }
        }
        return messages
    }

    private static func quoted(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            default: out += scalar.value < 0x20 ? " " : String(scalar)
            }
        }
        return out + "\""
    }
}

/// Liest Kurs-Nachrichten der Börsen — wie `LiveParser` (Kotlin). Bestätigungen, Pongs,
/// Herzschläge und Unbekanntes ergeben eine leere Liste.
enum LiveParser {

    static func parse(_ exchange: LiveExchange, _ text: String) -> [LiveTick] {
        // OKX antwortet auf «ping» mit dem Text «pong» (kein JSON)
        guard let first = text.first, first == "{" || first == "[",
              let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
        switch exchange {
        case .BINANCE, .BINANCE_FUTURES: return binance(root)
        case .BYBIT, .BYBIT_FUTURES: return bybit(root)
        case .OKX, .OKX_SWAP: return okx(root)
        case .COINBASE: return coinbase(root)
        case .KRAKEN: return kraken(root)
        }
    }

    /// 24hrTicker: «s» Symbol, «c» letzter Kurs, «P» Veränderung in % (gleitend), «E» Ereigniszeit.
    private static func binance(_ root: Any) -> [LiveTick] {
        guard let obj = root as? [String: Any] else { return [] }
        let data = obj["data"] as? [String: Any] ?? obj
        guard data["e"] as? String == "24hrTicker",
              let symbol = data["s"] as? String, !symbol.isEmpty else { return [] }
        return [LiveTick(symbol: symbol, price: positive(data["c"]), change24h: finite(data["P"]), time: millis(data["E"]))]
    }

    /// tickers.SYMBOL: «lastPrice», «price24hPcnt» (Bruchteil), «ts» oben.
    private static func bybit(_ root: Any) -> [LiveTick] {
        guard let obj = root as? [String: Any],
              let topic = obj["topic"] as? String, topic.hasPrefix("tickers."),
              let data = obj["data"] as? [String: Any] else { return [] }
        let named = data["symbol"] as? String ?? ""
        let symbol = named.isEmpty ? String(topic.dropFirst("tickers.".count)) : named
        let change = finite(data["price24hPcnt"]).map { $0 * 100 }
        return [LiveTick(symbol: symbol, price: positive(data["lastPrice"]), change24h: change, time: millis(obj["ts"]))]
    }

    /// tickers: je Eintrag «instId», «last», «open24h» (Kurs vor 24 h), «ts».
    private static func okx(_ root: Any) -> [LiveTick] {
        guard let obj = root as? [String: Any],
              let arg = obj["arg"] as? [String: Any], arg["channel"] as? String == "tickers",
              obj["event"] == nil,
              let data = obj["data"] as? [Any] else { return [] }
        return data.compactMap { raw in
            guard let item = raw as? [String: Any],
                  let symbol = item["instId"] as? String, !symbol.isEmpty else { return nil }
            let last = positive(item["last"])
            return LiveTick(symbol: symbol, price: last, change24h: fromOpen(last, positive(item["open24h"])),
                            time: millis(item["ts"]))
        }
    }

    /// ticker: «product_id», «price», «open_24h» (Kurs vor 24 h); Zeit = Empfang.
    private static func coinbase(_ root: Any) -> [LiveTick] {
        guard let obj = root as? [String: Any], obj["type"] as? String == "ticker",
              let symbol = obj["product_id"] as? String, !symbol.isEmpty else { return [] }
        let last = positive(obj["price"])
        return [LiveTick(symbol: symbol, price: last, change24h: fromOpen(last, positive(obj["open_24h"])), time: nil)]
    }

    /// v2 ticker: je Eintrag «symbol», «last»; «change_pct» bleibt weg (wie REST: Kerzen).
    private static func kraken(_ root: Any) -> [LiveTick] {
        guard let obj = root as? [String: Any], obj["channel"] as? String == "ticker",
              let data = obj["data"] as? [Any] else { return [] }
        return data.compactMap { raw in
            guard let item = raw as? [String: Any],
                  let symbol = item["symbol"] as? String, !symbol.isEmpty else { return nil }
            return LiveTick(symbol: symbol, price: positive(item["last"]), change24h: nil, time: nil)
        }
    }

    /// Zahl oder Zahl als Text; NaN/∞ → nil.
    static func finite(_ value: Any?) -> Double? {
        let d: Double?
        // Wahrheitswerte ausschließen – aber per CFBoolean: `value is Bool` trifft auf Apple-Plattformen
        // auch die Zahl 1/0 aus JSONSerialization (Kurs genau 1 ginge sonst verloren)
        if let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() {
            d = n.doubleValue
        } else if let s = value as? String {
            d = Double(s.trimmingCharacters(in: .whitespaces))
        } else {
            d = nil
        }
        guard let d, d.isFinite else { return nil }
        return d
    }

    private static func positive(_ value: Any?) -> Double? {
        guard let d = finite(value), d > 0 else { return nil }
        return d
    }

    private static func millis(_ value: Any?) -> Int64? {
        guard let d = finite(value), d >= 1 else { return nil }
        // Wie Kotlins toLong(): zu große Werte auf Int64.max begrenzen statt abzustürzen
        return JSONValue.int64(d)
    }

    /// Wie `Change24h.fromOpen`: (letzter − Eröffnung) / Eröffnung in %.
    private static func fromOpen(_ last: Double?, _ open: Double?) -> Double? {
        guard let last, let open, last > 0, open > 0 else { return nil }
        return (last - open) / open * 100
    }
}

/// Eine Verbindung: Börse, Nummer, Symbole (sortiert).
struct LiveConnectionSpec: Equatable, Sendable {
    var exchange: LiveExchange
    var index: Int
    var symbols: [String]

    var key: String { "\(exchange.rawValue)#\(index)" }
}

/// Ergebnis von `LivePlanner.plan`.
struct LivePlan: Equatable, Sendable {
    var connections: [LiveConnectionSpec]
    /// Je Börse: Symbol → Watch-Ids.
    var watchIds: [LiveExchange: [String: [Int64]]]

    var isEmpty: Bool { connections.isEmpty }
}

enum LivePlanner {
    /// Mehr Verbindungen je Börse öffnet die App nicht; die übrigen Paare bleiben bei REST.
    static let maxConnectionsPerExchange = 3

    /// Welche Verbindungen für die sichtbaren Paare? Ohne Börsen ohne WebSocket, nicht mehr
    /// gehandelte Paare und pausierte Börsen (`ExchangeBackoff`).
    static func plan(_ pairs: [LivePair], pausedMarketKeys: Set<String> = []) -> LivePlan {
        var ids: [LiveExchange: [String: [Int64]]] = [:]
        for pair in pairs {
            guard !pair.notTraded, !pausedMarketKeys.contains(pair.marketKey),
                  let exchange = LiveExchange.from(marketKey: pair.marketKey),
                  let symbol = exchange.symbol(for: pair) else { continue }
            ids[exchange, default: [:]][symbol, default: []].append(pair.watchId)
        }
        var connections: [LiveConnectionSpec] = []
        var kept: [LiveExchange: [String: [Int64]]] = [:]
        for exchange in LiveExchange.allCases {
            guard let bySymbol = ids[exchange] else { continue }
            let sorted = bySymbol.keys.sorted()
            var subscribed = Set<String>()
            var index = 0
            var start = 0
            while start < sorted.count && index < maxConnectionsPerExchange {
                let chunk = Array(sorted[start..<min(start + exchange.maxSymbolsPerConnection, sorted.count)])
                connections.append(LiveConnectionSpec(exchange: exchange, index: index, symbols: chunk))
                subscribed.formUnion(chunk)
                index += 1
                start += exchange.maxSymbolsPerConnection
            }
            kept[exchange] = bySymbol.filter { subscribed.contains($0.key) }
        }
        return LivePlan(connections: connections, watchIds: kept)
    }
}

/// Neuer Verbindungsversuch nach einem Abbruch: 1, 2, 4 … höchstens 60 s; abgewiesen (429/418) 5 min.
enum LiveBackoff {
    static let maxMillis: Int64 = 60_000
    static let rateLimitedMillis: Int64 = 300_000

    static func delayMillis(attempt: Int, rateLimited: Bool) -> Int64 {
        if rateLimited { return rateLimitedMillis }
        let step = min(max(attempt, 0), 6)
        return min(maxMillis, Int64(1_000) << Int64(step))
    }
}

/// Regeln — wie `LiveRules` (Kotlin).
enum LiveRules {
    static let uiIntervalMillis: Int64 = 500
    static let dbIntervalMillis: Int64 = 10_000
    static let widgetIntervalMillis: Int64 = 60_000
    /// So lange gilt ein Paar nach dem letzten Kurs als live; danach wieder REST.
    static let freshMillis: Int64 = 30_000
    /// Kein Lebenszeichen der Verbindung so lange (Börsen mit Text-Ping): neu verbinden.
    static let staleConnectionMillis: Int64 = 60_000

    /// Veränderung, die mit dem Live-Kurs gilt: die des Stroms nur bei Basis «Letzte 24 Std.»,
    /// passendem Stempel und Börse mit gleitendem Wert — sonst bleibt die bisherige.
    static func chooseChange(rollingBasis: Bool, stampCurrent: Bool, exchangeRolling: Bool,
                             live: Double?, existing: Double?) -> Double? {
        if rollingBasis && stampCurrent && exchangeRolling, let live, live.isFinite { return live }
        return existing
    }

    static func isFresh(lastTickAt: Int64?, now: Int64) -> Bool {
        guard let lastTickAt, lastTickAt > 0 else { return false }
        let age = now - lastTickAt
        return age >= 0 && age <= freshMillis
    }

    /// Darf die REST-Abfrage dieses Paar auslassen?
    static func skipRest(lastTickAt: Int64?, now: Int64, rollingBasis: Bool, stampUnchanged: Bool,
                         exchangeRolling: Bool) -> Bool {
        rollingBasis && stampUnchanged && exchangeRolling && isFresh(lastTickAt: lastTickAt, now: now)
    }
}

/// Puffer der Live-Kurse je Watch-Id — wie `LiveBuffer` (Kotlin).
struct LiveBuffer: Sendable {
    private var latest: [Int64: LiveQuote] = [:]
    private var unsaved: Set<Int64> = []
    private var uiDirty = false

    /// Kurs übernehmen; fehlende Felder (Delta) vom letzten Stand. false = nichts geändert.
    @discardableResult
    mutating func offer(_ watchIds: [Int64], _ tick: LiveTick, now: Int64) -> Bool {
        var changed = false
        for id in watchIds {
            let previous = latest[id]
            guard let price = tick.price ?? previous?.price else { continue }
            let time: Int64
            if let t = tick.time, t > 0 { time = t } else { time = now }
            let quote = LiveQuote(price: price, change24h: tick.change24h ?? previous?.change24h, time: time)
            if quote == previous { continue }
            latest[id] = quote
            if previous == nil || previous?.price != quote.price || previous?.change24h != quote.change24h {
                unsaved.insert(id)
            }
            uiDirty = true
            changed = true
        }
        return changed
    }

    /// Stand für die Anzeige, falls sich seit dem letzten Aufruf etwas geändert hat; sonst nil.
    mutating func takeForUi() -> [Int64: LiveQuote]? {
        guard uiDirty else { return nil }
        uiDirty = false
        return latest
    }

    /// Ungespeicherte Kurse; danach gelten sie als gespeichert.
    mutating func takeForDb() -> [Int64: LiveQuote] {
        guard !unsaved.isEmpty else { return [:] }
        var batch: [Int64: LiveQuote] = [:]
        for id in unsaved { if let q = latest[id] { batch[id] = q } }
        unsaved.removeAll()
        return batch
    }

    /// Speichern ging nicht: beim nächsten Mal nochmals.
    mutating func markUnsaved<C: Sequence>(_ ids: C) where C.Element == Int64 {
        for id in ids where latest[id] != nil { unsaved.insert(id) }
    }

    /// Nur noch diese Paare behalten (andere Gruppe gewählt).
    mutating func retain(_ ids: Set<Int64>) {
        let before = latest.count
        latest = latest.filter { ids.contains($0.key) }
        if latest.count != before { uiDirty = true }
        unsaved = unsaved.intersection(ids)
    }

    mutating func clear() {
        if !latest.isEmpty { uiDirty = true }
        latest.removeAll()
        unsaved.removeAll()
    }

    var count: Int { latest.count }
}

extension Watch {
    /// Was der Live-Strom von einem Paar braucht.
    var livePair: LivePair {
        LivePair(watchId: id, marketKey: marketKey, marketName: marketName, pairId: pairId,
                 base: baseAsset, quote: quoteAsset,
                 contract: contractType == .perpetual ? "PERPETUAL" : String(describing: contractType).uppercased(),
                 notTraded: isNotTraded)
    }

    /// Live-Kurs darübergelegt (nur Anzeige) — wie `LiveOverlay` (Kotlin).
    func withLive(_ quote: LiveQuote?, rollingBasis: Bool) -> Watch {
        guard let quote, !isNotTraded else { return self }
        var copy = self
        // «Seit letzter Aktualisierung»: Live-Kurs gegen den zuletzt gespeicherten
        if let stored = lastPrice, stored != quote.price { copy.previousPrice = stored }
        copy.lastPrice = quote.price
        copy.lastUpdate = max(lastUpdate, quote.time)
        copy.lastError = nil
        copy.change24h = LiveRules.chooseChange(
            rollingBasis: rollingBasis, stampCurrent: true,
            exchangeRolling: LiveExchange.from(marketKey: marketKey)?.rollingChange == true,
            live: quote.change24h, existing: change24h)
        return copy
    }
}
