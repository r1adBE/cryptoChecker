import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `csv_transfers.json` (Android: `CsvTransferMatchParityTest.kt`).
final class CsvTransferMatchTests: XCTestCase {

    private typealias S = TestSupport

    private func string(_ value: Any?) -> String? { value as? String }
    private func strings(_ value: Any?) -> [String] { (value as? [Any])?.compactMap { $0 as? String } ?? [] }
    private func dec(_ value: Any?) -> Decimal? { string(value).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) } }
    private func text(_ value: Decimal?) -> String? { value.map { CsvImport.text($0) } }
    private func unit(_ value: Any?) -> PortfolioFeeUnit? { string(value).flatMap(PortfolioFeeUnit.init(rawValue:)) }
    private func id(_ value: Any?) -> Int64? { S.number(value).map { Int64($0) } }

    private func tx(_ o: [String: Any]) -> CsvImportTx {
        CsvImportTx(line: Int(S.number(o["line"]) ?? 0), coin: string(o["coin"]) ?? "",
                    type: PortfolioTxType(rawValue: string(o["type"]) ?? "") ?? .BUY, amount: dec(o["amount"]) ?? 0,
                    priceUsdt: nil, time: S.millis(o["time"]), fee: dec(o["fee"]), feeUnit: unit(o["feeUnit"]),
                    feeCoin: string(o["feeCoin"]), feeCoinAmount: dec(o["feeCoinAmount"]), note: nil,
                    importKey: string(o["key"]) ?? "")
    }

    private func stored(_ o: [String: Any]) -> CsvStoredMove {
        CsvStoredMove(id: id(o["id"]) ?? 0, coin: string(o["coin"]) ?? "",
                      type: PortfolioTxType(rawValue: string(o["type"]) ?? "") ?? .BUY, amount: dec(o["amount"]) ?? 0,
                      fee: dec(o["fee"]), feeUnit: unit(o["feeUnit"]), feeCoin: string(o["feeCoin"]),
                      feeCoinAmount: dec(o["feeCoinAmount"]), time: S.millis(o["time"]),
                      importKey: string(o["importKey"]) ?? "", transferFrom: string(o["transferFrom"]),
                      transferTo: string(o["transferTo"]), priceUsdt: dec(o["priceUsdt"]),
                      acquiredTime: id(o["acquiredTime"]), exchange: string(o["exchange"]),
                      importBatch: string(o["importBatch"]), linkedImportKey: string(o["linkedImportKey"]))
    }

    /// Zustand aus dem Fixture (Format `CsvRestoreState`) — über die JSON-Form wie aus Datei und Sicherung.
    private func restoreState(_ value: Any?) -> CsvRestoreState? { CsvRestoreState.from(json: value) }

    /// Gleiche JSON-Struktur (Reihenfolge der Schlüssel egal).
    private func sameJSON(_ a: [String: Any]?, _ b: Any?) -> Bool {
        guard let a, let b = b as? [String: Any] else { return a == nil && (b == nil || b is NSNull) }
        return NSDictionary(dictionary: a).isEqual(to: b)
    }

    private func data() throws -> [String: Any] { try S.fixture("csv_transfers") }

    private func cases() throws -> [[String: Any]] { S.list(try data()["cases"]) }

    private func links(_ c: [String: Any]) -> [CsvTransferLink] {
        CsvTransferMatch.candidates(S.list(c["txs"]).map(tx), place: string(c["place"]), stored: S.list(c["stored"]).map(stored))
    }

    private func assertLinks(_ m: String, _ expected: [[String: Any]], _ links: [CsvTransferLink],
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(links.map(\.key), expected.map { string($0["key"]) ?? "" }, "\(m) keys", file: file, line: line)
        for (e, l) in zip(expected, links) {
            let n = "\(m) \(l.key)"
            XCTAssertEqual(l.key, CsvTransferMatch.linkId(l.withdrawalKey, l.depositKey), "\(n) id", file: file, line: line)
            XCTAssertEqual(l.withdrawalKey, string(e["withdrawalKey"]), "\(n) withdrawalKey", file: file, line: line)
            XCTAssertEqual(l.depositKey, string(e["depositKey"]), "\(n) depositKey", file: file, line: line)
            XCTAssertEqual(l.withdrawalId, id(e["withdrawalId"]), "\(n) withdrawalId", file: file, line: line)
            XCTAssertEqual(l.depositId, id(e["depositId"]), "\(n) depositId", file: file, line: line)
            XCTAssertEqual(text(l.sent), string(e["sent"]), "\(n) sent", file: file, line: line)
            XCTAssertEqual(text(l.received), string(e["received"]), "\(n) received", file: file, line: line)
            XCTAssertEqual(text(l.fee), string(e["fee"]), "\(n) fee", file: file, line: line)
            XCTAssertEqual(l.feeUnit?.rawValue, string(e["feeUnit"]), "\(n) feeUnit", file: file, line: line)
            XCTAssertEqual(l.feeCoin, string(e["feeCoin"]), "\(n) feeCoin", file: file, line: line)
            XCTAssertEqual(text(l.feeCoinAmount), string(e["feeCoinAmount"]), "\(n) feeCoinAmount", file: file, line: line)
            XCTAssertEqual(l.from, string(e["from"]), "\(n) from", file: file, line: line)
            XCTAssertEqual(l.to, string(e["to"]), "\(n) to", file: file, line: line)
            XCTAssertEqual(l.unambiguous, e["unambiguous"] as? Bool, "\(n) unambiguous", file: file, line: line)
        }
    }

    func testCandidatesAndDefaultChoice() throws {
        for c in try cases() {
            let m = string(c["name"]) ?? "?"
            let found = links(c)
            assertLinks(m, S.list(c["links"]), found)
            XCTAssertEqual(CsvTransferMatch.defaultChoice(found), Set(strings(c["default"])), "\(m) default")
        }
    }

    func testToggleKeepsOneLinkPerSide() throws {
        for c in try cases() where c["toggle"] != nil {
            let found = links(c)
            for t in S.list(c["toggle"]) {
                XCTAssertEqual(CsvTransferMatch.toggle(found, chosen: Set(strings(t["chosen"])), key: string(t["key"]) ?? "",
                                                       on: t["on"] as? Bool ?? false),
                               Set(strings(t["expected"])), "\(string(c["name"]) ?? "?") toggle \(t)")
            }
        }
    }

    func testApplyMergesNewSidesAndUpdatesStoredOnes() throws {
        for c in try cases() {
            guard let a = c["apply"] as? [String: Any] else { continue }
            let m = string(c["name"]) ?? "?"
            let found = links(c)
            let chosen = a["chosen"] != nil ? Set(strings(a["chosen"])) : CsvTransferMatch.defaultChoice(found)
            let plan = CsvTransferMatch.apply(S.list(c["txs"]).map(tx), links: found, chosen: chosen)
            let txs = S.list(a["transactions"])
            XCTAssertEqual(plan.transactions.map(\.importKey), txs.map { string($0["key"]) ?? "" }, "\(m) transactions")
            for (e, t) in zip(txs, plan.transactions) {
                let n = "\(m) \(t.importKey)"
                XCTAssertEqual(t.linkedImportKey, string(e["linkedKey"]), "\(n) linked")
                XCTAssertEqual(t.type.rawValue, string(e["type"]), "\(n) type")
                XCTAssertEqual(text(t.amount), string(e["amount"]), "\(n) amount")
                XCTAssertEqual(text(t.fee), string(e["fee"]), "\(n) fee")
                XCTAssertEqual(t.feeUnit?.rawValue, string(e["feeUnit"]), "\(n) feeUnit")
                XCTAssertEqual(t.transferFrom, string(e["transferFrom"]), "\(n) from")
                XCTAssertEqual(t.transferTo, string(e["transferTo"]), "\(n) to")
                if e["time"] != nil, !(e["time"] is NSNull) { XCTAssertEqual(t.time, S.millis(e["time"]), "\(n) time") }
            }
            let updates = S.list(a["storedUpdates"])
            XCTAssertEqual(plan.storedUpdates.map(\.id), updates.map { id($0["id"]) ?? -1 }, "\(m) updates")
            for (e, u) in zip(updates, plan.storedUpdates) {
                let n = "\(m) \(u.id)"
                XCTAssertEqual(text(u.amount), string(e["amount"]), "\(n) amount")
                XCTAssertEqual(text(u.fee), string(e["fee"]), "\(n) fee")
                XCTAssertEqual(u.feeUnit?.rawValue, string(e["feeUnit"]), "\(n) feeUnit")
                XCTAssertEqual(u.feeCoin, string(e["feeCoin"]), "\(n) feeCoin")
                XCTAssertEqual(text(u.feeCoinAmount), string(e["feeCoinAmount"]), "\(n) feeCoinAmount")
                XCTAssertEqual(u.time, S.millis(e["time"]), "\(n) time")
                XCTAssertEqual(u.transferFrom, string(e["transferFrom"]), "\(n) from")
                XCTAssertEqual(u.transferTo, string(e["transferTo"]), "\(n) to")
                XCTAssertEqual(u.importKey, string(e["importKey"]), "\(n) key")
                XCTAssertEqual(u.linkedImportKey, string(e["linkedImportKey"]), "\(n) linked")
                // Zustand für «Import rückgängig» im gespeicherten Format (Reihenfolge der Schlüssel egal)
                XCTAssertTrue(sameJSON(u.restoreState.json(), e["restoreState"]), "\(n) restoreState")
                // Zustand davor kommt unverändert zurück (gespeicherte Seite und aufgegangene neue Zeile)
                guard let before = S.list(c["stored"]).map(stored).first(where: { $0.id == u.id }),
                      let o = restoreState(u.restoreState.json()) else { XCTFail("\(n) origin"); continue }
                XCTAssertEqual(o.type, before.type, "\(n) origin type")
                XCTAssertEqual(o.amount, before.amount, "\(n) origin amount")
                XCTAssertEqual(o.priceUsdt, before.priceUsdt, "\(n) origin price")
                XCTAssertEqual(o.time, before.time, "\(n) origin time")
                XCTAssertEqual(o.fee, before.fee, "\(n) origin fee")
                XCTAssertEqual(o.feeUnit, before.feeUnit, "\(n) origin feeUnit")
                XCTAssertEqual(o.feeCoin, before.feeCoin, "\(n) origin feeCoin")
                XCTAssertEqual(o.feeCoinAmount, before.feeCoinAmount, "\(n) origin feeCoinAmount")
                XCTAssertEqual(o.transferFrom, before.transferFrom, "\(n) origin from")
                XCTAssertEqual(o.transferTo, before.transferTo, "\(n) origin to")
                XCTAssertEqual(o.acquiredTime, before.acquiredTime, "\(n) origin acquiredTime")
                XCTAssertEqual(o.exchange, before.exchange, "\(n) origin exchange")
                XCTAssertEqual(o.importKey, before.importKey, "\(n) origin key")
                XCTAssertEqual(o.importBatch, before.importBatch, "\(n) origin batch")
                guard let l = found.first(where: { $0.withdrawalId == u.id || $0.depositId == u.id }),
                      let new = l.absorbed, let r = o.absorbed else { XCTFail("\(n) absorbed"); continue }
                XCTAssertEqual(r, CsvAbsorbedRow(type: new.type, amount: new.amount, priceUsdt: new.priceUsdt, time: new.time,
                                                 fee: new.fee, feeUnit: new.feeUnit, feeCoin: new.feeCoin,
                                                 feeCoinAmount: new.feeCoinAmount, importKey: new.importKey), "\(n) absorbed")
            }
        }
    }

    /// Verknüpfte Umbuchung durch FIFO je Wallet: Menge = was ankommt, die Gebühr im Coin geht zusätzlich von der Quelle
    /// ab — Bestand je Wallet stimmt (nicht nur gesamt). Wie Android `linkedTransferMovesReceivedAmountBetweenWallets`.
    func testLinkedTransferMovesReceivedAmountBetweenWallets() throws {
        for c in try cases() {
            guard let f = c["fifoWallet"] as? [String: Any] else { continue }
            let m = string(c["name"]) ?? "?"
            let found = links(c)
            let coin = try XCTUnwrap(found.first?.coin)
            let plan = CsvTransferMatch.apply(S.list(c["txs"]).map(tx), links: found, chosen: CsvTransferMatch.defaultChoice(found))
            let base = S.list(f["trades"]).map { o in
                PortfolioTx(id: id(o["id"]) ?? 0, coin: string(o["coin"]) ?? "", type: PortfolioTxType(rawValue: string(o["type"]) ?? "") ?? .BUY,
                            amountExact: dec(o["amount"]) ?? 0, priceExact: dec(o["priceUsdt"]), time: S.millis(o["time"]),
                            exchange: string(o["exchange"]))
            }
            let moves = plan.storedUpdates.map { u -> PortfolioTx in
                var t = PortfolioTx(id: u.id, coin: coin, type: .TRANSFER, amountExact: u.amount, priceExact: nil, time: u.time,
                                    feeExact: u.fee, feeUnit: u.feeUnit)
                t.feeCoin = u.feeCoin
                t.feeCoinAmountExact = u.feeCoinAmount
                t.transferFrom = u.transferFrom
                t.transferTo = u.transferTo
                return t
            }
            let ledger = PortfolioCalculator.ledger(coin, trades: base + moves, currentPrice: nil, method: .FIFO_WALLET)
            let wallets = Dictionary(grouping: ledger.lots, by: { $0.wallet ?? "" })
                .mapValues { lots in text(lots.reduce(Decimal(0)) { $0 + $1.amount }) ?? "" }
            XCTAssertEqual(wallets, (f["wallets"] as? [String: String]) ?? [:], "\(m) wallets")
        }
    }

    /// «Import rückgängig»: verknüpfte Seite des späteren Laufs zurücksetzen, sonst löschen; früherer Lauf nimmt sie mit.
    func testUndoDissolvesLinksOfTheUndoneImport() throws {
        for c in S.list(try data()["undo"]) {
            let rows = S.list(c["rows"]).map {
                CsvImportedRef(id: id($0["id"]) ?? -1, importBatch: string($0["importBatch"]),
                               restoreState: restoreState($0["restoreState"]))
            }
            let plan = CsvTransferMatch.undoPlan(rows, batch: string(c["batch"]) ?? "")
            XCTAssertEqual(plan.delete, Set(((c["delete"] as? [Any]) ?? []).compactMap { id($0) }), "undo \(c) delete")
            XCTAssertEqual(Set(plan.revert.keys), Set(((c["revert"] as? [Any]) ?? []).compactMap { id($0) }), "undo \(c) revert")
            XCTAssertEqual(Set(plan.detach.keys), Set(((c["detach"] as? [Any]) ?? []).compactMap { id($0) }), "undo \(c) detach")
            if let e = c["absorbed"] as? [String: Any] {
                let a = try XCTUnwrap(plan.detach[id(e["id"]) ?? -1])
                XCTAssertEqual(a.type.rawValue, string(e["type"]))
                XCTAssertEqual(text(a.amount), string(e["amount"]))
                XCTAssertEqual(a.time, S.millis(e["time"]))
                XCTAssertEqual(a.importKey, string(e["importKey"]))
            }
            if let e = c["origin"] as? [String: Any] {
                let o = try XCTUnwrap(plan.revert[id(e["id"]) ?? -1])
                XCTAssertEqual(o.type.rawValue, string(e["type"]))
                XCTAssertEqual(text(o.amount), string(e["amount"]))
                XCTAssertEqual(o.time, S.millis(e["time"]))
                XCTAssertEqual(o.importKey, string(e["importKey"]))
                XCTAssertEqual(o.importBatch, string(e["importBatch"]))
            }
        }
    }

    func testCarryOverKeepsShownChoices() throws {
        let all = try cases()
        for o in S.list(try data()["carryOver"]) {
            guard let c = all.first(where: { string($0["name"]) == string(o["case"]) }) else { return XCTFail("case \(o)") }
            XCTAssertEqual(CsvTransferMatch.carryOver(links(c), seen: Set(strings(o["seen"])), chosen: Set(strings(o["chosen"]))),
                           Set(strings(o["expected"])), "carryOver \(o)")
        }
    }

    /// Gespeicherte Zeile im Ablauf von `testUndoBothImportsInSequence`: Id, Lauf, Schlüssel, Zustand.
    private struct Row: Equatable {
        let id: Int64
        let importBatch: String?
        let importKey: String?
        let linkedImportKey: String?
        let restoreState: CsvRestoreState?
    }

    /// Beide Läufe nacheinander rückgängig (beide Reihenfolgen): Zustand nach jedem Schritt, am Ende nichts aus ihnen.
    func testUndoBothImportsInSequence() throws {
        func rows(_ value: Any?) -> [Row] {
            S.list(value).map { Row(id: id($0["id"]) ?? -1, importBatch: string($0["importBatch"]), importKey: string($0["importKey"]),
                                    linkedImportKey: string($0["linkedImportKey"]), restoreState: restoreState($0["restoreState"])) }
        }
        for c in S.list(try data()["undoSequence"]) {
            var current = rows(c["rows"])
            for (batch, after) in zip(strings(c["batches"]), (c["after"] as? [Any]) ?? []) {
                let plan = CsvTransferMatch.undoPlan(
                    current.map { CsvImportedRef(id: $0.id, importBatch: $0.importBatch, restoreState: $0.restoreState) }, batch: batch)
                // Wie `AppData.undoPortfolioImport`: Verknüpfung und Zustand fallen weg
                current = current.compactMap { r -> Row? in
                    if let o = plan.revert[r.id] {
                        return Row(id: r.id, importBatch: o.importBatch, importKey: o.importKey, linkedImportKey: nil, restoreState: nil)
                    }
                    if let a = plan.detach[r.id] {
                        return Row(id: r.id, importBatch: r.importBatch, importKey: a.importKey, linkedImportKey: nil, restoreState: nil)
                    }
                    return plan.delete.contains(r.id) ? nil : r
                }
                XCTAssertEqual(current, rows(after), "sequence \(string(c["_comment"]) ?? "?") after \(batch)")
            }
        }
    }

    /// Beide Seiten einer Verknüpfung gelten als importiert, auch von älteren Versionen gekürzte lange Teile.
    func testLinkedKeysCountAsImported() throws {
        let d = try XCTUnwrap(try data()["duplicates"] as? [String: Any])
        let long = string(d["longKey"]) ?? ""
        let existing = Set(strings(d["existing"])).union([LegacyStorageMigration.shortKey(long)])
        func row(_ key: String) -> CsvImportRow {
            CsvImportRow(line: 1, kind: .DEPOSIT, rawKind: "Deposit", coin: "BTC", amount: 1, price: nil, quote: nil, fee: nil,
                         feeUnit: nil, timeMillis: 0, externalId: nil, key: key)
        }
        let known = strings(d["known"]) + [long]
        let split = CsvImport.splitDuplicates((known + strings(d["unknown"])).map(row), existingKeys: existing)
        XCTAssertEqual(split.duplicates.map(\.key), known)
        XCTAssertEqual(split.fresh.map(\.key), strings(d["unknown"]))
    }

    /// Verknüpft wird ohne Längengrenze: lange Schlüssel bleiben vollständig, kein Zustand geht verloren.
    func testLongKeysLinkWithoutLimit() throws {
        let c = try XCTUnwrap(try cases().first { string($0["name"]) == "stored side with separators in its text and a long key both link" })
        let found = links(c)
        let plan = CsvTransferMatch.apply(S.list(c["txs"]).map(tx), links: found, chosen: CsvTransferMatch.defaultChoice(found))
        let long = try XCTUnwrap(plan.storedUpdates.first { $0.id == 13 })
        // In älteren Versionen nicht verknüpft (Teil über 98 Zeichen passte nicht in den gepackten Schlüssel)
        XCTAssertEqual(long.importKey, S.list(c["stored"]).first { id($0["id"]) == 13 }.flatMap { string($0["importKey"]) })
        XCTAssertGreaterThan(long.importKey.count, 98)
        XCTAssertNil(plan.transactions.first { $0.importKey == "COINBASE|X11" })
    }

    /// Ende zu Ende: gespeicherte Binance-Auszahlung, danach der Coinbase-Bericht.
    func testEndToEndAcrossFiles() throws {
        let e = try XCTUnwrap(try data()["endToEnd"] as? [String: Any])
        func preview(_ name: String) throws -> CsvImportPreview {
            CsvImportMapping.preview(CsvImportParser.parse(try CsvExchangeImportTests.csv(name)), existingKeys: []) { _, _ in nil }
        }
        let old = try XCTUnwrap(try preview(string(e["storedFile"]) ?? "").transactions.first {
            $0.type.rawValue == string(e["storedType"])
        })
        let stored = CsvStoredMove(id: 1, coin: old.coin, type: old.type, amount: old.amount, fee: old.fee, feeUnit: old.feeUnit,
                                   feeCoin: old.feeCoin, feeCoinAmount: old.feeCoinAmount, time: old.time,
                                   importKey: old.importKey, transferFrom: nil, transferTo: nil)
        let fresh = try preview(string(e["importFile"]) ?? "")
        let found = CsvTransferMatch.candidates(fresh.transactions, place: fresh.format.exchangeName, stored: [stored])
        assertLinks("endToEnd", S.list(e["links"]), found)
        let plan = CsvTransferMatch.apply(fresh.transactions, links: found, chosen: CsvTransferMatch.defaultChoice(found))
        XCTAssertEqual(plan.transactions.count, fresh.transactions.count - 1)
        XCTAssertEqual(plan.storedUpdates.map(\.id), [1])
        // Gespeichert erkennt der nächste Import beide Seiten als doppelt (importKey + linkedImportKey)
        let u = try XCTUnwrap(plan.storedUpdates.first)
        XCTAssertEqual(Set([u.importKey, u.linkedImportKey]), Set([old.importKey, found[0].depositKey]))
    }
}
