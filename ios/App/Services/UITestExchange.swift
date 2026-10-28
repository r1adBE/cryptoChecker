#if DEBUG
import Foundation
import ObjectiveC

/// Feste Börsen-Antworten für die UI-Tests — wie `FakeExchange`/`FakeExchangeInterceptor` der instrumentierten
/// Android-Tests (gleiche Coins, Kurse und JSON-Formen): Binance (`exchangeInfo`, 24-h-Ticker einzeln und
/// gesammelt) und Coinbase (`products`, `stats`); jede andere Adresse antwortet 404 (die App zeigt «—»).
/// Kein Abruf verlässt das Gerät. Nur im Debug-Build, eingeschaltet von `UITesting.prepareIfRequested`.
enum UITestExchange {
    /// BTC und die übrigen Start-Coins mit festen Kursen (Reihenfolge wie Android `FakeExchange.coins`).
    static let prices: [(coin: String, price: Double)] = [
        ("BTC", 95_000.0), ("ETH", 3_500.0), ("XRP", 2.5), ("BNB", 900.0), ("SOL", 200.0),
    ]

    static func price(_ coin: String) -> Double? {
        prices.first { $0.coin == coin }?.price
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
        default:
            return nil
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
        let rows = prices.map {
            #"{"symbol":"\#($0.coin)USDT","status":"TRADING","baseAsset":"\#($0.coin)","quoteAsset":"USDT"}"#
        }
        return #"{"symbols":["# + rows.joined(separator: ",") + "]}"
    }

    private static func binanceTicker(_ base: String) -> String? {
        guard let price = price(base) else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return #"{"symbol":"\#(base)USDT","bidPrice":"\#(price)","askPrice":"\#(price)","volume":"1000.0","#
            + #""quoteVolume":"\#(price * 1000)","highPrice":"\#(price * 1.02)","lowPrice":"\#(price * 0.98)","#
            + #""lastPrice":"\#(price)","closeTime":\#(now),"priceChangePercent":"1.25"}"#
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
            wanted = prices.map(\.coin)
        }
        return "[" + wanted.compactMap(binanceTicker).joined(separator: ",") + "]"
    }

    // MARK: Coinbase

    private static func coinbaseProducts() -> String {
        "[" + prices.map {
            #"{"id":"\#($0.coin)-USD","base_currency":"\#($0.coin)","quote_currency":"USD","status":"online"}"#
        }.joined(separator: ",") + "]"
    }

    private static func coinbaseStats(_ productId: String) -> String? {
        guard let base = productId.split(separator: "-").first, let price = price(String(base)) else { return nil }
        return #"{"open":"\#(price / 1.0125)","high":"\#(price * 1.02)","low":"\#(price * 0.98)","#
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
