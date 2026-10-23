import Foundation

/// Lädt Guthaben beobachteter Adressen (`AddressBalance`: Hosts, Antworten, Abstand) — wie
/// `WatchedAddressSource.kt`. Aufruf aus `PortfolioModel.refreshWatchedAddresses` (Portfolio-Tab, Hintergrund).
///
/// Datenschutz: eigene flüchtige Sitzung (keine Cookies, kein Zwischenspeicher) und nicht über
/// `MarketHTTP.call`, damit die Adresse nicht im HTTP-Protokoll der App landet; Protokolle nennen nur
/// die gekürzte Adresse. Neutraler User-Agent (`HttpUserAgent.neutral`). Eigener Bitcoin-Server
/// (`AddressServer`): nur dorthin.
/// Pausen: 451/403 → Host 6 h übersprungen (`BlockedSources`, nicht der eigene Server); 429/418 und wiederholte
/// Zeitüberschreitungen → Pause je Host nach `ExchangeBackoff` («Retry-After» gilt); dann der
/// nächste Host. Adressen der Reihe nach, nie parallel (schont die kostenlosen Anbieter).
/// ETH: mit «Tokens mitzählen» eine Stapel-Anfrage (ETH + ERC-20, `Erc20Tokens`). xpub: abgeleitete Adressen einzeln mit
/// kurzer Pause (`XpubScan`, höchstens `XpubScan.maxRequests` je Aktualisierung).
actor WatchedAddressSource {
    static let shared = WatchedAddressSource()

    /// Ergebnis eines Abrufs. `tokens`: ERC-20-Guthaben (nur ETH mit «Tokens mitzählen»; nil = nicht gelesen, das letzte
    /// bleibt). `xpub`: neuer Zwischenspeicher nach einer xpub-Suche.
    struct Fetched: Equatable, Sendable {
        let balance: String
        let provider: String
        var tokens: [String: String]? = nil
        var xpub: XpubCache? = nil
        /// 0x-Adresse, erste Suche: EVM-Netze mit Guthaben (Vorschlag der Wahl).
        var networks: [String]? = nil
        /// Solana: Token mit Guthaben ausserhalb der Liste.
        var unknownTokens: Int? = nil
        /// Token, deren alter Wert mangels Antwort bleibt (TON: volle Jetton-Seite); nil = unverändert.
        var keptTokens: [String]? = nil
    }

    /// Ergebnis je Adresse: entweder `fetched` oder `error` (technischer Text).
    struct Update: Equatable, Sendable {
        let id: Int64
        let time: Int64
        let fetched: Fetched?
        let error: String?
    }

    /// Nicht privat: Test prüft den neutralen User-Agent.
    static let session: URLSession = {
        let c = PrivateSessionConfig.make(timeout: AddressBalance.timeoutSeconds,
                                          resource: AddressBalance.timeoutSeconds + 5)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        // Neutraler User-Agent ohne App-Name/Version wie Android
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.neutral, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Hintergrund/Widget: alle Adressen zusammen höchstens so lange — Kurse und Alarme gehen vor.
    static let backgroundTotalMillis: Int64 = 8_000
    /// Hintergrund: eine Anfrage höchstens so lange (Vordergrund: `AddressBalance.timeoutSeconds`).
    static let backgroundRequestMillis: Int64 = 5_000
    /// Weniger Restzeit lohnt keine Anfrage mehr.
    private static let minRequestMillis: Int64 = 1_000
    /// xpub: Pause zwischen zwei Adressen (Vordergrund) — höflich zu den kostenlosen Anbietern.
    static let xpubPauseMillis: Int64 = 200
    /// xpub im Hintergrund (Zeitbudget, bekannte benutzte Adressen und kurzer Vorausblick).
    static let xpubPauseBackgroundMillis: Int64 = 50

    /// Pausen je Host (nur im Speicher; ein Neustart beginnt frisch).
    private var backoff: [String: ExchangeBackoff.State] = [:]
    /// Laufende Abrufe (der Hintergrund wartet nicht auf einen laufenden — wie Android `tryLock`).
    private var running = 0

    /// Zeit um, bevor ein Host gefragt wurde (Adresse bleibt fällig, kein Fehler).
    struct OutOfTime: Error {}

    /// Alle fälligen Adressen (`AddressBalance.isDue`) der Reihe nach laden.
    /// - Parameter force: manuelles Aktualisieren (kürzerer Abstand).
    /// - Parameter budgetMillis: Hintergrund: höchstens so lange insgesamt (je Anfrage höchstens
    ///   `backgroundRequestMillis`); läuft schon ein Abruf, wird nicht gewartet. Was nicht mehr drankommt,
    ///   bleibt fällig. nil = Vordergrund, ohne Grenze. xpub im Hintergrund: die bekannten benutzten Adressen samt kurzem
    ///   Vorausblick und nur ganz (sonst bleibt er fällig). Hintergrund: die am längsten nicht versuchten zuerst
    ///   (`AddressBalance.dueOrder`).
    /// - Parameter btcServer: eigener Bitcoin-Server (`AddressServer`): Bitcoin nur dort, ohne Rückfall; nil = öffentlich.
    func refresh(_ addresses: [WatchedAddress], force: Bool, now: Int64 = TimeUtils.nowMillis,
                 budgetMillis: Int64? = nil, btcServer: String? = nil) async throws -> [Update] {
        let deadline = budgetMillis.map { TimeUtils.nowMillis + $0 }
        if deadline != nil && running > 0 { return [] }
        running += 1
        defer { running -= 1 }
        // Bitcoin-Hosts dieses Abrufs als Wert, nicht als Feld: Abrufe im Vordergrund können sich an `await` überlappen
        let btcEntries = AddressServer.btcEntries(btcServer)
        var updates: [Update] = []
        for a in AddressBalance.dueOrder(addresses, now: now, force: force, background: deadline != nil) {
            try Task.checkCancellation()
            let time = TimeUtils.nowMillis
            do {
                let fetched: Fetched
                if a.isXpub {
                    guard let result = try await fetchXpub(a, deadline: deadline, btcEntries: btcEntries) else { continue }
                    fetched = result
                } else if a.chain == .ETH {
                    fetched = try await fetchEvm(a, deadline: deadline)
                } else if a.chain == .TRX {
                    fetched = try await fetchTron(a, deadline: deadline)
                } else if a.chain == .SOL {
                    fetched = try await fetchSolana(a, deadline: deadline)
                } else if a.chain == .TON {
                    fetched = try await fetchTon(a, deadline: deadline)
                } else {
                    fetched = try await fetch(a.chain, address: a.address, deadline: deadline, btcEntries: btcEntries)
                }
                updates.append(Update(id: a.id, time: time, fetched: fetched, error: nil))
            } catch is CancellationError {
                throw CancellationError()
            } catch is OutOfTime {
                break
            } catch is XpubScan.TooMany {
                // Eigener Hinweis «Zu viele Adressen» statt «Aktualisierung fehlgeschlagen» (wiederholen hilft nicht)
                updates.append(Update(id: a.id, time: time, fetched: nil, error: XpubScan.tooManyMarker))
            } catch {
                HttpLog.add("Adresse \(WatchedAddressRules.shortAddress(a.address)): \(error.localizedDescription)")
                // Nur kurzer Code speichern (Fehlertexte enthalten Hosts/URLs, teils die Adresse); eigener Server: eigener Hinweis
                let ownServer = btcServer != nil && a.chain == .BTC
                updates.append(Update(id: a.id, time: time, fetched: nil,
                                      error: ownServer ? AddressServer.failedMarker
                                          : StoredError.sanitize(ConnectionErrors.describe(error))))
            }
        }
        return updates
    }

    /// Ein Guthaben (einzelne Adresse, ohne Token); wirft, wenn kein Host liefert.
    /// - Parameter deadline: Endzeit (ms) im Hintergrund, nil = ohne Grenze.
    /// - Parameter btcEntries: Bitcoin-Hosts (`AddressServer.btcEntries`): eigener Server allein oder die öffentlichen.
    func fetch(_ chain: AddressChain, address: String, deadline: Int64? = nil,
               btcEntries: [String] = AddressBalance.btcHosts) async throws -> Fetched {
        switch chain {
        case .BTC:
            let own = AddressServerRules.isOwnServer(btcEntries)
            let (balance, provider) = try await firstWorking(btcEntries, deadline: deadline, own: own) { (host: String) -> String in
                let body = try await Self.load(AddressServer.btcUrl(host, address: address), post: nil)
                return AddressBalance.btcText(try AddressBalance.parseBtc(body, address: address))
            }
            return Fetched(balance: balance, provider: provider)
        case .ETH:
            let (balance, provider) = try await firstWorking(AddressBalance.ethRpcs, deadline: deadline) { (rpc: String) -> String in
                let body = try await Self.load(rpc, post: AddressBalance.ethRequest(address))
                return AddressBalance.ethText(try AddressBalance.parseEthWei(body))
            }
            return Fetched(balance: balance, provider: provider)
        case .LTC:
            let (balance, provider) = try await firstWorking(ChainBalance.ltcHosts, deadline: deadline) { (host: String) -> String in
                let body = try await Self.load(ChainBalance.ltcUrl(host: host, address: address), post: nil)
                let stats = try AddressBalance.parseBtc(body, address: address, chain: .LTC)
                return AddressBalance.unitsText(String(stats.totalSats), decimals: AddressChain.LTC.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        case .XRP:
            let (balance, provider) = try await firstWorking(ChainBalance.xrpRpcs, deadline: deadline) { (rpc: String) -> String in
                let body = try await Self.load(rpc, post: ChainBalance.xrpRequest(address))
                return AddressBalance.unitsText(try ChainBalance.parseXrp(body), decimals: AddressChain.XRP.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        case .TON:
            let (balance, provider) = try await firstWorking(ChainBalance.tonHosts, deadline: deadline) { (host: String) -> String in
                let body = try await Self.load(ChainBalance.tonUrl(host: host, address: address), post: nil)
                return AddressBalance.unitsText(try ChainBalance.parseTon(body), decimals: AddressChain.TON.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        case .TRX:
            let (balance, provider) = try await firstWorking(ChainBalance.tronHosts, deadline: deadline) { (host: String) -> String in
                let body = try await Self.load(ChainBalance.tronAccountUrl(host), post: ChainBalance.tronAccountRequest(address))
                return AddressBalance.unitsText(try ChainBalance.parseTronAccount(body), decimals: AddressChain.TRX.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        case .ADA:
            let (balance, provider) = try await firstWorking(ChainBalance.adaHosts, deadline: deadline) { (host: String) -> String in
                let body = try await Self.load(ChainBalance.adaUrl(host), post: ChainBalance.adaRequest(address))
                return AddressBalance.unitsText(try ChainBalance.parseAda(body, address: address), decimals: AddressChain.ADA.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        case .SOL:
            let (balance, provider) = try await firstWorking(ChainBalance.solanaRpcs, deadline: deadline) { (rpc: String) -> String in
                let body = try await Self.load(rpc, post: ChainBalance.solanaBalanceRequest(1, address))
                return AddressBalance.unitsText(try ChainBalance.parseSolanaBalanceBody(body), decimals: AddressChain.SOL.decimals)
            }
            return Fetched(balance: balance, provider: provider)
        }
    }

    /// Speichern oder erfolgreicher Test des eigenen Servers: Sperre und Pause seines Hosts aufheben.
    func clearServerPauses(_ server: String) {
        let host = AddressServerRules.hostKey(server)
        backoff[host] = nil
        BlockedSources.unblock(host)
    }

    /// Erster Host, der liefert: Ergebnis und Anbieter-Name; wirft, wenn keiner liefert.
    /// - Parameter own: eigener Server (`AddressServerRules.isOwnServer`): nie 6 h sperren, nur die kurze Pause.
    func firstWorking<T: Sendable>(_ entries: [String], deadline: Int64?, own: Bool = false,
                                   _ load: @escaping @Sendable (String) async throws -> T) async throws -> (T, String) {
        var lastError: Error = AddressBalance.ParseError(message: "Alle Anbieter pausiert")
        var tried = false
        for entry in entries {
            let host = AddressServerRules.hostKey(entry)
            let now = TimeUtils.nowMillis
            if (!own && BlockedSources.isBlocked(host)) || ExchangeBackoff.isPaused(backoff[host], now: now) { continue }
            // Hintergrund: je Anfrage höchstens backgroundRequestMillis, nie über die Endzeit
            let limit = deadline.map { min(Self.backgroundRequestMillis, $0 - now) }
            if let limit, limit < Self.minRequestMillis {
                if tried { throw lastError }
                throw OutOfTime()
            }
            tried = true
            do {
                let result = try await Self.limited(limit) { try await load(entry) }
                backoff[host] = nil
                return (result, AddressBalance.providerName(host))
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if !own { BlockedSources.noteFailure(host, error) }
                let outcome: ExchangeBackoff.Outcome
                var retryAfterMillis: Int64?
                if let http = error as? HttpMarketError, ExchangeBackoff.isRateLimitCode(http.httpCode) {
                    outcome = .rateLimited
                    retryAfterMillis = http.retryAfterSeconds.map { $0 * 1000 }
                } else if (error as? URLError)?.code == .timedOut {
                    outcome = .timeout
                } else if (error as? URLError)?.code == .cancelled {
                    throw CancellationError()
                } else {
                    outcome = .other
                }
                backoff[host] = ExchangeBackoff.next(backoff[host], outcome: outcome, now: TimeUtils.nowMillis,
                                                     retryAfterMillis: retryAfterMillis,
                                                     banned: (error as? HttpMarketError)?.httpCode == 418)
                lastError = error
            }
        }
        throw lastError
    }

    /// `operation` mit Zeitgrenze (`millis`, nil = ohne): danach `URLError(.timedOut)` und Abbruch der Anfrage.
    private static func limited<T: Sendable>(_ millis: Int64?,
                                             _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        guard let millis else { return try await operation() }
        return try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(0, millis)) * 1_000_000)
                throw URLError(.timedOut)
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw URLError(.timedOut) }
            return first
        }
    }

    /// GET oder POST (JSON) über die eigene Sitzung; HTTP ≠ 2xx → `HttpMarketError` (mit «Retry-After»).
    static func load(_ url: String, post: String?) async throws -> String {
        try await load(url, post: post, session: session)
    }

    /// Wie oben mit wählbarer Sitzung (Tests: URLProtocol-Attrappe). Antwort höchstens `MarketHTTP.maxBodyBytes`
    /// (2 MB wie Android `callMarket` ohne Angabe), sonst `ResponseTooLargeError` — nie ungebremst in den Speicher.
    static func load(_ url: String, post: String?, session: URLSession) async throws -> String {
        guard let u = URL(string: url) else { throw AddressBalance.ParseError(message: "URL ungültig") }
        var request = URLRequest(url: u)
        if let post {
            request.httpMethod = "POST"
            request.httpBody = Data(post.utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await MarketHTTP.readLimited(request, session: session, limit: MarketHTTP.maxBodyBytes)
        let text = String(decoding: data, as: UTF8.self)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let retryAfter = ExchangeBackoff.parseRetryAfterSeconds(http.value(forHTTPHeaderField: "Retry-After"),
                                                                    now: TimeUtils.nowMillis)
            // Ohne Antworttext: er könnte die Adresse enthalten
            throw HttpMarketError(httpCode: http.statusCode, body: "", retryAfterSeconds: retryAfter)
        }
        return text
    }
}
