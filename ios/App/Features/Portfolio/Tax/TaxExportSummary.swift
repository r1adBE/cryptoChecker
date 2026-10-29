import Foundation

// Jahresübersicht je Steuer-Vorlage (`TaxExport.yearSummary`) — wie `TaxExportSummary.kt` (Android). Modell in
// TaxSummaryModel.swift, Texte in TaxSummaryTexts.swift, Hilfen (`Acc`, `atEarlyRewards`) in
// TaxExport+SummaryHelpers.swift.

extension TaxExport {
    /// Jahresübersicht der Vorlage `template` für das Steuerjahr ab `startYear` — ORIENTIERUNG, KEINE STEUERBERATUNG;
    /// Posten und Regeln wie `yearSummary` (Android). `run`: dieselben Veräusserungen schon gerechnet — nur mit genau
    /// diesen Eingaben (`trades`, `method`, Steuerjahr, `timeZone`, `priceAt`, `template.eventRules`, `walletFrom`); Debug
    /// prüft das per `assert` (`GainsRun.matches`), `trades` und `priceAt` nicht (Closure): dafür bürgt der Aufrufer.
    static func yearSummary(_ trades: [PortfolioTx], template: TaxTemplate, method: TaxMethod, startYear: Int,
                            timeZone: TimeZone, currency: String, rates: [Int: Decimal]?,
                            priceAt: ((String, Int64) -> Decimal?)? = nil,
                            walletFrom: LocalDay? = nil, usdtUsd: [Int: Decimal]? = nil,
                            today: LocalDay? = nil, yearEndCloses: CutoffCloses? = nil,
                            yearEndAddresses: AddressCutoffView? = nil, run: GainsRun? = nil) -> TaxYearSummary {
        let period = template.period(startYear)
        assert(run?.matches(method: method, period: period, timeZone: timeZone, rules: template.eventRules,
                            walletFrom: walletFrom) ?? true, "yearSummary: GainsRun aus anderen Eingaben")
        let fx = rates != nil || currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD"
        // `run`: dieselben Eingaben schon gerechnet (`PortfolioTaxExporter.summary`) — gleiche Zeilen ohne neue Rechnung
        let allRows = run?.gainRows(currency: currency, rates: rates ?? [:], usdtUsd: usdtUsd)
            ?? gainRows(trades, method: method, period: period, timeZone: timeZone, currency: currency,
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
        let allIncome = incomeRows(trades, period: period, timeZone: timeZone, currency: currency, rates: rates ?? [:],
                                   priceAt: priceAt, usdtUsd: usdtUsd)
        let allInterest = template == .AT ? allIncome.filter(\.interest) : []
        let income = template == .AT ? allIncome.filter { !$0.interest } : allIncome
        // § 27b EStG (Sondersteuersatz) gilt für Zinsen erst mit Zufluss ab dem 1.3.2022; davor alte Rechtslage (Tarif) —
        // eigener Posten, nicht in der Zeile 27,5 % (wie Android)
        let interestOld = allInterest.filter { $0.date < TaxTemplate.atSwapNeutralFrom }
        let interest = allInterest.filter { !($0.date < TaxTemplate.atSwapNeutralFrom) }
        let pos: (Decimal) -> Bool = { $0 > 0 }
        let neg: (Decimal) -> Bool = { $0 <= 0 }
        let all: (Decimal) -> Bool = { _ in true }
        var lines: [TaxSummaryLine] = []
        let net: Acc
        let short = rows.filter { $0.longTerm == false }
        let long = rows.filter { $0.longTerm == true }
        let unclassifiedRows = rows.filter { $0.longTerm == nil }
        // AT: Neuvermögen, verkauft vor dem 1.3.2022, fällt noch unter die alte Rechtslage (§ 30/§ 31 EStG a. F.:
        // Spekulationsgeschäft, Freigrenze 440 €); Option auf 27,5 % nur für Realisationen 1.1.–28.2.2022 (§ 124b Z 384
        // lit. c EStG), gerechnet ohne Option — wie Android
        let atOld = rows.filter { $0.oldHolding == true }
        // Gebühr-Aufwände (feeExpense, z. B. Gebühr verbraucht den ganzen Zugang) sind kein Spekulationsgeschäft: sie bleiben
        // im Posten Neubestand, auch vor dem 1.3.2022 (wie Android)
        let atEarlyNew = template == .AT
            ? rows.filter { $0.oldHolding != true && $0.feeExpense == nil && $0.soldDate < TaxTemplate.atSwapNeutralFrom } : []
        let atOption = atEarlyNew.contains { !($0.soldDate < TaxTemplate.atOptionFrom) }
        // UK/CA: Käufe bis 30 Tage nach dem Ende des Steuerjahrs können die Zahlen noch ändern (UK Bed-and-Breakfast, CA
        // Superficial Loss) — solange dieses Fenster offen ist (Käufe am letzten Tag zählen noch), Vermerk am Posten netto.
        // Ein solcher Kauf trifft nur Veräusserungen der letzten 30 Tage des Steuerjahrs (CA: mit Verlust, in der
        // Steuerwährung entschieden); ohne sie bleibt der Vermerk weg (wie Android)
        var windowDays: Int? = nil
        if template == .UK { windowDays = TaxTemplate.ukBbDays }
        if template == .CA { windowDays = TaxTemplate.caWindowDays }
        var windowEnd: LocalDay? = nil
        if let days = windowDays {
            let lateFrom = period.end.plusDays(-(days - 1))
            let affected = rows.contains { r in
                guard !(r.soldDate < lateFrom) else { return false }
                guard template == .CA else { return true }
                guard let g = (fx ? r.gainTarget : nil) ?? r.gainUsdt else { return false }
                return g < 0
            }
            let end = period.end.plusDays(days)
            let todayDay = today ?? localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: timeZone)
            if affected && !(end < todayDay) { windowEnd = end }
        }
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
                template == .FR && frRewards(trades, timeZone: timeZone, period: period) ? .FR_REWARDS_INCOME : nil
            lines.append(Acc(rows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false, note: info ?? frRewardsNote))
            lines.append(Acc(rows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false, note: info))
            // FR: beobachtete Adressen im Gesamtwert ohne Einstand — nicht ausgeschlossen (gehören zum Gesamtwert), nur vermerkt
            let frAddress: TaxSummaryNote? =
                template == .FR && rows.contains { $0.addressValueIncluded } ? .FR_ADDRESS_NO_COST : nil
            if let windowEnd {
                var l = net.line(.NET, fx: fx, note: .WINDOW_OPEN)
                l.noteDate = windowEnd
                lines.append(l)
            } else {
                lines.append(net.line(.NET, fx: fx, note: info ?? frAddress))
            }
        }
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
        // CA: nicht abziehbare Verluste
        let adjRows = rows.filter { $0.adjustmentUsdt != nil }
        let deniedUsdt: Decimal = adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentUsdt ?? 0) }
        let deniedTarget: Decimal = adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentTarget ?? 0) }
        let deniedFx = fx && adjRows.contains { $0.adjustmentTarget == nil }
        if !adjRows.isEmpty {
            lines.append(TaxSummaryLine(line: .DENIED_LOSS, usdt: deniedUsdt, target: fx ? deniedTarget : nil, noFx: deniedFx))
        }
        // Erlöse (FR: vor Gebühren); ohne Schenkungen an Ehe-/Lebenspartner — ihr «Erlös» ist nur der übergehende Einstand,
        // kein Verkaufserlös (UK s. 58, CA 73(1))
        let grossFr = template == .FR
        let sold = rows.filter { !$0.spouseGift }
        func procU(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsUsdt : r.proceedsUsdt }
        func procT(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsTarget : r.proceedsTarget }
        let procUsdt: Decimal = sold.reduce(Decimal(0)) { $0 + (procU($1) ?? 0) }
        let procTarget: Decimal = sold.reduce(Decimal(0)) { $0 + (procT($1) ?? 0) }
        let procMissing = sold.contains { procU($0) == nil }
        let procFx = fx && sold.contains { procU($0) != nil && procT($0) == nil }
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
        // Einkünfte
        let incUsdt: Decimal = income.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let incTarget: Decimal = income.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        let incMissing = income.contains { $0.valueMissing }
        let incFx = fx && income.contains { $0.fxMissing }
        // AT: Belohnungen im Neubestand mit Anschaffungskosten 0 (kein Zufluss besteuert, erst der Verkauf) — der Posten ist
        // nur Information und zählt in keine Summe (sonst doppelt: Zufluss + Verkaufsgewinn mit AK 0)
        lines.append(TaxSummaryLine(line: .INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                    incomplete: incMissing, noFx: incFx,
                                    note: template == .AT ? TaxSummaryNote.AT_REWARD_INFO : nil))
        // AT: Zinsen aus Lending beim Zufluss (§ 27b Abs. 2 Z 1 EStG, 27,5 %) — zählen, Anschaffungskosten = dieser Wert
        let intUsdt: Decimal = interest.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let intTarget: Decimal = interest.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        let intMissing = interest.contains { $0.valueMissing }
        let intFx = fx && interest.contains { $0.fxMissing }
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
        if template == .FR {
            // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
            let outs = TaxEngines.outflows(trades.filter { $0.amountExact > 0 }, method: method, priceAt: priceAt)
                .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            if !outs.isEmpty {
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
                    if days.contains(where: { usdtApproximateOn(usdtUsd, date: localDay($0, timeZone: timeZone)) }) {
                        outUsdtApproximate = true
                    }
                    // Kürzung = Anteil der Erwerbspreis-Summe: je Herkunft zum Kurs ihres Kaufdatums (wie der Einstand
                    // einer Abtretung), ohne Herkunft (Kürzung 0) zum Kurs des Abgangs — wie Android
                    var t: Decimal? = 0
                    if o.ptaReductionParts.isEmpty {
                        t = rateOn(rates, date: localDay(o.time, timeZone: timeZone), currency: currency, usdtUsd: usdtUsd)
                            .map { PortfolioDecimal.multiply(r, $0) }
                    } else {
                        for p in o.ptaReductionParts {
                            guard let acc = t,
                                  let rate = rateOn(rates, date: localDay(p.time, timeZone: timeZone), currency: currency,
                                                    usdtUsd: usdtUsd) else { t = nil; break }
                            t = acc + PortfolioDecimal.multiply(p.usdt, rate)
                        }
                    }
                    if let t { outTarget += t } else if fx { outFx = true }
                }
                lines.append(TaxSummaryLine(line: .FR_OUTFLOW, usdt: outUsdt, target: fx ? outTarget : nil,
                                            incomplete: true, noFx: outFx, usdtApproximate: fx && outUsdtApproximate,
                                            estimated: outEstimated))
            }
        }
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
        // CH: Vermögenswert per 31.12. — Posten mit eigenem «(Orientierung)» vor dem Orientierungs-Block; nur für ein
        // abgelaufenes Steuerjahr (nie ein laufender oder heutiger Kurs); Devisenkurs des 31.12. wie jede Zeile (`rateOn`)
        var wealth: TaxWealth? = nil
        if template == .CH {
            let todayDay = today ?? localDay(Int64(Date().timeIntervalSince1970 * 1000), timeZone: timeZone)
            if let yearEndCloses, period.end < todayDay {
                wealth = chWealth(trades, method: method.costMethod ?? .defaultMethod, date: period.end, timeZone: timeZone,
                                  closes: yearEndCloses,
                                  rate: rateOn(rates, date: period.end, currency: currency, usdtUsd: usdtUsd),
                                  addresses: yearEndAddresses)
            }
            lines.append(chWealthLine(wealth, fx: fx))
        }
        // Orientierung je Vorlage
        switch template {
        case .DE:
            lines.append(Acc(long, all, fx: fx).line(.DE_LONG, fx: fx))
            let sh = Acc(short, all, fx: fx)
            let limit = privateSalesLimitEur(startYear)
            let salesStatus: TaxLimitStatus
            if !fx || sh.unknown > 0 || !unclassifiedRows.isEmpty || sh.fxMissing > 0 {
                salesStatus = .unknown
            } else if sh.target < 0 {
                // Netto-Verlust: eigener Status (nur mit Gewinnen aus privaten Veräusserungsgeschäften verrechenbar)
                salesStatus = .loss
            } else {
                salesStatus = sh.target < limit ? .below : .reached
            }
            lines.append(TaxSummaryLine(line: .DE_PRIVATE_LIMIT, usdt: nil, target: limit, status: salesStatus))
            let incomeStatus: TaxLimitStatus
            if !fx || incMissing || incFx {
                incomeStatus = .unknown
            } else {
                incomeStatus = incTarget < incomeLimitEur ? .below : .reached
            }
            // Geschätzter Tageskurs in einer gezählten Einkunft: Vermerk an der 256-€-Grenze (wie Android)
            let incEstimated = income.contains { $0.estimated && $0.valueUsdt != nil }
            lines.append(TaxSummaryLine(line: .DE_INCOME_LIMIT, usdt: nil, target: incomeLimitEur, status: incomeStatus,
                                        estimated: incEstimated))
        case .AT:
            // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
            // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG); Vermerk, wenn Belohnungen vor dem 1.3.2022 mit
            // Anschaffungskosten 0 im Neubestand stecken (wie Android)
            let stakingNote: TaxSummaryNote? = atEarlyRewards(trades, timeZone: timeZone, period: period) ? .AT_STAKING_ZERO_COST : nil
            lines.append(TaxSummaryLine(line: .AT_RATE, usdt: net.usdt + intUsdt, target: fx ? net.target + intTarget : nil,
                                        incomplete: netIncomplete || intMissing, noFx: netFx || intFx, note: stakingNote))
            if !atOld.isEmpty {
                lines.append(Acc(atOld.filter { $0.longTerm == true }, all, fx: fx).line(.AT_OLD_LONG, fx: fx))
            }
            if !atOld.isEmpty || !atEarlyNew.isEmpty {
                // Spekulationsgeschäfte (Altbestand ≤ 1 Jahr, Neuvermögen verkauft vor dem 1.3.2022): Freigrenze 440 €
                // (§ 31 Abs. 3 EStG a. F.) — nicht prüfbar, sobald ein Gewinn, ein Devisenkurs oder beim Altbestand die
                // Haltedauer fehlt (wie Android)
                let spec = Acc(atOld.filter { $0.longTerm == false } + atEarlyNew, all, fx: fx)
                let specStatus: TaxLimitStatus
                if !fx || spec.unknown > 0 || spec.fxMissing > 0 || atOld.contains(where: { $0.longTerm == nil }) {
                    specStatus = .unknown
                } else if spec.target < 0 {
                    specStatus = .loss
                } else if spec.target <= atSpeculationLimitEur {
                    specStatus = .below
                } else {
                    specStatus = .reached
                }
                lines.append(TaxSummaryLine(line: .AT_OLD_SPECULATION_LIMIT, usdt: nil, target: atSpeculationLimitEur,
                                            status: specStatus, note: atOption ? .AT_WITHOUT_OPTION : nil))
            }
        case .CH:
            // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
            lines.append(TaxSummaryLine(line: .CH_PRIVATE))
            lines.append(TaxSummaryLine(line: .CH_INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                        incomplete: incMissing, noFx: incFx))
            // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»
            lines.append(TaxSummaryLine(line: .CH_WEALTH, notCalculated: true, note: .CH_EXCHANGE_VALUE))
        case .UK:
            let aea = TaxTemplate.ukAnnualExemptGbp(startYear)
            let aeaStatus: TaxLimitStatus? = aea == nil ? TaxLimitStatus.unknown : nil
            lines.append(TaxSummaryLine(line: .UK_EXEMPT, usdt: nil, target: aea, status: aeaStatus))
            var after: Decimal? = nil
            if let aea, fx { after = max(0, net.target - aea) }
            lines.append(TaxSummaryLine(line: .UK_AFTER_EXEMPT, usdt: nil, target: after, incomplete: netIncomplete,
                                        noFx: netFx))
        case .US:
            lines.append(TaxSummaryLine(line: .US_TERMS))
        case .CA:
            // Nur in CAD (Gewinn/Verlust in CAD entschieden): keine USDT-Spalte; netto ein Verlust → steuerbar 0, der
            // abziehbare Teil (× 1/2) als «net capital loss» vorzutragen — wie Android
            let f = TaxTemplate.caInclusion
            let taxable: Decimal? = fx ? PortfolioDecimal.multiply(net.target + deniedTarget, f) : nil
            lines.append(TaxSummaryLine(line: .CA_TAXABLE, usdt: nil, target: taxable.map { max(0, $0) },
                                        incomplete: netIncomplete, noFx: !fx || netFx || deniedFx))
            if let taxable, taxable < 0 {
                lines.append(TaxSummaryLine(line: .NET_CAPITAL_LOSS, usdt: nil, target: -taxable, incomplete: netIncomplete,
                                            noFx: netFx || deniedFx))
            }
        case .AU:
            // Verluste zuerst gegen Gewinne ohne Rabatt (≤ 12 Monate, ohne Haltedauer), dann gegen die übrigen; 50 % Rabatt
            func discounted(_ pick: (TaxGainRow) -> Decimal?) -> Decimal {
                var nonDisc: Decimal = 0
                var disc: Decimal = 0
                var losses: Decimal = 0
                for r in rows {
                    guard let g = pick(r) else { continue }
                    if g <= 0 {
                        losses -= g
                    } else if r.longTerm == true {
                        disc += g
                    } else {
                        nonDisc += g
                    }
                }
                var rest = nonDisc - losses
                if rest < 0 {
                    disc += rest
                    rest = 0
                }
                if disc < 0 { return disc }
                let factor: Decimal = 1 - TaxTemplate.auDiscount
                return rest + PortfolioDecimal.multiply(disc, factor)
            }
            // Netto ein Verlust: steuerbar 0, der ganze Verlust ist vorzutragen (ATO «net capital loss», ohne Rabatt)
            let u = discounted { $0.gainUsdt }
            let tg: Decimal? = fx ? discounted { $0.gainTarget } : nil
            lines.append(TaxSummaryLine(line: .AU_DISCOUNTED, usdt: max(0, u), target: tg.map { max(0, $0) },
                                        incomplete: netIncomplete, noFx: netFx))
            let basis: Decimal? = fx ? tg : u
            if let basis, basis < 0 {
                lines.append(TaxSummaryLine(line: .NET_CAPITAL_LOSS, usdt: max(0, -u), target: tg.map { max(0, -$0) },
                                            incomplete: netIncomplete, noFx: netFx))
            }
        case .FR:
            let status: TaxLimitStatus
            if !fx || procMissing || procFx {
                status = .unknown
            } else {
                status = procTarget <= TaxTemplate.frExemptEur ? .below : .reached
            }
            lines.append(TaxSummaryLine(line: .FR_THRESHOLD, usdt: nil, target: TaxTemplate.frExemptEur, status: status))
            lines.append(TaxSummaryLine(line: .FR_RATE))
        case .JP:
            lines.append(TaxSummaryLine(line: .JP_MISC, usdt: net.usdt + incUsdt, target: fx ? net.target + incTarget : nil,
                                        incomplete: netIncomplete || incMissing, noFx: netFx || incFx))
        case .NEUTRAL:
            break
        }
        // USDT = 1 irgendwo in der Übersicht (Tag ohne USDT/USD-Schluss, z. B. vor dem Fenster der Quellen): nie still
        let usdtApproximate = allRows.contains(where: \.usdtApproximate) || allIncome.contains(where: \.usdtApproximate)
            || lines.contains(where: \.usdtApproximate) || (wealth != nil && usdtApproximateOn(usdtUsd, date: period.end))
        return TaxYearSummary(template: template, method: method, period: period, currency: currency, fxAvailable: fx,
                              lines: lines, walletFrom: walletFrom, wealth: wealth, usdtApproximate: usdtApproximate)
    }
}
