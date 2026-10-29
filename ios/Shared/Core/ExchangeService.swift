import Foundation

/// Ergebnis einer Sammelabfrage. `complete`: enthält sicher alle gehandelten Paare.
struct BulkTickers: Sendable {
    var tickers: [String: Ticker] = [:]
    var complete = false
    /// Fehlertext, wenn die Abfrage scheiterte (für die Pause je Börse, z. B. HTTP 429).
    var error: String? = nil
    /// Paar-Kennungen, deren Zeile in einer sonst lesbaren Antwort unlesbar war (`BulkTickerRows`): Fehler statt
    /// «unverändert». Wie Android `BulkTickers.failed`.
    var failed: Set<String> = []
    /// Angefragte Paare, deren Teilabfrage scheiterte (z. B. Binance Alpha, während Spot klappte): Über sie sagt die
    /// Liste nichts — kein «nicht mehr gehandelt», auch wenn `complete` gilt. Wie Android `BulkTickers.incomplete`.
    var incomplete: Set<String> = []
    /// Art des Fehlers zu `error`, beim Abruf bestimmt (`ExchangeFailure`); nil = nur der Text ist bekannt.
    var exchangeFailure: ExchangeFailure? = nil
}

/// Netzzugriff auf die Börsen: Paarlisten, Sammelabfragen und Einzelkurse. Android: `ExchangeRemoteDataSource` samt
/// `ExchangeRepositoryImpl`.
enum ExchangeService {
    // MARK: Paarliste

    /// Paarliste einer Börse, sortiert. Einzeln gelesene Anfragen: nur die erste muss gelingen; gemeinsam gelesene
    /// (`pairListCombined`): jede.
    static func fetchCurrencyPairs(exchange: Exchange) async throws -> [ExchangePair] {
        if exchange.pairListCombined { return try await loadCombinedPairList(exchange) }
        return try await loadPairListByCall(exchange)
    }

    /// Kann die App für diese Börse eine Paarliste laden?
    static func supportsPairSync(_ exchange: Exchange) -> Bool {
        guard let address = exchange.pairListAddress(call: 0) else { return false }
        return !address.isEmpty
    }

    /// Alle Antworten zuerst holen (fehlende Adresse → leere Antwort), dann gemeinsam lesen (LATOKEN: IDs → Kürzel).
    /// Jeder Ladefehler bricht ab.
    private static func loadCombinedPairList(_ exchange: Exchange) async throws -> [ExchangePair] {
        var bodies: [String] = []
        for call in 0..<exchange.pairListCallCount {
            bodies.append(try await pairListBody(exchange, call: call) ?? "")
        }
        let gathered = bodies
        let pairs = try await Task.detached(priority: .utility) {
            try exchange.readPairListCombined(bodies: gathered)
        }.value.sorted()
        // Volle Coin-Namen aus denselben Antworten; ein Fehler dabei lässt die Liste unberührt
        if !pairs.isEmpty {
            let names = (try? exchange.parseBaseNamesCombined(bodies: gathered)) ?? [:]
            TokenIdentityStore.shared.saveExchange(exchange.key, names: names)
        }
        return pairs
    }

    /// Jede Anfrage für sich lesen (samt Folgeseiten); nur die erste muss gelingen — bei `pairListAllCallsRequired`
    /// jede (sonst würde eine halbe Liste gespeichert).
    private static func loadPairListByCall(_ exchange: Exchange) async throws -> [ExchangePair] {
        var collected: [ExchangePair] = []
        var names: [String: String] = [:]
        for call in 0..<exchange.pairListCallCount {
            do {
                try await loadPairListCall(exchange, call: call, into: &collected, names: &names)
            } catch {
                if call == 0 || exchange.pairListAllCallsRequired { throw error }
            }
        }
        if !collected.isEmpty { TokenIdentityStore.shared.saveExchange(exchange.key, names: names) }
        return collected.sorted()
    }

    /// Eine Anfrage der Paarliste samt Cursor-Folgeseiten. Was vor einem Fehler schon gelesen war (erste Seite,
    /// Namen), bleibt in `collected`/`names`; ohne Adresse geschieht nichts.
    private static func loadPairListCall(_ exchange: Exchange, call: Int, into collected: inout [ExchangePair],
                                         names: inout [String: String]) async throws {
        guard let firstBody = try await pairListBody(exchange, call: call) else { return }
        collected += try await readPairPage(exchange, call: call, body: firstBody)
        // Volle Namen: der erste Treffer je Kürzel zählt; ein Lesefehler dabei stört die Liste nicht
        let found = (try? exchange.parseBaseNames(call: call, body: firstBody)) ?? [:]
        names.merge(found) { earlier, _ in earlier }
        collected += try await followingPairPages(exchange, call: call, first: firstBody)
    }

    /// Folgeseiten (etwa Bybit `nextPageCursor`, Gemini-Details), zusammen höchstens `Exchange.maxPairListPages` Seiten
    /// samt der ersten. Den Blätterzustand hält ein eigenes `PairListPaging` dieser Anfrage, nie die geteilte Börse.
    /// Misslingt eine Folgeseite, misslingt die ganze Anfrage — sonst fehlten Paare unbemerkt.
    /// Wie `ExchangeRemoteDataSource.kt`.
    private static func followingPairPages(_ exchange: Exchange, call: Int, first: String) async throws -> [ExchangePair] {
        var pages: [ExchangePair] = []
        let paging = exchange.pairListPaging()
        var nextAddress = try paging.nextPage(call: call, body: first)
        var pagesLoaded = 1
        while pagesLoaded < Exchange.maxPairListPages, let address = nextAddress, !address.isEmpty {
            let pageBody = try await ExchangeHTTP.call(address, post: nil, session: ExchangeHTTP.bulkSession)
            pages += try await readPairPage(exchange, call: call, body: pageBody)
            nextAddress = try paging.nextPage(call: call, body: pageBody)
            pagesLoaded += 1
        }
        return pages
    }

    /// Antwort auf Paarlisten-Anfrage `call`; nil, wenn die Börse dafür keine Adresse hat.
    private static func pairListBody(_ exchange: Exchange, call: Int) async throws -> String? {
        guard let address = exchange.pairListAddress(call: call), !address.isEmpty else { return nil }
        return try await ExchangeHTTP.call(address, post: exchange.pairListPostBody(call: call), session: ExchangeHTTP.bulkSession)
    }

    /// Eine Seite ausserhalb des Haupt-Threads lesen (Antworten können mehrere MB gross sein).
    private static func readPairPage(_ exchange: Exchange, call: Int, body: String) async throws -> [ExchangePair] {
        try await Task.detached(priority: .utility) {
            try exchange.readPairList(call: call, body: body)
        }.value
    }

    // MARK: Sammelabfrage

    /// Kurse vieler Paare in möglichst wenigen Anfragen, Schlüssel = Paar-Kennung der Börse. Kann die Börse filtern,
    /// wird nur nach `pairIds` gefragt; lehnt sie das ab (etwa weil ein Paar nicht mehr gehandelt wird), folgt einmal
    /// die ungefilterte Abfrage.
    /// - Parameter lowMemory: Widget (`LowMemoryBulk`): nach gescheiterter gefilterter Anfrage nicht ungefiltert
    ///   nachladen und die Alpha-Token-Liste auslassen (deren Paare gelten als unvollständig → Einzelabfragen).
    static func fetchBulkTickers(exchange: Exchange, pairIds: [String], lowMemory: Bool = false) async -> BulkTickers {
        guard exchange.bulkTickersNumOfRequests > 0 else { return BulkTickers() }
        // Lange Listen verteilt die Börse auf mehrere gefilterte Anfragen
        let callCount = exchange.bulkTickersRequestCount(pairIds: pairIds)
        guard callCount > 0 else { return BulkTickers() }

        var tickers: [String: Ticker] = [:]
        // Paare mit unlesbarer Zeile: Fehler statt still «unverändert»
        var unreadable: Set<String> = []
        // Gruppen gescheiterter/ausgelassener Teilabfragen (`Exchange.bulkTickersRequestGroup`): nur deren Paare gelten
        // als unvollständig — bei Binance bleibt Spot ohne Alpha vollständig
        var brokenGroups: Set<Int> = []
        // Bereits geholte ungefilterte Abfragen decken jede Teilanfrage mit derselben Adresse ab
        var fullDone: Set<String> = []
        var responsesComplete = true
        // «Zu viele Anfragen» bei einer Folgeanfrage: Rest weglassen, Text für die Pause (`ExchangeBackoff`)
        var throttled: String? = nil
        var throttledFailure: ExchangeFailure? = nil

        for call in 0..<callCount {
            guard let filtered = exchange.bulkTickersURL(call: call, pairIds: pairIds), !filtered.isEmpty,
                  !fullDone.contains(RequestKey.of(filtered)) else { continue }
            if lowMemory && LowMemoryBulk.skipsRequest(url: filtered) {
                brokenGroups.insert(exchange.bulkTickersRequestGroup(call: call))
                continue
            }
            do {
                guard let part = try await loadBulkCall(exchange, call: call, pairIds: pairIds, filtered: filtered,
                                                        lowMemory: lowMemory, fullDone: &fullDone) else { continue }
                tickers.merge(part.tickers) { _, newer in newer }
                unreadable.formUnion(part.failed)
                // Eine Antwort, die sich selbst als lückenhaft meldet (`Exchange.bulkResponseComplete`), hebt
                // «vollständig» auf
                if !part.complete { responsesComplete = false }
                if !part.failed.isEmpty {
                    HttpLog.add("Sammelabfrage \(exchange.key): \(part.failed.count) Zeilen unlesbar")
                }
            } catch {
                brokenGroups.insert(exchange.bulkTickersRequestGroup(call: call))
                if error is BulkFormatError { HttpLog.add("Sammelabfrage \(exchange.key): \(error.localizedDescription)") }
                // Ohne die erste (tragende) Anfrage lohnt der Rest nicht; der Text pausiert bei Bremsung die Börse
                if call == 0 {
                    return BulkTickers(error: ConnectionErrors.describe(error), exchangeFailure: ExchangeFailure.of(error))
                }
                if isRateLimit(error) {
                    throttled = ConnectionErrors.describe(error)
                    throttledFailure = ExchangeFailure.of(error)
                    // Weggelassene Teile sagen ebenfalls nichts über ihre Paare
                    for skipped in (call + 1)..<callCount {
                        brokenGroups.insert(exchange.bulkTickersRequestGroup(call: skipped))
                    }
                    break
                }
            }
        }

        let incomplete = BulkCompleteness.incomplete(pairIds, failedGroups: brokenGroups) {
            exchange.bulkTickersPairGroup(pairId: $0)
        }
        let complete = exchange.bulkTickersComplete && responsesComplete && !tickers.isEmpty
            && BulkCompleteness.anyComplete(pairIds, failedGroups: brokenGroups, incomplete: incomplete)
        return BulkTickers(tickers: tickers, complete: complete, error: throttled,
                           failed: unreadable.subtracting(tickers.keys), incomplete: incomplete,
                           exchangeFailure: throttledFailure)
    }

    /// Eine Teilanfrage, gefiltert. Wird sie abgelehnt oder ist sie unlesbar: einmal ungefiltert — ohne ungefilterte
    /// Adresse über die Paarliste ohne unbekannte Paare (Bithumb); gelingt auch das nicht, gilt der ursprüngliche
    /// Fehler. Bremsung wird nie umgangen, sondern weitergeworfen. nil = schon durch eine ungefilterte Abfrage erledigt.
    private static func loadBulkCall(_ exchange: Exchange, call: Int, pairIds: [String], filtered: String,
                                     lowMemory: Bool, fullDone: inout Set<String>) async throws -> BulkTickerParse? {
        let fullURL = exchange.bulkTickersURL(call: call)
        let post = exchange.bulkTickersPostRequest(call: call)
        do {
            return try await loadBulk(exchange, call: call, url: filtered, post: post)
        } catch {
            if isRateLimit(error) { throw error }
            // Vergleich ohne Zeitstempel (BingX, `RequestKey`): sonst folgte dieselbe Anfrage noch einmal
            guard let fullURL, !fullURL.isEmpty, RequestKey.of(fullURL) != RequestKey.of(filtered) else {
                // Scheitert auch der Versuch ohne unbekannte Paare, zählt der ursprüngliche Fehler
                guard let retried = try? await withoutUnknownPairs(exchange, call: call, pairIds: pairIds, post: post) else {
                    throw error
                }
                return retried
            }
            // Widget: keine ungefilterte Liste (mehrere MB) — Fehler, die Paare dann einzeln
            if lowMemory { throw error }
            if fullDone.contains(RequestKey.of(fullURL)) { return nil }
            let parsed = try await loadBulk(exchange, call: call, url: fullURL, post: post)
            fullDone.insert(RequestKey.of(fullURL))
            return parsed
        }
    }

    /// Gefilterte Anfrage ohne ungefilterten Ersatz abgelehnt (`Exchange.bulkKnownPairsURL`, Bithumb): Paarliste holen,
    /// angefragte Paare, die dort fehlen, weglassen und ohne sie nochmals fragen. Die weggelassenen fehlen dann in der
    /// (vollständigen) Antwort → die Einzelabfrage bestätigt «nicht mehr gehandelt», `BulkDelisted` lässt sie künftig
    /// weg. nil = geht nicht oder kein Paar unbekannt.
    private static func withoutUnknownPairs(_ exchange: Exchange, call: Int, pairIds: [String],
                                            post: PostRequest?) async throws -> BulkTickerParse? {
        guard let listAddress = exchange.bulkKnownPairsURL(call: call), !listAddress.isEmpty else { return nil }
        let listBody = try await ExchangeHTTP.call(listAddress, post: nil, session: ExchangeHTTP.bulkSession)
        let listed = try exchange.readPairList(call: 0, body: listBody)
        let knownIds = Set(listed.compactMap(\.pairId))
        guard let unknown = BulkCompleteness.unknownPairs(pairIds, known: knownIds) else { return nil }

        HttpLog.add("Sammelabfrage \(exchange.key): ohne \(unknown.count) unbekannte Paare wiederholt")
        let remaining = pairIds.filter { !unknown.contains($0) }
        if remaining.isEmpty { return BulkTickerParse(tickers: [:], failed: []) }
        guard let address = exchange.bulkTickersURL(call: call, pairIds: remaining), !address.isEmpty else { return nil }
        return try await loadBulk(exchange, call: call, url: address, post: post)
    }

    /// Teilanfrage holen und ausserhalb des Haupt-Threads lesen. Die (grosse, für alle gleiche) Alpha-Liste von
    /// Binance gilt `BinanceAlpha.listCacheMillis` lang für alle Aufrufer — eine Aktualisierung lädt sie nie zweimal;
    /// gemerkt wird sie erst nach erfolgreichem Lesen.
    private static func loadBulk(_ exchange: Exchange, call: Int, url: String,
                                 post: PostRequest?) async throws -> BulkTickerParse {
        let isAlphaList = post == nil && url == BinanceAlpha.tokenListURL
        let fromCache = isAlphaList ? BinanceAlpha.listCache.get(now: EpochTime.nowMillis) : nil
        let body: String
        if let fromCache {
            body = fromCache
        } else {
            body = try await ExchangeHTTP.call(url, post: post, session: ExchangeHTTP.tickerBulkSession)
        }
        let parsed = try await Task.detached(priority: .utility) {
            try exchange.parseBulkTickersDetailed(call: call, body: body)
        }.value
        if isAlphaList && fromCache == nil { BinanceAlpha.listCache.put(body, now: EpochTime.nowMillis) }
        return parsed
    }

    /// Hat die Börse gebremst (429, auch erkannt über `RateLimitSignal`) oder gesperrt (418)?
    private static func isRateLimit(_ error: Error) -> Bool {
        guard let http = error as? HttpExchangeError else { return false }
        return ExchangeBackoff.isRateLimitCode(http.httpCode)
    }

    // MARK: Einzelkurs

    /// Kurs eines Paars. Die erste Anfrage muss gelingen; weitere (z. B. Volumen aus einer zweiten Quelle) ergänzen
    /// nur und dürfen scheitern, ohne den Kurs zu verwerfen.
    static func fetchTicker(exchange: Exchange, info: PairQuery) async throws -> Ticker {
        var ticker = Ticker()
        try await loadTickerCall(0, exchange: exchange, info: info, into: &ticker)
        for call in stride(from: 1, to: exchange.tickerCallCount(info), by: 1) {
            try? await loadTickerCall(call, exchange: exchange, info: info, into: &ticker)
        }
        return ticker
    }

    /// Teil `call` des Kurses holen und in `ticker` lesen; misslingt das, bleibt `ticker` unverändert. Ohne Adresse:
    /// bei Teil 0 ein Fehler, sonst nichts. Unlesbar oder ohne gültigen letzten Kurs → Fehlermeldung der Börse
    /// (`explained`).
    private static func loadTickerCall(_ call: Int, exchange: Exchange, info: PairQuery,
                                       into ticker: inout Ticker) async throws {
        // Unzulässige Kennung → Fehler ohne Anfrage (`tickerQueryError`, wie Android)
        if let refused = exchange.tickerQueryError(call: call, info: info) { throw refused }
        let address = exchange.tickerAddress(call: call, info: info)
        if address.isEmpty {
            if call == 0 { throw UserFriendlyExchangeError(message: L("something_went_wrong")) }
            return
        }
        let body: String
        do {
            body = try await ExchangeHTTP.call(address, post: exchange.tickerPostBody(call: call, info: info))
        } catch let server as HttpExchangeError {
            // Serverfehler: Kurs über die Ausweichadresse der Börse, sonst bleibt er (`SingleTickerFallback`)
            ticker = try await SingleTickerFallback.load(exchange: exchange, call: call, info: info, base: ticker,
                                                         serverError: server) { fallback in
                try await ExchangeHTTP.call(fallback, session: ExchangeHTTP.tickerBulkSession)
            }
            return
        }
        if body.isEmpty { throw UserFriendlyExchangeError(message: L("exchange_data_empty_error")) }

        var draft = ticker
        do {
            try exchange.readTicker(call: call, body: body, ticker: &draft, info: info)
            // Kein gültiger Kurs (fehlt, 0 wie bei Binance-«BREAK», NaN/∞ — ∞ löste «über» aus): «ohne Kurs» statt
            // Erfolg, sonst hielte die Einzelabfrage ein ruhendes Paar für gehandelt (`NotTraded.confirmedBySingle`).
            // Wie ExchangeRemoteDataSource.kt.
            guard draft.last.isFinite, draft.last > 0 else { throw NoTickerDataError() }
        } catch let readFailure {
            // Kennt die Börse das Paar nicht (DexScreener «Pool not found»): Ausweichadresse (GeckoTerminal);
            // scheitert auch sie, bleibt dieser Lesefehler (`SingleTickerFallback.loadAfterMissing`, wie Android)
            if exchange.tickerFallbackOnParseError(call: call, error: readFailure) {
                ticker = try await SingleTickerFallback.loadAfterMissing(exchange: exchange, call: call, info: info,
                                                                         base: ticker, missing: readFailure) { fallback in
                    try await ExchangeHTTP.call(fallback, session: ExchangeHTTP.tickerBulkSession)
                }
                return
            }
            throw explained(readFailure, exchange: exchange, call: call, body: body, info: info)
        }
        ticker = draft
    }

    /// Fehlermeldung der Börse zu einer unlesbaren Antwort als `UserFriendlyExchangeError`. Findet sich keine (das
    /// Auslesen wirft), bleibt es beim ursprünglichen Lesefehler — darum wird dieser zweite Fehler bewusst verworfen.
    /// Ohne eigene Meldung, aber mit «Paar unbekannt» im Lesefehler (Binance «Invalid symbol.»): dieser Text der Börse
    /// wird die Meldung, damit `ExchangeFailure.of` das Paar als unbekannt erkennt — wie Android.
    static func explained(_ readFailure: Error, exchange: Exchange, call: Int, body: String,
                          info: PairQuery) -> Error {
        do {
            let message = try exchange.readError(call: call, body: body, info: info)
            if message == nil, let json = readFailure as? JSONError, NotTraded.isUnknownInstrument(json.message) {
                return UserFriendlyExchangeError(message: json.message)
            }
            return UserFriendlyExchangeError(message: message ?? L("something_went_wrong"))
        } catch {
            return readFailure
        }
    }
}
