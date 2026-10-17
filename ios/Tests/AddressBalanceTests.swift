import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `address_balance.json` (Android: `AddressBalanceParityTest.kt`).
final class AddressBalanceTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("address_balance") }

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    private func chain(_ value: Any?) -> AddressChain? { (value as? String).flatMap { AddressChain(rawValue: $0) } }

    private func string(_ value: Any?) -> String? { value as? String }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    func testKeccak() throws {
        let d = try data()
        for c in S.list(d["keccak256"]) {
            XCTAssertEqual(Keccak256.hex(Array((c["text"] as? String ?? "").utf8)), c["hex"] as? String, "keccak: \(c)")
        }
        // Permutation gegen SHA3-256 (Python hashlib), auch an Blockgrenzen (136 Byte)
        for c in S.list(d["sha3_256"]) {
            let text = String(repeating: c["text"] as? String ?? "", count: Int(int64(c["repeat"])))
            XCTAssertEqual(hex(Keccak256.sha3(Array(text.utf8))), c["hex"] as? String, "sha3: \(c["repeat"] ?? "")")
        }
    }

    func testEip55() throws {
        for case let address as String in (try data()["eip55"] as? [Any] ?? []) {
            XCTAssertEqual(AddressValidation.eip55(String(address.dropFirst(2))), address)
        }
    }

    func testValidation() throws {
        for c in S.list(try data()["validation"]) {
            let check = AddressValidation.check(c["input"] as? String, expected: chain(c["expected"]))
            XCTAssertEqual(check.problem?.rawValue, string(c["problem"]), "problem: \(c)")
            XCTAssertEqual(check.chain?.rawValue, string(c["chain"]), "chain: \(c)")
            XCTAssertEqual(check.kind?.rawValue, string(c["kind"]), "kind: \(c)")
            XCTAssertEqual(check.normalized, string(c["normalized"]), "normalized: \(c)")
        }
    }

    func testBtc() throws {
        let btc = try XCTUnwrap(try data()["btc"] as? [String: Any])
        XCTAssertEqual(btc["hosts"] as? [String], AddressBalance.btcHosts)
        let url = try XCTUnwrap(btc["url"] as? [String: Any])
        XCTAssertEqual(AddressBalance.btcUrl(host: url["host"] as? String ?? "", address: url["address"] as? String ?? ""),
                       url["url"] as? String)
        for c in S.list(btc["cases"]) {
            let body = c["body"] as? String ?? ""
            let address = c["address"] as? String ?? ""
            let name = c["name"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try AddressBalance.parseBtc(body, address: address), name)
            } else {
                let stats = try AddressBalance.parseBtc(body, address: address)
                XCTAssertEqual(stats.confirmedSats, int64(c["confirmedSats"]), "confirmed: \(name)")
                XCTAssertEqual(stats.pendingSats, int64(c["pendingSats"]), "pending: \(name)")
                XCTAssertEqual(AddressBalance.btcText(stats), c["balance"] as? String, "balance: \(name)")
            }
        }
    }

    func testEth() throws {
        let d = try data()
        let eth = try XCTUnwrap(d["eth"] as? [String: Any])
        XCTAssertEqual(eth["rpcs"] as? [String], AddressBalance.ethRpcs)
        let request = try XCTUnwrap(eth["request"] as? [String: Any])
        XCTAssertEqual(AddressBalance.ethRequest(request["address"] as? String ?? ""), request["body"] as? String)
        for c in S.list(eth["cases"]) {
            let body = c["body"] as? String ?? ""
            if c["error"] as? Bool == true {
                XCTAssertThrowsError(try AddressBalance.parseEthWei(body), body)
            } else {
                let wei = try AddressBalance.parseEthWei(body)
                XCTAssertEqual(wei, c["wei"] as? String, "wei: \(body)")
                XCTAssertEqual(AddressBalance.ethText(wei), c["balance"] as? String, "balance: \(body)")
            }
        }
        XCTAssertEqual(d["privacyHosts"] as? [String], AddressBalance.privacyHosts)
    }

    func testNumbers() throws {
        let d = try data()
        for c in S.list(d["hexToDecimal"]) {
            XCTAssertEqual(AddressBalance.hexToDecimal(c["hex"] as? String ?? ""), string(c["decimal"]), "hex: \(c)")
        }
        for c in S.list(d["unitsText"]) {
            XCTAssertEqual(AddressBalance.unitsText(c["digits"] as? String ?? "", decimals: Int(int64(c["decimals"]))),
                           c["text"] as? String, "units: \(c)")
        }
    }

    func testCache() throws {
        let d = try data()
        let cache = try XCTUnwrap(d["cache"] as? [String: Any])
        XCTAssertEqual(AddressBalance.cacheMillis, int64(cache["cacheMillis"]))
        XCTAssertEqual(AddressBalance.minForcedMillis, int64(cache["minForcedMillis"]))
        for c in S.list(cache["cases"]) {
            let due = AddressBalance.isDue(lastAttempt: int64(c["lastAttempt"]), now: int64(c["now"]),
                                           force: c["force"] as? Bool ?? false)
            XCTAssertEqual(due, c["due"] as? Bool, "due: \(c)")
        }
        for c in S.list(d["providerName"]) {
            XCTAssertEqual(AddressBalance.providerName(c["input"] as? String ?? ""), c["name"] as? String)
        }
    }

    func testHoldings() throws {
        let h = try XCTUnwrap(try data()["holdings"] as? [String: Any])
        let list = S.list(h["addresses"]).map {
            WatchedAddress(chain: chain($0["chain"]) ?? .BTC, address: $0["address"] as? String ?? "",
                           lastBalance: string($0["lastBalance"]))
        }
        let expected = S.list(h["expected"]).map { "\($0["coin"] as? String ?? "")=\($0["amount"] as? String ?? "")" }
        XCTAssertEqual(AddressBalance.holdings(list).map { "\($0.coin)=\($0.amount)" }, expected)
    }

    private func trade(_ m: [String: Any]) -> PortfolioTx {
        PortfolioTx(id: 0, coin: m["coin"] as? String ?? "",
                    type: PortfolioTxType(rawValue: m["type"] as? String ?? "") ?? .BUY,
                    amountExact: PortfolioDecimal.parse(m["amount"] as? String) ?? 0,
                    priceExact: PortfolioDecimal.parse(m["price"] as? String), time: int64(m["time"]))
    }

    /// Adress-Guthaben im Rechner: Einzahlung ohne Kaufpreis (Android: `portfolioMerge`).
    func testPortfolioMerge() throws {
        let m = try XCTUnwrap(try data()["portfolioMerge"] as? [String: Any])
        let today = int64(m["todayUtcDay"])
        let addresses = S.list(m["addresses"]).map {
            WatchedAddress(chain: chain($0["chain"]) ?? .BTC, address: $0["address"] as? String ?? "",
                           lastBalance: string($0["lastBalance"]))
        }
        let expected = S.list(m["expectedTrades"])
        for c in S.list(m["cases"]) {
            let real = S.list(c["trades"]).map(trade)
            let held = int64(c["heldSince"])
            XCTAssertEqual(AddressBalance.heldSince(real, todayUtcDay: today), held, "heldSince: \(c["name"] ?? "")")
            let synthetic = AddressBalance.trades(addresses, realTrades: real, todayUtcDay: today)
            XCTAssertEqual(synthetic.count, expected.count)
            for (t, e) in zip(synthetic, expected) {
                XCTAssertEqual(t.id, int64(e["id"]))
                XCTAssertEqual(t.coin, e["coin"] as? String)
                XCTAssertEqual(t.type.rawValue, e["type"] as? String)
                XCTAssertEqual(PortfolioDecimal.text(t.amountExact), e["amount"] as? String)
                XCTAssertNil(t.priceExact)
                XCTAssertEqual(t.time, held)
                XCTAssertTrue(AddressBalance.isAddressTrade(t.id))
            }
            XCTAssertEqual(AddressBalance.withAddresses(real, addresses: addresses, todayUtcDay: today), real + synthetic)
        }

        // Gesamtwert mit; Adress-Guthaben als eigener Teil — «Seit Kauf» bleibt aus den eigenen Käufen
        let sm = try XCTUnwrap(m["summary"] as? [String: Any])
        let prices = (sm["prices"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) }
        let summary = PortfolioCalculator.summarize(
            AddressBalance.withAddresses(S.list(sm["trades"]).map(trade), addresses: addresses, todayUtcDay: today),
            prices: prices)
        XCTAssertEqual(summary.totalValue, S.number(sm["totalValue"]) ?? 0, accuracy: 1e-9)
        let btc = try XCTUnwrap(summary.open.first { $0.coin == "BTC" })
        XCTAssertEqual(PortfolioDecimal.text(btc.holdingsExact), sm["btcHoldings"] as? String)
        XCTAssertEqual(PortfolioDecimal.text(btc.pricedHoldingsExact), sm["btcPricedHoldings"] as? String)
        XCTAssertEqual(summary.costMissing, sm["costMissing"] as? Bool)
        XCTAssertEqual(PortfolioDecimal.text(btc.addressHoldingsExact), sm["btcAddressHoldings"] as? String)
        XCTAssertEqual(summary.missingCostCoins.sorted(), sm["missingCostCoins"] as? [String])
        XCTAssertEqual(summary.unrealized ?? .nan, S.number(sm["unrealized"]) ?? 0, accuracy: 1e-9)
        XCTAssertEqual(summary.invested ?? .nan, S.number(sm["invested"]) ?? 0, accuracy: 1e-9)
        XCTAssertEqual(summary.addressValue, S.number(sm["addressValue"]) ?? 0, accuracy: 1e-9)

        // Zeitraum: Adress-Einträge zählen nie rückwirkend (ohne beobachtete Reihe nichts)
        let g = try XCTUnwrap(m["periodGain"] as? [String: Any])
        var closes: [String: [Int: Double]] = [:]
        for (coin, value) in g["closes"] as? [String: Any] ?? [:] {
            var days: [Int: Double] = [:]
            for (day, close) in value as? [String: Any] ?? [:] { days[Int(day) ?? 0] = S.number(close) }
            closes[coin] = days
        }
        let gain = PortfolioPeriodChange.gain(
            trades: AddressBalance.withAddresses(S.list(g["trades"]).map(trade), addresses: addresses, todayUtcDay: today),
            current: (g["current"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) },
            closes: closes, baseDay: Int(int64(g["baseDay"])))
        XCTAssertNil(S.number(g["amount"]))
        XCTAssertNil(gain)

        // Ohne Adressen bleibt alles beim Alten
        let n = try XCTUnwrap(m["noAddresses"] as? [String: Any])
        let real = S.list(n["trades"]).map(trade)
        XCTAssertEqual(AddressBalance.withAddresses(real, addresses: [], todayUtcDay: today).count, Int(int64(n["expectedCount"])))
        XCTAssertEqual(AddressBalance.withAddresses(real, addresses: [addresses[addresses.count - 1]], todayUtcDay: today), real)
        XCTAssertNil(AddressBalance.amount(for: "ETH", addresses: [addresses[addresses.count - 1]]))
        XCTAssertEqual(AddressBalance.amount(for: "btc", addresses: addresses).map(PortfolioDecimal.text), "0.5")
    }

    /// Beobachtete Guthaben-Reihe (Android: `balanceHistory`).
    func testBalanceHistory() throws {
        let h = try XCTUnwrap(try data()["balanceHistory"] as? [String: Any])
        func obs(_ raw: Any?) -> [AddressObservation] {
            (raw as? [Any] ?? []).compactMap { $0 as? [Any] }.map {
                AddressObservation(time: int64($0[0]), balance: PortfolioDecimal.parse($0[1] as? String) ?? 0)
            }
        }
        var list: [AddressObservation] = []
        for (i, c) in S.list(h["append"]).enumerated() {
            list = AddressBalanceHistory.append(list, time: int64(c["time"]),
                                                balance: PortfolioDecimal.parse(c["balance"] as? String) ?? 0)
            XCTAssertEqual(list, obs(c["expected"]), "append \(i)")
        }
        let histories = S.list(h["histories"]).map {
            AddressBalanceHistory(chain: chain($0["chain"]) ?? .BTC, address: $0["address"] as? String ?? "",
                                  observations: obs($0["observations"]))
        }
        let trades = AddressBalanceHistory.trades(histories)
        let expected = S.list(h["expectedTrades"])
        XCTAssertEqual(trades.count, expected.count)
        for (t, e) in zip(trades, expected) {
            XCTAssertEqual(t.coin, e["coin"] as? String)
            XCTAssertEqual(t.type.rawValue, e["type"] as? String)
            XCTAssertEqual(PortfolioDecimal.text(t.amountExact), e["amount"] as? String)
            XCTAssertEqual(t.time, int64(e["time"]))
            XCTAssertNil(t.priceExact)
            XCTAssertTrue(AddressBalance.isAddressTrade(t.id))
        }
        XCTAssertEqual(AddressBalanceHistory.latestByCoin(histories).mapValues(PortfolioDecimal.text),
                       h["latestByCoin"] as? [String: String])
        let g = try XCTUnwrap(h["periodGain"] as? [String: Any])
        var closes: [String: [Int: Double]] = [:]
        for (coin, value) in g["closes"] as? [String: Any] ?? [:] {
            var days: [Int: Double] = [:]
            for (day, close) in value as? [String: Any] ?? [:] { days[Int(day) ?? 0] = S.number(close) }
            closes[coin] = days
        }
        let gain = try XCTUnwrap(PortfolioPeriodChange.gain(
            trades: [], current: (g["current"] as? [String: Any] ?? [:]).compactMapValues { S.number($0) },
            closes: closes, baseDay: Int(int64(g["baseDay"])), addresses: histories))
        XCTAssertEqual(gain.amount, S.number(g["amount"]) ?? 0, accuracy: 1e-9)
        XCTAssertEqual(gain.percent ?? .nan, S.number(g["percent"]) ?? 0, accuracy: 1e-9)
        XCTAssertEqual(gain.skipped, g["skipped"] as? [String])

        // Speichern nach einem Abruf: nur bei geändertem Guthaben
        for (i, c) in S.list(h["record"]).enumerated() {
            let after = AddressBalanceHistory.record(obs(c["before"]), time: int64(c["time"]),
                                                     balance: PortfolioDecimal.parse(c["balance"] as? String) ?? 0)
            XCTAssertEqual(after.map { "\($0.time) \(PortfolioDecimal.text($0.balance))" },
                           obs(c["expected"]).map { "\($0.time) \(PortfolioDecimal.text($0.balance))" }, "record \(i)")
        }

        // Reihen aus den gespeicherten Adressen (ohne Beobachtung: geladenes Guthaben ab seinem Abruf)
        let of = try XCTUnwrap(h["of"] as? [String: Any])
        let addresses = S.list(of["addresses"]).map {
            WatchedAddress(chain: chain($0["chain"]) ?? .BTC, address: $0["address"] as? String ?? "",
                           lastBalance: string($0["lastBalance"]), lastUpdated: int64($0["lastUpdated"]),
                           observations: obs($0["observations"]))
        }
        let built = AddressBalanceHistory.of(addresses)
        let expectedOf = S.list(of["expected"])
        XCTAssertEqual(built.count, expectedOf.count)
        for (a, e) in zip(built, expectedOf) {
            XCTAssertEqual(a.chain.rawValue, e["chain"] as? String)
            XCTAssertEqual(a.observations, obs(e["observations"]))
        }

        // Speicherformat (gleicher Text wie die Android-Spalte)
        let j = try XCTUnwrap(h["json"] as? [String: Any])
        let decoded = AddressBalanceHistory.decode(j["text"] as? String)
        XCTAssertEqual(decoded.map { "\($0.time) \(PortfolioDecimal.text($0.balance))" },
                       obs(j["expected"]).map { "\($0.time) \(PortfolioDecimal.text($0.balance))" })
        XCTAssertEqual(AddressBalanceHistory.encode(decoded), j["encoded"] as? String)
        XCTAssertNil(AddressBalanceHistory.encode([]))
        XCTAssertEqual(AddressBalanceHistory.decode("kaputt"), [])
        XCTAssertEqual(AddressBalanceHistory.decode(nil), [])
        // Codable (Datei der Adressen): Guthaben als Text, exakt
        let file = try JSONEncoder().encode(decoded)
        XCTAssertEqual(try JSONDecoder().decode([AddressObservation].self, from: file), decoded)

        // Stufen je Coin und Menge zu einem Zeitpunkt (24-h-Verlauf)
        let steps = AddressBalanceHistory.stepsByCoin(histories)
        let expectedSteps = (h["steps"] as? [String: Any] ?? [:]).mapValues {
            obs($0).map { "\($0.time) \(PortfolioDecimal.text($0.balance))" }
        }
        XCTAssertEqual(steps.mapValues { $0.map { "\($0.time) \(PortfolioDecimal.text($0.balance))" } }, expectedSteps)
        for c in S.list(h["amountAt"]) {
            let actual = PortfolioWidgetSeries.amountAt(c["coin"] as? String ?? "", amount: S.number(c["amount"]) ?? 0,
                                                        time: int64(c["time"]), addressSteps: steps)
            XCTAssertEqual(actual, S.number(c["expected"]) ?? -1, accuracy: 1e-12, "amountAt \(c)")
        }
    }

    /// Sicherung mit beobachteten Guthaben (Android: `backupWithObservations`).
    func testBackupWithObservations() throws {
        let b = try XCTUnwrap(try data()["backupObservations"] as? [String: Any])
        let array = try JSONSerialization.jsonObject(with: Data((b["text"] as? String ?? "").utf8)) as? [Any]
        let parsed = WatchedAddressRules.entries(fromJSON: array)
        let expected = S.list(b["expected"])
        XCTAssertEqual(parsed.count, expected.count)
        for (a, e) in zip(parsed, expected) {
            XCTAssertEqual(a.address, e["address"] as? String)
            XCTAssertEqual(a.addedAt, int64(e["addedAt"]))
            let pairs = (e["observations"] as? [Any] ?? []).compactMap { $0 as? [Any] }
                .map { "\(int64($0[0])) \($0[1] as? String ?? "")" }
            XCTAssertEqual(a.observations.map { "\($0.time) \(PortfolioDecimal.text($0.balance))" }, pairs)
        }
        // Hin und zurück (Sicherung wie Android) ohne Verlust; Wiederherstellen übernimmt die Beobachtungen
        let json = BackupManager.watchedToJson(parsed)
        XCTAssertEqual(WatchedAddressRules.entries(fromJSON: json), parsed)
        let merged = WatchedAddressRules.merge([], parsed)
        XCTAssertEqual(merged.first?.observations, parsed.first?.observations)
        XCTAssertEqual(WatchedAddressRules.backupEntries(merged).first?.observations, parsed.first?.observations)
    }

    func testRules() throws {
        let d = try data()
        let r = try XCTUnwrap(d["rules"] as? [String: Any])
        XCTAssertEqual(WatchedAddressRules.maxAddresses, Int(int64(r["maxAddresses"])))
        XCTAssertEqual(WatchedAddressRules.maxLabel, Int(int64(r["maxLabel"])))
        XCTAssertEqual(WatchedAddressRules.backupKey, r["backupKey"] as? String)
        for c in S.list(d["labels"]) {
            XCTAssertEqual(WatchedAddressRules.cleanLabel(string(c["input"])), string(c["label"]), "label: \(c)")
        }
        for c in S.list(d["shortAddress"]) {
            XCTAssertEqual(WatchedAddressRules.shortAddress(c["input"] as? String ?? ""), c["short"] as? String)
        }
    }

    private func entry(_ m: [String: Any]) -> WatchedAddressBackupEntry {
        WatchedAddressBackupEntry(chain: chain(m["chain"]) ?? .BTC, address: m["address"] as? String ?? "",
                                  label: string(m["label"]), addedAt: int64(m["addedAt"]))
    }

    func testMergeAndBackup() throws {
        let d = try data()
        let m = try XCTUnwrap(d["merge"] as? [String: Any])
        let existing = S.list(m["existing"]).map {
            WatchedAddress(chain: chain($0["chain"]) ?? .BTC, address: $0["address"] as? String ?? "")
        }
        let merged = WatchedAddressRules.merge(existing, S.list(m["incoming"]).map(entry))
        XCTAssertEqual(WatchedAddressRules.backupEntries(merged), S.list(m["expected"]).map(entry))

        let b = try XCTUnwrap(d["backupJson"] as? [String: Any])
        let array = try JSONSerialization.jsonObject(with: Data((b["text"] as? String ?? "").utf8)) as? [Any]
        let parsed = WatchedAddressRules.entries(fromJSON: array)
        XCTAssertEqual(parsed, S.list(b["expected"]).map(entry))
        // Codable hin und zurück ohne Verlust (Feldnamen wie Android)
        let encoded = try JSONEncoder().encode(parsed)
        XCTAssertEqual(try JSONDecoder().decode([WatchedAddressBackupEntry].self, from: encoded), parsed)
        let reparsed = WatchedAddressRules.entries(fromJSON: try JSONSerialization.jsonObject(with: encoded) as? [Any])
        XCTAssertEqual(reparsed, parsed)
    }

    func testMergeStopsAtLimit() {
        let existing = (1..<WatchedAddressRules.maxAddresses).map {
            WatchedAddress(id: Int64($0), chain: .BTC, address: "x\($0)")
        }
        let incoming = [
            WatchedAddressBackupEntry(chain: .BTC, address: "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
            WatchedAddressBackupEntry(chain: .BTC, address: "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
        ]
        let merged = WatchedAddressRules.merge(existing, incoming)
        XCTAssertEqual(merged.count, 1)
        XCTAssertTrue(WatchedAddressRules.canAdd(existing))
        XCTAssertFalse(WatchedAddressRules.canAdd(existing + merged))
        XCTAssertNil(AddressValidation.check(nil).normalized)
    }

    /// Speicher: Einfügen ohne Doppelte, Ergebnis übernehmen (Fehler lässt das Guthaben stehen), alte Dateien lesbar.
    func testStoreFile() throws {
        var file = WatchedAddressFile()
        let id = try XCTUnwrap(WatchedAddressStore.insert(
            WatchedAddress(chain: .ETH, address: "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"), into: &file))
        XCTAssertNil(WatchedAddressStore.insert(
            WatchedAddress(chain: .ETH, address: "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"), into: &file))
        WatchedAddressStore.apply(.init(id: id, time: 1000, fetched: .init(balance: "1.5", provider: "publicnode.com"), error: nil),
                                  to: &file)
        WatchedAddressStore.apply(.init(id: id, time: 2000, fetched: nil, error: "HTTP 429"), to: &file)
        let a = try XCTUnwrap(file.addresses.first)
        XCTAssertEqual(a.lastBalance, "1.5")
        XCTAssertEqual(a.lastUpdated, 1000)
        XCTAssertEqual(a.lastAttempt, 2000)
        XCTAssertEqual(a.lastError, "HTTP 429")
        XCTAssertEqual(a.lastProvider, "publicnode.com")
        // Erfolgreicher Abruf = Beobachtung (Fehler ändert nichts)
        XCTAssertEqual(a.observations, [AddressObservation(time: 1000, balance: PortfolioDecimal.parse("1.5") ?? 0)])
        let roundTrip = try JSONDecoder().decode(WatchedAddressFile.self, from: JSONEncoder().encode(file))
        XCTAssertEqual(roundTrip, file)
        let minimal = try JSONDecoder().decode(WatchedAddressFile.self,
                                               from: Data("{\"addresses\":[{\"chain\":\"BTC\",\"address\":\"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa\"}]}".utf8))
        XCTAssertEqual(minimal.addresses.first?.lastAttempt, 0)
        XCTAssertEqual(minimal.nextId, 1)
        XCTAssertEqual(minimal.addresses.first?.observations, [])
    }
    /// Einzahlung/Umbuchung gleich Guthaben oder beobachtetem Zugang (± 1 %) — gleiche Fälle wie Android.
    func testTransferMatchesAddress() {
        func d(_ text: String) -> Decimal { PortfolioDecimal.parse(text) ?? 0 }
        func tx(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ time: Int64,
                coinFee: String? = nil) -> PortfolioTx {
            PortfolioTx(id: id, coin: coin, type: type, amountExact: d(amount), time: time,
                        feeExact: coinFee.map { d($0) }, feeUnit: coinFee == nil ? nil : .COIN)
        }
        let a = WatchedAddress(id: 1, chain: .BTC, address: "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                               lastBalance: "0.5", lastUpdated: 2000,
                               observations: [AddressObservation(time: 1000, balance: d("0.3")),
                                              AddressObservation(time: 2000, balance: d("0.5"))])
        let txs: [PortfolioTx] = [
            tx(1, "BTC", .DEPOSIT, "0.5", 500),                    // = Guthaben
            tx(2, "BTC", .DEPOSIT, "0.2015", 400),                 // Zunahme 0.2 (+0.75 %)
            tx(3, "BTC", .DEPOSIT, "0.21", 300),                   // +5 % daneben
            tx(4, "BTC", .TRANSFER, "0.52", 600, coinFee: "0.02"), // erhalten 0.50
            tx(5, "BTC", .BUY, "0.5", 700),                        // Kauf: keine Umbuchung
            tx(6, "ETH", .DEPOSIT, "0.5", 800),                    // anderer Coin
            tx(-1, "BTC", .DEPOSIT, "0.5", 900),                   // Adress-Einzahlung
            tx(8, "btc", .DEPOSIT, "0.3", 100),                    // erste Beobachtung
        ]
        XCTAssertEqual(WatchedAddressRules.transferMatches(a, txs: txs).map(\.id), [8, 2, 1, 4])
        // «Nur anzeigen» ändert die Erkennung nicht (Hinweis bleibt prüfbar)
        var showOnly = a
        showOnly.countInPortfolio = false
        XCTAssertEqual(WatchedAddressRules.transferMatches(showOnly, txs: txs).count, 4)
        // Ohne geladenes Guthaben: nichts
        XCTAssertTrue(WatchedAddressRules.transferMatches(WatchedAddress(chain: .BTC, address: "x"), txs: txs).isEmpty)
        // Coin-Detail: alle zählenden Adressen des Coins, je Transaktion einmal; «Nur anzeigen» warnt nicht
        XCTAssertEqual(WatchedAddressRules.transferMatches(coin: "btc", addresses: [a, a], txs: txs).map(\.id), [8, 2, 1, 4])
        XCTAssertTrue(WatchedAddressRules.transferMatches(coin: "BTC", addresses: [showOnly], txs: txs).isEmpty)
        XCTAssertTrue(WatchedAddressRules.transferMatches(coin: "ETH", addresses: [a], txs: txs).isEmpty)
    }
}
