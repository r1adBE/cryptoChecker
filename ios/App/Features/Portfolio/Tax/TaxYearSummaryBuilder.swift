import Foundation

// Rechnung hinter `TaxExport.yearSummary` — wie `TaxYearSummaryBuilder.kt` (Android): Zeilen aufteilen, Posten der
// Tatsachen (Gewinne, Gebühren, Erlöse, Einkünfte), danach die Orientierung je Vorlage. Beschreibung der Posten dort.

extension TaxExport {
    /// Ein Lauf von `yearSummary`: die Eingaben, was daraus aufgeteilt wird, und die Posten in ihrer Reihenfolge.
    struct TaxYearSummaryBuilder {
        let trades: [PortfolioTx]
        let template: TaxTemplate
        let method: TaxMethod
        let startYear: Int
        let timeZone: TimeZone
        let currency: String
        let rates: [Int: Decimal]?
        let priceAt: ((String, Int64) -> Decimal?)?
        let walletFrom: LocalDay?
        let usdtUsd: [Int: Decimal]?
        let today: LocalDay?
        let yearEndCloses: CutoffCloses?
        let yearEndAddresses: AddressCutoffView?
        let period: TaxPeriod
        let fx: Bool
        let allRows: [TaxGainRow]
        let withdrawalFees: [TaxGainRow]
        let transferFees: [TaxGainRow]
        let rows: [TaxGainRow]
        let allIncome: [TaxIncomeRow]
        let allInterest: [TaxIncomeRow]
        let income: [TaxIncomeRow]
        let pos: (Decimal) -> Bool = { $0 > 0 }
        let neg: (Decimal) -> Bool = { $0 <= 0 }
        let all: (Decimal) -> Bool = { _ in true }
        var lines: [TaxSummaryLine] = []

        init(_ trades: [PortfolioTx], template: TaxTemplate, method: TaxMethod, startYear: Int, timeZone: TimeZone,
             currency: String, rates: [Int: Decimal]?, priceAt: ((String, Int64) -> Decimal?)?, walletFrom: LocalDay?,
             usdtUsd: [Int: Decimal]?, today: LocalDay?, yearEndCloses: CutoffCloses?,
             yearEndAddresses: AddressCutoffView?, run: GainsRun?) {
            let period = template.period(startYear)
            let fx = rates != nil || currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD"
            // `run`: dieselben Eingaben schon gerechnet (`PortfolioTaxExporter.summary`) — gleiche Zeilen ohne neue Rechnung
            let allRows = run?.gainRows(currency: currency, rates: rates ?? [:], usdtUsd: usdtUsd)
                ?? TaxExport.gainRows(trades, method: method, period: period, timeZone: timeZone, currency: currency,
                            rates: rates ?? [:], priceAt: priceAt, rules: template.eventRules,
                            walletFrom: walletFrom, usdtUsd: usdtUsd)
            // DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten, nicht in den Summen (DE: § 23 und Freigrenze;
            // AT: Aufwendungen beim Sondersteuersatz nicht abzugsfähig, § 20 Abs. 2 EStG) — wie Android
            // UK, US, CA, AU, JP: kein Verlust, nur Information (`TaxTemplate.quoteFeeIsInfo`); FR ebenso nur Information (die
            // Formel des art. 150 VH bis kennt nur Gebühren der Abtretung selbst) — wie Android
            let feeApart = template.withdrawalFeeApart || template == .FR || template.quoteFeeIsInfo
            let withdrawalFees = feeApart ? allRows.filter { $0.feeExpense == .WITHDRAWAL } : []
            let sales = feeApart ? allRows.filter { $0.feeExpense != .WITHDRAWAL } : allRows
            // Umbuchungsgebühren nur zur Information (`.TRANSFER`): eigener Posten — wie Android
            let transferFees = sales.filter { $0.feeExpense == .TRANSFER }
            let rows = sales.filter { $0.feeExpense != .TRANSFER }
            // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
            let allIncome = TaxExport.incomeRows(trades, period: period, timeZone: timeZone, currency: currency, rates: rates ?? [:],
                                       priceAt: priceAt, usdtUsd: usdtUsd)
            let allInterest = template == .AT ? allIncome.filter(\.interest) : []
            let income = template == .AT ? allIncome.filter { !$0.interest } : allIncome
            self.trades = trades
            self.template = template
            self.method = method
            self.startYear = startYear
            self.timeZone = timeZone
            self.currency = currency
            self.rates = rates
            self.priceAt = priceAt
            self.walletFrom = walletFrom
            self.usdtUsd = usdtUsd
            self.today = today
            self.yearEndCloses = yearEndCloses
            self.yearEndAddresses = yearEndAddresses
            self.period = period
            self.fx = fx
            self.allRows = allRows
            self.withdrawalFees = withdrawalFees
            self.transferFees = transferFees
            self.rows = rows
            self.allIncome = allIncome
            self.allInterest = allInterest
            self.income = income
        }

        // § 27b EStG (Sondersteuersatz) gilt für Zinsen erst mit Zufluss ab dem 1.3.2022; davor alte Rechtslage (Tarif) —
        // eigener Posten, nicht in der Zeile 27,5 % (wie Android)
        var interestOld: [TaxIncomeRow] { allInterest.filter { $0.date < TaxTemplate.atSwapNeutralFrom } }
        var interest: [TaxIncomeRow] { allInterest.filter { !($0.date < TaxTemplate.atSwapNeutralFrom) } }
        var short: [TaxGainRow] { rows.filter { $0.longTerm == false } }
        var long: [TaxGainRow] { rows.filter { $0.longTerm == true } }
        var unclassifiedRows: [TaxGainRow] { rows.filter { $0.longTerm == nil } }
        // AT: Neuvermögen, verkauft vor dem 1.3.2022, fällt noch unter die alte Rechtslage (§ 30/§ 31 EStG a. F.:
        // Spekulationsgeschäft, Freigrenze 440 €); Option auf 27,5 % nur für Realisationen 1.1.–28.2.2022 (§ 124b Z 384
        // lit. c EStG), gerechnet ohne Option — wie Android
        var atOld: [TaxGainRow] { rows.filter { $0.oldHolding == true } }
        // Gebühr-Aufwände (feeExpense, z. B. Gebühr verbraucht den ganzen Zugang) sind kein Spekulationsgeschäft: sie bleiben
        // im Posten Neubestand, auch vor dem 1.3.2022 (wie Android)
        var atEarlyNew: [TaxGainRow] {
            template == .AT
                ? rows.filter { $0.oldHolding != true && $0.feeExpense == nil && $0.soldDate < TaxTemplate.atSwapNeutralFrom } : []
        }
        var atOption: Bool { atEarlyNew.contains { !($0.soldDate < TaxTemplate.atOptionFrom) } }
        /// Heute (UK/CA: Fenster nach dem Steuerjahr noch offen? CH: Steuerjahr abgelaufen?).
        var todayDay: LocalDay { today ?? TaxExport.localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: timeZone) }

        // UK/CA: Käufe bis 30 Tage nach dem Ende des Steuerjahrs können die Zahlen noch ändern (UK Bed-and-Breakfast, CA
        // Superficial Loss) — solange dieses Fenster offen ist (Käufe am letzten Tag zählen noch), Vermerk am Posten netto.
        // Ein solcher Kauf trifft nur Veräusserungen der letzten 30 Tage des Steuerjahrs (CA: mit Verlust, in der
        // Steuerwährung entschieden); ohne sie bleibt der Vermerk weg (wie Android)
        var windowEnd: LocalDay? {
            var windowDays: Int? = nil
            if template == .UK { windowDays = TaxTemplate.ukBbDays }
            if template == .CA { windowDays = TaxTemplate.caWindowDays }
            var result: LocalDay? = nil
            if let days = windowDays {
                let lateFrom = period.end.plusDays(-(days - 1))
                let affected = rows.contains { r in
                    guard !(r.soldDate < lateFrom) else { return false }
                    guard template == .CA else { return true }
                    guard let g = (fx ? r.gainTarget : nil) ?? r.gainUsdt else { return false }
                    return g < 0
                }
                let end = period.end.plusDays(days)
                if affected && !(end < todayDay) { result = end }
            }
            return result
        }

        // CA: nicht abziehbare Verluste
        var adjRows: [TaxGainRow] { rows.filter { $0.adjustmentUsdt != nil } }
        var deniedUsdt: Decimal { adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentUsdt ?? 0) } }
        var deniedTarget: Decimal { adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentTarget ?? 0) } }
        var deniedFx: Bool { fx && adjRows.contains { $0.adjustmentTarget == nil } }
        // Erlöse (FR: vor Gebühren); ohne Schenkungen an Ehe-/Lebenspartner — ihr «Erlös» ist nur der übergehende Einstand,
        // kein Verkaufserlös (UK s. 58, CA 73(1))
        var grossFr: Bool { template == .FR }
        var sold: [TaxGainRow] { rows.filter { !$0.spouseGift } }
        func procU(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsUsdt : r.proceedsUsdt }
        func procT(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsTarget : r.proceedsTarget }
        var procUsdt: Decimal { sold.reduce(Decimal(0)) { $0 + (procU($1) ?? 0) } }
        var procTarget: Decimal { sold.reduce(Decimal(0)) { $0 + (procT($1) ?? 0) } }
        var procMissing: Bool { sold.contains { procU($0) == nil } }
        var procFx: Bool { fx && sold.contains { procU($0) != nil && procT($0) == nil } }
        // Einkünfte
        var incUsdt: Decimal { income.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) } }
        var incTarget: Decimal { income.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) } }
        var incMissing: Bool { income.contains { $0.valueMissing } }
        var incFx: Bool { fx && income.contains { $0.fxMissing } }
        // AT: Zinsen aus Lending beim Zufluss (§ 27b Abs. 2 Z 1 EStG, 27,5 %) — zählen, Anschaffungskosten = dieser Wert
        var intUsdt: Decimal { interest.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) } }
        var intTarget: Decimal { interest.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) } }
        var intMissing: Bool { interest.contains { $0.valueMissing } }
        var intFx: Bool { fx && interest.contains { $0.fxMissing } }

        /// Posten der Gewinne und Verluste; liefert die Summe netto (AT: Neubestand netto).
        private mutating func gainLines() -> Acc {
            let net: Acc
            if template.holdingBuckets {
                lines.append(Acc(short, pos, fx: fx).line(.SHORT_GAINS, fx: fx, countUnknown: false))
                lines.append(Acc(short, neg, fx: fx).line(.SHORT_LOSSES, fx: fx, countUnknown: false))
                lines.append(Acc(short, all, fx: fx).line(.SHORT_NET, fx: fx))
                lines.append(Acc(long, pos, fx: fx).line(.LONG_GAINS, fx: fx, countUnknown: false))
                lines.append(Acc(long, neg, fx: fx).line(.LONG_LOSSES, fx: fx, countUnknown: false))
                lines.append(Acc(long, all, fx: fx).line(.LONG_NET, fx: fx))
                if !unclassifiedRows.isEmpty { lines.append(Acc(unclassifiedRows, all, fx: fx).line(.UNCLASSIFIED, fx: fx)) }
                net = Acc(rows, all, fx: fx)
            } else if template == .AT {
                // Neubestand zum Sondersteuersatz: Verkäufe ab dem 1.3.2022; Gebühr-Aufwände hier (nicht in den
                // Spekulationsgeschäften, siehe `atEarlyNew`)
                let newRows = rows.filter {
                    $0.oldHolding != true && (!($0.soldDate < TaxTemplate.atSwapNeutralFrom) || $0.feeExpense != nil)
                }
                net = Acc(newRows, all, fx: fx)
                lines.append(Acc(newRows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false))
                lines.append(Acc(newRows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false))
                lines.append(net.line(.NEW_NET, fx: fx))
                if !atOld.isEmpty || !atEarlyNew.isEmpty {
                    // Spekulationsgeschäfte nach alter Rechtslage: Altbestand ≤ 1 Jahr (bzw. ohne Haltedauer) und Neuvermögen,
                    // verkauft vor dem 1.3.2022; Vermerk zur Option, wenn Verkäufe vom 1.1. bis 28.2.2022 darin sind
                    lines.append(Acc(atOld.filter { $0.longTerm != true } + atEarlyNew, all, fx: fx)
                        .line(.OLD_SHORT_NET, fx: fx, note: atOption ? .AT_TRANSITION_2021 : nil))
                    lines.append(Acc(atOld.filter { $0.longTerm == true }, all, fx: fx).line(.OLD_LONG_NET, fx: fx))
                }
            } else {
                net = Acc(rows, all, fx: fx)
                // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
                // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
                let info: TaxSummaryNote? = template == .CH ? .CH_FIFO_INFO : nil
                // FR: Belohnungen zum Wert beim Erhalt in der Erwerbspreis-Summe — nur vermerkt
                let frRewardsNote: TaxSummaryNote? =
                    template == .FR && TaxExport.frRewards(trades, timeZone: timeZone, period: period) ? .FR_REWARDS_INCOME : nil
                lines.append(Acc(rows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false, note: info ?? frRewardsNote))
                lines.append(Acc(rows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false, note: info))
                // FR: beobachtete Adressen im Gesamtwert ohne Einstand — nicht ausgeschlossen (gehören zum Gesamtwert), nur vermerkt
                let frAddress: TaxSummaryNote? =
                    template == .FR && rows.contains { $0.addressValueIncluded } ? .FR_ADDRESS_NO_COST : nil
                if let windowEnd = windowEnd {
                    var l = net.line(.NET, fx: fx, note: .WINDOW_OPEN)
                    l.noteDate = windowEnd
                    lines.append(l)
                } else {
                    lines.append(net.line(.NET, fx: fx, note: info ?? frAddress))
                }
            }
            return net
        }

        /// Alle Posten in ihrer Reihenfolge: Tatsachen, dann die Orientierung der Vorlage.
        mutating func build() -> TaxYearSummary {
            let net = gainLines()
            if !withdrawalFees.isEmpty {
                let note: TaxSummaryNote
                switch template {
                case .AT: note = .AT_WITHDRAWAL_FEE
                case .DE: note = .DE_WITHDRAWAL_FEE
                case .NEUTRAL, .CH, .UK, .US, .CA, .AU, .FR, .JP: note = .WITHDRAWAL_FEE_INFO
                }
                lines.append(Acc(withdrawalFees, all, fx: fx).line(.WITHDRAWAL_FEES, fx: fx, note: note))
            }
            if !transferFees.isEmpty {
                lines.append(Acc(transferFees, all, fx: fx).line(.TRANSFER_FEES, fx: fx, note: .TRANSFER_FEE_INFO))
            }
            if !adjRows.isEmpty {
                lines.append(TaxSummaryLine(line: .DENIED_LOSS, usdt: deniedUsdt, target: fx ? deniedTarget : nil, noFx: deniedFx))
            }
            // UK: Erlöse über der Meldegrenze sind HMRC zu melden (wer für Self Assessment registriert ist), auch ohne
            // steuerbaren Gewinn — geprüft mit den Erlösen vor Gebühren (ohne Wert vor Gebühren: der Erlös), nur in GBP prüfbar
            var ukReport = false
            if template == .UK, fx, currency.trimmingCharacters(in: .whitespaces).uppercased() == "GBP",
               let limit = TaxTemplate.ukReportProceedsGbp(startYear) {
                let beforeFees: Decimal = sold.reduce(Decimal(0)) { $0 + ($1.proceedsBeforeFeesTarget ?? $1.proceedsTarget ?? 0) }
                ukReport = beforeFees > limit
            }
            lines.append(TaxSummaryLine(line: .PROCEEDS, usdt: procUsdt, target: fx ? procTarget : nil,
                                        incomplete: procMissing, noFx: procFx, note: ukReport ? .UK_HMRC_REPORT : nil))
            // AT: Belohnungen im Neubestand mit Anschaffungskosten 0 (kein Zufluss besteuert, erst der Verkauf) — der Posten ist
            // nur Information und zählt in keine Summe (sonst doppelt: Zufluss + Verkaufsgewinn mit AK 0)
            lines.append(TaxSummaryLine(line: .INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                        incomplete: incMissing, noFx: incFx,
                                        note: template == .AT ? TaxSummaryNote.AT_REWARD_INFO : nil))
            if !interest.isEmpty {
                lines.append(TaxSummaryLine(line: .AT_INTEREST, usdt: intUsdt, target: fx ? intTarget : nil,
                                            incomplete: intMissing, noFx: intFx, note: .AT_INTEREST_INCOME))
            }
            // AT: Zinsen vor dem 1.3.2022 — alte Rechtslage (Tarif), zählen nicht zum Sondersteuersatz
            if !interestOld.isEmpty {
                let oldUsdt: Decimal = interestOld.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
                let oldTarget: Decimal = interestOld.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
                lines.append(TaxSummaryLine(line: .AT_INTEREST_OLD, usdt: oldUsdt, target: fx ? oldTarget : nil,
                                            incomplete: interestOld.contains { $0.valueMissing },
                                            noFx: fx && interestOld.contains { $0.fxMissing }, note: .AT_INTEREST_OLD_LAW))
            }
            // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»,
            // «Wert geschätzt», wenn eine Kürzung darauf beruht (`TaxOutflow.estimated`)
            if template == .FR, let line = frOutflowLine() { lines.append(line) }
            // CH: Vermögenswert per 31.12. — Posten mit eigenem «(Orientierung)» vor dem Orientierungs-Block; nur für ein
            // abgelaufenes Steuerjahr (nie ein laufender oder heutiger Kurs); Devisenkurs des 31.12. wie jede Zeile (`rateOn`)
            var wealth: TaxWealth? = nil
            if template == .CH {
                if let yearEndCloses = yearEndCloses, period.end < todayDay {
                    wealth = TaxExport.chWealth(trades, method: method.costMethod ?? .defaultMethod, date: period.end, timeZone: timeZone,
                                      closes: yearEndCloses,
                                      rate: TaxExport.rateOn(rates, date: period.end, currency: currency, usdtUsd: usdtUsd),
                                      addresses: yearEndAddresses)
                }
                lines.append(TaxExport.chWealthLine(wealth, fx: fx))
            }
            orientation(net)
            // USDT = 1 irgendwo in der Übersicht (Tag ohne USDT/USD-Schluss, z. B. vor dem Fenster der Quellen): nie still
            let usdtApproximate = allRows.contains(where: \.usdtApproximate) || allIncome.contains(where: \.usdtApproximate)
                || lines.contains(where: \.usdtApproximate) || (wealth != nil && TaxExport.usdtApproximateOn(usdtUsd, date: period.end))
            return TaxYearSummary(template: template, method: method, period: period, currency: currency, fxAvailable: fx,
                                  lines: lines, walletFrom: walletFrom, wealth: wealth, usdtApproximate: usdtApproximate)
        }

        /// FR: Abgänge ohne Verkauf im Jahr; nil = keine.
        private func frOutflowLine() -> TaxSummaryLine? {
            // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
            let outs = TaxEngines.outflows(trades.filter { $0.amountExact > 0 }, method: method, priceAt: priceAt)
                .filter { period.contains(TaxExport.localDay($0.time, timeZone: timeZone)) }
            if outs.isEmpty { return nil }
            var outUsdt: Decimal = 0
            var outTarget: Decimal = 0
            var outFx = false
            var outUsdtApproximate = false
            var outEstimated = false
            for o in outs {
                guard let r = o.ptaReductionUsdt else { continue }
                outUsdt += r
                if o.estimated { outEstimated = true }
                // Tage, deren Kurs die Kürzung braucht: ohne USDT/USD-Schluss mit USDT = 1 (nie still) — wie Android
                let days = o.ptaReductionParts.isEmpty ? [o.time] : o.ptaReductionParts.map(\.time)
                if days.contains(where: { TaxExport.usdtApproximateOn(usdtUsd, date: TaxExport.localDay($0, timeZone: timeZone)) }) {
                    outUsdtApproximate = true
                }
                // Kürzung = Anteil der Erwerbspreis-Summe: je Herkunft zum Kurs ihres Kaufdatums (wie der Einstand
                // einer Abtretung), ohne Herkunft (Kürzung 0) zum Kurs des Abgangs — wie Android
                var t: Decimal? = 0
                if o.ptaReductionParts.isEmpty {
                    t = TaxExport.rateOn(rates, date: TaxExport.localDay(o.time, timeZone: timeZone), currency: currency, usdtUsd: usdtUsd)
                        .map { PortfolioDecimal.multiply(r, $0) }
                } else {
                    for p in o.ptaReductionParts {
                        guard let acc = t,
                              let rate = TaxExport.rateOn(rates, date: TaxExport.localDay(p.time, timeZone: timeZone), currency: currency,
                                                usdtUsd: usdtUsd) else { t = nil; break }
                        t = acc + PortfolioDecimal.multiply(p.usdt, rate)
                    }
                }
                if let t { outTarget += t } else if fx { outFx = true }
            }
            return TaxSummaryLine(line: .FR_OUTFLOW, usdt: outUsdt, target: fx ? outTarget : nil,
                                  incomplete: true, noFx: outFx, usdtApproximate: fx && outUsdtApproximate,
                                  estimated: outEstimated)
        }
    }
}
