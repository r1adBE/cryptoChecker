import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `tax_export.json` (Android: `TaxExportParityTest.kt`).
final class TaxExportTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("tax_export") }

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func day(_ value: Any?) -> LocalDay { LocalDay(iso: value as? String ?? "") ?? LocalDay(1970, 1, 1) }

    private func trades(_ d: [String: Any]) -> [PortfolioTx] {
        S.list(d["trades"]).map { (t: [String: Any]) -> PortfolioTx in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]), time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.feeCoin = t["feeCoin"] as? String
            tx.feeCoinAmountExact = dec(t["feeCoinAmount"])
            tx.feeCoinPriceExact = dec(t["feeCoinPrice"])
            tx.acquiredTime = (t["acquiredTime"] as? NSNumber)?.int64Value
            tx.exchange = t["exchange"] as? String
            tx.transferFrom = t["transferFrom"] as? String
            tx.transferTo = t["transferTo"] as? String
            tx.toCoin = t["toCoin"] as? String
            tx.toAmountExact = dec(t["toAmount"])
            tx.swapValueExact = dec(t["swapValue"])
            tx.interest = t["interest"] as? Bool ?? false
            return tx
        }
    }

    private func rates(_ d: [String: Any]) -> [Int: Decimal] {
        var out: [Int: Decimal] = [:]
        for (key, value) in d["rates"] as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(value) { out[k] = r }
        }
        return out
    }

    private func texts(_ d: [String: Any]) -> TaxCsvTexts {
        let x = d["texts"] as? [String: Any] ?? [:]
        func s(_ k: String) -> String { x[k] as? String ?? "" }
        return TaxCsvTexts(
            coin: s("coin"), acquired: s("acquired"), sold: s("sold"), holdingDays: s("holdingDays"),
            longTerm: s("longTerm"), amount: s("amount"), costUsdt: s("costUsdt"), proceedsUsdt: s("proceedsUsdt"),
            gainUsdt: s("gainUsdt"), costTarget: s("costTarget"), proceedsTarget: s("proceedsTarget"),
            gainTarget: s("gainTarget"), date: s("date"), valueUsdt: s("valueUsdt"), valueTarget: s("valueTarget"),
            note: s("note"), total: s("total"), yes: s("yes"), no: s("no"), costMissing: s("costMissing"),
            proceedsMissing: s("proceedsMissing"), feeLeg: s("feeLeg"), feeValueMissing: s("feeValueMissing"),
            avgCostRates: s("avgCostRates"), valueMissing: s("valueMissing"), noFx: s("noFx"),
            incomplete: s("incomplete"), oversold: s("oversold"), proceedsWithoutCost: s("proceedsWithoutCost"),
            timeZone: s("timeZone"), swap: s("swap"), wallet: s("wallet"), walletUnspecified: s("walletUnspecified"))
    }

    /// Zeilen ohne BOM; letzte Zeile endet mit CRLF.
    private func lines(_ csv: String) -> [String] {
        XCTAssertTrue(csv.hasPrefix("\u{FEFF}"), "BOM")
        var all = String(csv.dropFirst()).components(separatedBy: "\r\n")
        XCTAssertEqual(all.last, "", "Zeilenende")
        all.removeLast()
        return all
    }

    func testYearsAndRules() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let txs = trades(d)
        let currency = d["currency"] as? String ?? ""
        let year = Int(int64(d["year"]))
        XCTAssertEqual(TaxExport.years(txs, timeZone: zone), (d["years"] as? [Any] ?? []).map { Int(int64($0)) })
        let def = try XCTUnwrap(d["defaultYear"] as? [String: Any])
        XCTAssertEqual(TaxExport.defaultYear(TaxExport.years(txs, timeZone: zone), today: day(def["today"])),
                       Int(int64(def["expected"])))
        XCTAssertNil(TaxExport.defaultYear([], today: LocalDay(2026, 1, 1)))
        for case let c as [Any] in d["longTerm"] as? [Any] ?? [] {
            XCTAssertEqual(TaxExport.isLongTerm(acquired: day(c[0]), sold: day(c[1])), c[2] as? Bool, "longTerm \(c)")
        }
        for case let c as [Any] in d["rateOn"] as? [Any] ?? [] {
            XCTAssertEqual(TaxExport.rateOn(rates(d), date: day(c[0]), currency: currency), dec(c[1]), "rateOn \(c)")
        }
        XCTAssertEqual(TaxExport.rateOn(nil, date: LocalDay(2025, 1, 1), currency: "USD"), 1)
        XCTAssertNil(TaxExport.rateOn(nil, date: LocalDay(2025, 1, 1), currency: "CHF"))
        for (method, expected) in d["fxFrom"] as? [String: Any] ?? [:] {
            XCTAssertEqual(TaxExport.fxFrom(txs, method: CostMethod.fromName(method), year: year, timeZone: zone),
                           day(expected), "fxFrom \(method)")
        }
    }

    /// USDT zum Marktkurs (Owner-Entscheid Okt. 2026): Umrechnung × USDT/USD-Tagesschluss; ohne Schluss USDT = 1 als
    /// Näherung, je Zeile vermerkt. Wie Android.
    func testUsdtMarket() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let currency = d["currency"] as? String ?? ""
        let m = try XCTUnwrap(d["usdtMarket"] as? [String: Any])
        var usdt: [Int: Decimal] = [:]
        for (key, value) in m["usdtUsd"] as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(value) { usdt[k] = r }
        }
        for case let c as [Any] in m["rateOn"] as? [Any] ?? [] {
            XCTAssertEqual(TaxExport.rateOn(rates(d), date: day(c[0]), currency: c[1] as? String ?? "", usdtUsd: usdt),
                           dec(c[2]), "rateOn \(c)")
            XCTAssertEqual(TaxExport.usdtApproximateOn(usdt, date: day(c[0])), c[3] as? Bool, "usdtApproximateOn \(c)")
        }
        let txs = trades(m)
        let method = TaxMethod.of(.FIFO)
        let period = TaxTemplate.NEUTRAL.period(2025)
        for (key, usdtUsd) in [("gains", usdt), ("gainsPar", nil)] as [(String, [Int: Decimal]?)] {
            let rows = TaxExport.gainRows(txs, method: method, period: period, timeZone: zone, currency: currency,
                                          rates: rates(d), usdtUsd: usdtUsd)
            let expected = S.list(m[key])
            XCTAssertEqual(rows.count, expected.count, "\(key) rows")
            for (e, r) in zip(expected, rows) {
                XCTAssertEqual(r.coin, e["coin"] as? String, "\(key) coin")
                XCTAssertEqual(r.costTarget, dec(e["costTarget"]), "\(key) cost")
                XCTAssertEqual(r.proceedsTarget, dec(e["proceedsTarget"]), "\(key) proceeds")
                XCTAssertEqual(r.gainTarget, dec(e["gainTarget"]), "\(key) gain")
                XCTAssertEqual(r.usdtApproximate, e["usdtApproximate"] as? Bool, "\(key) usdtApproximate")
            }
        }
        for (key, usdtUsd) in [("income", usdt), ("incomePar", nil)] as [(String, [Int: Decimal]?)] {
            let rows = TaxExport.incomeRows(txs, period: period, timeZone: zone, currency: currency, rates: rates(d),
                                            usdtUsd: usdtUsd)
            let expected = S.list(m[key])
            XCTAssertEqual(rows.count, expected.count, "\(key) rows")
            for (e, r) in zip(expected, rows) {
                XCTAssertEqual(r.date, day(e["date"]), "\(key) date")
                XCTAssertEqual(r.valueTarget, dec(e["valueTarget"]), "\(key) value")
                XCTAssertEqual(r.usdtApproximate, e["usdtApproximate"] as? Bool, "\(key) usdtApproximate")
            }
        }
        // Vermerk je Zeile: nie still mit USDT = 1 gerechnet
        var marked = texts(d)
        marked.usdtAssumed = m["usdtAssumed"] as? String ?? ""
        let csv = lines(TaxExport.incomeCsv(TaxExport.incomeRows(txs, period: period, timeZone: zone, currency: currency,
                                                                 rates: rates(d), usdtUsd: usdt),
                                            texts: marked, header: d["header"] as? [String] ?? []))
        for line in m["incomeLines"] as? [String] ?? [] { XCTAssertTrue(csv.contains(line), "income line \(line) in \(csv)") }
    }

    /// Einkünfte-CSV AT — Zins (beim Zufluss steuerpflichtig) und Staking (nur Information) je Zeile vermerkt,
    /// zwei Summen mit je eigenem «unvollständig»; ohne die AT-Texte (alle anderen Vorlagen) unverändert eine Summe. Wie Android.
    func testAtIncomeSplitsInterestAndStaking() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let currency = d["currency"] as? String ?? ""
        let header = d["header"] as? [String] ?? []
        let a = try XCTUnwrap(d["atIncome"] as? [String: Any])
        let rows = TaxExport.incomeRows(trades(a), period: TaxTemplate.NEUTRAL.period(2025), timeZone: zone,
                                        currency: currency, rates: rates(d))
        let x = a["atTexts"] as? [String: Any] ?? [:]
        var at = texts(d)
        at.incomeInterest = x["incomeInterest"] as? String ?? ""
        at.incomeStaking = x["incomeStaking"] as? String ?? ""
        at.totalInterest = x["totalInterest"] as? String ?? ""
        at.totalStaking = x["totalStaking"] as? String ?? ""
        XCTAssertEqual(lines(TaxExport.incomeCsv(rows, texts: at, header: header)), a["at"] as? [String], "AT")
        XCTAssertEqual(lines(TaxExport.incomeCsv(rows, texts: texts(d), header: header)), a["neutral"] as? [String], "neutral")
    }

    /// Einkünfte-CSV AT: Zufluss vor dem 1.3.2022 (vor § 27b EStG) — Vermerk «vor 1.3.2022 – mit Fachperson prüfen» statt
    /// Zins/Staking, Summen getrennt wie bisher. Wie Android.
    func testAtIncomeBeforeNewLawIsMarked() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let header = d["header"] as? [String] ?? []
        let a = try XCTUnwrap(d["atIncome"] as? [String: Any])
        let b = try XCTUnwrap(d["atIncomeBefore2022"] as? [String: Any])
        let rows = TaxExport.incomeRows(trades(b), period: TaxTemplate.NEUTRAL.period(2022), timeZone: zone,
                                        currency: b["currency"] as? String ?? "", rates: nil)
        let x = a["atTexts"] as? [String: Any] ?? [:]
        var at = texts(d)
        at.incomeInterest = x["incomeInterest"] as? String ?? ""
        at.incomeStaking = x["incomeStaking"] as? String ?? ""
        at.totalInterest = x["totalInterest"] as? String ?? ""
        at.totalStaking = x["totalStaking"] as? String ?? ""
        at.incomeBeforeNewLaw = b["incomeBeforeNewLaw"] as? String ?? ""
        XCTAssertEqual(lines(TaxExport.incomeCsv(rows, texts: at, header: header)), b["at"] as? [String], "AT")
    }

    func testCsvCases() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let txs = trades(d)
        let currency = d["currency"] as? String ?? ""
        let year = Int(int64(d["year"]))
        let header = d["header"] as? [String] ?? []
        let cases = S.list(d["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 2)
        for c in cases {
            let method = CostMethod.fromName(c["method"] as? String)
            let format = CutoffCsvFormat(separator: Character(c["separator"] as? String ?? ";"),
                                         decimal: Character(c["decimal"] as? String ?? "."))
            let gains = TaxExport.gainsCsv(TaxExport.gainRows(txs, method: method, year: year, timeZone: zone,
                                                              currency: currency, rates: rates(d)),
                                           texts: texts(d), header: header, format: format)
            XCTAssertEqual(lines(gains), c["gains"] as? [String], "gains \(method)")
            let income = TaxExport.incomeCsv(TaxExport.incomeRows(txs, year: year, timeZone: zone, currency: currency,
                                                                  rates: rates(d)),
                                             texts: texts(d), header: header, format: format)
            XCTAssertEqual(lines(income), c["income"] as? [String], "income \(method)")
        }
    }

    /// Jahresübersicht DE über die Vorlage (`yearSummary` mit `.DE`) — wie `deSummary` (Kotlin): Summen, Freigrenzen je
    /// Jahr, CSV; Tausch als Veräusserung. Zähler des Fixtures als Merker der Posten (> 0 ↔ Merker gesetzt).
    func testDeSummary() throws {
        let d = try data()
        let de = try XCTUnwrap(d["deSummary"] as? [String: Any])
        let zone = try XCTUnwrap(TimeZone(identifier: de["zone"] as? String ?? ""))
        let txs = trades(de)
        var eurRates: [Int: Decimal] = [:]
        for (key, value) in de["eurRates"] as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(value) { eurRates[k] = r }
        }
        let t = de["texts"] as? [String: String] ?? [:]
        func s(_ k: String) -> String { t[k] ?? "" }
        var labels: [TaxLine: String] = [:]
        for (key, value) in de["labels"] as? [String: String] ?? [:] {
            if let line = TaxLine(rawValue: key) { labels[line] = value }
        }
        let summaryTexts = TaxSummaryTexts(
            title: s("title"), disclaimer: s("disclaimer"), item: s("item"), usdt: s("usdt"), target: s("eur"), note: s("note"),
            orientation: "Orientation", labels: labels, below: s("below"), reached: s("reached"), unknown: s("unknown"),
            incomplete: s("incomplete"), noFx: s("noFx"),
            orientationHeading: s("orientationHeading"), orientationItem: s("orientationItem"))
        let deHeader = de["header"] as? [String] ?? []
        for case let pair as [Any] in de["limits"] as? [Any] ?? [] {
            XCTAssertEqual(TaxExport.privateSalesLimitEur(Int(int64(pair[0]))), dec(pair[1]), "limit \(pair)")
        }
        XCTAssertEqual(TaxExport.incomeLimitEur, 256)
        let cases = S.list(de["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 4)
        for c in cases {
            let year = Int(int64(c["year"]))
            let method = CostMethod.fromName(c["method"] as? String)
            let eur = c["eur"] as? Bool ?? false
            let sum = TaxExport.yearSummary(txs, template: .DE, method: TaxMethod.of(method), startYear: year, timeZone: zone,
                                            currency: "EUR", rates: eur ? eurRates : nil)
            let label = "de \(year) \(method) eur=\(eur)"
            func line(_ l: TaxLine) -> TaxSummaryLine? { sum.lines.first { $0.line == l } }
            func m(_ v: Decimal?) -> String? { v.map { CutoffExport.money($0) } }
            // Posten fehlt (z. B. ohne Haltedauer): Betrag 0, in EUR nur mit Kursen
            func usdt(_ l: TaxLine) -> String? { m(line(l)?.usdt ?? 0) }
            func eurOf(_ l: TaxLine) -> String? { eur ? m(line(l)?.target ?? 0) : nil }
            func status(_ st: TaxLimitStatus?) -> String? {
                switch st {
                case .some(.below): return "BELOW"
                case .some(.reached): return "REACHED"
                case .some(.unknown): return "UNKNOWN"
                case .some(.loss): return "LOSS"
                case .none: return nil
                }
            }
            let unclassified = line(.UNCLASSIFIED)
            let money: [String: String?] = [
                "shortGainsUsdt": usdt(.SHORT_GAINS), "shortLossesUsdt": usdt(.SHORT_LOSSES),
                "netShortUsdt": usdt(.SHORT_NET), "longGainsUsdt": usdt(.LONG_GAINS),
                "longLossesUsdt": usdt(.LONG_LOSSES), "unclassifiedUsdt": usdt(.UNCLASSIFIED),
                "shortGainsEur": eurOf(.SHORT_GAINS), "shortLossesEur": eurOf(.SHORT_LOSSES),
                "netShortEur": eurOf(.SHORT_NET), "longGainsEur": eurOf(.LONG_GAINS),
                "longLossesEur": eurOf(.LONG_LOSSES), "unclassifiedEur": eurOf(.UNCLASSIFIED),
                "privateSalesLimitEur": m(line(.DE_PRIVATE_LIMIT)?.target), "incomeUsdt": m(line(.INCOME)?.usdt),
                "incomeEur": m(line(.INCOME)?.target), "incomeLimitEur": m(line(.DE_INCOME_LIMIT)?.target),
                "privateSalesStatus": status(line(.DE_PRIVATE_LIMIT)?.status), "incomeStatus": status(line(.DE_INCOME_LIMIT)?.status),
            ]
            let flags: [String: Bool] = [
                "unknownGainCount": line(.SHORT_NET)?.incomplete == true || unclassified?.incomplete == true,
                "unclassifiedCount": unclassified != nil,
                "eurFxMissingCount": line(.SHORT_NET)?.noFx == true || unclassified?.noFx == true,
                "incomeValueMissingCount": line(.INCOME)?.incomplete == true,
                "incomeFxMissingCount": line(.INCOME)?.noFx == true,
            ]
            for (k, v) in c["expected"] as? [String: Any] ?? [:] {
                if k == "eurAvailable" {
                    XCTAssertEqual(sum.fxAvailable, v as? Bool, "\(label) \(k)")
                } else if let flag = flags[k] {
                    XCTAssertEqual(flag, int64(v) > 0, "\(label) \(k)")
                } else if let actual = money[k] {
                    XCTAssertEqual(actual, v as? String, "\(label) \(k)")
                } else {
                    XCTFail("\(label): unknown key \(k)")
                }
            }
            if let expected = c["csv"] as? [String] {
                let csv = TaxExport.summaryCsv(sum, texts: summaryTexts, header: deHeader, zoneLine: de["zoneLine"] as? String ?? "")
                XCTAssertEqual(lines(csv), expected, "\(label) csv")
            }
            if let swapLine = c["swapLine"] as? [String], swapLine.count == 2 {
                let rows = TaxExport.gainRows(txs, method: method, year: year, timeZone: zone, currency: "EUR", rates: eurRates)
                let gains = lines(TaxExport.gainsCsv(rows, texts: texts(d), header: d["header"] as? [String] ?? []))
                XCTAssertTrue(gains.contains { $0.hasPrefix(swapLine[0]) && $0.hasSuffix(swapLine[1]) }, "\(label) swap line")
            }
        }
    }

    func testEmptyYear() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let gains = TaxExport.gainsCsv(TaxExport.gainRows(trades(d), method: .FIFO, year: 2019, timeZone: zone,
                                                          currency: "CHF", rates: rates(d)),
                                       texts: texts(d), header: [])
        let all = lines(gains)
        XCTAssertEqual(all.count, 3)
        XCTAssertEqual(all.first, (d["texts"] as? [String: Any])?["timeZone"] as? String, "Zeitzone zuerst")
        XCTAssertTrue(all.last?.hasPrefix("Total;;;;;;0.00;0.00;0.00;0.00;0.00;0.00;") ?? false)
    }

    /// CH: «Vermögenssteuer nicht gerechnet – Kursliste ESTV» als Kopfzeile 2 jeder CSV; andere Vorlagen unverändert — wie Android.
    func testChWealthNoteInFirstLines() {
        let header = ["Title", "Info", "Disclaimer"]
        let note = "Wealth tax not calculated – ESTV price list: https://example"
        XCTAssertEqual(TaxExport.withChWealthNote(.CH, header: header, chWealthNote: note), ["Title", note, "Info", "Disclaimer"])
        XCTAssertEqual(TaxExport.withChWealthNote(.DE, header: header, chWealthNote: note), header)
        XCTAssertEqual(TaxExport.withChWealthNote(.CH, header: header, chWealthNote: ""), header)
        // Nicht doppelt
        let once = TaxExport.withChWealthNote(.CH, header: header, chWealthNote: note)
        XCTAssertEqual(TaxExport.withChWealthNote(.CH, header: once, chWealthNote: note), once)
        XCTAssertEqual(TaxExport.withChWealthNote(.CH, header: [], chWealthNote: note), [note])
    }

    /// B4: Gewinn 1020 ≤ 1 Jahr und Auszahlungsgebühr 25 USDT — die Gebühr ist keine Veräusserung: eigener Posten,
    /// NICHT in der Summe § 23 → 1020 ≥ 1000 «erreicht» (vorher 995 «unter der Freigrenze»). Wie Android.
    /// Gebühr in USDT/Fiat einer Auszahlung bzw. Umbuchung bei UK, US, CA, AU, JP nur Information (Vermerk,
    /// nicht in der Summe; UK HMRC CRYPTO22150), DE und Neutral unverändert. FR: Auszahlungsgebühr ebenso nur Information
    /// (wie in der Jahresübersicht). Wie Android.
    func testWithdrawalFeeInfoIsNotInTotals() throws {
        let d = try data()
        let w = try XCTUnwrap(d["withdrawalFeeInfo"] as? [String: Any])
        let zone = try XCTUnwrap(TimeZone(identifier: w["zone"] as? String ?? ""))
        let year = Int(int64(w["year"]))
        let txs = trades(w)
        var t = texts(d)
        t.withdrawalFee = "fee"
        t.withdrawalFeeInfo = "feeinfo"
        t.transferFeeInfo = "feeinfo"
        for c in S.list(w["cases"]) {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c["template"] as? String ?? ""))
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            // Kalenderjahr für alle (UK-Steuerjahr ab 6. April enthielte die Fälle nicht); ein Fall mit eigenen
            // Transaktionen (FR: Auszahlung mit Wert für die Kürzung der Erwerbspreis-Summe) nimmt diese
            let caseTxs = c["trades"] != nil ? trades(c) : txs
            let rows = TaxExport.gainRows(caseTxs, method: method, period: TaxTemplate.NEUTRAL.period(year), timeZone: zone,
                                          currency: "USD", rates: nil, rules: tpl.eventRules)
            let info = Int(int64(c["infoRows"]))
            XCTAssertEqual(rows.filter(\.feeInfoOnly).count, info, "\(tpl) infoRows")
            // DE/AT: Auszahlungsgebühren als eigene Summenzeile (der Text füllt nur diese Vorlage, wie die App)
            var caseTexts = t
            if tpl.withdrawalFeeApart { caseTexts.withdrawalFeesTotal = "fees" }
            let csv = lines(TaxExport.gainsCsv(rows, texts: caseTexts, header: []))
            let total = try XCTUnwrap(csv.first { $0.hasPrefix(t.total + ";") }).components(separatedBy: ";")
            XCTAssertEqual(total[8], c["totalGain"] as? String, "\(tpl) total")
            XCTAssertEqual(csv.filter { $0.hasSuffix(";feeinfo") }.count, info, "\(tpl) note")
            let feesLine = csv.first { $0.hasPrefix("fees;") }?.components(separatedBy: ";")
            XCTAssertEqual(feesLine?[8], c["feesTotalGain"] as? String, "\(tpl) fees line")
            if tpl.withdrawalFeeApart {
                // Summe der CSV = netto der Jahresübersicht (Gebühr dort eigener Posten), Gebühren = Posten «Auszahlungsgebühren»
                let s = TaxExport.yearSummary(caseTxs, template: tpl, method: method, startYear: year, timeZone: zone,
                                              currency: "USD", rates: nil)
                let netLines: Set<TaxLine> = [.SHORT_NET, .LONG_NET, .UNCLASSIFIED, .NET]
                let net = s.lines.filter { netLines.contains($0.line) }.reduce(Decimal(0)) { $0 + ($1.usdt ?? 0) }
                XCTAssertEqual(total[8], CutoffExport.money(net), "\(tpl) net")
                let fees = try XCTUnwrap(s.lines.first { $0.line == .WITHDRAWAL_FEES }?.usdt)
                XCTAssertEqual(feesLine?[8], CutoffExport.money(fees), "\(tpl) fees")
            }
        }
    }

    /// FIFO-Posten tragen ihren Einstand in Teilen mit Datum — CH: Kauf 2018 5000 USDT (Kurs 1.00) +
    /// Umbuchungsgebühr 2024 100 USDT (Kurs 0.85) = 5085 CHF (FIFO, FIFO je Wallet und Ø gleich); Umbuchungsgebühr auf
    /// Bestand ohne Kaufpreis als Info-Zeile «nicht zugeordnet – Einstand unbekannt» nach der Summe. Wie Android.
    func testFifoCostPartsAtOwnRates() throws {
        let d = try data()
        let f = try XCTUnwrap(d["fifoCostParts"] as? [String: Any])
        let zone = try XCTUnwrap(TimeZone(identifier: f["zone"] as? String ?? ""))
        let year = Int(int64(f["year"]))
        let currency = f["currency"] as? String ?? ""
        let txs = trades(f)
        var t = texts(d)
        t.unassignedFee = f["unassignedFeeText"] as? String ?? ""
        for c in S.list(f["cases"]) {
            let method = CostMethod.fromName(c["method"] as? String)
            let rows = TaxExport.gainRows(txs, method: method, year: year, timeZone: zone, currency: currency, rates: rates(f))
            let unassigned = TaxExport.unassignedFees(in: txs, method: method, year: year, timeZone: zone)
            let csv = TaxExport.gainsCsv(rows, texts: t, header: [], timeZone: zone, unassignedFees: unassigned)
            XCTAssertEqual(lines(csv), c["gains"] as? [String], "fifoCostParts \(method)")
        }
    }

    func testWithdrawalFeeIsNotNettedIntoPrivateSales() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let t0: Int64 = 1_736_510_400_000 // 10.01.2025
        let dayMs: Int64 = 86_400_000
        let fee = PortfolioTx(id: 4, coin: "ETH", type: .WITHDRAWAL, amountExact: 0.5, priceExact: nil, time: t0 + 80 * dayMs,
                              feeExact: 25, feeUnit: .QUOTE)
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 1000, time: t0),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amountExact: 1, priceExact: 2020, time: t0 + 50 * dayMs),
            PortfolioTx(id: 3, coin: "ETH", type: .BUY, amountExact: 1, priceExact: 100, time: t0),
            fee,
        ]
        // EUR = USD (Kurs 1) an jedem Tag des Jahres
        var eur: [Int: Decimal] = [:]
        for d in LocalDay(2025, 1, 1).epochDay...LocalDay(2025, 12, 31).epochDay { eur[d] = 1 }
        let s = TaxExport.yearSummary(trades, template: .DE, method: .FIFO, startYear: 2025, timeZone: zone, currency: "EUR",
                                      rates: eur)
        func line(_ l: TaxLine) -> TaxSummaryLine? { s.lines.first { $0.line == l } }
        XCTAssertEqual(line(.SHORT_GAINS)?.usdt, 1020)
        XCTAssertEqual(line(.SHORT_LOSSES)?.usdt, 0)
        XCTAssertEqual(line(.SHORT_NET)?.target, 1020)
        XCTAssertEqual(line(.DE_PRIVATE_LIMIT)?.status, .reached)
        XCTAssertEqual(line(.WITHDRAWAL_FEES)?.usdt, -25)
        XCTAssertEqual(line(.WITHDRAWAL_FEES)?.target, -25)
        XCTAssertEqual(line(.WITHDRAWAL_FEES)?.note, .DE_WITHDRAWAL_FEE)
        let texts = TaxSummaryTexts(title: "T", disclaimer: "D", item: "I", usdt: "USDT", target: "EUR", note: "N", orientation: "O",
                                    labels: [.WITHDRAWAL_FEES: "WF", .DE_PRIVATE_LIMIT: "PL"], below: "below", reached: "reached",
                                    unknown: "unknown", incomplete: "inc", noFx: "nofx",
                                    notes: [.DE_WITHDRAWAL_FEE: "not netted"])
        let csv = TaxExport.summaryCsv(s, texts: texts, header: [])
        XCTAssertTrue(csv.contains("WF;-25.00;-25.00;not netted"), csv)
    }
}
