import Foundation

// CSV der Steuer-Exporte: UTF-8 mit BOM, CRLF, `CutoffCsvFormat` — wie `TaxExportCsv.kt` (Android). Texte in
// TaxCsvTexts.swift.

extension TaxExport {

    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"

    /// Summe der bekannten Werte; nil, wenn keiner bekannt ist (und es Zeilen gibt). Summiert werden die auf 2 Stellen
    /// GERUNDETEN Werte, wie sie in den Zeilen stehen (`CutoffExport.roundedMoney`) — Nachsummieren der Spalte geht auf
    /// (0.34 + 0.34 + 0.34 = 1.02, nicht die exakte Summe 1.01). Wie Android.
    private static func sum<T>(_ rows: [T], _ pick: (T) -> Decimal?) -> Decimal? {
        let known = rows.compactMap(pick)
        if known.isEmpty { return rows.isEmpty ? 0 : nil }
        return known.reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1) }
    }

    /// CSV «Realisierte Gewinne» mit BOM; `header` = Kopfzeilen (je ein Feld), danach die Zeitzone (`texts.timeZone`).
    /// Summe nur über vollständige Zeilen (Einstand, Erlös und Gewinn bekannt — je für USDT und Anzeigewährung), so
    /// geht Erlös − Einstand = Gewinn auf; Zeilen mit unbekanntem Einstand summiert die Zeile «Erlös ohne Kaufpreis».
    /// Zusatzspalten vor «Hinweis» nur, wenn eine Zeile sie hat (CA nicht abziehbarer Verlust; FR Preis vor Gebühren und
    /// Gesamtwert). Textfelder gegen Formel-Injektion geschützt, Zahlen unverändert — wie Android.
    static func gainsCsv(_ rows: [TaxGainRow], texts: TaxCsvTexts, header: [String],
                         format: CutoffCsvFormat = .semicolonPoint,
                         lostFeeCosts: [PortfolioLostFeeCost] = [], timeZone: TimeZone = TimeZone(identifier: "UTC")!,
                         unassignedFees: [PortfolioUnassignedFee] = []) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> CsvCell { CsvCell(text: n(v.map { CutoffExport.money($0) } ?? ""), numeric: true) }
        func t(_ v: String) -> CsvCell { CsvCell(text: v) }
        let blank = CsvCell(text: "", numeric: true)
        let adj = rows.contains { $0.adjustmentUsdt != nil }
        let fr = rows.contains { $0.portfolioValueUsdt != nil || $0.grossProceedsUsdt != nil }
        /// Zusatzspalten einer Zeile (leer, wenn es sie nicht gibt).
        func extra(_ a: Decimal?, _ aT: Decimal?, _ g: Decimal?, _ gT: Decimal?, _ v: Decimal?, _ vT: Decimal?) -> [CsvCell] {
            var out: [CsvCell] = []
            if adj { out += [money(a), money(aT)] }
            if fr { out += [money(g), money(gT), money(v), money(vT)] }
            return out
        }
        var out = bom
        for h in header { out += line([t(h)], format) }
        if !texts.timeZone.isEmpty { out += line([t(texts.timeZone)], format) }
        var head: [CsvCell] = [texts.coin, texts.acquired, texts.sold, texts.holdingDays, texts.longTerm, texts.amount,
                               texts.costUsdt, texts.proceedsUsdt, texts.gainUsdt,
                               texts.costTarget, texts.proceedsTarget, texts.gainTarget].map { t($0) }
        if adj { head += [t(texts.adjustmentUsdt), t(texts.adjustmentTarget)] }
        if fr { head += [t(texts.grossUsdt), t(texts.grossTarget), t(texts.portfolioValueUsdt), t(texts.portfolioValueTarget)] }
        head.append(t(texts.note))
        out += line(head, format)
        for r in rows {
            var notes: [String] = []
            let expenseText: String?
            switch r.feeExpense {
            case .some(.CONSUMED): expenseText = texts.feeConsumed
            case .some(.WITHDRAWAL):
                expenseText = r.feeInfoOnly && !texts.withdrawalFeeInfo.isEmpty ? texts.withdrawalFeeInfo : texts.withdrawalFee
            case .some(.TRANSFER): expenseText = texts.transferFeeInfo
            case .none: expenseText = nil
            }
            if let expenseText, !expenseText.isEmpty { notes.append(expenseText) }
            if r.feeRow && !texts.feeRow.isEmpty { notes.append(texts.feeRow) }
            if r.gift && !texts.gift.isEmpty { notes.append(texts.gift) }
            if r.spouseGift && !texts.spouseGift.isEmpty { notes.append(texts.spouseGift) }
            if r.payment && !texts.payment.isEmpty { notes.append(texts.payment) }
            if r.valueEstimated && !texts.valueEstimated.isEmpty { notes.append(texts.valueEstimated) }
            if r.usdtApproximate && !texts.usdtAssumed.isEmpty { notes.append(texts.usdtAssumed) }
            let matchText: String?
            switch r.match {
            case .some(.SAME_DAY): matchText = texts.matchSameDay
            case .some(.THIRTY_DAY): matchText = texts.matchThirtyDay
            case .some(.POOL): matchText = texts.matchPool
            case .none: matchText = nil
            }
            if let matchText, !matchText.isEmpty { notes.append(matchText) }
            if let old = r.oldHolding {
                let text = old ? texts.oldHolding : texts.newHolding
                if !text.isEmpty { notes.append(text) }
            }
            if r.swap && !texts.swap.isEmpty { notes.append(texts.swap) }
            if let w = r.wallet, !texts.wallet.isEmpty {
                notes.append(texts.wallet.replacingOccurrences(of: "%s", with: w.isEmpty ? texts.walletUnspecified : w))
            }
            if r.feeLeg { notes.append(texts.feeLeg) }
            if r.costMissing { notes.append(texts.costMissing) }
            if r.uncovered && !texts.oversold.isEmpty { notes.append(texts.oversold) }
            if r.proceedsMissing { notes.append(texts.proceedsMissing) }
            if r.feeValueMissing { notes.append(texts.feeValueMissing) }
            if r.costIncomplete && !texts.costIncomplete.isEmpty { notes.append(texts.costIncomplete) }
            if r.adjustmentUsdt != nil && !texts.adjustment.isEmpty { notes.append(texts.adjustment) }
            if r.pooled && r.costTarget != nil { notes.append(texts.avgCostRates) }
            if r.fxMissing { notes.append(texts.noFx) }
            let longTerm: String
            switch r.longTerm {
            case .some(true): longTerm = texts.yes
            case .some(false): longTerm = texts.no
            case .none: longTerm = ""
            }
            var cells: [CsvCell] = [
                t(r.coin),
                t(r.acquiredDate?.description ?? ""),
                t(r.soldDate.description),
                CsvCell(text: r.holdingDays.map { String($0) } ?? "", numeric: true),
                t(longTerm),
                CsvCell(text: n(CutoffExport.amount(r.amount)), numeric: true),
                money(r.costUsdt), money(r.proceedsUsdt), money(r.gainUsdt),
                money(r.costTarget), money(r.proceedsTarget), money(r.gainTarget),
            ]
            cells += extra(r.adjustmentUsdt, r.adjustmentTarget, r.grossProceedsUsdt, r.grossProceedsTarget,
                           r.portfolioValueUsdt, r.portfolioValueTarget)
            cells.append(t(notes.joined(separator: ", ")))
            out += line(cells, format)
        }
        // Summe nur vollständiger Zeilen; fehlt irgendwo etwas, «unvollständig». Gebühren nur zur Information
        // (`TaxGainRow.feeInfoOnly`) zählen nicht mit
        let counted = rows.filter { !$0.feeInfoOnly }
        let incomplete = counted.contains {
            $0.costMissing || $0.proceedsMissing || $0.feeValueMissing || $0.fxMissing || $0.costIncomplete
        }
        let usdt = counted.filter(\.completeUsdt)
        let target = counted.filter(\.completeTarget)
        func total(_ list: [TaxGainRow], _ pick: (TaxGainRow) -> Decimal?) -> Decimal? {
            list.isEmpty && !counted.isEmpty ? nil : sum(list, pick)
        }
        var totalCells: [CsvCell] = [
            t(texts.total), t(""), t(""), blank, t(""), blank,
            money(total(usdt) { $0.costUsdt }), money(total(usdt) { $0.proceedsUsdt }), money(total(usdt) { $0.gainUsdt }),
            money(total(target) { $0.costTarget }), money(total(target) { $0.proceedsTarget }),
            money(total(target) { $0.gainTarget }),
        ]
        totalCells += extra(sum(counted) { $0.adjustmentUsdt }, sum(counted) { $0.adjustmentTarget },
                            sum(counted) { $0.grossProceedsUsdt }, sum(counted) { $0.grossProceedsTarget }, nil, nil)
        totalCells.append(t(incomplete ? texts.incomplete : ""))
        out += line(totalCells, format)
        // Erlös der Zeilen ohne Kaufpreis (nicht in der Summe oben)
        let noCost = counted.filter { $0.costMissing && $0.proceedsUsdt != nil }
        if !noCost.isEmpty {
            var cells: [CsvCell] = [
                t(texts.proceedsWithoutCost), t(""), t(""), blank, t(""), blank,
                blank, money(sum(noCost) { $0.proceedsUsdt }), blank,
                blank, money(sum(noCost) { $0.proceedsTarget }), blank,
            ]
            cells += extra(nil, nil, nil, nil, nil, nil)
            cells.append(t(texts.incomplete))
            out += line(cells, format)
        }
        // Netzwerkgebühr-Einstand ohne bezahlten Posten: kein Gewinn/Verlust, aber nicht still verloren
        if !texts.lostFeeCost.isEmpty {
            for l in lostFeeCosts {
                var cells: [CsvCell] = [
                    t(l.coin), t(""), t(localDay(l.time, timeZone: timeZone).description), blank, t(""), blank,
                    money(l.costUsdt), blank, blank, blank, blank, blank,
                ]
                cells += extra(nil, nil, nil, nil, nil, nil)
                cells.append(t(texts.lostFeeCost))
                out += line(cells, format)
            }
        }
        // Umbuchungsgebühr auf Bestand ohne Kaufpreis: offener Posten, sichtbar statt still verworfen
        if !texts.unassignedFee.isEmpty {
            for f in unassignedFees {
                var cells: [CsvCell] = [
                    t(f.coin), t(""), t(localDay(f.time, timeZone: timeZone).description), blank, t(""), blank,
                    money(f.usdt), blank, blank, blank, blank, blank,
                ]
                cells += extra(nil, nil, nil, nil, nil, nil)
                cells.append(t(texts.unassignedFee))
                out += line(cells, format)
            }
        }
        return out
    }

    /// CSV «Einkünfte» mit BOM; `header` = Kopfzeilen (je ein Feld). Mit `texts.totalInterest` und `texts.totalStaking`
    /// (AT) Zins und übrige Belohnungen getrennt: Vermerk je Zeile und je eine Summenzeile mit eigenem «unvollständig» —
    /// Zinsen sind beim Zufluss steuerpflichtig, Staking im Neubestand nur Information (sonst doppelt mit dem
    /// Verkaufsgewinn bei Anschaffungskosten 0; gleiche Einteilung wie `yearSummary`). Wie Android.
    static func incomeCsv(_ rows: [TaxIncomeRow], texts: TaxCsvTexts, header: [String],
                          format: CutoffCsvFormat = .semicolonPoint) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> CsvCell { CsvCell(text: n(v.map { CutoffExport.money($0) } ?? ""), numeric: true) }
        func t(_ v: String) -> CsvCell { CsvCell(text: v) }
        var out = bom
        for h in header { out += line([t(h)], format) }
        if !texts.timeZone.isEmpty { out += line([t(texts.timeZone)], format) }
        out += line([texts.date, texts.coin, texts.amount, texts.valueUsdt, texts.valueTarget, texts.note].map { t($0) }, format)
        let split = !texts.totalInterest.isEmpty && !texts.totalStaking.isEmpty
        for r in rows {
            var notes: [String] = []
            if split {
                // Zufluss vor dem 1.3.2022: alte Rechtslage — eigener Vermerk statt «steuerpflichtig»/«nur Information»
                let early = !texts.incomeBeforeNewLaw.isEmpty && r.date < TaxTemplate.atSwapNeutralFrom
                let kind = early ? texts.incomeBeforeNewLaw : (r.interest ? texts.incomeInterest : texts.incomeStaking)
                if !kind.isEmpty { notes.append(kind) }
            }
            if r.valueMissing { notes.append(texts.valueMissing) }
            if r.estimated && !texts.valueEstimated.isEmpty { notes.append(texts.valueEstimated) }
            if r.usdtApproximate && !texts.usdtAssumed.isEmpty { notes.append(texts.usdtAssumed) }
            if r.fxMissing { notes.append(texts.noFx) }
            out += line([t(r.date.description), t(r.coin), CsvCell(text: n(CutoffExport.amount(r.amount)), numeric: true),
                         money(r.valueUsdt), money(r.valueTarget), t(notes.joined(separator: ", "))], format)
        }
        func total(_ label: String, _ list: [TaxIncomeRow]) {
            let incomplete = list.contains { $0.valueMissing || $0.fxMissing }
            out += line([t(label), t(""), CsvCell(text: "", numeric: true), money(sum(list) { $0.valueUsdt }),
                         money(sum(list) { $0.valueTarget }), t(incomplete ? texts.incomplete : "")], format)
        }
        if split {
            total(texts.totalInterest, rows.filter(\.interest))
            total(texts.totalStaking, rows.filter { !$0.interest })
        } else {
            total(texts.total, rows)
        }
        return out
    }

    /// CSV «Jahresübersicht» mit BOM — wie `summaryCsv` (Android): Kopfzeilen, Titel, Hinweis, Zeitzone, dann Posten |
    /// USDT | Währung | Hinweis — zuerst nur Tatsachen, Schlüsse der Vorlage (Sätze, Grenzen, Freibeträge) nur im Block
    /// `texts.orientationHeading`, jeder Posten mit Vorsatz «Orientierung:» (`taxOrientationLabel`).
    static func summaryCsv(_ s: TaxYearSummary, texts: TaxSummaryTexts, header: [String], zoneLine: String = "",
                           format: CutoffCsvFormat = .semicolonPoint) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> String { n(v.map { CutoffExport.money($0) } ?? "") }
        var out = bom
        for h in header { out += textLine([h], format) }
        out += textLine([texts.title], format)
        out += textLine([texts.disclaimer], format)
        if !zoneLine.isEmpty { out += textLine([zoneLine], format) }
        // USDT = 1 an einem nötigen Tag: eigene Zeile (die Posten der Übersicht tragen keinen Vermerk je Transaktion)
        if s.usdtApproximate && !texts.usdtAssumed.isEmpty { out += textLine([texts.usdtAssumed], format) }
        out += textLine([texts.item, texts.usdt, texts.target, texts.note], format)
        func row(_ l: TaxSummaryLine, _ label: String) -> String {
            var notes: [String] = []
            switch l.status {
            case .some(.below): notes.append(texts.below)
            case .some(.reached): notes.append(texts.reached)
            case .some(.unknown): notes.append(texts.unknown)
            case .some(.loss): notes.append(texts.loss.isEmpty ? texts.below : texts.loss)
            case .none: break
            }
            if l.incomplete { notes.append(texts.incomplete) }
            if l.noFx { notes.append(texts.noFx) }
            if l.notCalculated && !texts.notCalculated.isEmpty { notes.append(texts.notCalculated) }
            if let text = l.noteText(texts) { notes.append(text) }
            return textLine([label, money(l.usdt), money(l.target), notes.joined(separator: ", ")], format, numeric: [1, 2])
        }
        // Zuerst nur Tatsachen; Schlüsse der Vorlage nur im eigenen Block, jeder Posten mit Vorsatz «Orientierung:»
        let facts = s.lines.filter { !$0.line.orientation }
        let oriented = s.lines.filter { $0.line.orientation }
        for l in facts {
            out += row(l, texts.labels[l.line] ?? "")
            // CH Vermögenswert: darunter je Coin eine Zeile in denselben Spalten (Coin – Bestand | USDT | Währung |
            // Hinweis); Werte unrund summiert, gerundet nur hier im Text — wie Android
            if l.line == .CH_WEALTH_VALUE, let wealth = s.wealth {
                for r in wealth.rows {
                    let label = texts.wealthCoinLabel(r.coin, amount: n(CutoffExport.amount(r.amount)))
                    out += textLine([label, money(r.valueUsdt), money(s.fxAvailable ? r.valueTarget : nil),
                                     texts.wealthCoinNote(r)], format, numeric: [1, 2])
                }
            }
        }
        if !oriented.isEmpty {
            out += textLine([texts.orientationHeading.isEmpty ? texts.orientation : texts.orientationHeading], format)
            for l in oriented {
                out += row(l, taxOrientationLabel(texts.orientationItem, fallback: texts.orientation, label: texts.labels[l.line] ?? ""))
            }
        }
        return out
    }

    /// Zeile aus typisierten Zellen (Text gegen Formel-Injektion geschützt, Zahlen unverändert).
    private static func line(_ cells: [CsvCell], _ format: CutoffCsvFormat) -> String {
        CutoffExport.joinCells(cells, separator: format.separator) + eol
    }

    /// Zeile aus Texten; Spalten in `numeric` sind Zahlen (z. B. Beträge der Übersicht).
    private static func textLine(_ fields: [String], _ format: CutoffCsvFormat, numeric: Set<Int> = []) -> String {
        line(fields.enumerated().map { CsvCell(text: $0.element, numeric: numeric.contains($0.offset)) }, format)
    }
}
