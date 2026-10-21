import XCTest
@testable import CryptoChecker

/// ERC-20, xpub (BIP32/49/84) und Gap-Suche aus `address_balance.json` (Android: `XpubTokenParityTest.kt`).
final class XpubTokenTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("address_balance") }

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    private func unhex(_ s: String) -> [UInt8] {
        let chars = Array(s)
        return stride(from: 0, to: chars.count, by: 2).map { (i: Int) -> UInt8 in UInt8(String(chars[i...i + 1]), radix: 16) ?? 0 }
    }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    private func obs(_ raw: Any?) -> [AddressObservation] {
        S.list(raw).compactMap { o in
            PortfolioDecimal.parse(o["b"] as? String).map { AddressObservation(time: int64(o["t"]), balance: $0) }
        }
    }

    private func obsText(_ list: [AddressObservation]) -> [String] {
        list.map { "\($0.time)=\(PortfolioDecimal.text($0.balance))" }
    }

    private func tokenObs(_ raw: Any?) -> [String: [AddressObservation]] {
        (raw as? [String: Any] ?? [:]).mapValues { obs($0) }
    }

    private func tokenObsText(_ map: [String: [AddressObservation]]) -> [String: [String]] { map.mapValues { obsText($0) } }

    func testRipemd160() throws {
        let d = try data()
        for c in S.list(d["ripemd160"]) {
            let text = String(repeating: c["text"] as? String ?? "", count: int(c["repeat"]))
            XCTAssertEqual(hex(Bip32.ripemd160(Array(text.utf8))), c["hex"] as? String, "ripemd: \(c)")
        }
        let h = try XCTUnwrap((d["bip32"] as? [String: Any])?["hash160"] as? [String: Any])
        let keyhash = Bip32.hash160(unhex(h["pubkey"] as? String ?? ""))
        XCTAssertEqual(hex(keyhash), h["keyhash"] as? String)
        XCTAssertEqual(hex(Bip32.hash160([0x00, 0x14] + keyhash)), h["scriptHash"] as? String)
    }

    func testBip32Children() throws {
        let b = try XCTUnwrap(try data()["bip32"] as? [String: Any])
        for c in S.list(b["children"]) {
            let parent = try XCTUnwrap(Bip32.parse(c["parent"] as? String ?? ""))
            let child = try XCTUnwrap(Bip32.child(parent, index: int64(c["index"])))
            XCTAssertEqual(Bip32.serialize(child), c["child"] as? String, "child \(c["index"] ?? "")")
            XCTAssertEqual(Bip32.serialize(parent), c["parent"] as? String)
        }
        let hardened = try XCTUnwrap(b["hardened"] as? [String: Any])
        let key = try XCTUnwrap(Bip32.parse(hardened["parent"] as? String ?? ""))
        XCTAssertNil(Bip32.child(key, index: int64(hardened["index"])))
    }

    func testBip32Addresses() throws {
        let b = try XCTUnwrap(try data()["bip32"] as? [String: Any])
        for c in S.list(b["addresses"]) {
            let note = c["note"] as? String ?? ""
            let key = try XCTUnwrap(Bip32.parse(c["key"] as? String ?? ""))
            let chainKey = try XCTUnwrap(Bip32.child(key, index: int64(c["chain"])))
            let leaf = try XCTUnwrap(Bip32.child(chainKey, index: int64(c["index"])))
            XCTAssertEqual(hex(leaf.publicKey), c["pubkey"] as? String, "pubkey \(note)")
            XCTAssertEqual(Bip32.address(key.script, publicKey: leaf.publicKey), c["address"] as? String, "address \(note)")
            let deriver = try XCTUnwrap(XpubDeriver(c["key"] as? String ?? ""))
            XCTAssertEqual(deriver.address(chain: int(c["chain"]), index: int(c["index"])), c["address"] as? String)
            XCTAssertTrue(AddressValidation.check(c["address"] as? String).isValid)
        }
    }

    func testTokenList() throws {
        let t = try XCTUnwrap(try data()["tokens"] as? [String: Any])
        let expected = S.list(t["list"]).map {
            Erc20Tokens.Token(symbol: $0["symbol"] as? String ?? "", contract: $0["contract"] as? String ?? "",
                              decimals: int($0["decimals"]), coin: $0["coin"] as? String)
        }
        XCTAssertEqual(Erc20Tokens.list, expected)
        // Funktionsauswahl = keccak256("balanceOf(address)")[0..4]
        let signature = t["signature"] as? String ?? ""
        XCTAssertEqual("0x" + String(Keccak256.hex(Array(signature.utf8)).prefix(8)), t["selector"] as? String)
        XCTAssertEqual(Erc20Tokens.balanceOf, t["selector"] as? String)
        let batch = try XCTUnwrap(t["batchRequest"] as? [String: Any])
        XCTAssertEqual(Erc20Tokens.batchRequest(batch["address"] as? String ?? ""), batch["body"] as? String)
        let single = try XCTUnwrap(t["singleRequest"] as? [String: Any])
        let token = try XCTUnwrap(Erc20Tokens.bySymbol(single["symbol"] as? String ?? ""))
        XCTAssertEqual(Erc20Tokens.singleRequest(single["address"] as? String ?? "", token), single["body"] as? String)
        for (symbol, id) in t["tradeIds"] as? [String: Any] ?? [:] {
            XCTAssertEqual(AddressBalance.tokenTradeId(symbol), int64(id), symbol)
        }
    }

    func testTokenResponses() throws {
        let d = try data()
        let t = try XCTUnwrap(d["tokens"] as? [String: Any])
        for c in S.list(t["batch"]) {
            let body = c["body"] as? String ?? ""
            let name = c["name"] as? String ?? ""
            if c["unsupported"] as? Bool == true {
                XCTAssertThrowsError(try Erc20Tokens.parseBatch(body), name) { XCTAssertTrue($0 is Erc20Tokens.BatchUnsupported) }
            } else if c["error"] as? Bool == true {
                XCTAssertThrowsError(try Erc20Tokens.parseBatch(body), name) { XCTAssertFalse($0 is Erc20Tokens.BatchUnsupported) }
            } else {
                let parsed = try Erc20Tokens.parseBatch(body)
                XCTAssertEqual(parsed.weiDigits, c["wei"] as? String, name)
                XCTAssertEqual(parsed.tokens, c["tokens"] as? [String: String], name)
            }
        }
        for c in S.list(t["single"]) {
            let token = try XCTUnwrap(Erc20Tokens.bySymbol(c["symbol"] as? String ?? ""))
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try Erc20Tokens.parseSingle(body, token), body)
            } else {
                XCTAssertEqual(try Erc20Tokens.parseSingle(body, token), c["text"] as? String)
            }
        }
        for c in S.list(d["btcTxCount"]) {
            let stats = try AddressBalance.parseBtc(c["body"] as? String ?? "", address: c["address"] as? String ?? "")
            XCTAssertEqual(stats.txCount, int(c["txCount"]))
            XCTAssertEqual(stats.totalSats, int64(c["totalSats"]))
        }
    }

    private func xpubCache(_ m: [String: Any]?) -> XpubCache? {
        guard let m else { return nil }
        return XpubCache(receive: m["receive"] as? [String] ?? [], change: m["change"] as? [String] ?? [],
                         usedReceive: (m["usedReceive"] as? [Any] ?? []).map { int($0) },
                         usedChange: (m["usedChange"] as? [Any] ?? []).map { int($0) },
                         fullScan: int64(m["fullScan"]))
    }

    private func watched(_ m: [String: Any]) -> WatchedAddress {
        WatchedAddress(chain: AddressChain(rawValue: m["chain"] as? String ?? "") ?? .BTC,
                       address: m["address"] as? String ?? "",
                       lastBalance: m["lastBalance"] as? String,
                       tokenBalances: m["tokenBalances"] as? [String: String] ?? [:],
                       tokenObservations: tokenObs(m["tokenObservations"]),
                       countTokens: m["countTokens"] as? Bool ?? true,
                       xpub: xpubCache(m["xpub"] as? [String: Any]))
    }

    func testTokenHoldings() throws {
        let h = try XCTUnwrap(try data()["tokenHoldings"] as? [String: Any])
        let list = S.list(h["addresses"]).map(watched)
        let expected = (h["expected"] as? [[String]] ?? []).map { "\($0[0])=\($0[1])" }
        XCTAssertEqual(AddressBalance.holdings(list).map { "\($0.coin)=\($0.amount)" }, expected)
        XCTAssertEqual(AddressBalance.amount(for: "usdt", addresses: list).map { PortfolioDecimal.text($0) }, "100.5")
        let trades = AddressBalance.trades(list, realTrades: [], todayUtcDay: 20_000)
        XCTAssertEqual(trades.map(\.coin), (h["expected"] as? [[String]] ?? []).map { $0[0] })
        XCTAssertEqual(trades.first(where: { $0.coin == "USDT" })?.id, -101)
        XCTAssertTrue(trades.allSatisfy { AddressBalance.isAddressTrade($0.id) })
        XCTAssertEqual(Set(trades.map(\.id)).count, trades.count)
    }

    func testApplyFetched() throws {
        let f = try XCTUnwrap(try data()["applyFetched"] as? [String: Any])
        var a = watched(try XCTUnwrap(f["start"] as? [String: Any]))
        for (i, s) in S.list(f["steps"]).enumerated() {
            a = WatchedAddressRules.applyFetched(a, time: int64(s["time"]), balance: s["balance"] as? String ?? "",
                                                 provider: "publicnode.com", tokens: s["tokens"] as? [String: String])
            XCTAssertEqual(a.tokenBalances, s["tokenBalances"] as? [String: String], "tokenBalances \(i)")
            XCTAssertEqual(tokenObsText(a.tokenObservations), tokenObsText(tokenObs(s["tokenObservations"])), "tokenObservations \(i)")
            XCTAssertEqual(obsText(a.observations), obsText(obs(s["observations"])), "observations \(i)")
            XCTAssertEqual(a.lastUpdated, int64(s["time"]))
            // Speicherform (watched_addresses.json) hin und zurück
            let decoded = try JSONDecoder().decode(WatchedAddress.self, from: JSONEncoder().encode(a))
            XCTAssertEqual(decoded, a)
            if i == 1 {
                let history = AddressBalanceHistory.trades(AddressBalanceHistory.of([a])).filter { $0.coin == "USDT" || $0.coin == "LINK" }
                let expected = S.list(f["history"])
                XCTAssertEqual(history.count, expected.count)
                for (e, t) in zip(expected, history) {
                    XCTAssertEqual(t.coin, e["coin"] as? String)
                    XCTAssertEqual(t.type.rawValue, e["type"] as? String)
                    XCTAssertEqual(PortfolioDecimal.text(t.amountExact), e["amount"] as? String)
                    XCTAssertEqual(t.time, int64(e["time"]))
                }
            }
        }
        // «Tokens mitzählen» aus: Token weg, keine Reihen; wieder an: sofort fällig
        var before = a
        before.lastAttempt = 5
        let off = WatchedAddressRules.withCountTokens(before, false)
        XCTAssertTrue(off.tokenBalances.isEmpty)
        XCTAssertTrue(off.tokenObservations.isEmpty)
        XCTAssertTrue(AddressBalanceHistory.of([off]).allSatisfy { $0.token == nil })
        XCTAssertTrue(WatchedAddressRules.applyFetched(off, time: 99, balance: "1", provider: nil, tokens: ["USDT": "1"])
            .tokenBalances.isEmpty)
        XCTAssertEqual(try JSONDecoder().decode(WatchedAddress.self, from: JSONEncoder().encode(off)).countTokens, false)
        XCTAssertEqual(WatchedAddressRules.withCountTokens(off, true).lastAttempt, 0)
        // Ältere Datei ohne Zusatzfelder: Standardwerte
        let old = Data(#"{"id":1,"chain":"ETH","address":"0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"}"#.utf8)
        let legacy = try JSONDecoder().decode(WatchedAddress.self, from: old)
        XCTAssertTrue(legacy.countTokens)
        XCTAssertTrue(legacy.tokenBalances.isEmpty)
        XCTAssertNil(legacy.xpub)
    }

    private func scanCache(_ m: [String: Any]?) -> XpubCache? {
        guard let m else { return nil }
        return XpubCache(receive: (0..<int(m["receive"])).map { "r\($0)" }, change: (0..<int(m["change"])).map { "c\($0)" },
                         usedReceive: (m["usedReceive"] as? [Any] ?? []).map { int($0) },
                         usedChange: (m["usedChange"] as? [Any] ?? []).map { int($0) },
                         fullScan: int64(m["fullScan"]))
    }

    func testGapScan() throws {
        let g = try XCTUnwrap(try data()["gapScan"] as? [String: Any])
        XCTAssertEqual(XpubScan.gap, int(g["gap"]))
        XCTAssertEqual(XpubScan.lookahead, int(g["lookahead"]))
        XCTAssertEqual(XpubScan.maxRequests, int(g["maxRequests"]))
        XCTAssertEqual(XpubScan.fullScanMillis, int64(g["fullScanMillis"]))
        for c in S.list(g["cases"]) {
            let name = c["name"] as? String ?? ""
            var used: [Int: [Int: Int64]] = [:]
            for (chain, raw) in c["used"] as? [String: Any] ?? [:] {
                var m: [Int: Int64] = [:]
                for pair in raw as? [[Any]] ?? [] { m[int(pair[0])] = int64(pair[1]) }
                used[Int(chain) ?? 0] = m
            }
            let mode = try XCTUnwrap(XpubScan.Mode(rawValue: c["mode"] as? String ?? ""))
            let oldCache = scanCache(c["cache"] as? [String: Any])
            let scan = XpubScan(mode: mode, cache: oldCache)
            let e = try XCTUnwrap(c["expected"] as? [String: Any])
            var maxIndex = [-1, -1]
            do {
                while let t = try scan.next() {
                    maxIndex[t.chain] = max(maxIndex[t.chain], t.index)
                    let sats = used[t.chain]?[t.index]
                    scan.report(t, txCount: sats != nil ? 1 : 0, sats: sats ?? 0)
                }
                XCTAssertNotEqual(e["tooMany"] as? Bool, true, "zu viele erwartet: \(name)")
            } catch is XpubScan.TooMany {
                XCTAssertEqual(e["tooMany"] as? Bool, true, name)
                continue
            }
            XCTAssertEqual(scan.requests, int(e["requests"]), "requests \(name)")
            XCTAssertEqual(scan.totalSats, int64(e["totalSats"]), "sats \(name)")
            XCTAssertEqual(maxIndex[0], int(e["maxReceive"]), "maxReceive \(name)")
            XCTAssertEqual(maxIndex[1], int(e["maxChange"]), "maxChange \(name)")
            let cache = scan.cache(receive: (0...max(maxIndex[0], 0)).map { "r\($0)" },
                                   change: (0...max(maxIndex[1], 0)).map { "c\($0)" }, now: 777)
            XCTAssertEqual(cache.usedReceive, (e["usedReceive"] as? [Any] ?? []).map { int($0) }, "usedReceive \(name)")
            XCTAssertEqual(cache.usedChange, (e["usedChange"] as? [Any] ?? []).map { int($0) }, "usedChange \(name)")
            XCTAssertEqual(cache.fullScan, mode == .FULL ? Int64(777) : (oldCache?.fullScan ?? 0))
            XCTAssertEqual(scan.complete, e["complete"] as? Bool ?? true, "complete \(name)")
        }
        // Seit Build 94: Hintergrund mit Vorausblick; unvollständig und tiefer als zuletzt = nicht übernehmen
        XCTAssertEqual(XpubScan.backgroundLookahead, int(g["backgroundLookahead"]))
        for c in S.list(g["keepBackground"]) {
            XCTAssertEqual(XpubScan.keepBackground(complete: c["complete"] as? Bool ?? false, sats: int64(c["sats"]),
                                                   lastBalance: c["lastBalance"] as? String),
                           c["keep"] as? Bool, "keepBackground \(c)")
        }
        for c in S.list(g["modeFor"]) {
            let cache = (c["cache"] as? [String: Any]).map { XpubCache(fullScan: int64($0["fullScan"])) }
            let mode = XpubScan.mode(for: cache, now: int64(c["now"]), background: c["background"] as? Bool ?? false)
            XCTAssertEqual(mode?.rawValue, c["mode"] as? String, "modeFor \(c)")
        }
        // Zwischenspeicher hin und zurück (Kurzform wie Android); unpassende Indizes fallen weg
        let cache = XpubCache(receive: ["a", "b"], change: ["c"], usedReceive: [1], usedChange: [0], fullScan: 42)
        XCTAssertEqual(try JSONDecoder().decode(XpubCache.self, from: JSONEncoder().encode(cache)), cache)
        let raw = Data(#"{"r":["a","b"],"ru":[1,5,-1,1]}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(XpubCache.self, from: raw).usedReceive, [1])
    }

    /// xpub eines Segwit-Kontos (Ledger Live): dieselben Schlüssel wie die BIP84-zpub bzw. BIP49-ypub der Testvektoren, nur
    /// mit xpub-Versionsbytes — als P2WPKH/P2SH-P2WPKH abgeleitet ergeben sie die Vektor-Adressen. Wie Android.
    func testXpubWithSegwitScript() throws {
        let b = try XCTUnwrap(try data()["bip32"] as? [String: Any])
        let cases = S.list(b["addresses"]).filter { c in
            let k = c["key"] as? String ?? ""
            return k.hasPrefix("zpub") || k.hasPrefix("ypub")
        }
        XCTAssertTrue(cases.contains { ($0["key"] as? String ?? "").hasPrefix("zpub") })
        XCTAssertTrue(cases.contains { ($0["key"] as? String ?? "").hasPrefix("ypub") })
        for c in cases {
            let key = try XCTUnwrap(Bip32.parse(c["key"] as? String ?? ""))
            let asXpub = Bip32.serialize(Bip32.ExtendedKey(version: Bip32.xpubVersion, depth: key.depth,
                                                           parentFingerprint: key.parentFingerprint,
                                                           childNumber: key.childNumber, chainCode: key.chainCode,
                                                           publicKey: key.publicKey))
            XCTAssertTrue(asXpub.hasPrefix("xpub"))
            let chain = int(c["chain"])
            let index = int(c["index"])
            // Ohne Typ: xpub = P2PKH (andere Adresse)
            let plain = try XCTUnwrap(XpubDeriver(asXpub))
            XCTAssertEqual(plain.script, .p2pkh)
            XCTAssertNotEqual(plain.address(chain: chain, index: index), c["address"] as? String)
            let segwit = try XCTUnwrap(XpubDeriver(asXpub, script: key.script))
            XCTAssertEqual(segwit.address(chain: chain, index: index), c["address"] as? String, c["note"] as? String ?? "")
            // ypub/zpub: Typ nach Präfix, keine Suche
            XCTAssertEqual(XpubScan.scripts(key, cache: nil), [key.script])
            XCTAssertNil(XpubScan.pickScript(key, tried: [(script: key.script, used: 3)]))
        }
    }

    /// Adresstypen der Suche und gemerkter Typ (xpub) — wie Android.
    func testXpubScriptSearch() throws {
        let b = try XCTUnwrap(try data()["bip32"] as? [String: Any])
        let first = try XCTUnwrap(S.list(b["addresses"]).first { ($0["key"] as? String ?? "").hasPrefix("xpub") })
        let xpub = try XCTUnwrap(Bip32.parse(first["key"] as? String ?? ""))
        let all: [Bip32.Script] = [.p2pkh, .p2shP2wpkh, .p2wpkh]
        XCTAssertEqual(XpubScan.scripts(xpub, cache: nil), all)
        XCTAssertEqual(XpubScan.scripts(xpub, cache: XpubCache(receive: ["a"], fullScan: 5)), all)
        // Bis Build 46 mit benutzter Adresse = als P2PKH gefunden
        XCTAssertEqual(XpubScan.scripts(xpub, cache: XpubCache(receive: ["a"], usedReceive: [0])), [.p2pkh])
        XCTAssertEqual(XpubScan.scripts(xpub, cache: XpubCache(script: .p2wpkh)), [.p2wpkh])
        // Erster Typ mit Verlauf; keiner = nil (wieder alle prüfen); nur einer = dieser
        XCTAssertEqual(XpubScan.pickScript(xpub, tried: [(script: .p2pkh, used: 0), (script: .p2shP2wpkh, used: 0),
                                                         (script: .p2wpkh, used: 2)]), .p2wpkh)
        XCTAssertNil(XpubScan.pickScript(xpub, tried: all.map { (script: $0, used: 0) }))
        XCTAssertEqual(XpubScan.pickScript(xpub, tried: [(script: .p2pkh, used: 0)]), .p2pkh)
        // Gespeichert als «s»; ohne Typ kein «s»; unbekannt = keiner
        let cache = XpubCache(receive: ["a"], change: ["c"], usedReceive: [0], usedChange: [], fullScan: 42, script: .p2shP2wpkh)
        let encoded = try JSONEncoder().encode(cache)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertEqual(object["s"] as? String, "P2SH_P2WPKH")
        XCTAssertEqual(try JSONDecoder().decode(XpubCache.self, from: encoded), cache)
        var noScript = cache
        noScript.script = nil
        let plain = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(noScript)) as? [String: Any])
        XCTAssertNil(plain["s"])
        let unknown = Data(#"{"r":["a"],"s":"P2XX"}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(XpubCache.self, from: unknown).script)
    }

    func testBackupWithTokens() throws {
        let b = try XCTUnwrap(try data()["backupTokens"] as? [String: Any])
        let array = try JSONSerialization.jsonObject(with: Data((b["text"] as? String ?? "").utf8)) as? [Any]
        let parsed = WatchedAddressRules.entries(fromJSON: array)
        let expected = S.list(b["expected"])
        XCTAssertEqual(parsed.count, expected.count)
        for (e, a) in zip(expected, parsed) {
            XCTAssertEqual(a.address, e["address"] as? String)
            XCTAssertEqual(a.countTokens, e["countTokens"] as? Bool)
            let tokens = (e["tokens"] as? [String: Any] ?? [:]).mapValues { raw in
                (raw as? [[Any]] ?? []).map { "\(int64($0[0]))=\($0[1] as? String ?? "")" }
            }
            XCTAssertEqual(tokenObsText(a.tokenObservations), tokens)
        }
        // Hin und zurück (JSON der Sicherung und Codable)
        let json = WatchedAddressRules.json(parsed)
        XCTAssertEqual(WatchedAddressRules.entries(fromJSON: json), parsed)
        XCTAssertEqual(try JSONDecoder().decode([WatchedAddressBackupEntry].self, from: JSONEncoder().encode(parsed)), parsed)
        let merged = WatchedAddressRules.merge([], parsed)
        XCTAssertEqual(merged.count, 3)
        XCTAssertEqual(merged[0].countTokens, false)
        XCTAssertEqual(merged[1].tokenObservations, parsed[1].tokenObservations)
        XCTAssertTrue(merged[2].isXpub)
        XCTAssertEqual(WatchedAddressRules.backupEntries(merged), parsed)
    }
}
