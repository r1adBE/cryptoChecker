import XCTest
@testable import CryptoChecker

/// Einstand für Adressguthaben und Einträge ohne Einstand (`Parity/address_cost_lots.json`) — wie
/// `AddressCostLotsParityTest.kt`: Abdeckung, Adress-Teil ohne Einstand, Rechner-Einträge, Karte je Methode, Stichtag
/// (CH-Vermögenswert ohne Posten), FR-Erwerbspreis-Summe samt Vermerk «Adressen ohne Einstand», Steuer-Export (Info-Zeilen,
/// Jahre), Bestand je Wallet bei erfassten Umbuchungen von/zu der Adress-Wallet, Stichtag/CH-Vermögen wie die Karte
/// («cutoff», `AddressCutoff`).
final class AddressCostLotsParityTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ raw: Any?) -> Decimal? {
        (raw as? String).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) }
    }

    private func lot(_ raw: [String: Any]) -> AddressCostLot {
        AddressCostLot(id: Int64(S.number(raw["id"]) ?? 0), coin: raw["coin"] as? String ?? "",
                       time: Int64(S.number(raw["t"]) ?? 0), amount: dec(raw["amount"]) ?? 0,
                       priceUsdt: dec(raw["price"]), costUsdt: dec(raw["cost"]))
    }

    private func address(_ o: [String: Any]) -> WatchedAddress {
        WatchedAddress(id: Int64(S.number(o["id"]) ?? 0), chain: AddressChain(rawValue: o["chain"] as? String ?? "") ?? .BTC,
                       address: o["address"] as? String ?? "", label: o["label"] as? String,
                       lastBalance: o["lastBalance"] as? String,
                       lastUpdated: Int64(S.number(o["lastUpdated"]) ?? 0),
                       observations: S.list(o["observations"]).map {
                           AddressObservation(time: Int64(S.number($0["t"]) ?? 0), balance: dec($0["b"]) ?? 0)
                       },
                       countInPortfolio: o["countInPortfolio"] as? Bool ?? true,
                       costLots: S.list(o["costLots"]).map(lot))
    }

    private func trade(_ o: [String: Any]) -> PortfolioTx {
        var t = PortfolioTx(id: Int64(S.number(o["id"]) ?? 0), coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"]) ?? 0, priceExact: dec(o["price"]),
                            time: Int64(S.number(o["time"]) ?? 0), exchange: o["exchange"] as? String)
        t.interest = o["interest"] as? Bool ?? false
        t.feeExact = dec(o["fee"])
        t.feeUnit = (o["feeUnit"] as? String).flatMap(PortfolioFeeUnit.init(rawValue:))
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        return t
    }

    /// Bis auf Rundung der letzten Stellen (Android rechnet mit 34 Stellen).
    private func near(_ label: String, _ expected: Any?, _ actual: Decimal?) {
        guard let e = dec(expected) else {
            XCTAssertNil(actual, label)
            return
        }
        guard let actual else { return XCTFail("\(label): nil ≠ \(e)") }
        let diff: Decimal = abs(e - actual)
        XCTAssertTrue(diff < Decimal(string: "1e-20")!, "\(label): \(actual) ≠ \(e)")
    }

    /// Stichtag/CH-Vermögen (`AddressCutoff`): Posten, Adress-Teil ohne Einstand, Hinweis; Bestand je Methode wie erwartet
    /// und — Stichtag nach allen Buchungen und Abrufen — je Coin dieselbe Menge wie die Karte. Wie Android `checkCutoff`.
    private func checkCutoff(_ name: String, _ x: [String: Any], _ addresses: [WatchedAddress], _ real: [PortfolioTx],
                             _ methods: [String: Any], _ all: [PortfolioTx]) throws {
        let time = Int64(S.number(x["time"]) ?? 0)
        let view = AddressCutoff.at(addresses, real: real, cutoffMillis: time)
        XCTAssertEqual(Int(S.number(x["lots"]) ?? -1), view.lots.count, "\(name) cutoff lots")
        XCTAssertEqual(x["note"] as? Bool, view.lotsAfterObservation, "\(name) cutoff note")
        let address = x["address"] as? [String: Any] ?? [:]
        XCTAssertEqual(Set(address.keys), Set(view.part.balances.keys), "\(name) cutoff address coins")
        for (coin, v) in address { near("\(name) cutoff address \(coin)", v, view.part.balances[coin]) }
        let expected = x["holdings"] as? [String: Any] ?? [:]
        for m in methods.isEmpty ? ["FIFO"] : Array(methods.keys) {
            let method = try XCTUnwrap(CostMethod(rawValue: m))
            var held: [String: Decimal] = [:]
            for h in AddressCutoff.holdingsAt(real, view: view, cutoffMillis: time, method: method) { held[h.coin] = h.amount }
            XCTAssertEqual(Set(expected.keys), Set(held.keys), "\(name)/\(m) cutoff coins")
            for (coin, v) in expected { near("\(name)/\(m) cutoff \(coin)", v, held[coin]) }
            for coin in (methods[m] as? [String: Any] ?? [:]).keys {
                let card = PortfolioCalculator.position(coin, trades: all, currentPrice: nil, method: method).holdingsExact
                let file = (held[coin] ?? 0) + (view.part.balances[coin] ?? 0)
                near("\(name)/\(m) cutoff = card \(coin)", "\(card)", file)
            }
        }
    }

    func testLotsFeedTheCalculators() throws {
        let data = try S.fixture("address_cost_lots")
        let today = Int64(S.number(data["today"]) ?? 0)
        for c in S.list(data["cases"]) {
            let name = c["name"] as? String ?? ""
            let addresses = S.list(c["addresses"]).map(address)
            let real = S.list(c["trades"]).map(trade)
            for x in S.list(c["coverage"]) {
                let a = try XCTUnwrap(addresses.first { $0.id == Int64(S.number(x["address"]) ?? -1) })
                let got = try XCTUnwrap(AddressCostLots.coverage(a, trades: real, all: addresses).first { $0.coin == x["coin"] as? String })
                near("\(name) lotted", x["lotted"], got.lotted)
                near("\(name) excess", x["excess"], got.excess)
                near("\(name) unlotted", x["unlotted"], got.unlotted)
            }
            let all = AddressBalance.withAddresses(real, addresses: addresses, todayUtcDay: today)
            let part = AddressBalance.split(all).address
            let expectedPart = c["addressPart"] as? [String: Any] ?? [:]
            XCTAssertEqual(Set(expectedPart.keys), Set(part.keys), "\(name) address coins")
            for (coin, v) in expectedPart { near("\(name) address \(coin)", v, part[coin]) }
            let lots = all.filter { AddressCostLots.isLotTrade($0.id) }
            let expectedLots = S.list(c["lotTrades"])
            XCTAssertEqual(expectedLots.count, lots.count, "\(name) lot trades")
            for (x, t) in zip(expectedLots, lots) {
                XCTAssertEqual(Int64(S.number(x["id"]) ?? 0), t.id, "\(name) lot id")
                XCTAssertEqual(t.type, .BUY, "\(name) lot type")
                XCTAssertEqual(Int64(S.number(x["time"]) ?? 0), t.time, "\(name) lot time")
                XCTAssertEqual(x["exchange"] as? String, t.exchange, "\(name) lot wallet")
                near("\(name) lot amount", x["amount"], t.amountExact)
                near("\(name) lot price", x["price"], t.priceExact)
            }
            // Stichtag/CH-Vermögenswert: weder Posten noch Adress-Teil
            var held: [String: Decimal] = [:]
            for h in PortfolioCalculator.holdingsAt(all) { held[h.coin] = h.amount }
            let expectedHeld = c["holdingsAt"] as? [String: Any] ?? [:]
            XCTAssertEqual(Set(expectedHeld.keys), Set(held.keys), "\(name) holdingsAt coins")
            for (coin, v) in expectedHeld { near("\(name) holdingsAt \(coin)", v, held[coin]) }
            for (m, coins) in c["methods"] as? [String: Any] ?? [:] {
                let method = try XCTUnwrap(CostMethod(rawValue: m))
                for (coin, spec) in coins as? [String: Any] ?? [:] {
                    let x = spec as? [String: Any] ?? [:]
                    let p = PortfolioCalculator.position(coin, trades: all, currentPrice: nil, method: method)
                    near("\(name)/\(m) holdings", x["holdings"], p.holdingsExact)
                    near("\(name)/\(m) address", x["address"], p.addressHoldingsExact)
                    if x["cost"] != nil { near("\(name)/\(m) cost", x["cost"], p.costBasisExact) }
                    if x["realized"] != nil { near("\(name)/\(m) realized", x["realized"], p.realizedExact) }
                }
            }
            // FIFO je Wallet: Bestand je Wallet (Adress-Wallet samt erfasster Umbuchungen), kein Fehlbestand
            for (coin, wallets) in c["wallets"] as? [String: Any] ?? [:] {
                var got: [String: Decimal] = [:]
                for l in PortfolioCalculator.openLots(coin, trades: all, method: .FIFO_WALLET) {
                    got[l.wallet ?? "", default: 0] += l.amount
                }
                let expected = wallets as? [String: Any] ?? [:]
                XCTAssertEqual(Set(expected.keys), Set(got.keys), "\(name) wallets \(coin)")
                for (w, v) in expected { near("\(name) wallet \(w)", v, got[w]) }
            }
            if let x = c["cutoff"] as? [String: Any] {
                try checkCutoff(name, x, addresses, real, c["methods"] as? [String: Any] ?? [:], all)
            }
            if let n = S.number(c["shortfalls"]) {
                XCTAssertEqual(Int(n), PortfolioCalculator.walletShortfalls(all).count, "\(name) shortfalls")
            }
            if let fr = c["fr"] as? [String: Any] {
                let price = dec(fr["price"]) ?? 0
                let rows = TaxEngines.disposals(all, method: .FR_PTA, timeZone: TimeZone(identifier: "UTC")!,
                                                priceAt: { _, _ in price })
                XCTAssertEqual(rows.count, 1, "\(name) fr rows")
                let d = try XCTUnwrap(rows.first)
                near("\(name) fr cost", fr["cost"], d.costUsdt)
                XCTAssertEqual(d.addressValueIncluded, fr["addressValueIncluded"] as? Bool, "\(name) fr address note")
            }
        }
    }

    /// Steuer-Export rechnet mit den Posten wie die Veräusserungen: keine Gebühr «ohne Kaufpreis», Jahr nur mit Posten.
    func testTaxExportSeesLots() throws {
        let data = try S.fixture("address_cost_lots")
        let c = try XCTUnwrap(data["taxExport"] as? [String: Any])
        let utc = TimeZone(identifier: "UTC")!
        let real = S.list(c["trades"]).map(trade)
        let all = AddressBalance.withAddresses(real, addresses: S.list(c["addresses"]).map(address),
                                               todayUtcDay: Int64(S.number(data["today"]) ?? 0))
        func ints(_ key: String) -> [Int] { (c[key] as? [Any] ?? []).compactMap { S.number($0).map { Int($0) } } }
        XCTAssertEqual(TaxExport.years(all, timeZone: utc), ints("years"), "years")
        XCTAssertEqual(TaxExport.years(real, timeZone: utc), ints("yearsWithoutAddresses"), "years without addresses")
        let year = Int(S.number(c["infoYear"]) ?? 0)
        for m in c["methods"] as? [String] ?? [] {
            let method = try XCTUnwrap(CostMethod(rawValue: m))
            XCTAssertEqual(TaxExport.unassignedFees(in: all, method: method, year: year, timeZone: utc).count,
                           Int(S.number(c["unassignedFees"]) ?? -1), "\(m) unassigned")
            XCTAssertEqual(TaxExport.lostFeeCosts(in: all, method: method, year: year, timeZone: utc).count,
                           Int(S.number(c["lostFeeCosts"]) ?? -1), "\(m) lost fee cost")
        }
    }

    func testGapsAndSuggestions() throws {
        let g = try XCTUnwrap(try S.fixture("address_cost_lots")["gaps"] as? [String: Any])
        let trades = S.list(g["trades"]).map(trade)
        for (template, list) in g["expected"] as? [String: Any] ?? [:] {
            let got = PortfolioCostGaps.gaps(trades, template: try XCTUnwrap(TaxTemplate(rawValue: template)))
            let expected = S.list(list)
            XCTAssertEqual(expected.map { Int64(S.number($0["id"]) ?? 0) }, got.map(\.trade.id), "\(template) ids")
            XCTAssertEqual(expected.map { $0["kind"] as? String ?? "" }, got.map(\.kind.rawValue), "\(template) kinds")
        }
        let gaps = PortfolioCostGaps.gaps(trades, template: .CH)
        let acknowledged = Set((g["acknowledged"] as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } })
        let pending = (g["pending"] as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } }
        XCTAssertEqual(pending, PortfolioCostGaps.pending(gaps, acknowledged: acknowledged).map(\.trade.id))
        let pruned = Set((g["pruned"] as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } })
        XCTAssertEqual(pruned, PortfolioCostGaps.prune(acknowledged, gaps: gaps))
        for x in S.list(g["suggestions"]) {
            let amount = dec(x["amount"]) ?? 0
            let close = dec(x["close"])
            near("lot suggestion", x["expected"], AddressCostLots.suggestion(amount: amount, close: close))
            let t = PortfolioTx(id: 1, coin: "BTC", type: .DEPOSIT, amountExact: amount, priceExact: nil, time: 1)
            near("gap suggestion", x["expected"], PortfolioCostGaps.suggestion(t, close: close))
        }
        for x in S.list(g["priceFromTotal"]) {
            near("priceFromTotal", x["expected"], PortfolioCostGaps.priceFromTotal(dec(x["total"]), amount: dec(x["amount"]) ?? 0))
        }
    }
}
