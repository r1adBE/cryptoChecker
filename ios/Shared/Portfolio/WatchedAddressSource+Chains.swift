import Foundation

// Abfragen je Kette (Teil von `WatchedAddressSource`): EVM-Netze samt ERC-20, TON, Tron, Solana und xpub-Konten
// (Adressen ableiten und einzeln fragen) — wie `WatchedAddressSource.kt` (Android).

extension WatchedAddressSource {
    /// 0x-Adresse: Ethereum (mit «Tokens mitzählen» als Stapel mit ERC-20) und die gewählten weiteren EVM-Netze (vor der
    /// ersten Suche alle) — je Netz eine Stapel-Anfrage. Ein Netz, das nicht antwortet, bleibt wie zuletzt. Die erste Wahl
    /// der Netze nur im Vordergrund, stumme Netze bleiben darin (`WatchedAddressRules.discoveredNetworks`). Wie Android.
    func fetchEvm(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        var result: Fetched
        if a.countTokens {
            result = try await fetchEthWithTokens(a.address, deadline: deadline)
        } else {
            result = try await fetch(.ETH, address: a.address, deadline: deadline)
        }
        var tokens = result.tokens ?? [:]
        var found: [String] = []
        var unanswered: [String] = []
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
                unanswered.append(net.id)
            }
        }
        if !tokens.isEmpty { result.tokens = tokens }
        if a.networks == nil {
            result.networks = WatchedAddressRules.discoveredNetworks(found: found, unanswered: unanswered,
                                                                    background: deadline != nil)
        }
        return result
    }

    /// TON: Guthaben und (mit «Tokens mitzählen») Jettons über TON Center v3 — ohne Schlüssel höchstens 1 Anfrage/s, daher eine
    /// Pause dazwischen. Jettons ohne Antwort bleiben wie zuletzt. Wie Android.
    func fetchTon(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
        var ton = try await fetch(.TON, address: a.address, deadline: deadline)
        guard a.countTokens else { return ton }
        let address = a.address
        do {
            try await Task.sleep(nanoseconds: UInt64(ChainBalance.tonPauseMillis) * 1_000_000)
            let (page, _) = try await firstWorking(ChainBalance.tonHosts, deadline: deadline) { (host: String) -> ChainBalance.TonJettonPage in
                try ChainBalance.parseTonJettonPage(try await Self.load(ChainBalance.tonJettonsUrl(host: host, address: address), post: nil))
            }
            // Auf voller Seite fehlende bekannte Jettons: alter Wert bleibt, gilt aber als nicht bestätigt
            ton.tokens = page.tokens
            ton.keptTokens = page.kept
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            HttpLog.add("Jettons: \(error.localizedDescription)")
        }
        return ton
    }

    private static func positive(_ text: String) -> Bool { (PortfolioDecimal.parse(text) ?? 0) > 0 }

    /// TRON: TRX und (mit «Tokens mitzählen») je TRC-20 eine Abfrage; ein Token ohne Antwort bleibt wie zuletzt.
    func fetchTron(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
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
    func fetchSolana(_ a: WatchedAddress, deadline: Int64?) async throws -> Fetched {
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

    /// xpub/ypub/zpub/Taproot-Konto: Adressen auf dem Gerät ableiten (`XpubDeriver`) und einzeln wie eine Adresse fragen (`XpubScan`:
    /// Gap-Regel, vollständig höchstens täglich). Hintergrund ohne Zwischenspeicher: nil (bleibt fällig). Zwischen zwei
    /// Anfragen eine kurze Pause (schont die Anbieter). xpub ohne bekannten Adresstyp (`XpubScan.scripts`): vollständig
    /// als P2PKH, ohne Verlauf als P2SH-P2WPKH, dann P2WPKH (Segwit-Konto mit xpub, Ledger Live); der erste Typ mit
    /// Verlauf wird gemerkt (`XpubCache.script`), im Hintergrund wird nicht gesucht. Hintergrund unvollständig und tiefer
    /// als zuletzt (`XpubScan.keepBackground`): nil (bleibt fällig). Vollständige Suche zu gross (`XpubScan.TooMany`) mit
    /// Zwischenspeicher: einmal schnell wiederholen statt Fehler; dieser Rückfall und der von `XpubScan.mode(for:)` setzen
    /// `XpubCache.limitedFallback`. Ohne Zwischenspeicher oder wenn auch die schnelle zu gross ist: Fehler. Wie Android.
    func fetchXpub(_ a: WatchedAddress, deadline: Int64?, btcEntries: [String]) async throws -> Fetched? {
        guard let key = Bip32.parse(a.address) else { throw AddressBalance.ParseError(message: "xpub ungültig") }
        let isXpub = key.detectsScript
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
        var keeps: [Bool] = []
        for script in scripts {
            let cache: XpubCache? = storedScript == script ? stored : nil
            let now = TimeUtils.nowMillis
            let mode: XpubScan.Mode
            if scripts.count > 1 {
                if deadline != nil { return nil }
                mode = .FULL
            } else {
                guard let m = XpubScan.mode(for: cache, now: now, background: deadline != nil) else { return nil }
                mode = m
            }
            // Fällige vollständige Suche schon vorab durch die schnelle ersetzt (sicher zu gross)
            var limited = deadline == nil && mode == .QUICK && XpubScan.fullDue(cache, now: now)
            var retried = false
            guard let deriver = XpubDeriver(a.address, script: script) else {
                throw AddressBalance.ParseError(message: "xpub ungültig")
            }
            let scan: (used: Int, fetched: Fetched, complete: Bool, sats: Int64)
            do {
                scan = try await scanXpub(deriver, cache: cache, mode: mode, deadline: deadline, entries: btcEntries)
            } catch let error as XpubScan.TooMany {
                // Vollständige Suche doch zu gross: mit Zwischenspeicher einmal schnell (wirft sie auch TooMany: Fehler)
                guard mode == .FULL, let cache, cache.fullScan > 0 else { throw error }
                limited = true
                retried = true
                scan = try await scanXpub(deriver, cache: cache, mode: .QUICK, deadline: deadline, entries: btcEntries)
            }
            var fetched = scan.fetched
            if limited { fetched.xpub?.limitedFallback = true }
            // Nach einer gescheiterten vollständigen Suche diese erst wieder nach einem Tag versuchen (wie Android)
            if retried { fetched.xpub?.fullScan = now }
            tried.append((script: script, used: scan.used))
            results.append(fetched)
            keeps.append(XpubScan.keepBackground(complete: scan.complete, sats: scan.sats, lastBalance: a.lastBalance))
            if scan.used > 0 { break }
        }
        let pick = XpubScan.pickScript(key, tried: tried)
        let index = max(0, tried.firstIndex(where: { $0.script == pick }) ?? 0)
        // Hintergrund mit möglicherweise übersehener neuer Adresse und tieferer Summe: nicht speichern (keine Beobachtung,
        // kein Wert für Alarme) — bleibt fällig für die Suche im Vordergrund
        if deadline != nil && !keeps[index] { return nil }
        var chosen = results[index]
        chosen.xpub?.script = pick
        return chosen
    }

    /// Eine Suche über die Adressen eines Adresstyps (`deriver`): benutzte Adressen, Ergebnis, vollständig
    /// (`XpubScan.complete`) und Summe in Satoshi. `entries`: eigener Server oder die öffentlichen Hosts, für die ganze
    /// Suche gleich.
    private func scanXpub(_ deriver: XpubDeriver, cache: XpubCache?, mode: XpubScan.Mode, deadline: Int64?,
                          entries: [String]) async throws -> (used: Int, fetched: Fetched, complete: Bool, sats: Int64) {
        var lists = [cache?.receive ?? [], cache?.change ?? []]
        let scan = XpubScan(mode: mode, cache: cache)
        let own = AddressServerRules.isOwnServer(entries)
        var provider = AddressBalance.providerName(ChainBalance.host(entries[0]))
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
            let (stats, host) = try await firstWorking(entries, deadline: deadline, own: own) { (h: String) -> AddressBalance.BtcStats in
                try AddressBalance.parseBtc(try await Self.load(AddressServer.btcUrl(h, address: address), post: nil),
                                            address: address)
            }
            provider = host
            scan.report(target, txCount: stats.txCount, sats: stats.totalSats)
        }
        let balance = AddressBalance.unitsText(String(scan.totalSats), decimals: AddressChain.BTC.decimals)
        return (used: scan.usedCount,
                fetched: Fetched(balance: balance, provider: provider,
                                 xpub: scan.cache(receive: lists[0], change: lists[1], now: TimeUtils.nowMillis)),
                complete: scan.complete, sats: scan.totalSats)
    }
}
