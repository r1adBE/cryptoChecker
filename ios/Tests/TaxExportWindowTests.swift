import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `tax_export.json` → `ukBbWindow` (Android: `TaxExportWindowParityTest.kt`): UK 30-Tage-Regel über
/// das Ende des Steuerjahrs hinaus — Kurse und Tagesschlüsse bis 30 Tage nach dem 5. April, Einstand in GBP zum Kurs des
/// Zugangsdatums, kein Vermerk «ohne Devisenkurs».
final class TaxExportWindowTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] {
        try XCTUnwrap(try S.fixture("tax_export")["ukBbWindow"] as? [String: Any])
    }

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func day(_ value: Any?) -> LocalDay { LocalDay(iso: value as? String ?? "") ?? LocalDay(1970, 1, 1) }

    private func trades(_ d: [String: Any]) -> [PortfolioTx] {
        S.list(d["trades"]).map { t in
            PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                        type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                        amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]), time: int64(t["time"]),
                        feeExact: nil, feeUnit: nil)
        }
    }

    private func rates(_ d: [String: Any]) -> [Int: Decimal] {
        var out: [Int: Decimal] = [:]
        for (key, value) in d["rates"] as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(value) { out[k] = r }
        }
        return out
    }

    /// Tagesschluss der Belohnung ohne Preis (wie `feeDisposalPrices`, `TaxDayClose.lookup`).
    private func priceAt(_ d: [String: Any]) -> (String, Int64) -> Decimal? {
        var closes: [String: Decimal] = [:]
        for c in S.list(d["closes"]) {
            if let close = dec(c["close"]) { closes[TaxDayClose.key(c["coin"] as? String ?? "", Int(int64(c["day"])))] = close }
        }
        return TaxDayClose.lookup(closes)
    }

    func testPriceUntilCoversTheWindowAfterTheTaxYear() throws {
        let d = try data()
        let year = Int(int64(d["year"]))
        for case let c as [Any] in d["priceUntil"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            XCTAssertEqual(TaxExport.priceUntil(tpl, period: tpl.period(year), today: day(c[1])), day(c[2]), "priceUntil \(c)")
        }
    }

    func testFxSpanReachesTheLatestMatchedAcquisition() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let period = TaxTemplate.UK.period(Int(int64(d["year"])))
        let today = day(d["today"])
        let txs = trades(d)
        let rules = TaxTemplate.UK.eventRules
        let until = TaxExport.priceUntil(.UK, period: period, today: today)
        let span = TaxExport.fxSpan(txs, method: .UK_POOL, period: period, timeZone: zone, priceAt: priceAt(d), rules: rules,
                                    until: until, today: today)
        // Erster Tag: Beginn des Steuerjahrs (der Pool-Kauf 1.6.2024 liegt darin)
        XCTAssertEqual(span.from, period.start)
        XCTAssertEqual(span.until, day(d["fxUntil"]))
        // Ohne Fenster: das späteste Datum eines genutzten Zugangs (Rückkauf 20.4.)
        let latest = TaxExport.fxSpan(txs, method: .UK_POOL, period: period, timeZone: zone, priceAt: priceAt(d), rules: rules,
                                      today: today)
        XCTAssertEqual(latest.until, day(d["fxUntilWithoutWindow"]))
        // fxFrom unverändert
        XCTAssertEqual(TaxExport.fxFrom(txs, method: .UK_POOL, period: period, timeZone: zone, priceAt: priceAt(d), rules: rules),
                       span.from)
    }

    func testCostInGbpAtTheRateOfTheAcquisitionDate() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let period = TaxTemplate.UK.period(Int(int64(d["year"])))
        let currency = d["currency"] as? String ?? ""
        let txs = trades(d)
        let rows = TaxExport.gainRows(txs, method: .UK_POOL, period: period, timeZone: zone, currency: currency,
                                      rates: rates(d), priceAt: priceAt(d), rules: TaxTemplate.UK.eventRules)
        let expected = S.list(d["rows"])
        XCTAssertEqual(rows.count, expected.count)
        for e in expected {
            let acquired: LocalDay? = (e["acquired"] as? String).flatMap { LocalDay(iso: $0) }
            let matching = rows.filter { $0.acquiredDate == acquired }
            XCTAssertEqual(matching.count, 1, "row \(String(describing: acquired))")
            guard let r = matching.first else { continue }
            let label = "row \(String(describing: acquired))"
            XCTAssertEqual(r.match, TaxMatch(rawValue: e["match"] as? String ?? ""), label)
            XCTAssertEqual(r.amount, dec(e["amount"]), label)
            XCTAssertEqual(r.costTarget, dec(e["costTarget"]), "\(label) cost")
            XCTAssertEqual(r.proceedsTarget, dec(e["proceedsTarget"]), "\(label) proceeds")
            XCTAssertFalse(r.fxMissing, "\(label) noFx")
        }
        // Mit Kursen nur bis zum Ende des Steuerjahrs: Zugänge nach dem 5. April ohne Devisenkurs
        let truncated = rates(d).filter { $0.key <= period.end.epochDay }
        let old = TaxExport.gainRows(txs, method: .UK_POOL, period: period, timeZone: zone, currency: currency,
                                     rates: truncated, priceAt: priceAt(d), rules: TaxTemplate.UK.eventRules)
        XCTAssertEqual(old.filter(\.fxMissing).count, Int(int64(d["truncatedFxMissing"])))
    }
}
