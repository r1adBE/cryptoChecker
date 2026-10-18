import Foundation

// CSV der Steuer-Exporte: UTF-8 mit BOM, CRLF, `CutoffCsvFormat` — wie `TaxExportCsv.kt` (Android).

/// Übersetzte Texte der Steuer-Exporte (Spalten mit Währung schon eingesetzt) — wie `TaxCsvTexts` (Android).
struct TaxCsvTexts: Sendable {
    var coin: String
    var acquired: String
    var sold: String
    var holdingDays: String
    /// «> 1 Jahr».
    var longTerm: String
    var amount: String
    var costUsdt: String
    var proceedsUsdt: String
    var gainUsdt: String
    var costTarget: String
    var proceedsTarget: String
    var gainTarget: String
    var date: String
    var valueUsdt: String
    var valueTarget: String
    var note: String
    var total: String
    var yes: String
    var no: String
    var costMissing: String
    var proceedsMissing: String
    var feeLeg: String
    var feeValueMissing: String
    /// «Einstand zu den Kursen der Kaufdaten (Ø)».
    var avgCostRates: String
    var valueMissing: String
    var noFx: String
    var incomplete: String
    /// «mehr verkauft als erfasst» (Teil ohne erfassten Zugang).
    var oversold: String = ""
    /// Summenzeile «Erlös ohne Kaufpreis» (Zeilen mit unbekanntem Einstand).
    var proceedsWithoutCost: String = ""
    /// «Zeitzone: …» schon mit der Zeitzone; leer = keine Zeile.
    var timeZone: String = ""
    /// «Tausch»; leer = kein Vermerk.
    var swap: String = ""
    /// «Wallet: %s» (`.FIFO_WALLET`) mit «%s» für den Namen; leer = kein Vermerk.
    var wallet: String = ""
    /// «ohne Angabe» (Wallet ohne Börse).
    var walletUnspecified: String = ""
    /// «Netzwerkgebühr-Einstand ohne bezahlten Posten» (`PortfolioLostFeeCost`); leer = keine solchen Zeilen.
    var lostFeeCost: String = ""
    /// «Gebühr nicht zugeordnet – Einstand unbekannt» (`PortfolioUnassignedFee`); leer = keine solchen Zeilen.
    var unassignedFee: String = ""
    /// UK: Vermerke der Zuordnung «gleicher Tag», «30 Tage», «Pool (Section 104)».
    var matchSameDay: String = ""
    var matchThirtyDay: String = ""
    var matchPool: String = ""
    /// CA: Spalten «Nicht abziehbarer Verlust USDT/…» (nur wenn es solche gibt) und Vermerk «Superficial Loss».
    var adjustmentUsdt: String = ""
    var adjustmentTarget: String = ""
    var adjustment: String = ""
    /// AT: Vermerke «Altbestand (erworben vor 1.3.2021)» / «Neubestand».
    var oldHolding: String = ""
    var newHolding: String = ""
    /// FR: Vermerk «Erwerbspreis-Summe unvollständig»; Spalten Gesamtwert Portfolio und Abtretungspreis vor Gebühren.
    var costIncomplete: String = ""
    var portfolioValueUsdt: String = ""
    var portfolioValueTarget: String = ""
    var grossUsdt: String = ""
    var grossTarget: String = ""
    /// Vermerke der Gebühr-Aufwände (`PortfolioFeeExpense`): «Gebühr verbraucht den ganzen Zugang», «Gebühr Auszahlung».
    var feeConsumed: String = ""
    var withdrawalFee: String = ""
    /// Vermerk «Gebühr» einer Veräusserung ohne Erlös (`TaxGainRow.feeRow`); leer = kein Vermerk.
    var feeRow: String = ""
    /// Vermerk «Schenkung» (`TaxGainRow.gift`); leer = kein Vermerk.
    var gift: String = ""
    /// Vermerk «Zahlung mit Krypto» (`TaxGainRow.payment`); leer = kein Vermerk.
    var payment: String = ""
    /// Vermerk «Wert geschätzt (Tagesschluss)» (`TaxGainRow.valueEstimated`); leer = kein Vermerk.
    var valueEstimated: String = ""
    /// Vermerk «an Ehe-/Lebenspartner – kein Gewinn/Verlust» (`TaxGainRow.spouseGift`); leer = kein Vermerk.
    var spouseGift: String = ""
    /// Vermerk «Auszahlungsgebühr USDT/Fiat – kein Verlust, nur Information» (`TaxGainRow.feeInfoOnly`); leer = `withdrawalFee`.
    var withdrawalFeeInfo: String = ""
    /// Vermerk «Umbuchungsgebühr USDT/Fiat – kein Verlust, nicht im Einstand, nur Information» (`.TRANSFER`).
    var transferFeeInfo: String = ""
    /// Vermerk «USDT = 1 USD angenommen» (`TaxGainRow.usdtApproximate`, `TaxIncomeRow.usdtApproximate`); leer = kein Vermerk.
    var usdtAssumed: String = ""
    /// AT (nur diese Vorlage füllt sie, `withTemplateTexts`): Einkünfte getrennt wie in der Jahresübersicht
    /// (`TaxIncomeRow.interest`) — Vermerk je Zeile «Zins – steuerpflichtig beim Zufluss» bzw. «Staking – nur zur
    /// Information (Anschaffungskosten 0, besteuert beim Verkauf)» und statt der einen Summe zwei Summenzeilen «Summe Zins
    /// (steuerpflichtig)» / «Summe Staking (Information)». Leer (alle anderen Vorlagen) = eine Summe wie bisher. Wie Android.
    var incomeInterest: String = ""
    var incomeStaking: String = ""
    var totalInterest: String = ""
    var totalStaking: String = ""
    /// AT: Vermerk für Belohnungen (Zins und Staking) mit Zufluss vor dem 1.3.2022 «vor 1.3.2022 – mit Fachperson prüfen»
    /// statt `incomeInterest`/`incomeStaking` — § 27b EStG gilt erst ab dann. Leer = kein eigener Vermerk. Wie Android.
    var incomeBeforeNewLaw: String = ""

    /// Aus den App-Texten; Währungsspalten mit `currency`, Zeitzone der Daten `zone` (die der Vorlage, Neutral: Gerät).
    static func localized(currency: String, zone: TimeZone = .current) -> TaxCsvTexts {
        TaxCsvTexts(
            coin: L("portfolio_export_col_coin"),
            acquired: L("tax_export_col_acquired"),
            sold: L("tax_export_col_sold"),
            holdingDays: L("tax_export_col_days"),
            longTerm: L("tax_export_col_long_term"),
            amount: L("portfolio_export_col_amount"),
            costUsdt: L("tax_export_col_cost", "USDT"),
            proceedsUsdt: L("tax_export_col_proceeds", "USDT"),
            gainUsdt: L("tax_export_col_gain", "USDT"),
            costTarget: L("tax_export_col_cost", currency),
            proceedsTarget: L("tax_export_col_proceeds", currency),
            gainTarget: L("tax_export_col_gain", currency),
            date: L("tax_export_col_date"),
            valueUsdt: L("portfolio_export_col_value"),
            valueTarget: L("portfolio_export_col_value_cur", currency),
            note: L("portfolio_export_col_note"),
            total: L("portfolio_export_total"),
            yes: L("tax_export_yes"),
            no: L("tax_export_no"),
            costMissing: L("tax_export_cost_missing"),
            proceedsMissing: L("tax_export_proceeds_missing"),
            feeLeg: L("tax_export_fee_leg"),
            feeValueMissing: L("tax_export_fee_value_missing"),
            avgCostRates: L("tax_export_avg_cost_rates"),
            valueMissing: L("tax_export_value_missing"),
            noFx: L("portfolio_export_no_fx"),
            incomplete: L("portfolio_export_incomplete"),
            oversold: L("tax_export_oversold"),
            proceedsWithoutCost: L("tax_export_proceeds_without_cost"),
            // Jahr und Daten in der Zeitzone der Vorlage (wie `PortfolioTaxExporter.build`) — in der CSV genannt
            // («Daten in Ortszeit (Zone)»; Verlauf/Heute rechnen in UTC)
            timeZone: L("tax_export_year_local_zone", zone.identifier),
            // Tausch und FIFO je Wallet (Platzhalter «%s» setzt der Export mit dem Namen der Wallet)
            swap: L("tax_export_swap"),
            wallet: L("tax_export_wallet", "%s"),
            walletUnspecified: L("tax_export_wallet_unspecified"),
            lostFeeCost: L("tax_export_lost_fee_cost"),
            unassignedFee: L("tax_export_unassigned_fee"),
            // Gebühr als Aufwand (`PortfolioFeeExpenses`)
            feeConsumed: L("tax_export_fee_consumed"),
            withdrawalFee: L("tax_export_withdrawal_fee"),
            // CSV-Zeile «Fee»: Veräusserung ohne Erlös
            feeRow: L("tax_export_fee_row"),
            // Verschenkt/bezahlt und geschätzter Wert (Build 47)
            gift: L("tax_export_gift"),
            payment: L("tax_export_payment"),
            valueEstimated: L("tax_export_value_estimated"),
            // Build 56: Schenkung an Ehe-/Lebenspartner (UK s. 58, CA 73(1))
            spouseGift: L("tax_export_spouse_gift"),
            // Auszahlungsgebühr USDT/Fiat nur Information (UK, US, CA, AU, JP: `TaxTemplate.quoteFeeIsInfo`)
            withdrawalFeeInfo: L("tax_export_withdrawal_fee_info"),
            // Build 57: Umbuchungsgebühr USDT/Fiat nur Information (nicht im Einstand)
            transferFeeInfo: L("tax_export_transfer_fee_info"),
            // USDT zum Marktkurs: Tag ohne USDT/USD-Tagesschluss rechnet mit 1 — je Zeile vermerkt
            usdtAssumed: L("portfolio_usdt_assumed")
        )
    }
}

extension TaxExport {

    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"

    /// Summe der bekannten Werte; nil, wenn keiner bekannt ist (und es Zeilen gibt).
    private static func sum<T>(_ rows: [T], _ pick: (T) -> Decimal?) -> Decimal? {
        let known = rows.compactMap(pick)
        if known.isEmpty { return rows.isEmpty ? 0 : nil }
        return known.reduce(Decimal(0), +)
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
        for l in facts { out += row(l, texts.labels[l.line] ?? "") }
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
