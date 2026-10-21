import XCTest
@testable import CryptoChecker

/// Weitere Netze (EVM, TRON, Solana, LTC, XRP, TON) und «Nur anzeigen» aus `address_balance.json`
/// (Android: `ChainParityTest.kt`).
final class ChainTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("address_balance") }

    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func balance(_ d: [String: Any]) throws -> [String: Any] { try XCTUnwrap(d["chainBalance"] as? [String: Any]) }

    func testLists() throws {
        let c = try XCTUnwrap(try data()["chainAssets"] as? [String: Any])
        let evm = S.list(c["evm"])
        XCTAssertEqual(evm.map { $0["id"] as? String ?? "" }, ChainAssets.evm.map(\.id))
        for (e, n) in zip(evm, ChainAssets.evm) {
            XCTAssertEqual(e["name"] as? String, n.name)
            XCTAssertEqual(e["native"] as? String, n.native)
            XCTAssertEqual(e["rpcs"] as? [String], n.rpcs)
            let expected = S.list(e["tokens"]).map {
                Erc20Tokens.Token(symbol: $0["symbol"] as? String ?? "", contract: $0["contract"] as? String ?? "",
                                  decimals: int($0["decimals"]), coin: $0["coin"] as? String)
            }
            XCTAssertEqual(n.tokens, expected, n.id)
            XCTAssertFalse(n.tokens.contains { $0.symbol == n.native })
        }
        XCTAssertEqual(S.list(c["tron"]).map { "\($0["symbol"] ?? "")/\($0["contract"] ?? "")/\(int($0["decimals"]))/\($0["coin"] ?? "")" },
                       ChainAssets.tronTokens.map { "\($0.symbol)/\($0.contract)/\($0.decimals)/\($0.coin)" })
        XCTAssertEqual(S.list(c["solana"]).map { "\($0["symbol"] ?? "")/\($0["mint"] ?? "")/\(int($0["decimals"]))/\($0["coin"] ?? "")" },
                       ChainAssets.solanaTokens.map { "\($0.symbol)/\($0.mint)/\($0.decimals)/\($0.coin)" })
        XCTAssertEqual(S.list(c["ton"]).map { "\($0["symbol"] ?? "")/\($0["master"] ?? "")/\($0["rawHex"] ?? "")/\(int($0["decimals"]))" },
                       ChainAssets.tonJettons.map { "\($0.symbol)/\($0.master)/\($0.rawHex)/\($0.decimals)" })
        for r in S.list(c["resolve"]) {
            let chain = try XCTUnwrap(AddressChain(rawValue: r["chain"] as? String ?? ""))
            let asset = ChainAssets.resolve(chain, r["key"] as? String ?? "")
            if r["coin"] == nil {
                XCTAssertNil(asset, "\(r)")
            } else {
                XCTAssertEqual(asset?.label, r["label"] as? String, "\(r)")
                XCTAssertEqual(asset?.coin, r["coin"] as? String, "\(r)")
                XCTAssertEqual(asset?.native, r["native"] as? Bool, "\(r)")
                XCTAssertEqual(asset?.network, r["network"] as? String, "\(r)")
            }
        }
    }

    func testTron() throws {
        let t = try XCTUnwrap(try balance(try data())["tron"] as? [String: Any])
        XCTAssertEqual(t["hosts"] as? [String], ChainBalance.tronHosts)
        let address = t["address"] as? String ?? ""
        XCTAssertEqual(try ChainBalance.tronHex20(address), t["hex20"] as? String)
        XCTAssertEqual(ChainBalance.tronAccountUrl("https://api.trongrid.io"), t["accountUrl"] as? String)
        XCTAssertEqual(ChainBalance.tronCallUrl("https://api.trongrid.io"), t["callUrl"] as? String)
        XCTAssertEqual(ChainBalance.tronAccountRequest(address), t["accountRequest"] as? String)
        XCTAssertEqual(try ChainBalance.tronTokenRequest(address, ChainAssets.tronTokens[0]), t["tokenRequest"] as? String)
        for c in S.list(t["accounts"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseTronAccount(body), body)
            } else {
                XCTAssertEqual(try ChainBalance.parseTronAccount(body), c["sun"] as? String)
            }
        }
        for c in S.list(t["calls"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseTronCall(body), body)
            } else {
                XCTAssertEqual(try ChainBalance.parseTronCall(body), c["digits"] as? String)
            }
        }
    }

    func testSolana() throws {
        let s = try XCTUnwrap(try balance(try data())["solana"] as? [String: Any])
        XCTAssertEqual(s["rpcs"] as? [String], ChainBalance.solanaRpcs)
        XCTAssertEqual(ChainBalance.solanaBatch(s["address"] as? String ?? ""), s["batch"] as? String)
        for c in S.list(s["responses"]) {
            let body = c["body"] as? String ?? ""
            let name = c["name"] as? String ?? ""
            if c["unsupported"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseSolanaBatch(body), name) { XCTAssertTrue($0 is Erc20Tokens.BatchUnsupported) }
            } else if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseSolanaBatch(body), name)
            } else {
                let r = try ChainBalance.parseSolanaBatch(body)
                XCTAssertEqual(r.lamports, c["lamports"] as? String, name)
                XCTAssertEqual(r.tokens, c["tokens"] as? [String: String], name)
                XCTAssertEqual(r.unknown, int(c["unknown"]), name)
            }
        }
        XCTAssertEqual(ChainBalance.addDigits("999", "1"), "1000")
    }

    func testLtcXrpTon() throws {
        let d = try data()
        let b = try balance(d)
        let ltc = try XCTUnwrap(b["ltc"] as? [String: Any])
        XCTAssertEqual(ltc["hosts"] as? [String], ChainBalance.ltcHosts)
        XCTAssertEqual(ChainBalance.ltcUrl(host: "litecoinspace.org", address: ltc["address"] as? String ?? ""), ltc["url"] as? String)
        let xrp = try XCTUnwrap(b["xrp"] as? [String: Any])
        XCTAssertEqual(xrp["rpcs"] as? [String], ChainBalance.xrpRpcs)
        XCTAssertEqual(ChainBalance.xrpRequest(xrp["address"] as? String ?? ""), xrp["request"] as? String)
        for c in S.list(xrp["cases"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseXrp(body), body)
            } else {
                XCTAssertEqual(try ChainBalance.parseXrp(body), c["drops"] as? String)
            }
        }
        let ton = try XCTUnwrap(b["ton"] as? [String: Any])
        XCTAssertEqual(ton["hosts"] as? [String], ChainBalance.tonHosts)
        XCTAssertEqual(ChainBalance.tonUrl(host: "https://toncenter.com", address: ton["address"] as? String ?? ""), ton["url"] as? String)
        let rawUrl = ton["rawUrl"] as? String ?? ""
        let hex = rawUrl.components(separatedBy: "%3A").last ?? ""
        XCTAssertEqual(ChainBalance.tonUrl(host: "https://toncenter.com", address: "0:" + hex), rawUrl)
        for c in S.list(ton["cases"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseTon(body), body)
            } else {
                XCTAssertEqual(try ChainBalance.parseTon(body), c["nano"] as? String)
            }
        }
        XCTAssertEqual(ChainBalance.tonJettonsUrl(host: "https://toncenter.com", address: ton["jettonsAddress"] as? String ?? ""),
                       ton["jettonsUrl"] as? String)
        XCTAssertEqual(ChainBalance.tonJettonLimit, int(ton["jettonLimit"]))
        for c in S.list(ton["jettons"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseTonJettons(body), c["name"] as? String ?? "")
            } else {
                XCTAssertEqual(try ChainBalance.parseTonJettons(body), c["tokens"] as? [String: String], c["name"] as? String ?? "")
            }
        }
        let ada = try XCTUnwrap(b["ada"] as? [String: Any])
        let adaAddress = ada["address"] as? String ?? ""
        XCTAssertEqual(ada["hosts"] as? [String], ChainBalance.adaHosts)
        XCTAssertEqual(ChainBalance.adaUrl("https://api.koios.rest"), ada["url"] as? String)
        XCTAssertEqual(ChainBalance.adaRequest(adaAddress), ada["request"] as? String)
        for c in S.list(ada["cases"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try ChainBalance.parseAda(body, address: adaAddress), body)
            } else {
                XCTAssertEqual(try ChainBalance.parseAda(body, address: adaAddress), c["lovelace"] as? String)
            }
        }
        XCTAssertEqual(d["privacyHosts"] as? [String], AddressBalance.privacyHosts)
    }

    private func watched(_ m: [String: Any]) -> WatchedAddress {
        WatchedAddress(chain: AddressChain(rawValue: m["chain"] as? String ?? "") ?? .BTC,
                       address: m["address"] as? String ?? "",
                       lastBalance: m["lastBalance"] as? String,
                       tokenBalances: m["tokenBalances"] as? [String: String] ?? [:],
                       countTokens: m["countTokens"] as? Bool ?? true,
                       // wie nach dem Speichern: Netze in fester Reihenfolge (`cleanNetworks`), sonst ist das
                       // gespeicherte und wieder gelesene Objekt nur wegen der Reihenfolge «anders»
                       networks: WatchedAddressRules.cleanNetworks(m["networks"] as? [String]),
                       countInPortfolio: m["countInPortfolio"] as? Bool ?? true)
    }

    private func overlapTx(_ m: [String: Any]) -> PortfolioTx {
        PortfolioTx(id: int64(m["id"]), coin: m["coin"] as? String ?? "",
                    type: PortfolioTxType(rawValue: m["type"] as? String ?? "") ?? .BUY,
                    amountExact: PortfolioDecimal.parse(m["amount"] as? String ?? "0") ?? 0,
                    priceExact: (m["price"] as? String).flatMap { PortfolioDecimal.parse($0) },
                    time: int64(m["time"]))
    }

    func testMultiHoldings() throws {
        let h = try XCTUnwrap(try data()["multiHoldings"] as? [String: Any])
        let list = S.list(h["addresses"]).map(watched)
        let expected = (h["expected"] as? [[String]] ?? []).map { "\($0[0])=\($0[1])" }
        XCTAssertEqual(AddressBalance.holdings(list).map { "\($0.coin)=\($0.amount)" }, expected)
        let trades = AddressBalance.trades(list, realTrades: [], todayUtcDay: 20_000)
        for (coin, id) in h["tradeIds"] as? [String: Any] ?? [:] {
            XCTAssertEqual(trades.first(where: { $0.coin == coin })?.id, int64(id), coin)
        }
        XCTAssertEqual(Set(trades.map(\.id)).count, trades.count)
        // «Nur anzeigen» zählt auch im Verlauf nicht
        var shown = try XCTUnwrap(list.last)
        shown.observations = [AddressObservation(time: 5, balance: 1)]
        XCTAssertTrue(AddressBalanceHistory.of([shown]).isEmpty)
        let o = try XCTUnwrap(h["overlap"] as? [String: Any])
        let own = Set(o["ownCoins"] as? [String] ?? [])
        XCTAssertEqual(WatchedAddressRules.overlap(list[int(o["address"])], ownCoins: own), o["expected"] as? [String])
        // Später aufgetauchte Überschneidung MIT passender Menge aller Coins: einmal automatisch «Nur anzeigen»,
        // «Rückgängig» bleibt entschieden; nur gleicher Coin bzw. nur ein Teil passt: zählt weiter — wie Android
        var counting = list[int(o["address"])]
        counting.countInPortfolio = true
        counting.overlapChecked = false
        let amountCases: [[String: Any]] = S.list(h["overlapAmounts"])
        for c in amountCases {
            let name = c["name"] as? String ?? ""
            var a = list[int(c["address"])]
            a.countInPortfolio = true
            a.overlapChecked = false
            let txs: [PortfolioTx] = S.list(c["trades"]).map(overlapTx)
            let matched: [String] = c["matched"] as? [String] ?? []
            XCTAssertEqual(WatchedAddressRules.overlapMatched(a, txs: txs), matched, name)
            let due = !matched.isEmpty
            // Automatisch nur, wenn ALLE Coins mit Guthaben passen (ETH passt, Token nicht: zählt weiter)
            let auto = try XCTUnwrap(c["autoShowOnly"] as? Bool, name)
            XCTAssertTrue(!auto || due, name)
            XCTAssertEqual(WatchedAddressRules.overlapAllMatched(a, txs: txs), auto, name)
            XCTAssertEqual(WatchedAddressRules.overlapAutoShowOnly([a], txs: txs), auto ? [a] : [], name)
            // Alter Entscheid «Nur anzeigen»: ohne passende Menge zurück auf zählen, mit bleibt er
            var legacy = a
            legacy.countInPortfolio = false
            legacy.overlapChecked = true
            legacy.overlapLegacy = true
            XCTAssertEqual(WatchedAddressRules.overlapLegacyRevert(legacy, txs: txs), !due, name)
            let migrated = WatchedAddressRules.withOverlapMigrated(legacy, revert: !due)
            XCTAssertFalse(migrated.overlapLegacy, name)
            XCTAssertEqual(migrated.countInPortfolio, !due, name)
            XCTAssertEqual(migrated.overlapChecked, due, name)
            // Alter Entscheid «zählen» bleibt immer
            var legacyCounting = legacy
            legacyCounting.countInPortfolio = true
            XCTAssertFalse(WatchedAddressRules.overlapLegacyRevert(legacyCounting, txs: txs), name)
        }
        let matching: [PortfolioTx] = S.list(amountCases.first?["trades"]).map(overlapTx)
        XCTAssertEqual(WatchedAddressRules.overlapAutoShowOnly([counting], txs: matching), [counting])
        let switched = WatchedAddressRules.withOverlapShowOnly(counting)
        XCTAssertFalse(switched.countInPortfolio)
        XCTAssertTrue(switched.overlapChecked)
        XCTAssertTrue(WatchedAddressRules.overlapAutoShowOnly([switched], txs: matching).isEmpty)
        let undone = WatchedAddressRules.withCountInPortfolio(switched, true)
        XCTAssertTrue(undone.countInPortfolio && undone.overlapChecked)
        XCTAssertTrue(WatchedAddressRules.overlapAutoShowOnly([undone], txs: matching).isEmpty)
        let stored = try JSONDecoder().decode(WatchedAddress.self, from: JSONEncoder().encode(undone))
        XCTAssertEqual(stored, undone)
        // Gespeichert ohne `overlapV2` (alte Regel): alter Entscheid, nach der Übernahme mit `overlapV2`
        let oldJson = #"{"chain":"BTC","address":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa","countInPortfolio":false,"overlapChecked":true}"#
        let old = try JSONDecoder().decode(WatchedAddress.self, from: Data(oldJson.utf8))
        XCTAssertTrue(old.overlapChecked && old.overlapLegacy && !old.countInPortfolio)
        let kept = WatchedAddressRules.withOverlapMigrated(old, revert: false)
        let keptStored = try JSONDecoder().decode(WatchedAddress.self, from: JSONEncoder().encode(kept))
        XCTAssertFalse(keptStored.overlapLegacy)
        for c in S.list(h["overlapAtAdd"]) {
            let chain = try XCTUnwrap(AddressChain(rawValue: c["chain"] as? String ?? ""))
            XCTAssertEqual(WatchedAddressRules.overlapAtAdd(chain, ownCoins: Set(c["ownCoins"] as? [String] ?? [])),
                           c["ask"] as? Bool)
        }
        // Reihen je Schlüssel: ETH auf Ethereum und Arbitrum getrennt, gleicher Coin
        var a = list[0]
        a.observations = [AddressObservation(time: 10, balance: 1)]
        a.tokenObservations = ["ARBITRUM:ETH": [AddressObservation(time: 10, balance: Decimal(string: "0.5")!)],
                               "BASE:ETH": [AddressObservation(time: 10, balance: 9)]]
        let histories = AddressBalanceHistory.of([a])
        XCTAssertEqual(histories.map(\.coin), ["ETH", "ETH"])
        XCTAssertEqual(AddressBalanceHistory.latestByCoin(histories)["ETH"].map { PortfolioDecimal.text($0) }, "1.5")
    }

    func testNetworkRules() throws {
        let d = try data()
        let r = try XCTUnwrap(d["networkRules"] as? [String: Any])
        let f = try XCTUnwrap(r["firstScan"] as? [String: Any])
        let fresh = WatchedAddress(chain: .ETH, address: f["address"] as? String ?? "")
        let after = WatchedAddressRules.applyFetched(fresh, time: 100, balance: "1", provider: "publicnode.com",
                                                     tokens: f["tokens"] as? [String: String],
                                                     networks: f["networks"] as? [String])
        XCTAssertEqual(after.networks, f["expectedNetworks"] as? [String])
        // Erste Wahl: stumme Netze bleiben gewählt, im Hintergrund keine Wahl (seit Build 94)
        for c in S.list(r["discovered"]) {
            XCTAssertEqual(WatchedAddressRules.discoveredNetworks(found: c["found"] as? [String] ?? [],
                                                                 unanswered: c["unanswered"] as? [String] ?? [],
                                                                 background: c["background"] as? Bool ?? false),
                           c["expected"] as? [String], "discovered \(c)")
        }
        XCTAssertEqual(after.tokenBalances, f["expectedBalances"] as? [String: String])
        // Netze und «Nur anzeigen» überleben Speicherform und Sicherung
        let shown = WatchedAddressRules.withCountInPortfolio(after, false)
        let decoded = try JSONDecoder().decode(WatchedAddress.self, from: JSONEncoder().encode(shown))
        XCTAssertEqual(decoded, shown)
        let entries = WatchedAddressRules.backupEntries([shown])
        XCTAssertEqual(WatchedAddressRules.entries(fromJSON: WatchedAddressRules.json(entries)), entries)
        let merged = try XCTUnwrap(WatchedAddressRules.merge([], entries).first)
        XCTAssertEqual(merged.networks, after.networks)
        XCTAssertFalse(merged.countInPortfolio)

        let firstAddress = try XCTUnwrap(S.list((d["multiHoldings"] as? [String: Any])?["addresses"]).first)
        var base = watched(firstAddress)
        base.lastAttempt = 5
        let de = try XCTUnwrap(r["deselect"] as? [String: Any])
        // watched() ordnet die Netze wie nach dem Speichern (cleanNetworks); die Vorlage nennt sie in freier Reihenfolge
        XCTAssertEqual(base.networks, WatchedAddressRules.cleanNetworks(de["before"] as? [String]))
        let off = WatchedAddressRules.withNetworks(base, de["after"] as? [String] ?? [])
        XCTAssertEqual(off.networks, de["after"] as? [String])
        XCTAssertEqual(Set(off.tokenBalances.keys), Set(de["keptKeys"] as? [String] ?? []))
        XCTAssertEqual(off.lastAttempt, 5)
        let se = try XCTUnwrap(r["select"] as? [String: Any])
        var one = base
        one.networks = se["before"] as? [String]
        XCTAssertEqual(WatchedAddressRules.withNetworks(one, se["after"] as? [String] ?? []).lastAttempt, 0)
        // «Tokens mitzählen» aus: Netz-Guthaben bleiben, Token weg
        XCTAssertEqual(Set(WatchedAddressRules.withCountTokens(base, false).tokenBalances.keys), ["ARBITRUM:ETH", "BSC:BNB"])
        // Ältere Datei ohne neue Felder
        let legacy = try JSONDecoder().decode(WatchedAddress.self, from: Data(#"{"chain":"SOL","address":"x"}"#.utf8))
        XCTAssertNil(legacy.networks)
        XCTAssertTrue(legacy.countInPortfolio)
        XCTAssertEqual(legacy.unknownTokens, 0)
    }

    func testWrappedCoinsStayOwnCoinWithBaseFallback() {
        // Gekapselt/gebrückt: eigener Coin (kein stilles Verschmelzen), Basis nur als Ersatzkurs — wie Android
        XCTAssertEqual(ChainAssets.resolve(.ETH, "WBTC")?.coin, "WBTC")
        XCTAssertEqual(ChainAssets.resolve(.ETH, "BSC:BTCB")?.coin, "BTCB")
        XCTAssertEqual(ChainAssets.resolve(.ETH, "AVALANCHE:BTC.b")?.coin, "BTC.B")
        XCTAssertEqual(ChainAssets.resolve(.ETH, "BASE:WETH")?.coin, "WETH")
        XCTAssertEqual(ChainAssets.resolve(.SOL, "wSOL")?.coin, "WSOL")
        // Gebrückte Stablecoins zählen als Basis-Stablecoin
        XCTAssertEqual(ChainAssets.resolve(.ETH, "ARBITRUM:USDC.e")?.coin, "USDC")
        XCTAssertEqual(ChainAssets.resolve(.ETH, "BASE:USDbC")?.coin, "USDC")
        XCTAssertEqual(ChainAssets.wrappedBase("btc.b"), "BTC")
        XCTAssertNil(ChainAssets.wrappedBase("BTC"))
        XCTAssertNil(ChainAssets.wrappedBase("USDC.E"))
        XCTAssertEqual(ChainAssets.withBases(["WBTC", "ETH", "BTC.b"]), ["WBTC", "ETH", "BTC.B", "BTC"])
        // Eigener Kurs (WBTC an einer Börse) gewinnt; ohne eigenen Kurs die Basis, nur mit Kurs der Basis
        XCTAssertEqual(ChainAssets.priceViaBase(["WBTC", "BTC.B", "WAVAX", "ETH"], available: ["WBTC", "BTC", "ETH"]),
                       ["BTC.B": "BTC"])
        XCTAssertEqual(ChainAssets.fillFromBase(["BTCB", "WETH"], ["WBTC": 1.0, "BTC": 1.0]),
                       ["WBTC": 1.0, "BTC": 1.0, "BTCB": 1.0])
    }
}
