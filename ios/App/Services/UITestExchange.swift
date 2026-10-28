#if DEBUG
import Foundation
import ObjectiveC

/// Feste Börsen-Antworten für die UI-Tests — wie `FakeExchange`/`FakeExchangeInterceptor` der instrumentierten
/// Android-Tests (gleiche Coins, Kurse und JSON-Formen): Binance (`exchangeInfo`, 24-h-Ticker einzeln und
/// gesammelt) und Coinbase (`products`, `stats`); jede andere Adresse antwortet 404 (die App zeigt «—»).
/// Mit `marketData` (nur die Bildschirmfoto-Tour) dazu der Binance-Spiegel und die Quellen des Markt-Tabs
/// (`UITestExchange+Market.swift`, `UITestExchange+Series.swift`) — wie `FakeExchange.binanceMirror`/`marketData`.
/// Kein Abruf verlässt das Gerät. Nur im Debug-Build, eingeschaltet von `UITesting.prepareIfRequested`.
enum UITestExchange {
    /// BTC und die übrigen Start-Coins mit festen Kursen (Reihenfolge wie Android `FakeExchange.coins`).
    static let prices: [(coin: String, price: Double)] = [
        ("BTC", 95_000.0), ("ETH", 3_500.0), ("XRP", 2.5), ("BNB", 900.0), ("SOL", 200.0),
    ]

    /// Umgebungsvariable, mit der die Bildschirmfoto-Tour die Markt-Antworten einschaltet (gleicher Text in
    /// `ScreenshotTourTests.marketDataEnvironment`; die UI-Tests sehen diesen Typ nicht).
    static let marketDataEnvironment = "UITEST_MARKET_DATA"

    /// Auch Binance-Spiegel (`data-api.binance.vision`: Portfolio-Kurse, 24-h-Ticker, Kerzen), CoinGecko, Fear & Greed,
    /// Coin Metrics, Funding, Gebühren, die Binance-Symbolliste (Namen), USDT-USD bei Coinbase und dessen Tageskerzen
    /// bei Kraken beantworten. Nur mit `marketDataEnvironment` = 1 (Bildschirmfoto-Tour), damit die übrigen UI-Tests
    /// unverändert laufen; gilt ab dem Start, also auch für die Abrufe gleich beim Start (z. B. die Symbolliste, danach
    /// erst wieder in einer Stunde).
    static let marketData = ProcessInfo.processInfo.environment[marketDataEnvironment] == "1"

    /// Kurs; mit `marketData` auch für die übrigen Coins der Markt-Tour (und USDT/USD für Coinbase).
    static func price(_ coin: String) -> Double? {
        if let fixed = prices.first(where: { $0.coin == coin }) { return fixed.price }
        return marketData ? tourCoin(coin)?.price : nil
    }

    /// 24-h-Veränderung in %: sonst überall 1.25, mit `marketData` die Werte der Markt-Tour (Merkliste = Markt-Tab).
    static func change(_ coin: String) -> Double {
        (marketData ? tourCoin(coin)?.change24h : nil) ?? 1.25
    }

    /// Coins der Börsen; mit `marketData` dazu die Coins der Markt-Tour.
    static var exchangeCoins: [String] {
        let start = prices.map(\.coin)
        guard marketData else { return start }
        return start + tradableTourCoins.filter { !start.contains($0) }
    }

    /// Antwort auf eine Adresse; nil = 404.
    static func answer(_ url: URL) -> String? {
        let path = url.path
        switch url.host?.lowercased() {
        case "api.binance.com":
            switch path {
            case "/api/v3/exchangeInfo": return binanceExchangeInfo()
            case "/api/v3/ticker/24hr": return binanceTickers(url)
            default: return nil
            }
        case "api.exchange.coinbase.com":
            if path == "/products" { return coinbaseProducts() }
            let parts = path.split(separator: "/")
            if parts.count == 3, parts[0] == "products", parts[2] == "stats" { return coinbaseStats(String(parts[1])) }
            return nil
        // Binance-Spiegel nur mit `marketData`: Paarliste und Kurse (Portfolio), 24-h-Ticker (Pulse, «Heute
        // auffällig»), Kerzen (Markt-Tab)
        case "data-api.binance.vision" where marketData:
            switch path {
            case "/api/v3/exchangeInfo": return binanceExchangeInfo()
            case "/api/v3/ticker/price": return binancePrices(url)
            case "/api/v3/ticker/24hr": return binanceTickers(url)
            case "/api/v3/klines": return klines(url)
            default: return nil
            }
        default:
            return marketData ? marketAnswer(url) : nil
        }
    }

    /// Ab jetzt beantwortet `UITestExchangeProtocol` alle Abrufe: `URLSession.shared` über die Registrierung,
    /// alle eigenen Sitzungen über ihre Konfiguration (`default`/`ephemeral` liefern das Protokoll mit).
    static func install() {
        _ = URLProtocol.registerClass(UITestExchangeProtocol.self)
        exchange(NSSelectorFromString("defaultSessionConfiguration"),
                 #selector(URLSessionConfiguration.uiTestDefaultConfiguration))
        exchange(NSSelectorFromString("ephemeralSessionConfiguration"),
                 #selector(URLSessionConfiguration.uiTestEphemeralConfiguration))
    }

    private static func exchange(_ original: Selector, _ replacement: Selector) {
        let type: AnyClass = URLSessionConfiguration.self
        guard let a = class_getClassMethod(type, original), let b = class_getClassMethod(type, replacement) else {
            assertionFailure("UITestMarket: \(original) not found")
            return
        }
        method_exchangeImplementations(a, b)
    }

    // MARK: Binance

    private static func binanceExchangeInfo() -> String {
        let rows = exchangeCoins.map {
            #"{"symbol":"\#($0)USDT","status":"TRADING","baseAsset":"\#($0)","quoteAsset":"USDT"}"#
        }
        return #"{"symbols":["# + rows.joined(separator: ",") + "]}"
    }

    private static func binanceTicker(_ base: String) -> String? {
        guard let price = price(base) else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Markt-Tour: Umsatz nach Marktkapitalisierung (für «Heute auffällig») und Zahl der Abschlüsse (lebendes Paar)
        let tour = marketData ? tourCoin(base) : nil
        let quoteVolume = tour.map { $0.marketCap * turnover($0) } ?? price * 1000
        let volume = tour == nil ? 1000.0 : quoteVolume / price
        let extra = tour == nil ? "" : #","openTime":\#(now - dayMillis),"count":250000"#
        return #"{"symbol":"\#(base)USDT","bidPrice":"\#(price)","askPrice":"\#(price)","volume":"\#(volume)","#
            + #""quoteVolume":"\#(quoteVolume)","highPrice":"\#(price * 1.02)","lowPrice":"\#(price * 0.98)","#
            + #""lastPrice":"\#(price)","closeTime":\#(now),"priceChangePercent":"\#(change(base))"\#(extra)}"#
    }

    private static func binanceTickers(_ url: URL) -> String? {
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let symbol = query.first(where: { $0.name == "symbol" })?.value {
            return binanceTicker(String(symbol.dropLast(symbol.hasSuffix("USDT") ? 4 : 0)))
        }
        let wanted: [String]
        if let list = query.first(where: { $0.name == "symbols" })?.value {
            // «["BTCUSDT","ETHUSDT"]» → BTC, ETH
            wanted = list.split(separator: "\"").map(String.init).filter { $0.hasSuffix("USDT") }.map { String($0.dropLast(4)) }
        } else {
            wanted = exchangeCoins
        }
        return "[" + wanted.compactMap(binanceTicker).joined(separator: ",") + "]"
    }

    /// Sammelkurse des Spiegels (`ticker/price`, nur mit `marketData`): nur Symbol und Kurs; ohne Auswahl alle.
    private static func binancePrices(_ url: URL) -> String {
        let wanted = queryValue(url, "symbols").map { list in
            list.split(separator: "\"").map(String.init).filter { $0.hasSuffix("USDT") }.map { String($0.dropLast(4)) }
        } ?? exchangeCoins
        let rows = wanted.compactMap { coin in price(coin).map { #"{"symbol":"\#(coin)USDT","price":"\#($0)"}"# } }
        return "[" + rows.joined(separator: ",") + "]"
    }

    // MARK: Coinbase

    private static func coinbaseProducts() -> String {
        "[" + exchangeCoins.map {
            #"{"id":"\#($0)-USD","base_currency":"\#($0)","quote_currency":"USD","status":"online"}"#
        }.joined(separator: ",") + "]"
    }

    /// `open` = Kurs vor 24 h (+1.25 %); mit `marketData` passend zu `change`, dazu USDT-USD (Messung USDT/USD für das
    /// Portfolio, `DepegAlarmCheck`).
    private static func coinbaseStats(_ productId: String) -> String? {
        guard let base = productId.split(separator: "-").first, let price = price(String(base)) else { return nil }
        let open = marketData ? price / (1.0 + change(String(base)) / 100.0) : price / 1.0125
        return #"{"open":"\#(open)","high":"\#(price * 1.02)","low":"\#(price * 0.98)","#
            + #""last":"\#(price)","volume":"1000.0"}"#
    }
}

/// Beantwortet jeden HTTP(S)-Abruf aus `UITestExchange` (sonst 404); WebSockets schlagen sofort fehl.
final class UITestExchangeProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        guard let scheme = request.url?.scheme?.lowercased() else { return false }
        return ["http", "https", "ws", "wss"].contains(scheme)
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let scheme = url.scheme?.lowercased(), scheme.hasPrefix("http") else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let body = UITestExchange.answer(url)
        guard let response = HTTPURLResponse(url: url, statusCode: body == nil ? 404 : 200, httpVersion: "HTTP/1.1",
                                             headerFields: ["Content-Type": "application/json"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((body ?? #"{"message":"not found"}"#).utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

extension URLSessionConfiguration {
    /// Nach dem Tausch in `UITestExchange.install` ruft der eigene Name die ursprüngliche Implementierung auf.
    @objc dynamic class func uiTestDefaultConfiguration() -> URLSessionConfiguration {
        withTestProtocol(uiTestDefaultConfiguration())
    }

    @objc dynamic class func uiTestEphemeralConfiguration() -> URLSessionConfiguration {
        withTestProtocol(uiTestEphemeralConfiguration())
    }

    private static func withTestProtocol(_ configuration: URLSessionConfiguration) -> URLSessionConfiguration {
        configuration.protocolClasses = [UITestExchangeProtocol.self] + (configuration.protocolClasses ?? [])
        return configuration
    }
}
#endif
