import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `cost_fx.json` (Android: `PortfolioCostFxParityTest.kt`): «Seit Kauf» und Einstand der
/// Gesamtkarte in der Anzeigewährung zum Kurs der Erwerbstage (`PortfolioCostFx`) — FIFO, FIFO je Wallet und Ø, fehlender
/// Devisenkurs, Kurs bis 7 Tage davor, Kauf heute.
final class PortfolioCostFxTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func decMap(_ value: Any?) -> [Int: Decimal] {
        var out: [Int: Decimal] = [:]
        for (key, v) in value as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(v) { out[k] = r }
        }
        return out
    }

    private func trades(_ value: Any?) -> [PortfolioTx] {
        S.list(value).map { t in
            PortfolioTx(id: (t["id"] as? NSNumber)?.int64Value ?? 0, coin: t["coin"] as? String ?? "",
                        type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                        amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]),
                        time: (t["time"] as? NSNumber)?.int64Value ?? 0)
        }
    }

    private func prices(_ value: Any?) -> [String: Double] {
        var out: [String: Double] = [:]
        for (coin, v) in value as? [String: Any] ?? [:] { out[coin] = S.number(v) }
        return out
    }

    private func summary(_ c: [String: Any]) -> PortfolioSummary {
        PortfolioCalculator.summarize(trades(c["trades"]), prices: prices(c["prices"]),
                                      method: CostMethod.fromName(c["method"] as? String))
    }

    func testCases() throws {
        let d = try S.fixture("cost_fx")
        let currency = d["currency"] as? String ?? ""
        let today = (d["todayEpochDay"] as? NSNumber)?.intValue ?? 0
        let currentRate = try XCTUnwrap(dec(d["currentRate"]))
        let cases = S.list(d["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let s = summary(c)
            let e = c["expected"] as? [String: Any] ?? [:]
            XCTAssertEqual(PortfolioCostFx.firstDay(s), (e["firstDay"] as? NSNumber)?.intValue, "\(name) firstDay")
            let f = PortfolioCostFx.figures(s, currency: currency, currentRate: currentRate, todayEpochDay: today,
                                            rates: decMap(c["rates"]), usdtUsd: decMap(c["usdtUsd"]))
            XCTAssertEqual(f.unrealized, dec(e["unrealized"]), "\(name) unrealized")
            XCTAssertEqual(f.invested, dec(e["invested"]), "\(name) invested")
            if let percent = S.number(e["percent"]) {
                XCTAssertEqual(try XCTUnwrap(f.unrealizedPercent, "\(name) percent"), percent, accuracy: 1e-9, "\(name) percent")
            } else {
                XCTAssertNil(f.unrealizedPercent, "\(name) percent")
            }
            XCTAssertEqual(f.unrealizedFxMissing, e["unrealizedFxMissing"] as? Bool, "\(name) unrealizedFxMissing")
            XCTAssertEqual(f.investedFxMissing, e["investedFxMissing"] as? Bool, "\(name) investedFxMissing")
            XCTAssertEqual(f.fxMissingCoins, e["fxMissingCoins"] as? [String], "\(name) fxMissingCoins")
            XCTAssertEqual(f.usdtApproximate, e["usdtApproximate"] as? Bool, "\(name) usdtApproximate")
        }
    }

    /// Teile des Einstands gehen exakt auf den Einstand des Teils mit Kaufpreis auf (alle Methoden).
    func testPartsSumToPricedCost() throws {
        let d = try S.fixture("cost_fx")
        for c in S.list(d["cases"]) {
            for p in summary(c).open {
                guard let cost = p.pricedCostExact else { continue }
                let sum = p.pricedCostParts.reduce(Decimal(0)) { $0 + $1.usdt }
                XCTAssertEqual(sum, cost, "\(c["name"] ?? "") \(p.coin) parts")
            }
        }
    }
}
