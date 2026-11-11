import Foundation

// Orientierung je Steuer-Vorlage in der Jahresübersicht (`TaxExport.yearSummary`) — letzter Schritt von
// `TaxYearSummaryBuilder.build`, wie die `…Orientation`-Methoden in `TaxYearSummaryBuilder.kt` (Android).

extension TaxExport.TaxYearSummaryBuilder {
    /// Orientierung je Vorlage.
    mutating func orientation(_ net: TaxExport.Acc) {
        switch template {
        case .DE: deOrientation()
        case .AT: atOrientation(net)
        case .CH: chOrientation()
        case .UK: ukOrientation(net)
        case .US: lines.append(TaxSummaryLine(line: .US_TERMS))
        case .CA: caOrientation(net)
        case .AU: auOrientation(net)
        case .FR: frOrientation()
        case .JP:
            lines.append(TaxSummaryLine(line: .JP_MISC, usdt: net.usdt + incUsdt, target: fx ? net.target + incTarget : nil,
                                        incomplete: net.incomplete || incMissing, noFx: net.fxMissing > 0 || incFx))
        case .NEUTRAL:
            break
        }
    }

    private mutating func deOrientation() {
        lines.append(TaxExport.Acc(long, all, fx: fx).line(.DE_LONG, fx: fx))
        let sh = TaxExport.Acc(short, all, fx: fx)
        let limit = TaxExport.privateSalesLimitEur(startYear)
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
            incomeStatus = incTarget < TaxExport.incomeLimitEur ? .below : .reached
        }
        // Geschätzter Tageskurs in einer gezählten Einkunft: Vermerk an der 256-€-Grenze (wie Android)
        let incEstimated = income.contains { $0.estimated && $0.valueUsdt != nil }
        lines.append(TaxSummaryLine(line: .DE_INCOME_LIMIT, usdt: nil, target: TaxExport.incomeLimitEur, status: incomeStatus,
                                    estimated: incEstimated))
    }

    private mutating func atOrientation(_ net: TaxExport.Acc) {
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
        // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
        // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG); Vermerk, wenn Belohnungen vor dem 1.3.2022 mit
        // Anschaffungskosten 0 im Neubestand stecken (wie Android)
        let stakingNote: TaxSummaryNote? = TaxExport.atEarlyRewards(trades, timeZone: timeZone, period: period) ? .AT_STAKING_ZERO_COST : nil
        lines.append(TaxSummaryLine(line: .AT_RATE, usdt: net.usdt + intUsdt, target: fx ? net.target + intTarget : nil,
                                    incomplete: netIncomplete || intMissing, noFx: netFx || intFx, note: stakingNote))
        if !atOld.isEmpty {
            lines.append(TaxExport.Acc(atOld.filter { $0.longTerm == true }, all, fx: fx).line(.AT_OLD_LONG, fx: fx))
        }
        if !atOld.isEmpty || !atEarlyNew.isEmpty {
            // Spekulationsgeschäfte (Altbestand ≤ 1 Jahr, Neuvermögen verkauft vor dem 1.3.2022): Freigrenze 440 €
            // (§ 31 Abs. 3 EStG a. F.) — nicht prüfbar, sobald ein Gewinn, ein Devisenkurs oder beim Altbestand die
            // Haltedauer fehlt (wie Android)
            let spec = TaxExport.Acc(atOld.filter { $0.longTerm == false } + atEarlyNew, all, fx: fx)
            let specStatus: TaxLimitStatus
            if !fx || spec.unknown > 0 || spec.fxMissing > 0 || atOld.contains(where: { $0.longTerm == nil }) {
                specStatus = .unknown
            } else if spec.target < 0 {
                specStatus = .loss
            } else if spec.target <= TaxExport.atSpeculationLimitEur {
                specStatus = .below
            } else {
                specStatus = .reached
            }
            lines.append(TaxSummaryLine(line: .AT_OLD_SPECULATION_LIMIT, usdt: nil, target: TaxExport.atSpeculationLimitEur,
                                        status: specStatus, note: atOption ? .AT_WITHOUT_OPTION : nil))
        }
    }

    private mutating func chOrientation() {
        // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
        lines.append(TaxSummaryLine(line: .CH_PRIVATE))
        lines.append(TaxSummaryLine(line: .CH_INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                    incomplete: incMissing, noFx: incFx))
        // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»
        lines.append(TaxSummaryLine(line: .CH_WEALTH, notCalculated: true, note: .CH_EXCHANGE_VALUE))
    }

    private mutating func ukOrientation(_ net: TaxExport.Acc) {
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
        let aea = TaxTemplate.ukAnnualExemptGbp(startYear)
        let aeaStatus: TaxLimitStatus? = aea == nil ? TaxLimitStatus.unknown : nil
        lines.append(TaxSummaryLine(line: .UK_EXEMPT, usdt: nil, target: aea, status: aeaStatus))
        var after: Decimal? = nil
        if let aea, fx { after = max(0, net.target - aea) }
        lines.append(TaxSummaryLine(line: .UK_AFTER_EXEMPT, usdt: nil, target: after, incomplete: netIncomplete,
                                    noFx: netFx))
    }

    private mutating func caOrientation(_ net: TaxExport.Acc) {
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
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
    }

    private mutating func auOrientation(_ net: TaxExport.Acc) {
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
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
    }

    /// AU: Verluste zuerst gegen Gewinne ohne Rabatt (≤ 12 Monate, ohne Haltedauer), dann gegen die übrigen; 50 % Rabatt.
    private func discounted(_ pick: (TaxGainRow) -> Decimal?) -> Decimal {
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

    private mutating func frOrientation() {
        let status: TaxLimitStatus
        if !fx || procMissing || procFx {
            status = .unknown
        } else {
            status = procTarget <= TaxTemplate.frExemptEur ? .below : .reached
        }
        lines.append(TaxSummaryLine(line: .FR_THRESHOLD, usdt: nil, target: TaxTemplate.frExemptEur, status: status))
        lines.append(TaxSummaryLine(line: .FR_RATE))
    }
}
