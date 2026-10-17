import Foundation

/// Lädt Guthaben beobachteter Adressen (`AddressBalance`: Hosts, Antworten, Abstand) — wie
/// `WatchedAddressSource.kt`. Aufruf aus `PortfolioModel.refreshWatchedAddresses` (Portfolio-Tab, Hintergrund).
///
/// Datenschutz: eigene flüchtige Sitzung (keine Cookies, kein Zwischenspeicher) und nicht über
/// `MarketHTTP.call`, damit die Adresse nicht im HTTP-Protokoll der App landet; Protokolle nennen nur
/// die gekürzte Adresse.
/// Pausen: 451/403 → Host 6 h übersprungen (`BlockedSources`); 429/418 und wiederholte
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
    }

    /// Ergebnis je Adresse: entweder `fetched` oder `error` (technischer Text).
    struct Update: Equatable, Sendable {
        let id: Int64
        let time: Int64
        let fetched: Fetched?
        let error: String?
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = AddressBalance.timeoutSeconds
        c.timeoutIntervalForResource = AddressBalance.timeoutSeconds + 5
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpCookieAcceptPolicy = .never
        c.httpShouldSetCookies = false
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
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
    /// xpub im Hintergrund (Zeitbudget, nur bekannte benutzte Adressen).
    static let xpubPauseBackgroundMillis: Int64 = 50

    /// Pausen je Host (nur im Speicher; ein Neustart beginnt frisch).
    private var backoff: [String: ExchangeBackoff.State] = [:]
    /// Laufende Abrufe (der Hintergrund wartet nicht auf einen laufenden — wie Android `tryLock`).
    private var running = 0

    /// Zeit um, bevor ein Host gefragt wurde (Adresse bleibt fällig, kein Fehler).
    private struct OutOfTime: Error {}

    /// Alle fälligen Adressen (`AddressBalance.isDue`) der Reihe nach laden.
    /// - Parameter force: manuelles Aktualisieren (kürzerer Abstand).
    /// - Parameter budgetMillis: Hintergrund: höchstens so lange insgesamt (je Anfrage höchstens
    ///   `backgroundRequestMillis`); läuft schon ein Abruf, wird nicht gewartet. Was nicht mehr drankommt,
    ///   bleibt fällig. nil = Vordergrund, ohne Grenze. xpub im Hintergrund: nur die bekannten benutzten Adressen und
    ///   nur ganz (sonst bleibt er fällig).
    func refresh(_ addresses: [WatchedAddress], force: Bool, now: Int64 = TimeUtils.nowMillis,
                 budgetMillis: Int64? = nil) async throws -> [Update] {
        let deadline = budgetMillis.map { TimeUtils.nowMillis + $0 }
        if deadline != nil && running > 0 { return [] }
        running += 1
        defer { running -= 1 }
        var updates: [Update] = []
        for a in addresses where AddressBalance.isDue(lastAttempt: a.lastAttempt, now: now, force: force) {
            try Task.checkCancellation()
            let time = TimeUtils.nowMillis
            do {
                let fetched: Fetched
                if a.isXpub {
                    guard let result = try await fetchXpub(a, deadline: deadline) else { continue }
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
                    fetched = try await fetch(a.chain, address: a.address, deadline: deadline)
                }
                updates.append(Update(id: a.id, time: time, fetched: fetched, error: nil))
            } catch is CancellationError {
                throw CancellationError()
            } catch is OutOfTime {
                break
            } catch {
                HttpLog.add("Adresse \(WatchedAddressRules.shortAddress(a.address)): \(error.localizedDescription)")
                // Nur kurzer Code speichern (Fehlertexte enthalten Hosts/URLs, teils die Adresse)
                updates.append(Update(id: a.id, time: time, fetched: nil,
                                      error: StoredError.sanitize(ConnectionErrors.describe(error))))
            }
        }
        return updates
    }

    /// Ein Guthaben (einzelne Adresse, ohne Token); wirft, wenn kein Host liefert.
    /// - Parameter deadline: Endzeit (ms) im Hintergrund, nil = ohne Grenze.
    func fetch(_ chain: AddressChain, address: String, deadline: Int64? = nil) async throws -> Fetched {
        switch chain {
        case .BTC:
            let (balance, provider) = try await firstWorking(AddressBalance.btcHosts, deadline: deadline) { (host: String) -> String in
                let body = try await Self.load(AddressBalance.btcUrl(host: host, address: address), post: nil)
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
                let stats = try AddressBalance.parseBtc(body, address: address)
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

    /// 0x-Adresse: Ethereum (mit «Tokens mitzählen» als Stapel mit ERC-20) und die gewählten weiteren EVM-Netze (vor der
    /// ersten Suche alle) — je Netz eine Stapel-Anfrage. Ein Netz, das nicht antwortet, bleibt wie zuletzt. Wie Android.
    private func fetchEvm(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        var result: Fetched
        if a.countTokens {
            result = try await fetchEthWithTokens(a.address, deadline: deadline)
        } else {
            result = try await fetch(.ETH, address: a.address, deadline: deadline)
        }
        var tokens = result.tokens ?? [:]
        var found: [String] = []
        let address = a.address
        for id in a.networks ?? ChainAssets.evm.map(\.id) {
            guard let net = ChainAssets.network(id) else { continue }
            let list = a.countTokens ? net.tokens : []
            do {
                let (batch, _) = try await firstWorking(net.rpcs, deadline: deadline) { (rpc: String) -> Erc20Tokens.Batch in
                    try Erc20Tokens.parseBatch(try await Self.load(rpc, post: Erc20Tokens.batchRequest(address, tokens: list)),
                                               tokens: list)
                }
                let native = AddressBalance.unitsText(batch.weiDigits, decimals: 18)
                tokens[ChainAssets.key(net.id, net.native)] = native
                for (symbol, text) in batch.tokens { tokens[ChainAssets.key(net.id, symbol)] = text }
                if Self.positive(native) || batch.tokens.values.contains(where: Self.positive) { found.append(net.id) }
            } catch is CancellationError {
                throw CancellationError()
            } catch is OutOfTime {
                break
            } catch {
                HttpLog.add("Netz \(net.id): \(error.localizedDescription)")
            }
        }
        if !tokens.isEmpty { result.tokens = tokens }
        if a.networks == nil { result.networks = found }
        return result
    }

    /// TON: Guthaben und (mit «Tokens mitzählen») Jettons über TON Center v3 — ohne Schlüssel höchstens 1 Anfrage/s, daher eine
    /// Pause dazwischen. Jettons ohne Antwort bleiben wie zuletzt. Wie Android.
    private func fetchTon(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        var ton = try await fetch(.TON, address: a.address, deadline: deadline)
        guard a.countTokens else { return ton }
        let address = a.address
        do {
            try await Task.sleep(nanoseconds: UInt64(ChainBalance.tonPauseMillis) * 1_000_000)
            let (tokens, _) = try await firstWorking(ChainBalance.tonHosts, deadline: deadline) { (host: String) -> [String: String] in
                try ChainBalance.parseTonJettons(try await Self.load(ChainBalance.tonJettonsUrl(host: host, address: address), post: nil))
            }
            ton.tokens = tokens
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            HttpLog.add("Jettons: \(error.localizedDescription)")
        }
        return ton
    }

    private static func positive(_ text: String) -> Bool { (PortfolioDecimal.parse(text) ?? 0) > 0 }

    /// TRON: TRX und (mit «Tokens mitzählen») je TRC-20 eine Abfrage; ein Token ohne Antwort bleibt wie zuletzt.
    private func fetchTron(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        var trx = try await fetch(.TRX, address: a.address, deadline: deadline)
        guard a.countTokens else { return trx }
        var tokens: [String: String] = [:]
        let address = a.address
        for token in ChainAssets.tronTokens {
            do {
                let request = try ChainBalance.tronTokenRequest(address, token)
                let (digits, _) = try await firstWorking(ChainBalance.tronHosts, deadline: deadline) { (host: String) -> String in
                    try ChainBalance.parseTronCall(try await Self.load(ChainBalance.tronCallUrl(host), post: request))
                }
                tokens[token.symbol] = AddressBalance.unitsText(digits, decimals: token.decimals)
            } catch is CancellationError {
                throw CancellationError()
            } catch is OutOfTime {
                break
            } catch {
                HttpLog.add("TRC-20 \(token.symbol): \(error.localizedDescription)")
            }
        }
        trx.tokens = tokens
        return trx
    }

    /// Solana: SOL und (mit «Tokens mitzählen») Token-Konten beider Token-Programme in einer Stapel-Anfrage; ohne Stapel drei
    /// Einzelanfragen. Unbekannte Mints zählen nicht. Wie Android.
    private func fetchSolana(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        guard a.countTokens else { return try await fetch(.SOL, address: a.address, deadline: deadline) }
        let address = a.address
        let loaded: (ChainBalance.Solana, String)
        do {
            loaded = try await firstWorking(ChainBalance.solanaRpcs, deadline: deadline) { (rpc: String) -> ChainBalance.Solana in
                try ChainBalance.parseSolanaBatch(try await Self.load(rpc, post: ChainBalance.solanaBatch(address)))
            }
        } catch is Erc20Tokens.BatchUnsupported {
            loaded = try await firstWorking(ChainBalance.solanaRpcs, deadline: deadline) { (rpc: String) -> ChainBalance.Solana in
                let singles = ChainBalance.solanaSingles(address)
                let lamports = try ChainBalance.parseSolanaBalanceBody(try await Self.load(rpc, post: singles[0]))
                let legacy = try ChainBalance.parseSolanaTokensBody(try await Self.load(rpc, post: singles[1]))
                let t2022 = try ChainBalance.parseSolanaTokensBody(try await Self.load(rpc, post: singles[2]))
                return ChainBalance.solana(lamports: lamports, mints: [legacy, t2022])
            }
        }
        let (result, provider) = loaded
        return Fetched(balance: AddressBalance.unitsText(result.lamports, decimals: AddressChain.SOL.decimals),
                       provider: provider, tokens: result.tokens, unknownTokens: result.unknown)
    }

    /// ETH + ERC-20 in einer Stapel-Anfrage (`Erc20Tokens.batchRequest`). Kann kein Knoten Stapel: ETH allein und (nur im
    /// Vordergrund) die Token einzeln; scheitert der Stapel sonst: ETH allein, Token bleiben wie zuletzt. Wie Android.
    private func fetchEthWithTokens(_ address: String, deadline: Int64?) async throws -> Fetched {
        do {
            let (batch, provider) = try await firstWorking(AddressBalance.ethRpcs, deadline: deadline) { (rpc: String) -> Erc20Tokens.Batch in
                try Erc20Tokens.parseBatch(try await Self.load(rpc, post: Erc20Tokens.batchRequest(address)))
            }
            return Fetched(balance: AddressBalance.ethText(batch.weiDigits), provider: provider, tokens: batch.tokens)
        } catch is CancellationError {
            throw CancellationError()
        } catch is OutOfTime {
            throw OutOfTime()
        } catch {
            var eth = try await fetch(.ETH, address: address, deadline: deadline)
            guard error is Erc20Tokens.BatchUnsupported, deadline == nil else { return eth }
            var tokens: [String: String] = [:]
            for token in Erc20Tokens.list {
                try Task.checkCancellation()
                do {
                    let (text, _) = try await firstWorking(AddressBalance.ethRpcs, deadline: nil) { (rpc: String) -> String in
                        try Erc20Tokens.parseSingle(try await Self.load(rpc, post: Erc20Tokens.singleRequest(address, token)), token)
                    }
                    tokens[token.symbol] = text
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    // Dieses Token bleibt wie zuletzt
                }
            }
            eth.tokens = tokens
            return eth
        }
    }

    /// xpub/ypub/zpub: Adressen auf dem Gerät ableiten (`XpubDeriver`) und einzeln wie eine Adresse fragen (`XpubScan`:
    /// Gap-Regel, vollständig höchstens täglich). Hintergrund ohne Zwischenspeicher: nil (bleibt fällig). Zwischen zwei
    /// Anfragen eine kurze Pause (schont die Anbieter). xpub ohne bekannten Adresstyp (`XpubScan.scripts`): vollständig
    /// als P2PKH, ohne Verlauf als P2SH-P2WPKH, dann P2WPKH (Segwit-Konto mit xpub, Ledger Live); der erste Typ mit
    /// Verlauf wird gemerkt (`XpubCache.script`), im Hintergrund wird nicht gesucht. Wie Android.
    private func fetchXpub(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched? {
        guard let key = Bip32.parse(a.address) else { throw AddressBalance.ParseError(message: "xpub ungültig") }
        let isXpub = key.version == Bip32.xpubVersion
        // Zwischenspeicher nur, wenn er zu diesem Schlüssel gehört (erste Adresse im gemerkten Typ stimmt)
        var stored = a.xpub
        if let c = stored {
            guard let deriver = XpubDeriver(a.address, script: isXpub ? c.script : nil) else {
                throw AddressBalance.ParseError(message: "xpub ungültig")
            }
            if !(c.receive.isEmpty || c.receive[0] == deriver.address(chain: 0, index: 0))
                || !(c.change.isEmpty || c.change[0] == deriver.address(chain: 1, index: 0)) {
                stored = nil
            }
        }
        let storedScript: Bip32.Script? = stored.map { c in (isXpub ? c.script : nil) ?? key.script }
        let scripts = XpubScan.scripts(key, cache: stored)
        var tried: [(script: Bip32.Script, used: Int)] = []
        var results: [Fetched] = []
        for script in scripts {
            let cache: XpubCache? = storedScript == script ? stored : nil
            let mode: XpubScan.Mode
            if scripts.count > 1 {
                if deadline != nil { return nil }
                mode = .FULL
            } else {
                guard let m = XpubScan.mode(for: cache, now: TimeUtils.nowMillis, background: deadline != nil) else { return nil }
                mode = m
            }
            guard let deriver = XpubDeriver(a.address, script: script) else {
                throw AddressBalance.ParseError(message: "xpub ungültig")
            }
            let (used, fetched) = try await scanXpub(deriver, cache: cache, mode: mode, deadline: deadline)
            tried.append((script: script, used: used))
            results.append(fetched)
            if used > 0 { break }
        }
        let pick = XpubScan.pickScript(key, tried: tried)
        var chosen = results[max(0, tried.firstIndex(where: { $0.script == pick }) ?? 0)]
        chosen.xpub?.script = pick
        return chosen
    }

    /// Eine Suche über die Adressen eines Adresstyps (`deriver`): benutzte Adressen und Ergebnis.
    private func scanXpub(_ deriver: XpubDeriver, cache: XpubCache?, mode: XpubScan.Mode,
                          deadline: Int64?) async throws -> (used: Int, fetched: Fetched) {
        var lists = [cache?.receive ?? [], cache?.change ?? []]
        let scan = XpubScan(mode: mode, cache: cache)
        var provider = AddressBalance.providerName(AddressBalance.btcHosts[0])
        while let target = try scan.next() {
            try Task.checkCancellation()
            while lists[target.chain].count <= target.index {
                guard let derived = deriver.address(chain: target.chain, index: lists[target.chain].count) else {
                    throw AddressBalance.ParseError(message: "Ableitung ungültig")
                }
                lists[target.chain].append(derived)
            }
            let address = lists[target.chain][target.index]
            if scan.requests > 1 {
                let pause = deadline == nil ? Self.xpubPauseMillis : Self.xpubPauseBackgroundMillis
                try await Task.sleep(nanoseconds: UInt64(pause) * 1_000_000)
            }
            let (stats, host) = try await firstWorking(AddressBalance.btcHosts, deadline: deadline) { (h: String) -> AddressBalance.BtcStats in
                try AddressBalance.parseBtc(try await Self.load(AddressBalance.btcUrl(host: h, address: address), post: nil),
                                            address: address)
            }
            provider = host
            scan.report(target, txCount: stats.txCount, sats: stats.totalSats)
        }
        let balance = AddressBalance.unitsText(String(scan.totalSats), decimals: AddressChain.BTC.decimals)
        return (used: scan.usedCount,
                fetched: Fetched(balance: balance, provider: provider,
                                 xpub: scan.cache(receive: lists[0], change: lists[1], now: TimeUtils.nowMillis)))
    }

    private static func host(of entry: String) -> String {
        var host = entry.hasPrefix("https://") ? String(entry.dropFirst(8)) : entry
        if let slash = host.firstIndex(of: "/") { host = String(host[..<slash]) }
        if let colon = host.firstIndex(of: ":") { host = String(host[..<colon]) }
        return host
    }

    /// Erster Host, der liefert: Ergebnis und Anbieter-Name; wirft, wenn keiner liefert.
    private func firstWorking<T: Sendable>(_ entries: [String], deadline: Int64?,
                                           _ load: @escaping @Sendable (String) async throws -> T) async throws -> (T, String) {
        var lastError: Error = AddressBalance.ParseError(message: "Alle Anbieter pausiert")
        var tried = false
        for entry in entries {
            let host = Self.host(of: entry)
            let now = TimeUtils.nowMillis
            if BlockedSources.isBlocked(host) || ExchangeBackoff.isPaused(backoff[host], now: now) { continue }
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
                BlockedSources.noteFailure(host, error)
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
    private static func load(_ url: String, post: String?) async throws -> String {
        guard let u = URL(string: url) else { throw AddressBalance.ParseError(message: "URL ungültig") }
        var request = URLRequest(url: u)
        if let post {
            request.httpMethod = "POST"
            request.httpBody = Data(post.utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
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
