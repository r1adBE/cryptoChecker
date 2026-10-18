import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `tax_templates.json` (Android: `TaxTemplatesParityTest.kt`).
final class TaxTemplatesTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("tax_templates") }

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func day(_ value: Any?) -> LocalDay { LocalDay(iso: value as? String ?? "") ?? LocalDay(1970, 1, 1) }

    /// Vergleich auf 8 Nachkommastellen (Teilungen runden auf 34 gültige Stellen).
    private func r8(_ value: Decimal?) -> Decimal? {
        guard var v = value else { return nil }
        var out = Decimal()
        NSDecimalRound(&out, &v, 8, .plain)
        return out
    }

    private func trades(_ raw: Any?) -> [PortfolioTx] {
        S.list(raw).map { (t: [String: Any]) -> PortfolioTx in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]), time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.toCoin = t["toCoin"] as? String
            tx.toAmountExact = dec(t["toAmount"])
            tx.swapValueExact = dec(t["swapValue"])
            tx.feeCoin = t["feeCoin"] as? String
            tx.feeCoinAmountExact = dec(t["feeCoinAmount"])
            tx.feeCoinPriceExact = dec(t["feeCoinPrice"])
            tx.exchange = t["exchange"] as? String
            tx.transferFrom = t["transferFrom"] as? String
            tx.transferTo = t["transferTo"] as? String
            tx.giftToSpouse = t["giftToSpouse"] as? Bool ?? false
            tx.acquiredTime = TestSupport.number(t["acquiredTime"]).map { Int64($0) }
            tx.importKey = t["importKey"] as? String
            tx.note = t["note"] as? String
            // Gebühr-Zeile und Zins: eigene Felder (Build 76); fehlen sie im Fixture, die frühere Regel
            tx.feeRow = t["feeRow"] as? Bool
                ?? LegacyStorageMigration.legacyFeeRow(tx.type, importKey: tx.importKey, price: tx.priceExact)
            tx.interest = t["interest"] as? Bool ?? LegacyStorageMigration.legacyInterest(tx.type, note: tx.note)
            return tx
        }
    }

    /// Devisenkurse «Tag → USD→Währung» als Tageskurs-Tabelle des Exports (epochDay) — wie Android.
    private func rates(_ raw: Any?) -> [Int: Decimal]? {
        guard let map = raw as? [String: Any] else { return nil }
        var out: [Int: Decimal] = [:]
        for (k, v) in map {
            if let d = LocalDay(iso: k), let r = dec(v) { out[d.epochDay] = r }
        }
        return out
    }

    private func priceAt(_ raw: Any?) -> ((String, Int64) -> Decimal?)? {
        guard let map = raw as? [String: Any] else { return nil }
        var prices: [String: Decimal] = [:]
        for (k, v) in map { prices[k] = dec(v) }
        return { coin, _ in prices[coin] }
    }

    /// US-Übergang 1.1.2025 (Rev. Proc. 2024-28): universelles FIFO bis 31.12.2024, Restposten den Wallets zugeordnet —
    /// wie Android `usTransition`.
    func testUsTransition() throws {
        let d = try data()
        for case let c as [Any] in d["walletFrom"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            let got = tpl.walletFrom(CostMethod.fromName(c[1] as? String), Int(int64(c[2])))
            XCTAssertEqual(got, (c[3] as? String).map { day($0) }, "walletFrom \(c)")
        }
        let u = try XCTUnwrap(d["usTransition"] as? [String: Any])
        let tpl = try XCTUnwrap(TaxTemplate(rawValue: u["template"] as? String ?? ""))
        let app = CostMethod.fromName(u["app"] as? String)
        let year = Int(int64(u["year"]))
        let txs = trades(u["trades"])
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? "UTC"))
        let walletFrom = tpl.walletFrom(app, year)
        let rows = TaxExport.gainRows(txs, method: tpl.methodFor(app, year), period: tpl.period(year), timeZone: zone,
                                      currency: "USD", rates: nil, walletFrom: walletFrom)
        let expected = S.list(u["disposals"])
        XCTAssertEqual(rows.count, expected.count)
        for (e, r) in zip(expected, rows) {
            XCTAssertEqual(r.coin, e["coin"] as? String)
            XCTAssertEqual(r.soldDate, day(e["sold"]))
            XCTAssertEqual(r.acquiredDate, day(e["acquired"]))
            XCTAssertEqual(r8(r.amount), r8(dec(e["amount"])))
            XCTAssertEqual(r8(r.costUsdt), r8(dec(e["cost"])))
            XCTAssertEqual(r8(r.proceedsUsdt), r8(dec(e["proceeds"])))
            XCTAssertEqual(r8(r.gainUsdt), r8(dec(e["gain"])))
            XCTAssertEqual(r.wallet, e["wallet"] as? String)
        }
        // Ohne Übergang (FIFO je Wallet über die ganze Geschichte): ergäbe einen falschen Einstand für Käufe vor 2025
        let plain = TaxExport.gainRows(txs, method: .FIFO_WALLET, period: tpl.period(year), timeZone: zone,
                                       currency: "USD", rates: nil)
        XCTAssertEqual(plain.count, 1)
        XCTAssertEqual(r8(plain.first?.gainUsdt), r8(dec(u["withoutTransitionGain"])))
        // Jahre davor unverändert universell
        let y2023 = TaxExport.gainRows(txs, method: tpl.methodFor(app, 2023), period: tpl.period(2023), timeZone: zone,
                                       currency: "USD", rates: nil)
        XCTAssertEqual(y2023.count, 1)
        XCTAssertEqual(r8(y2023.first?.gainUsdt), r8(dec(u["year2023Gain"])))
        // Übersicht rechnet mit demselben Übergang
        let s = TaxExport.yearSummary(txs, template: tpl, method: tpl.methodFor(app, year), startYear: year, timeZone: zone,
                                      currency: "USD", rates: nil, walletFrom: walletFrom)
        XCTAssertEqual(s.walletFrom, walletFrom)
    }

    /// Steuerjahr-Grenze in der Ortszeit (UK 6. April, AU 1. Juli, JP 1. Januar): ein Verkauf kurz nach Mitternacht
    /// Ortszeit gehört ins neue Steuerjahr, auch wenn es in UTC noch der Vortag ist. Wie Android `zoneBoundary`.
    func testZoneBoundary() throws {
        for c in S.list(try data()["zoneBoundary"]) {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c["template"] as? String ?? ""))
            let tz = try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? ""))
            let year = Int(int64(c["year"]))
            let sell = PortfolioTx(id: 2, coin: "BTC", type: .SELL, amountExact: 1, priceExact: 200, time: int64(c["sellTime"]))
            let trades = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 100, time: 1_577_836_800_000), sell]
            let label = "zoneBoundary \(c)"
            XCTAssertEqual(TaxExport.years([sell], timeZone: tz, template: tpl), [year], label)
            let method = tpl.methodFor(.FIFO, year)
            XCTAssertEqual(TaxExport.disposals(in: trades, method: method, period: tpl.period(year), timeZone: tz).count, 1, label)
            XCTAssertEqual(TaxExport.disposals(in: trades, method: method, period: tpl.period(year - 1), timeZone: tz).count, 0, label)
            XCTAssertEqual(TaxExport.disposals(in: trades, method: method, period: tpl.period(year + 1), timeZone: tz).count, 0, label)
        }
    }

    func testPeriodsLabelsSuggestions() throws {
        let d = try data()
        for case let c as [Any] in d["periodOf"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            XCTAssertEqual(tpl.periodOf(day(c[1])), Int(int64(c[2])), "periodOf \(c)")
        }
        for case let c as [Any] in d["period"] as? [Any] ?? [] {
            let p = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? "")).period(Int(int64(c[1])))
            XCTAssertEqual(p.start, day(c[2]), "start \(c)")
            XCTAssertEqual(p.end, day(c[3]), "end \(c)")
        }
        for case let c as [Any] in d["labels"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            XCTAssertEqual(tpl.label(Int(int64(c[1]))), c[2] as? String, "label \(c)")
        }
        for case let c as [Any] in d["suggest"] as? [Any] ?? [] {
            XCTAssertEqual(TaxTemplate.suggest(c[0] as? String ?? ""), TaxTemplate(rawValue: c[1] as? String ?? ""), "suggest \(c)")
        }
        for case let c as [Any] in d["methodFor"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            let got = tpl.methodFor(CostMethod.fromName(c[1] as? String))
            XCTAssertEqual(got, TaxMethod(rawValue: c[2] as? String ?? ""), "methodFor \(c)")
        }
        // Vorlage verlangt eine andere Methode als die App-Einstellung: Hinweis in Kopfzeile, Blatt und Einstellungen
        for case let c as [Any] in d["methodOverridden"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            let app = CostMethod.fromName(c[1] as? String)
            XCTAssertEqual(tpl.methodOverridden(app), c[2] as? Bool, "methodOverridden \(c)")
            XCTAssertEqual(tpl.methodOverridden(app), tpl.methodFor(app) != TaxMethod.of(app), "methodOverridden/methodFor \(c)")
        }
        // Methode je Steuerjahr (US ab 2025 nur FIFO je Wallet, Rev. Proc. 2024-28)
        for case let c as [Any] in d["methodForYear"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            let app = CostMethod.fromName(c[1] as? String)
            let year = Int(int64(c[2]))
            XCTAssertEqual(tpl.methodFor(app, year), TaxMethod(rawValue: c[3] as? String ?? ""), "methodForYear \(c)")
            XCTAssertEqual(tpl.methodOverridden(app, year), c[4] as? Bool, "methodOverriddenYear \(c)")
        }
        for case let c as [Any] in d["ukExempt"] as? [Any] ?? [] {
            XCTAssertEqual(TaxTemplate.ukAnnualExemptGbp(Int(int64(c[0]))), dec(c[1]), "ukExempt \(c)")
        }
        // Build 56: Regeln je Vorlage (Schenkung, Schenkung an Ehe-/Lebenspartner, Netzwerkgebühr); Partner-Merker (Build 58: Feld)
        for case let c as [Any] in d["eventRules"] as? [Any] ?? [] {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c[0] as? String ?? ""))
            let expected = TaxEventRules(giftIsDisposal: c[1] as? Bool ?? false, spouseGiftNeutral: c[2] as? Bool ?? false,
                                         networkFeeIsDisposal: c[3] as? Bool ?? false,
                                         quoteFeeIsInfo: c[4] as? Bool ?? false)
            XCTAssertEqual(tpl.eventRules, expected, "eventRules \(c)")
        }
        for case let c as [Any] in d["spouseFlag"] as? [Any] ?? [] {
            var tx = PortfolioTx(coin: "BTC", type: try XCTUnwrap(PortfolioTxType(rawValue: c[0] as? String ?? "")),
                                 amountExact: 1, time: 0)
            tx.giftToSpouse = c[1] as? Bool ?? false
            XCTAssertEqual(tx.isGiftToSpouse, c[2] as? Bool, "spouseFlag \(c)")
        }
        XCTAssertEqual(TaxTemplate.fromName(nil), .NEUTRAL)
        XCTAssertEqual(TaxTemplate.fromName("XX"), .NEUTRAL)
        XCTAssertEqual(TaxTemplate.fromName("UK"), .UK)
        // Unbekannter Wert beim Dekodieren (neuere Version): Neutral
        let decoded = try JSONDecoder().decode([TaxTemplate].self, from: Data("[\"XX\",\"FR\"]".utf8))
        XCTAssertEqual(decoded, [.NEUTRAL, .FR])
    }

    func testEngines() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let cases = S.list(d["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            // CA: Superficial Loss in der Steuerwährung (Kurse je Tag wie im Export)
            var fx: ((Int64) -> Decimal?)? = nil
            if let fxRates = rates(c["fx"]) {
                fx = { (t: Int64) -> Decimal? in
                    let d = LocalDay(date: Date(timeIntervalSince1970: Double(t) / 1000), timeZone: zone)
                    return TaxExport.rateOn(fxRates, date: d, currency: "CAD")
                }
            }
            // Schenkung und Netzwerkgebühr nach der Vorlage («template», z. B. AU/US mit FIFO), sonst nach der Rechnung
            let rules = (c["template"] as? String).flatMap { TaxTemplate(rawValue: $0) }?.eventRules ?? TaxEventRules.of(method)
            let got = TaxEngines.disposals(trades(c["trades"]), method: method, timeZone: zone, priceAt: priceAt(c["prices"]),
                                           fx: fx, rules: rules)
            let expected = S.list(c["disposals"])
            XCTAssertEqual(got.count, expected.count, "\(name): Anzahl")
            for (i, pair) in zip(expected, got).enumerated() {
                let (e, g) = pair
                let label = "\(name) #\(i)"
                XCTAssertEqual(g.coin, e["coin"] as? String, label)
                XCTAssertEqual(LocalDay(date: Date(timeIntervalSince1970: Double(g.sellTime) / 1000), timeZone: zone),
                               day(e["sold"]), "\(label) sold")
                let acquired = (g.acquiredTime ?? g.matchedTime).map {
                    LocalDay(date: Date(timeIntervalSince1970: Double($0) / 1000), timeZone: zone).description
                }
                XCTAssertEqual(acquired, e["acquired"] as? String, "\(label) acquired")
                XCTAssertEqual(r8(g.amount), r8(dec(e["amount"])), "\(label) amount")
                XCTAssertEqual(r8(g.costUsdt), r8(dec(e["cost"])), "\(label) cost")
                XCTAssertEqual(r8(g.proceedsUsdt), r8(dec(e["proceeds"])), "\(label) proceeds")
                XCTAssertEqual(r8(g.gainUsdt), r8(dec(e["gain"])), "\(label) gain")
                XCTAssertEqual(g.match, (e["match"] as? String).flatMap { TaxMatch(rawValue: $0) }, "\(label) match")
                XCTAssertEqual(r8(g.adjustmentUsdt), r8(dec(e["adjustment"])), "\(label) adjustment")
                XCTAssertEqual(g.oldHolding, e["oldHolding"] as? Bool, "\(label) oldHolding")
                XCTAssertEqual(r8(g.portfolioValueUsdt), r8(dec(e["portfolioValue"])), "\(label) portfolioValue")
                XCTAssertEqual(r8(g.grossProceedsUsdt), r8(dec(e["gross"])), "\(label) gross")
                if let ci = e["costIncomplete"] as? Bool { XCTAssertEqual(g.costIncomplete, ci, "\(label) costIncomplete") }
                if let w = e["wallet"] as? String { XCTAssertEqual(g.wallet, w, "\(label) wallet") }
                // Build 47: Verschenkt/bezahlt und geschätzter Wert (Tagesschluss)
                if let v = e["gift"] as? Bool { XCTAssertEqual(g.gift, v, "\(label) gift") }
                if let v = e["payment"] as? Bool { XCTAssertEqual(g.payment, v, "\(label) payment") }
                if let v = e["valueEstimated"] as? Bool { XCTAssertEqual(g.valueEstimated, v, "\(label) valueEstimated") }
                // Build 56: Schenkung an Ehe-/Lebenspartner (UK s. 58, CA 73(1))
                if let v = e["spouseGift"] as? Bool { XCTAssertEqual(g.spouseGift, v, "\(label) spouseGift") }
                // Herkunftsteile summieren sich exakt zum Einstand
                if !g.costParts.isEmpty {
                    XCTAssertEqual(g.costParts.reduce(Decimal(0)) { $0 + $1.usdt }, g.costUsdt, "\(label) parts")
                }
            }
            // FR: Abgänge ohne Verkauf (Kürzung der Erwerbspreis-Summe)
            if c["outflows"] != nil {
                let outs = TaxEngines.outflows(trades(c["trades"]), method: method, priceAt: priceAt(c["prices"]))
                let exp = S.list(c["outflows"])
                XCTAssertEqual(outs.count, exp.count, "\(name) outflows")
                for (e, o) in zip(exp, outs) {
                    XCTAssertEqual(o.coin, e["coin"] as? String, "\(name) outflow coin")
                    XCTAssertEqual(r8(o.amount), r8(dec(e["amount"])), "\(name) outflow amount")
                    XCTAssertEqual(r8(o.valueUsdt), r8(dec(e["value"])), "\(name) outflow value")
                    XCTAssertEqual(r8(o.ptaReductionUsdt), r8(dec(e["reduction"])), "\(name) outflow reduction")
                }
            }
            // Gebühr in Krypto als eigene Veräusserung (UK Netzwerkgebühr, FR Gebühr-Abtretung): Vermerk «Gebühr» im Export
            if let fr = c["feeRows"] as? [Any], fr.count == 2 {
                let rows = TaxExport.gainRows(trades(c["trades"]), method: method,
                                              period: TaxTemplate.NEUTRAL.period(Int(int64(fr[0]))), timeZone: zone,
                                              currency: "USD", rates: nil, priceAt: priceAt(c["prices"]),
                                              rules: rules)
                XCTAssertEqual(rows.filter(\.feeRow).count, Int(int64(fr[1])), "\(name) feeRows")
            }
        }
    }

    /// Netzwerkgebühr frisst den letzten Teil mit Kaufpreis: Einstand vermerkt, für jede Vorlage — wie Android.
    func testLostFeeCosts() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let lf = try XCTUnwrap(d["lostFee"] as? [String: Any])
        let txs = trades(lf["trades"])
        let period = TaxTemplate.NEUTRAL.period(Int(int64(lf["year"])))
        for (m, v) in lf["expected"] as? [String: Any] ?? [:] {
            let method = try XCTUnwrap(TaxMethod(rawValue: m))
            let got = TaxExport.lostFeeCosts(in: txs, method: method, period: period, timeZone: zone)
            if let expected = dec(v) {
                XCTAssertEqual(got.map { r8($0.costUsdt) }, [r8(expected)], "\(m) lost")
            } else {
                XCTAssertTrue(got.isEmpty, "\(m) lost")
            }
        }
    }

    /// Jahresübersicht-CSV: Tatsachen zuerst, Schlüsse (Grenzen, Sätze) nur im Orientierungs-Block mit Vorsatz — wie Android.
    func testSummaryCsvOrientationBlock() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let heading = "Orientation - no tax advice, no assessment"
        for c in S.list(d["summaries"]) {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c["template"] as? String ?? ""))
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            let s = TaxExport.yearSummary(trades(c["trades"]), template: tpl, method: method, startYear: Int(int64(c["year"])),
                                          timeZone: zone, currency: c["currency"] as? String ?? "USD", rates: rates(c["rates"]),
                                          priceAt: priceAt(c["prices"]), today: (c["today"] as? String).flatMap { LocalDay(iso: $0) })
            var labels: [TaxLine: String] = [:]
            for l in TaxLine.allCases { labels[l] = l.rawValue }
            let texts = TaxSummaryTexts(
                title: "T", disclaimer: "D", item: "Item", usdt: "USDT", target: "USD", note: "Note", orientation: "O",
                labels: labels, below: "below", reached: "reached", unknown: "unknown", incomplete: "incomplete",
                noFx: "nofx", orientationHeading: heading, orientationItem: "Orientation: %s", loss: "loss",
                notes: [.AT_REWARD_INFO: "atinfo", .CH_EXCHANGE_VALUE: "chvalue", .CH_FIFO_INFO: "chfifo",
                        .DE_WITHDRAWAL_FEE: "dewfee", .AT_WITHDRAWAL_FEE: "atwfee", .AT_INTEREST_INCOME: "atinterest",
                        .WITHDRAWAL_FEE_INFO: "wfeeinfo", .TRANSFER_FEE_INFO: "tfeeinfo",
                        .AT_TRANSITION_2021: "attransition", .AT_WITHOUT_OPTION: "atnooption",
                        .AT_STAKING_ZERO_COST: "atstaking", .WINDOW_OPEN: "window " + TaxExport.noteDateToken,
                        .UK_HMRC_REPORT: "ukreport", .AT_INTEREST_OLD_LAW: "atinterestold"])
            let csv = TaxExport.summaryCsv(s, texts: texts, header: ["H"])
            let lines = String(csv.dropFirst()).components(separatedBy: "\r\n").filter { !$0.isEmpty }
            // Zusatz-Vermerke (Datum eingesetzt) und Status «Verlust» stehen in der Hinweis-Spalte ihres Postens
            for l in s.lines {
                let text: String? = l.noteText(texts) ?? (l.status == .loss ? "loss" : nil)
                if let text {
                    XCTAssertTrue(lines.contains {
                        $0.contains(l.line.rawValue) && ($0.hasSuffix(";" + text) || $0.hasSuffix(", " + text))
                    }, "\(tpl) \(l.line) \(text)")
                    XCTAssertFalse(text.contains(TaxExport.noteDateToken), "\(tpl) \(l.line) Platzhalter")
                }
            }
            let oriented = s.lines.filter { $0.line.orientation }
            guard let head = lines.firstIndex(of: heading) else {
                XCTAssertTrue(oriented.isEmpty, "\(tpl) Block")
                continue
            }
            for l in lines[..<head] {
                XCTAssertFalse(oriented.contains { l.hasPrefix($0.line.rawValue + ";") }, "\(tpl) Tatsache \(l)")
            }
            let after: [String] = lines[(head + 1)...].map { String($0.split(separator: ";", omittingEmptySubsequences: false).first ?? "") }
            XCTAssertEqual(after, oriented.map { "Orientation: \($0.line.rawValue)" }, "\(tpl) Orientierung")
        }
    }

    func testSummaries() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        for c in S.list(d["summaries"]) {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c["template"] as? String ?? ""))
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            let year = Int(int64(c["year"]))
            let s = TaxExport.yearSummary(trades(c["trades"]), template: tpl, method: method, startYear: year,
                                          timeZone: zone, currency: c["currency"] as? String ?? "USD", rates: rates(c["rates"]),
                                          priceAt: priceAt(c["prices"]), today: (c["today"] as? String).flatMap { LocalDay(iso: $0) })
            let expected = (c["lines"] as? [Any] ?? []).compactMap { $0 as? [Any] }
            XCTAssertEqual(s.lines.map(\.line.rawValue), expected.map { $0[0] as? String ?? "" }, "\(tpl) lines")
            for (e, l) in zip(expected, s.lines) {
                XCTAssertEqual(r8(l.usdt), r8(dec(e[1])), "\(tpl) \(l.line) usdt")
                XCTAssertEqual(r8(l.target), r8(dec(e[2])), "\(tpl) \(l.line) target")
                let status: TaxLimitStatus?
                switch e[3] as? String {
                case "BELOW": status = .below
                case "REACHED": status = .reached
                case "UNKNOWN": status = .unknown
                case "LOSS": status = .loss
                default: status = nil
                }
                XCTAssertEqual(l.status, status, "\(tpl) \(l.line) status")
                // Zusatz-Vermerk (optional, 5. Feld): AT Einkünfte nur Information, CH Wert per 31.12. = Börsenkurs × USD/CHF
                let note: TaxSummaryNote? = e.count > 4 ? (e[4] as? String).flatMap { TaxSummaryNote(rawValue: $0) } : nil
                XCTAssertEqual(l.note, note, "\(tpl) \(l.line) note")
                // Datum im Vermerk (optional, 6. Feld): UK/CA 30-Tage-Fenster noch offen, Käufe bis …
                let noteDate: LocalDay? = e.count > 5 ? (e[5] as? String).flatMap { LocalDay(iso: $0) } : nil
                XCTAssertEqual(l.noteDate, noteDate, "\(tpl) \(l.line) noteDate")
            }
            // Posten «unvollständig» (optional): z. B. FR Gewinn aus unvollständiger Erwerbspreis-Summe
            if let inc = c["incompleteLines"] as? [String] {
                XCTAssertEqual(s.lines.filter(\.incomplete).map(\.line.rawValue), inc, "\(tpl) incomplete")
            }
            XCTAssertTrue(s.fxAvailable)
            XCTAssertEqual(s.period, tpl.period(year))
        }
        XCTAssertTrue(TaxLine.DE_PRIVATE_LIMIT.orientation)
        XCTAssertFalse(TaxLine.LONG_GAINS.orientation)
        // AT: Zinsen sind eine Tatsache (Einkunft beim Zufluss), der Sondersteuersatz auf netto + Zinsen ein Schluss
        XCTAssertFalse(TaxLine.AT_INTEREST.orientation)
        XCTAssertFalse(TaxLine.AT_INTEREST_OLD.orientation)
        XCTAssertTrue(TaxLine.AT_RATE.orientation)
        // CA/AU: Vortrag eines Netto-Kapitalverlusts ist ein Schluss der Vorlage
        XCTAssertTrue(TaxLine.NET_CAPITAL_LOSS.orientation)
    }

    /// UK-Meldegrenze an HMRC: £50,000 ab 2023/24, 2020/21–2022/23 4 × Annual Exempt Amount (£49,200), früher keine.
    /// Wie Android.
    func testUkReportProceedsThreshold() {
        XCTAssertEqual(TaxTemplate.ukReportProceedsGbp(2025), 50000)
        XCTAssertEqual(TaxTemplate.ukReportProceedsGbp(2023), 50000)
        XCTAssertEqual(TaxTemplate.ukReportProceedsGbp(2022), 49200)
        XCTAssertEqual(TaxTemplate.ukReportProceedsGbp(2020), 49200)
        XCTAssertNil(TaxTemplate.ukReportProceedsGbp(2019))
    }

    /// FR-Kurse für den Gesamtwert: gehalten ist, was die Rechnung vor dem Abgang bucht — auch ein Kauf derselben
    /// Millisekunde (Zugänge vor Abgängen). Wie Android `frHeldBeforeUsesEngineOrder`.
    func testFrHeldBeforeUsesEngineOrder() {
        let t0: Int64 = 1_740_830_400_000
        func tx(_ id: Int64, _ coin: String, _ type: PortfolioTxType, _ amount: String, _ price: Decimal, _ time: Int64) -> PortfolioTx {
            PortfolioTx(id: id, coin: coin, type: type, amountExact: dec(amount) ?? 0, priceExact: price, time: time,
                        feeExact: nil, feeUnit: nil)
        }
        let btc = tx(1, "BTC", .BUY, "1", 20000, t0 - 86_400_000)
        // Kauf ETH in derselben Millisekunde wie der Verkauf, höhere Id: die Rechnung bucht ihn trotzdem davor
        let eth = tx(5, "ETH", .BUY, "10", 2000, t0)
        let sell = tx(3, "BTC", .SELL, "0.5", 30000, t0)
        XCTAssertEqual(TaxEngines.frHeldBefore([btc, eth, sell], sell), ["BTC", "ETH"])
        XCTAssertEqual(TaxEngines.frHeldBefore([btc, sell], sell), ["BTC"])
        // Abgänge derselben Millisekunde mit kleinerer Id kommen davor (gleiche Art, dann Id): danach kein BTC mehr
        let first = tx(2, "BTC", .SELL, "0.5", 30000, t0)
        let last = tx(6, "BTC", .SELL, "0.5", 30000, t0)
        XCTAssertEqual(TaxEngines.frHeldBefore([btc, eth, first, sell, last], last), ["ETH"])
    }

    /// Formel-Injektion: Textfelder mit = + - @ Tab CR am Anfang bekommen ein Apostroph, Zahlen nie — wie Android.
    func testFormulaInjection() {
        XCTAssertEqual(CutoffExport.escape("=1+2"), "'=1+2")
        XCTAssertEqual(CutoffExport.escape("+41"), "'+41")
        XCTAssertEqual(CutoffExport.escape("-x"), "'-x")
        XCTAssertEqual(CutoffExport.escape("@SUM(A1)"), "'@SUM(A1)")
        XCTAssertEqual(CutoffExport.escape("\tx"), "'\tx")
        XCTAssertEqual(CutoffExport.escape("\rx"), "\"'\rx\"")
        XCTAssertEqual(CutoffExport.escape("=a;b"), "\"'=a;b\"")
        XCTAssertEqual(CutoffExport.escape("a=b"), "a=b")
        XCTAssertEqual(CutoffExport.escape(""), "")
        XCTAssertEqual(CutoffExport.escapeNumber("-12.50"), "-12.50")
        XCTAssertEqual(CutoffExport.escapeNumber("-12,50", separator: ","), "\"-12,50\"")
        XCTAssertEqual(CutoffExport.joinCells([CsvCell(text: "-12.50", numeric: true), CsvCell(text: "-1"), CsvCell(text: "x")],
                                              separator: ";"), "-12.50;'-1;x")
    }

    /// Steuer-CSV: Coin und Wallet aus Eingaben neutralisiert, negativer Gewinn bleibt Zahl — wie Android.
    func testTaxCsvInjection() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let t0 = LocalDay(2025, 1, 2).epochMillisUTC
        var buy = PortfolioTx(id: 1, coin: "=EVIL", type: .BUY, amountExact: 1, priceExact: 100, time: t0,
                              feeExact: nil, feeUnit: nil)
        buy.exchange = "@wallet"
        var sell = PortfolioTx(id: 2, coin: "=EVIL", type: .SELL, amountExact: 1, priceExact: 40, time: t0 + 86_400_000,
                               feeExact: nil, feeUnit: nil)
        sell.exchange = "@wallet"
        let texts = TaxCsvTexts(
            coin: "Coin", acquired: "Acq", sold: "Sold", holdingDays: "Days", longTerm: "> 1y", amount: "Amount",
            costUsdt: "Cost", proceedsUsdt: "Proceeds", gainUsdt: "Gain", costTarget: "CostT", proceedsTarget: "ProcT",
            gainTarget: "GainT", date: "Date", valueUsdt: "V", valueTarget: "VT", note: "Note", total: "Total",
            yes: "yes", no: "no", costMissing: "cm", proceedsMissing: "pm", feeLeg: "fl", feeValueMissing: "fv",
            avgCostRates: "avg", valueMissing: "vm", noFx: "nofx", incomplete: "inc", wallet: "%s")
        let rows = TaxExport.gainRows([buy, sell], method: .FIFO_WALLET, year: 2025, timeZone: zone, currency: "USD", rates: nil)
        let csv = TaxExport.gainsCsv(rows, texts: texts, header: ["-header"], format: .semicolonPoint)
        let lines = String(csv.dropFirst()).components(separatedBy: "\r\n")
        XCTAssertEqual(lines[0], "'-header")
        XCTAssertEqual(lines[2], "'=EVIL;2025-01-02;2025-01-03;1;no;1;100.00;40.00;-60.00;100.00;40.00;-60.00;'@wallet")
        XCTAssertEqual(lines[3], "Total;;;;;;100.00;40.00;-60.00;100.00;40.00;-60.00;")
    }
}
