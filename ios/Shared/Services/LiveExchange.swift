import Foundation

// Live-Kurse per WebSocket (Teil von `LiveFeed.swift`): Börsen mit Adressen, Symbolen und Abo-Nachrichten sowie die
// Parser der Kurs-Nachrichten — wie `LiveExchange`/`LiveParser` in `LiveFeed.kt` (Android).

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
            // Alpha-Paare («ALPHA_175USDT») haben keinen Spot-Datenstrom: Kurse per Abfrage
            guard let id, !BinanceAlpha.isPairId(id) else { return nil }
            return id.uppercased()
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
        // Wahrheitswerte ausschliessen – aber per CFBoolean: `value is Bool` trifft auf Apple-Plattformen
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
        // Wie Kotlins toLong(): zu grosse Werte auf Int64.max begrenzen statt abzustürzen
        return JSONValue.int64(d)
    }

    /// Wie `Change24h.fromOpen`: (letzter − Eröffnung) / Eröffnung in %.
    private static func fromOpen(_ last: Double?, _ open: Double?) -> Double? {
        guard let last, let open, last > 0, open > 0 else { return nil }
        return (last - open) / open * 100
    }
}
