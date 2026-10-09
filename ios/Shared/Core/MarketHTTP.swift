import Foundation

/// HTTP-Fehler einer Börse (Status ≠ 2xx). Der Text ist technisch (Bericht, gespeicherter
/// Fehler); angezeigt wird er nie roh (`ConnectionErrors.display`).
struct HttpMarketError: LocalizedError {
    let httpCode: Int
    let body: String
    /// «Retry-After» der Antwort (bei 429/503) für die Pause je Börse (`ExchangeBackoff`).
    var retryAfterSeconds: Int64? = nil
    var errorDescription: String? {
        let head = "HTTP \(httpCode)" + ExchangeBackoff.retryAfterSuffix(retryAfterSeconds)
        let snippet = body.prefix(160).trimmingCharacters(in: .whitespacesAndNewlines)
        return snippet.isEmpty ? head : "\(head): \(snippet)"
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
        // Für alle gleiche Marktdaten zuerst vom Spiegel auf GitHub (`DataMirror`), sonst beim Anbieter
        if post == nil, let entry = DataMirror.entry(for: url),
           let text = try? await call(entry.url, session: session),
           let body = DataMirror.unwrap(text, now: TimeUtils.nowMillis, maxAgeMillis: entry.maxAgeMillis) {
            return body
        }
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
        // GET: gleiche gleichzeitige Anfragen nur einmal ins Netz (`InFlightRequests`)
        guard post == nil else { return try await load(request, url: url, session: session) }
        let key = "\(session.configuration.timeoutIntervalForRequest)|\(url)"
        let shared = request
        return try await InFlightRequests.shared.value(key) {
            try await load(shared, url: url, session: session)
        }
    }

    private static func load(_ request: URLRequest, url: String, session: URLSession) async throws -> String {
        let (data, response) = try await session.data(for: request)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            HttpLog.add("\(http.statusCode) \(url)")
            let retryAfter = ExchangeBackoff.parseRetryAfterSeconds(http.value(forHTTPHeaderField: "Retry-After"),
                                                                    now: TimeUtils.nowMillis)
            throw HttpMarketError(httpCode: http.statusCode, body: text, retryAfterSeconds: retryAfter)
        }
        HttpLog.add("200 \(url) · \(data.count / 1024) KB")
        return text
    }
}

/// Gleiche GET-Anfragen, die gleichzeitig laufen, nur einmal schicken (Mini-Chart, Blatt und
/// Aktualisierung für dasselbe Paar). Nichts wird zwischengespeichert: Ist die Anfrage fertig,
/// fällt der Eintrag weg. Bricht der einzige Wartende ab (Blatt zu), wird die Anfrage abgebrochen.
actor InFlightRequests {
    static let shared = InFlightRequests()

    private struct Entry {
        let id: UUID
        let task: Task<String, Error>
        var waiters: Int
    }

    private var entries: [String: Entry] = [:]

    func value(_ key: String, load: @escaping @Sendable () async throws -> String) async throws -> String {
        let id: UUID
        let task: Task<String, Error>
        if var entry = entries[key] {
            entry.waiters += 1
            entries[key] = entry
            id = entry.id
            task = entry.task
        } else {
            id = UUID()
            task = Task { try await load() }
            entries[key] = Entry(id: id, task: task, waiters: 1)
        }
        defer { leave(key, id: id) }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            Task { await self.cancelIfAlone(key, id: id) }
        }
    }

    private func leave(_ key: String, id: UUID) {
        guard var entry = entries[key], entry.id == id else { return }
        entry.waiters -= 1
        if entry.waiters <= 0 {
            entries[key] = nil
        } else {
            entries[key] = entry
        }
    }

    private func cancelIfAlone(_ key: String, id: UUID) {
        guard let entry = entries[key], entry.id == id, entry.waiters <= 1 else { return }
        entry.task.cancel()
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
    /// Fehlertext, wenn die Abfrage scheiterte (für die Pause je Börse, z. B. HTTP 429).
    var error: String? = nil
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
        guard market.bulkTickersNumOfRequests > 0 else { return BulkTickers() }
        // Lange Paarlisten verteilt die Börse auf mehrere gefilterte Anfragen.
        let n = market.bulkTickersRequestCount(pairIds: pairIds)
        guard n > 0 else { return BulkTickers() }

        var result: [String: Ticker] = [:]
        var allOk = true
        // Schon geladene ungefilterte Abfragen decken alle Teilanfragen mit derselben URL ab.
        var loadedFull: Set<String> = []

        for requestId in 0..<n {
            guard let filtered = market.bulkTickersURL(requestId: requestId, pairIds: pairIds), !filtered.isEmpty,
                  !loadedFull.contains(filtered) else { continue }
            let full = market.bulkTickersURL(requestId: requestId)
            let post = market.bulkTickersPostRequestInfo(requestId: requestId)
            do {
                let parsed: [String: Ticker]
                do {
                    parsed = try await loadBulkTickers(market, requestId: requestId, url: filtered, post: post)
                } catch {
                    // Abgelehnt oder unlesbar (z. B. ein Paar wird nicht mehr gehandelt und die
                    // Börse verwirft die ganze Liste) → einmal ungefiltert
                    guard let full, !full.isEmpty, full != filtered else { throw error }
                    if loadedFull.contains(full) { continue }
                    parsed = try await loadBulkTickers(market, requestId: requestId, url: full, post: post)
                    loadedFull.insert(full)
                }
                result.merge(parsed) { _, new in new }
            } catch {
                allOk = false
                // Fehlertext weitergeben: «zu viele Anfragen» pausiert die Börse (`ExchangeBackoff`)
                if requestId == 0 { return BulkTickers(error: ConnectionErrors.describe(error)) }
            }
        }
        return BulkTickers(tickers: result, complete: market.bulkTickersComplete && allOk && !result.isEmpty)
    }

    private static func loadBulkTickers(_ market: Market, requestId: Int, url: String,
                                        post: PostRequestInfo?) async throws -> [String: Ticker] {
        let response = try await MarketHTTP.call(url, post: post, session: MarketHTTP.bulkSession)
        return try await Task.detached(priority: .utility) {
            try market.parseBulkTickersMain(requestId: requestId, response: response)
        }.value
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
