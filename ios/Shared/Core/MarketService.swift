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
    /// Art des Fehlers zu `error`, beim Abruf bestimmt (`MarketFailure`); nil = nur der Text ist bekannt.
    var marketFailure: MarketFailure? = nil
}

/// Netzzugriff auf die Börsen: Paarlisten, Sammelabfragen und Einzelkurse. Android: `MarketRemoteDataSource` samt
/// `MarketRepositoryImpl`.
enum MarketService {
    // MARK: Paarliste

    /// Paarliste einer Börse, sortiert. Einzeln gelesene Anfragen: nur die erste muss gelingen; gemeinsam gelesene
    /// (`pairListCombined`): jede.
    static func fetchCurrencyPairs(market: Market) async throws -> [MarketPair] {
        if market.pairListCombined { return try await loadCombinedPairList(market) }
        return try await loadPairListByCall(market)
    }

    /// Kann die App für diese Börse eine Paarliste laden?
    static func supportsPairSync(_ market: Market) -> Bool {
        guard let address = market.pairListAddress(call: 0) else { return false }
        return !address.isEmpty
    }

    /// Alle Antworten zuerst holen (fehlende Adresse → leere Antwort), dann gemeinsam lesen (LATOKEN: IDs → Kürzel).
    /// Jeder Ladefehler bricht ab.
    private static func loadCombinedPairList(_ market: Market) async throws -> [MarketPair] {
        var bodies: [String] = []
        for call in 0..<market.pairListCallCount {
            bodies.append(try await pairListBody(market, call: call) ?? "")
        }
        let gathered = bodies
        let pairs = try await Task.detached(priority: .utility) {
            try market.readPairListCombined(bodies: gathered)
        }.value.sorted()
        // Volle Coin-Namen (K-8) aus denselben Antworten; ein Fehler dabei lässt die Liste unberührt
        if !pairs.isEmpty {
            let names = (try? market.parseBaseNamesCombined(bodies: gathered)) ?? [:]
            TokenIdentityStore.shared.saveMarket(market.key, names: names)
        }
        return pairs
    }

    /// Jede Anfrage für sich lesen (samt Folgeseiten); nur die erste muss gelingen — bei `pairListAllCallsRequired`
    /// jede (sonst würde eine halbe Liste gespeichert).
    private static func loadPairListByCall(_ market: Market) async throws -> [MarketPair] {
        var collected: [MarketPair] = []
        var names: [String: String] = [:]
        for call in 0..<market.pairListCallCount {
            do {
                try await loadPairListCall(market, call: call, into: &collected, names: &names)
            } catch {
                if call == 0 || market.pairListAllCallsRequired { throw error }
            }
        }
        if !collected.isEmpty { TokenIdentityStore.shared.saveMarket(market.key, names: names) }
        return collected.sorted()
    }

    /// Eine Anfrage der Paarliste samt Cursor-Folgeseiten. Was vor einem Fehler schon gelesen war (erste Seite,
    /// Namen), bleibt in `collected`/`names`; ohne Adresse geschieht nichts.
    private static func loadPairListCall(_ market: Market, call: Int, into collected: inout [MarketPair],
                                         names: inout [String: String]) async throws {
        guard let firstBody = try await pairListBody(market, call: call) else { return }
        collected += try await readPairPage(market, call: call, body: firstBody)
        // Volle Namen: der erste Treffer je Kürzel zählt; ein Lesefehler dabei stört die Liste nicht
        let found = (try? market.parseBaseNames(call: call, body: firstBody)) ?? [:]
        names.merge(found) { earlier, _ in earlier }
        collected += try await followingPairPages(market, call: call, first: firstBody)
    }

    /// Folgeseiten (etwa Bybit `nextPageCursor`, Gemini-Details), zusammen höchstens `Market.maxPairListPages` Seiten
    /// samt der ersten. Den Blätterzustand hält ein eigenes `PairListPaging` dieser Anfrage, nie die geteilte Börse.
    /// Misslingt eine Folgeseite, misslingt die ganze Anfrage — sonst fehlten Paare unbemerkt.
    /// Wie `MarketRemoteDataSource.kt`.
    private static func followingPairPages(_ market: Market, call: Int, first: String) async throws -> [MarketPair] {
        var pages: [MarketPair] = []
        let paging = market.pairListPaging()
        var nextAddress = try paging.nextPage(call: call, body: first)
        var pagesLoaded = 1
        while pagesLoaded < Market.maxPairListPages, let address = nextAddress, !address.isEmpty {
            let pageBody = try await MarketHTTP.call(address, post: nil, session: MarketHTTP.bulkSession)
            pages += try await readPairPage(market, call: call, body: pageBody)
            nextAddress = try paging.nextPage(call: call, body: pageBody)
            pagesLoaded += 1
        }
        return pages
    }

    /// Antwort auf Paarlisten-Anfrage `call`; nil, wenn die Börse dafür keine Adresse hat.
    private static func pairListBody(_ market: Market, call: Int) async throws -> String? {
        guard let address = market.pairListAddress(call: call), !address.isEmpty else { return nil }
        return try await MarketHTTP.call(address, post: market.pairListPostBody(call: call), session: MarketHTTP.bulkSession)
    }

    /// Eine Seite ausserhalb des Haupt-Threads lesen (Antworten können mehrere MB gross sein).
    private static func readPairPage(_ market: Market, call: Int, body: String) async throws -> [MarketPair] {
        try await Task.detached(priority: .utility) {
            try market.readPairList(call: call, body: body)
        }.value
    }

    // MARK: Sammelabfrage

    /// Kurse vieler Paare in möglichst wenigen Anfragen, Schlüssel = Paar-Kennung der Börse. Kann die Börse filtern,
    /// wird nur nach `pairIds` gefragt; lehnt sie das ab (etwa weil ein Paar nicht mehr gehandelt wird), folgt einmal
    /// die ungefilterte Abfrage.
    /// - Parameter lowMemory: Widget (`LowMemoryBulk`): nach gescheiterter gefilterter Anfrage nicht ungefiltert
    ///   nachladen und die Alpha-Token-Liste auslassen (deren Paare gelten als unvollständig → Einzelabfragen).
    static func fetchBulkTickers(market: Market, pairIds: [String], lowMemory: Bool = false) async -> BulkTickers {
        guard market.bulkTickersNumOfRequests > 0 else { return BulkTickers() }
        // Lange Listen verteilt die Börse auf mehrere gefilterte Anfragen
        let callCount = market.bulkTickersRequestCount(pairIds: pairIds)
        guard callCount > 0 else { return BulkTickers() }

        var tickers: [String: Ticker] = [:]
        // Paare mit unlesbarer Zeile: Fehler statt still «unverändert»
        var unreadable: Set<String> = []
        // Gruppen gescheiterter/ausgelassener Teilabfragen (`Market.bulkTickersRequestGroup`): nur deren Paare gelten
        // als unvollständig — bei Binance bleibt Spot ohne Alpha vollständig
        var brokenGroups: Set<Int> = []
        // Bereits geholte ungefilterte Abfragen decken jede Teilanfrage mit derselben Adresse ab
        var fullDone: Set<String> = []
        var responsesComplete = true
        // «Zu viele Anfragen» bei einer Folgeanfrage: Rest weglassen, Text für die Pause (`ExchangeBackoff`)
        var throttled: String? = nil
        var throttledFailure: MarketFailure? = nil

        for call in 0..<callCount {
            guard let filtered = market.bulkTickersURL(call: call, pairIds: pairIds), !filtered.isEmpty,
                  !fullDone.contains(filtered) else { continue }
            if lowMemory && LowMemoryBulk.skipsRequest(url: filtered) {
                brokenGroups.insert(market.bulkTickersRequestGroup(call: call))
                continue
            }
            do {
                guard let part = try await loadBulkCall(market, call: call, pairIds: pairIds, filtered: filtered,
                                                        lowMemory: lowMemory, fullDone: &fullDone) else { continue }
                tickers.merge(part.tickers) { _, newer in newer }
                unreadable.formUnion(part.failed)
                // Eine Antwort, die sich selbst als lückenhaft meldet (`Market.bulkResponseComplete`), hebt
                // «vollständig» auf
                if !part.complete { responsesComplete = false }
                if !part.failed.isEmpty {
                    HttpLog.add("Sammelabfrage \(market.key): \(part.failed.count) Zeilen unlesbar")
                }
            } catch {
                brokenGroups.insert(market.bulkTickersRequestGroup(call: call))
                if error is BulkFormatError { HttpLog.add("Sammelabfrage \(market.key): \(error.localizedDescription)") }
                // Ohne die erste (tragende) Anfrage lohnt der Rest nicht; der Text pausiert bei Bremsung die Börse
                if call == 0 {
                    return BulkTickers(error: ConnectionErrors.describe(error), marketFailure: MarketFailure.of(error))
                }
                if isRateLimit(error) {
                    throttled = ConnectionErrors.describe(error)
                    throttledFailure = MarketFailure.of(error)
                    // Weggelassene Teile sagen ebenfalls nichts über ihre Paare
                    for skipped in (call + 1)..<callCount {
                        brokenGroups.insert(market.bulkTickersRequestGroup(call: skipped))
                    }
                    break
                }
            }
        }

        let incomplete = BulkCompleteness.incomplete(pairIds, failedGroups: brokenGroups) {
            market.bulkTickersPairGroup(pairId: $0)
        }
        let complete = market.bulkTickersComplete && responsesComplete && !tickers.isEmpty
            && BulkCompleteness.anyComplete(pairIds, failedGroups: brokenGroups, incomplete: incomplete)
        return BulkTickers(tickers: tickers, complete: complete, error: throttled,
                           failed: unreadable.subtracting(tickers.keys), incomplete: incomplete,
                           marketFailure: throttledFailure)
    }

    /// Eine Teilanfrage, gefiltert. Wird sie abgelehnt oder ist sie unlesbar: einmal ungefiltert — ohne ungefilterte
    /// Adresse über die Paarliste ohne unbekannte Paare (Bithumb); gelingt auch das nicht, gilt der ursprüngliche
    /// Fehler. Bremsung wird nie umgangen, sondern weitergeworfen. nil = schon durch eine ungefilterte Abfrage erledigt.
    private static func loadBulkCall(_ market: Market, call: Int, pairIds: [String], filtered: String,
                                     lowMemory: Bool, fullDone: inout Set<String>) async throws -> BulkTickerParse? {
        let fullURL = market.bulkTickersURL(call: call)
        let post = market.bulkTickersPostRequestInfo(call: call)
        do {
            return try await loadBulk(market, call: call, url: filtered, post: post)
        } catch {
            if isRateLimit(error) { throw error }
            guard let fullURL, !fullURL.isEmpty, fullURL != filtered else {
                // Scheitert auch der Versuch ohne unbekannte Paare, zählt der ursprüngliche Fehler
                guard let retried = try? await withoutUnknownPairs(market, call: call, pairIds: pairIds, post: post) else {
                    throw error
                }
                return retried
            }
            // Widget: keine ungefilterte Liste (mehrere MB) — Fehler, die Paare dann einzeln
            if lowMemory { throw error }
            if fullDone.contains(fullURL) { return nil }
            let parsed = try await loadBulk(market, call: call, url: fullURL, post: post)
            fullDone.insert(fullURL)
            return parsed
        }
    }

    /// Gefilterte Anfrage ohne ungefilterten Ersatz abgelehnt (`Market.bulkKnownPairsURL`, Bithumb): Paarliste holen,
    /// angefragte Paare, die dort fehlen, weglassen und ohne sie nochmals fragen. Die weggelassenen fehlen dann in der
    /// (vollständigen) Antwort → die Einzelabfrage bestätigt «nicht mehr gehandelt», `BulkDelisted` lässt sie künftig
    /// weg. nil = geht nicht oder kein Paar unbekannt.
    private static func withoutUnknownPairs(_ market: Market, call: Int, pairIds: [String],
                                            post: PostRequestInfo?) async throws -> BulkTickerParse? {
        guard let listAddress = market.bulkKnownPairsURL(call: call), !listAddress.isEmpty else { return nil }
        let listBody = try await MarketHTTP.call(listAddress, post: nil, session: MarketHTTP.bulkSession)
        let listed = try market.readPairList(call: 0, body: listBody)
        let knownIds = Set(listed.compactMap(\.pairId))
        guard let unknown = BulkCompleteness.unknownPairs(pairIds, known: knownIds) else { return nil }

        HttpLog.add("Sammelabfrage \(market.key): ohne \(unknown.count) unbekannte Paare wiederholt")
        let remaining = pairIds.filter { !unknown.contains($0) }
        if remaining.isEmpty { return BulkTickerParse(tickers: [:], failed: []) }
        guard let address = market.bulkTickersURL(call: call, pairIds: remaining), !address.isEmpty else { return nil }
        return try await loadBulk(market, call: call, url: address, post: post)
    }

    /// Teilanfrage holen und ausserhalb des Haupt-Threads lesen. Die (grosse, für alle gleiche) Alpha-Liste von
    /// Binance gilt `BinanceAlpha.listCacheMillis` lang für alle Aufrufer — eine Aktualisierung lädt sie nie zweimal;
    /// gemerkt wird sie erst nach erfolgreichem Lesen.
    private static func loadBulk(_ market: Market, call: Int, url: String,
                                 post: PostRequestInfo?) async throws -> BulkTickerParse {
        let isAlphaList = post == nil && url == BinanceAlpha.tokenListURL
        let fromCache = isAlphaList ? BinanceAlpha.listCache.get(now: TimeUtils.nowMillis) : nil
        let body: String
        if let fromCache {
            body = fromCache
        } else {
            body = try await MarketHTTP.call(url, post: post, session: MarketHTTP.tickerBulkSession)
        }
        let parsed = try await Task.detached(priority: .utility) {
            try market.parseBulkTickersDetailed(call: call, body: body)
        }.value
        if isAlphaList && fromCache == nil { BinanceAlpha.listCache.put(body, now: TimeUtils.nowMillis) }
        return parsed
    }

    /// Hat die Börse gebremst (429, auch erkannt über `RateLimitSignal`) oder gesperrt (418)?
    private static func isRateLimit(_ error: Error) -> Bool {
        guard let http = error as? HttpMarketError else { return false }
        return ExchangeBackoff.isRateLimitCode(http.httpCode)
    }

    // MARK: Einzelkurs

    /// Kurs eines Paars. Die erste Anfrage muss gelingen; weitere (z. B. Volumen aus einer zweiten Quelle) ergänzen
    /// nur und dürfen scheitern, ohne den Kurs zu verwerfen.
    static func fetchTicker(market: Market, info: PairQuery) async throws -> Ticker {
        var ticker = Ticker()
        try await loadTickerCall(0, market: market, info: info, into: &ticker)
        for call in stride(from: 1, to: market.tickerCallCount(info), by: 1) {
            try? await loadTickerCall(call, market: market, info: info, into: &ticker)
        }
        return ticker
    }

    /// Teil `call` des Kurses holen und in `ticker` lesen; misslingt das, bleibt `ticker` unverändert. Ohne Adresse:
    /// bei Teil 0 ein Fehler, sonst nichts. Unlesbar oder ohne gültigen letzten Kurs → Fehlermeldung der Börse
    /// (`explained`).
    private static func loadTickerCall(_ call: Int, market: Market, info: PairQuery,
                                       into ticker: inout Ticker) async throws {
        let address = market.tickerAddress(call: call, info: info)
        if address.isEmpty {
            if call == 0 { throw UserFriendlyMarketError(message: L("something_went_wrong")) }
            return
        }
        let body = try await MarketHTTP.call(address, post: market.tickerPostBody(call: call, info: info))
        if body.isEmpty { throw UserFriendlyMarketError(message: L("market_data_empty_error")) }

        var draft = ticker
        do {
            try market.readTicker(call: call, body: body, ticker: &draft, info: info)
            // Kein gültiger Kurs (fehlt, 0 wie bei Binance-«BREAK», NaN/∞ — ∞ löste «über» aus): «ohne Kurs» statt
            // Erfolg, sonst hielte die Einzelabfrage ein ruhendes Paar für gehandelt (`NotTraded.confirmedBySingle`).
            // Wie MarketRemoteDataSource.kt.
            guard draft.last.isFinite, draft.last > 0 else { throw NoTickerDataError() }
        } catch let readFailure {
            throw explained(readFailure, market: market, call: call, body: body, info: info)
        }
        ticker = draft
    }

    /// Fehlermeldung der Börse zu einer unlesbaren Antwort als `UserFriendlyMarketError`. Findet sich keine (das
    /// Auslesen wirft), bleibt es beim ursprünglichen Lesefehler — darum wird dieser zweite Fehler bewusst verworfen.
    private static func explained(_ readFailure: Error, market: Market, call: Int, body: String,
                                  info: PairQuery) -> Error {
        do {
            let message = try market.readError(call: call, body: body, info: info)
            return UserFriendlyMarketError(message: message ?? L("something_went_wrong"))
        } catch {
            return readFailure
        }
    }
}
