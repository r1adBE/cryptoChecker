import Foundation

/// HTTP-Fehler einer Börse (Status ≠ 2xx).
struct HttpMarketError: LocalizedError {
    let httpCode: Int
    let body: String
    var errorDescription: String? {
        let snippet = body.prefix(160).trimmingCharacters(in: .whitespacesAndNewlines)
        return snippet.isEmpty ? "HTTP \(httpCode)" : "HTTP \(httpCode): \(snippet)"
    }
}

/// Fehlertext, der so dem Nutzer gezeigt werden kann.
struct UserFriendlyMarketError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Netzwerkzugriff für Börsen, Zyklus-Daten und Widgets.
enum MarketHTTP {
    /// Einzelabfragen: kurze Zeitgrenze, damit eine hängende Börse nicht alles aufhält.
    static let session: URLSession = makeSession(timeout: 20)
    /// Sammelabfragen und Paarlisten (mehrere MB).
    static let bulkSession: URLSession = makeSession(timeout: 60)

    private static func makeSession(timeout: TimeInterval) -> URLSession {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = timeout + 10
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16", "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }

    /// GET (oder POST, wenn `post` gesetzt) — liefert den Text der Antwort.
    static func call(_ url: String, post: PostRequestInfo? = nil, session: URLSession = MarketHTTP.session) async throws -> String {
        guard let u = URL(string: url) else { throw UserFriendlyMarketError(message: L("something_went_wrong")) }
        var request = URLRequest(url: u)
        if let post {
            request.httpMethod = "POST"
            request.httpBody = post.body.data(using: .utf8)
            if request.value(forHTTPHeaderField: "Content-Type") == nil {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            post.headers?.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        }
        let (data, response) = try await session.data(for: request)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            HttpLog.add("\(http.statusCode) \(url)")
            throw HttpMarketError(httpCode: http.statusCode, body: text)
        }
        HttpLog.add("200 \(url) · \(data.count / 1024) KB")
        return text
    }
}

/// Kleines HTTP-Protokoll für die Entwickleroption (nur im Speicher).
enum HttpLog {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var lines: [String] = []

    static func add(_ line: String) {
        lock.lock(); defer { lock.unlock() }
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        lines.append("\(f.string(from: Date())) \(line)")
        if lines.count > 200 { lines.removeFirst(lines.count - 200) }
    }

    static var all: [String] {
        lock.lock(); defer { lock.unlock() }
        return lines
    }

    static func clear() {
        lock.lock(); defer { lock.unlock() }
        lines.removeAll()
    }
}

/// Ergebnis einer Sammelabfrage. `complete`: enthält sicher alle gehandelten Paare.
struct BulkTickers: Sendable {
    var tickers: [String: Ticker] = [:]
    var complete = false
}

/// Kursabfragen gegen die Börsen — wie `MarketRemoteDataSource` + `MarketRepositoryImpl`.
enum MarketService {

    /// Einzelkurs eines Paars (inkl. Zusatzanfragen mancher Börsen).
    static func fetchTicker(market: Market, info: CheckerInfo) async throws -> Ticker {
        var ticker = Ticker()
        try await updateTicker(&ticker, market: market, requestId: 0, info: info)
        let n = market.numOfRequests(info)
        if n > 1 {
            for requestId in 1..<n {
                try? await updateTicker(&ticker, market: market, requestId: requestId, info: info)
            }
        }
        return ticker
    }

    private static func updateTicker(_ ticker: inout Ticker, market: Market, requestId: Int, info: CheckerInfo) async throws {
        let url = market.url(requestId: requestId, info: info)
        if url.isEmpty {
            if requestId > 0 { return }
            throw UserFriendlyMarketError(message: L("something_went_wrong"))
        }
        let response = try await MarketHTTP.call(url, post: market.postRequestInfo(requestId: requestId, info: info))
        if response.isEmpty { throw UserFriendlyMarketError(message: L("market_data_empty_error")) }

        do {
            var t = ticker
            try market.parseTickerMain(requestId: requestId, response: response, ticker: &t, info: info)
            if t.last <= Ticker.noData { throw JSONError(message: "Parsed ticker has no data") }
            ticker = t
        } catch let original {
            // Fehlertext der Börse auslesen, sonst den ursprünglichen Fehler weitergeben.
            let message: String?
            do {
                message = try market.parseErrorMain(requestId: requestId, response: response, info: info)
            } catch {
                throw original
            }
            throw UserFriendlyMarketError(message: message ?? L("something_went_wrong"))
        }
    }

    /// Kurse einer Börse in möglichst wenigen Anfragen. Schlüssel = Paar-Kennung.
    static func fetchBulkTickers(market: Market, pairIds: [String]) async -> BulkTickers {
        let n = market.bulkTickersNumOfRequests
        guard n > 0 else { return BulkTickers() }

        var result: [String: Ticker] = [:]
        var allOk = true

        for requestId in 0..<n {
            guard let filtered = market.bulkTickersURL(requestId: requestId, pairIds: pairIds), !filtered.isEmpty else { continue }
            let full = market.bulkTickersURL(requestId: requestId)
            let post = market.bulkTickersPostRequestInfo(requestId: requestId)
            do {
                let response: String
                do {
                    response = try await MarketHTTP.call(filtered, post: post, session: MarketHTTP.bulkSession)
                } catch let e as HttpMarketError {
                    // Gefilterte Abfrage abgelehnt → einmal ungefiltert
                    guard let full, !full.isEmpty, full != filtered else { throw e }
                    response = try await MarketHTTP.call(full, post: post, session: MarketHTTP.bulkSession)
                }
                let parsed = try await Task.detached(priority: .utility) {
                    try market.parseBulkTickersMain(requestId: requestId, response: response)
                }.value
                result.merge(parsed) { _, new in new }
            } catch {
                allOk = false
                if requestId == 0 { return BulkTickers() }
            }
        }
        return BulkTickers(tickers: result, complete: market.bulkTickersComplete && allOk && !result.isEmpty)
    }

    /// Lädt die Paarliste einer Börse.
    static func fetchCurrencyPairs(market: Market) async throws -> [CurrencyPairInfo] {
        var pairs: [CurrencyPairInfo] = []
        if market.currencyPairsCombined {
            var responses: [String] = []
            for requestId in 0..<market.currencyPairsNumOfRequests {
                guard let url = market.currencyPairsURL(requestId: requestId), !url.isEmpty else {
                    responses.append("")
                    continue
                }
                responses.append(try await MarketHTTP.call(url, post: market.currencyPairsPostRequestInfo(requestId: requestId), session: MarketHTTP.bulkSession))
            }
            let all = responses
            return try await Task.detached(priority: .utility) {
                try market.parseCurrencyPairsCombinedMain(responses: all)
            }.value.sorted()
        }
        for requestId in 0..<market.currencyPairsNumOfRequests {
            do {
                guard let url = market.currencyPairsURL(requestId: requestId), !url.isEmpty else { continue }
                let response = try await MarketHTTP.call(url, post: market.currencyPairsPostRequestInfo(requestId: requestId), session: MarketHTTP.bulkSession)
                let next = try await Task.detached(priority: .utility) {
                    try market.parseCurrencyPairsMain(requestId: requestId, response: response)
                }.value
                pairs.append(contentsOf: next)
            } catch {
                if requestId == 0 { throw error }
            }
        }
        return pairs.sorted()
    }

    static func supportsPairSync(_ market: Market) -> Bool {
        !(market.currencyPairsURL(requestId: 0) ?? "").isEmpty
    }
}
