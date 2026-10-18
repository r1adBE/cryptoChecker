import Foundation

// Jahresübersicht je Steuer-Vorlage (`TaxExport.yearSummary`) — wie `TaxExportSummary.kt` (Android).

/// Ergebnis einer Freigrenzen-Prüfung — wie `TaxLimitStatus` (Android).
enum TaxLimitStatus: Sendable, Equatable {
    /// Unter der Freigrenze (steuerfrei).
    case below
    /// Erreicht oder überschritten: der GANZE Betrag ist steuerpflichtig (Freigrenze, kein Freibetrag).
    case reached
    /// Nicht prüfbar (EUR-Werte fehlen, Gewinn/Wert unbekannt, Haltedauer fehlt).
    case unknown
    /// DE § 23: netto ein Verlust — kein steuerpflichtiger Gewinn; nur mit Gewinnen aus privaten Veräusserungsgeschäften
    /// verrechenbar (§ 23 Abs. 3 Sätze 7–8 EStG, Rück-/Vortrag nach § 10d). Wie `TaxLimitStatus.LOSS` (Android).
    case loss
}

/// Zusatz-Vermerk eines Postens der Jahresübersicht (`TaxSummaryLine.note`, Text in `TaxSummaryTexts.notes`) — wie
/// `TaxSummaryNote` (Android).
enum TaxSummaryNote: String, Sendable, Equatable, Hashable {
    /// AT Einkünfte: nur Information, nicht addieren — Belohnungen (Staking, Airdrop) haben im Neubestand Anschaffungskosten
    /// 0 und sind erst beim Verkauf steuerpflichtig (dort schon in den Gewinnen; BMF, § 27b Abs. 2 EStG).
    case AT_REWARD_INFO
    /// CH Vermögen: der Wert per 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, nicht die Steuerwerte der ESTV-Kursliste.
    case CH_EXCHANGE_VALUE
    /// CH Gewinne/Verluste/netto: nur Information — FIFO-Rechnung der App, keine Einordnung (Kapitalgewinne im
    /// Privatvermögen «nicht gerechnet – siehe ESTV»).
    case CH_FIFO_INFO
    /// DE Auszahlungsgebühren (`TaxLine.WITHDRAWAL_FEES`): nicht mit § 23 verrechnet — ggf. Werbungskosten prüfen.
    case DE_WITHDRAWAL_FEE
    /// AT Auszahlungsgebühren: nicht mit dem Neubestand verrechnet (§ 20 Abs. 2 EStG: beim Sondersteuersatz nicht abzugsfähig).
    case AT_WITHDRAWAL_FEE
    /// AT Zinsen (`TaxLine.AT_INTEREST`): beim Zufluss laufende Einkünfte aus Kryptowährungen (Entgelt für die Überlassung,
    /// § 27b Abs. 2 Z 1 EStG) zum Sondersteuersatz 27,5 %; die Coins behalten diesen Wert als Anschaffungskosten (BMF).
    case AT_INTEREST_INCOME
    /// UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat (`TaxTemplate.quoteFeeIsInfo`): kein Verlust, nur Information
    /// (UK: HMRC CRYPTO22150; sonst keine Regel, die sie abziehbar macht).
    case WITHDRAWAL_FEE_INFO
    /// UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat (`TaxLine.TRANSFER_FEES`, Build 57): kein Verlust und nicht im
    /// Einstand, nur Information.
    case TRANSFER_FEE_INFO
    /// AT Neubestand netto (`TaxLine.NEW_NET`): Verkäufe bzw. Tausch von Neuvermögen vom 1.3.2021 (`TaxTemplate.atNewFrom`)
    /// bis 28.2.2022 (vor `TaxTemplate.atSwapNeutralFrom`) sind mit 27,5 % gebucht — rechtlich galt dort noch die alte
    /// Rechtslage (§ 31 EStG a. F.) mit Option auf 27,5 % (§ 124b Z 384 EStG): mit Steuerberatung klären.
    case AT_TRANSITION_2021
}

/// Posten der Jahresübersicht einer Vorlage (`TaxExport.yearSummary`) — wie `TaxLine` (Android); `orientation` = Schluss
/// nach den Regeln der Vorlage, nur als «Orientierung» mit Hinweis.
enum TaxLine: String, CaseIterable, Sendable {
    case SHORT_GAINS, SHORT_LOSSES, SHORT_NET, LONG_GAINS, LONG_LOSSES, LONG_NET, UNCLASSIFIED
    case GAINS, LOSSES, NET
    case NEW_NET, OLD_SHORT_NET, OLD_LONG_NET
    case DENIED_LOSS
    case PROCEEDS
    case INCOME
    /// AT: Zinsen aus Lending (`TaxIncomeRow.interest`) zum Wert beim Zufluss — zählen (Vermerk `.AT_INTEREST_INCOME`); nicht
    /// in `INCOME` (dort nur Belohnungen mit Anschaffungskosten 0, Information).
    case AT_INTEREST
    /// FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe (`TaxOutflow`); das Jahr gilt als unvollständig.
    case FR_OUTFLOW
    /// DE, AT: Gebühren von Auszahlungen — eigener Posten, nicht mit den Veräusserungen verrechnet.
    case WITHDRAWAL_FEES
    /// UK, US, CA, AU, JP (Build 57): Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets (`.TRANSFER`) — nur
    /// Information, weder Verlust noch Einstand.
    case TRANSFER_FEES
    case DE_LONG, DE_PRIVATE_LIMIT, DE_INCOME_LIMIT
    case AT_RATE, AT_OLD_LONG
    /// AT: Altbestand ≤ 1 Jahr = Spekulationsgeschäft nach alter Rechtslage (§ 30 EStG a. F.) — Freigrenze 440 € im
    /// Kalenderjahr (§ 31 Abs. 3 EStG a. F.), Status wie `DE_PRIVATE_LIMIT`. Wie Android.
    case AT_OLD_SPECULATION_LIMIT
    case CH_PRIVATE, CH_INCOME, CH_WEALTH
    case UK_EXEMPT, UK_AFTER_EXEMPT
    case US_TERMS
    case CA_TAXABLE
    case AU_DISCOUNTED
    case FR_THRESHOLD, FR_RATE
    case JP_MISC

    var orientation: Bool {
        switch self {
        case .SHORT_GAINS, .SHORT_LOSSES, .SHORT_NET, .LONG_GAINS, .LONG_LOSSES, .LONG_NET, .UNCLASSIFIED,
             .GAINS, .LOSSES, .NET, .NEW_NET, .OLD_SHORT_NET, .OLD_LONG_NET, .DENIED_LOSS, .PROCEEDS, .INCOME, .AT_INTEREST, .FR_OUTFLOW,
             .WITHDRAWAL_FEES, .TRANSFER_FEES:
            return false
        case .DE_LONG, .DE_PRIVATE_LIMIT, .DE_INCOME_LIMIT, .AT_RATE, .AT_OLD_LONG, .AT_OLD_SPECULATION_LIMIT, .CH_PRIVATE, .CH_INCOME, .CH_WEALTH,
             .UK_EXEMPT, .UK_AFTER_EXEMPT, .US_TERMS, .CA_TAXABLE, .AU_DISCOUNTED, .FR_THRESHOLD, .FR_RATE, .JP_MISC:
            return true
        }
    }
}

/// Ein Posten — wie `TaxSummaryLine` (Android).
struct TaxSummaryLine: Sendable, Equatable {
    let line: TaxLine
    var usdt: Decimal? = nil
    var target: Decimal? = nil
    var status: TaxLimitStatus? = nil
    /// Ein Gewinn/Wert fehlt — Betrag nur aus dem Bekannten.
    var incomplete = false
    /// Ein Devisenkurs fehlt.
    var noFx = false
    /// Kein Betrag, weil die App ihn nicht rechnet (CH Vermögenssteuer: ESTV-Kursliste) — Vermerk `notCalculated`.
    var notCalculated = false
    /// Zusatz-Vermerk (`TaxSummaryTexts.notes`); nil = keiner.
    var note: TaxSummaryNote? = nil
}

/// Jahresübersicht einer Vorlage — ORIENTIERUNG, keine Steuerberatung. Wie `TaxYearSummary` (Android).
struct TaxYearSummary: Sendable, Equatable {
    let template: TaxTemplate
    let method: TaxMethod
    let period: TaxPeriod
    /// Steuerwährung (`TaxTemplate.currency`, Neutral: Anzeigewährung).
    let currency: String
    /// Devisenkurse geladen (oder USD).
    let fxAvailable: Bool
    let lines: [TaxSummaryLine]
    /// US-Übergang, mit dem gerechnet wurde (`TaxTemplate.walletFrom`); nil = keiner.
    var walletFrom: LocalDay? = nil
}

/// Texte der Jahresübersicht (`TaxExport.summaryCsv`) — wie `TaxSummaryTexts` (Android).
struct TaxSummaryTexts: Sendable {
    var title: String
    var disclaimer: String
    var item: String
    var usdt: String
    var target: String
    var note: String
    var orientation: String
    var labels: [TaxLine: String]
    var below: String
    var reached: String
    var unknown: String
    var incomplete: String
    var noFx: String
    /// Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = `orientation`.
    var orientationHeading: String = ""
    /// Posten im Orientierungs-Block «Orientierung: %s»; leer = «`orientation`: Posten».
    var orientationItem: String = ""
    /// Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» (`TaxSummaryLine.notCalculated`); leer = keiner.
    var notCalculated: String = ""
    /// Status «Verlust» (`TaxLimitStatus.loss`); leer = `below`.
    var loss: String = ""
    /// Texte der Zusatz-Vermerke (`TaxSummaryLine.note`); fehlt einer = kein Vermerk.
    var notes: [TaxSummaryNote: String] = [:]
}

/// Posten im Orientierungs-Block — wie `taxOrientationLabel` (Android): `item` mit «%s» für `label`; ohne `item`
/// «`fallback`: `label`» (leer = nur `label`). Jeder Schluss einer Vorlage steht so sichtbar als Orientierung da.
func taxOrientationLabel(_ item: String, fallback: String, label: String) -> String {
    if item.contains("%s") { return item.replacingOccurrences(of: "%s", with: label) }
    if !fallback.isEmpty { return "\(fallback): \(label)" }
    return label
}

extension TaxExport {

    /// Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
    /// Einstand (FR: Erwerbspreis-Summe nicht gesichert, `TaxGainRow.costIncomplete`), Anzahl ohne Devisenkurs.
    private struct Acc {
        var usdt: Decimal = 0
        var target: Decimal = 0
        var unknown = 0
        var partial = 0
        var fxMissing = 0

        init(_ rows: [TaxGainRow], _ pick: (Decimal) -> Bool, fx: Bool) {
            for r in rows {
                guard let g = r.gainUsdt else { unknown += 1; continue }
                // Gewinn oder Verlust entscheidet die Steuerwährung (ein USDT-Verlust kann in CAD/EUR ein Gewinn sein);
                // ohne Kurs USDT
                let gT = r.gainTarget
                let key: Decimal = fx ? (gT ?? g) : g
                guard pick(key) else { continue }
                usdt += g
                // Gewinn aus unvollständigem Einstand: gezählt, aber der Posten ist «unvollständig»
                if r.costIncomplete { partial += 1 }
                if let gT { target += gT } else if fx { fxMissing += 1 }
            }
        }

        /// Ein Gewinn fehlt oder beruht auf unvollständigem Einstand.
        var incomplete: Bool { unknown > 0 || partial > 0 }

        func line(_ l: TaxLine, fx: Bool, countUnknown: Bool = true, note: TaxSummaryNote? = nil) -> TaxSummaryLine {
            TaxSummaryLine(line: l, usdt: usdt, target: fx ? target : nil,
                           incomplete: (countUnknown && unknown > 0) || partial > 0, noFx: fxMissing > 0, note: note)
        }
    }

    /// Jahresübersicht der Vorlage `template` für das Steuerjahr ab `startYear` — ORIENTIERUNG, KEINE STEUERBERATUNG;
    /// Posten und Regeln wie `yearSummary` (Android).
    static func yearSummary(_ trades: [PortfolioTx], template: TaxTemplate, method: TaxMethod, startYear: Int,
                            timeZone: TimeZone, currency: String, rates: [Int: Decimal]?,
                            priceAt: ((String, Int64) -> Decimal?)? = nil,
                            walletFrom: LocalDay? = nil, usdtUsd: [Int: Decimal]? = nil) -> TaxYearSummary {
        let period = template.period(startYear)
        let fx = rates != nil || currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD"
        let allRows = gainRows(trades, method: method, period: period, timeZone: timeZone, currency: currency,
                               rates: rates ?? [:], priceAt: priceAt, rules: template.eventRules,
                               walletFrom: walletFrom, usdtUsd: usdtUsd)
        // DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten, nicht in den Summen (DE: § 23 und Freigrenze;
        // AT: Aufwendungen beim Sondersteuersatz nicht abzugsfähig, § 20 Abs. 2 EStG) — wie Android
        // UK, US, CA, AU, JP: kein Verlust, nur Information (`TaxTemplate.quoteFeeIsInfo`) — wie Android
        let feeApart = template == .DE || template == .AT || template.quoteFeeIsInfo
        let withdrawalFees = feeApart ? allRows.filter { $0.feeExpense == .WITHDRAWAL } : []
        let sales = feeApart ? allRows.filter { $0.feeExpense != .WITHDRAWAL } : allRows
        // Umbuchungsgebühren nur zur Information (`.TRANSFER`, Build 57): eigener Posten — wie Android
        let transferFees = sales.filter { $0.feeExpense == .TRANSFER }
        let rows = sales.filter { $0.feeExpense != .TRANSFER }
        // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
        let allIncome = incomeRows(trades, period: period, timeZone: timeZone, currency: currency, rates: rates ?? [:],
                                   priceAt: priceAt, usdtUsd: usdtUsd)
        let interest = template == .AT ? allIncome.filter(\.interest) : []
        let income = template == .AT ? allIncome.filter { !$0.interest } : allIncome
        let pos: (Decimal) -> Bool = { $0 > 0 }
        let neg: (Decimal) -> Bool = { $0 <= 0 }
        let all: (Decimal) -> Bool = { _ in true }
        var lines: [TaxSummaryLine] = []
        let net: Acc
        let short = rows.filter { $0.longTerm == false }
        let long = rows.filter { $0.longTerm == true }
        let unclassifiedRows = rows.filter { $0.longTerm == nil }
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
            let newRows = rows.filter { $0.oldHolding != true }
            let old = rows.filter { $0.oldHolding == true }
            net = Acc(newRows, all, fx: fx)
            lines.append(Acc(newRows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false))
            lines.append(Acc(newRows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false))
            // Verkäufe/Tausch von Neuvermögen 1.3.2021–28.2.2022: alte Rechtslage mit Option auf 27,5 % — Vermerk am Posten
            let transition = newRows.contains {
                $0.soldDate >= TaxTemplate.atNewFrom && $0.soldDate < TaxTemplate.atSwapNeutralFrom
            }
            lines.append(net.line(.NEW_NET, fx: fx, note: transition ? .AT_TRANSITION_2021 : nil))
            if !old.isEmpty {
                lines.append(Acc(old.filter { $0.longTerm != true }, all, fx: fx).line(.OLD_SHORT_NET, fx: fx))
                lines.append(Acc(old.filter { $0.longTerm == true }, all, fx: fx).line(.OLD_LONG_NET, fx: fx))
            }
        } else {
            net = Acc(rows, all, fx: fx)
            // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
            // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
            let info: TaxSummaryNote? = template == .CH ? .CH_FIFO_INFO : nil
            lines.append(Acc(rows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false, note: info))
            lines.append(Acc(rows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false, note: info))
            lines.append(net.line(.NET, fx: fx, note: info))
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
        lines.append(TaxSummaryLine(line: .PROCEEDS, usdt: procUsdt, target: fx ? procTarget : nil,
                                    incomplete: procMissing, noFx: procFx))
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
        // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»
        if template == .FR {
            // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
            let outs = TaxEngines.outflows(trades.filter { $0.amountExact > 0 }, method: method, priceAt: priceAt)
                .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            if !outs.isEmpty {
                var outUsdt: Decimal = 0
                var outTarget: Decimal = 0
                var outFx = false
                for o in outs {
                    guard let r = o.ptaReductionUsdt else { continue }
                    outUsdt += r
                    if let rate = rateOn(rates, date: localDay(o.time, timeZone: timeZone), currency: currency,
                                         usdtUsd: usdtUsd) {
                        outTarget += PortfolioDecimal.multiply(r, rate)
                    } else if fx {
                        outFx = true
                    }
                }
                lines.append(TaxSummaryLine(line: .FR_OUTFLOW, usdt: outUsdt, target: fx ? outTarget : nil,
                                            incomplete: true, noFx: outFx))
            }
        }
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
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
            lines.append(TaxSummaryLine(line: .DE_INCOME_LIMIT, usdt: nil, target: incomeLimitEur, status: incomeStatus))
        case .AT:
            // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
            // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG)
            lines.append(TaxSummaryLine(line: .AT_RATE, usdt: net.usdt + intUsdt, target: fx ? net.target + intTarget : nil,
                                        incomplete: netIncomplete || intMissing, noFx: netFx || intFx))
            if rows.contains(where: { $0.oldHolding == true }) {
                let oldLong = rows.filter { $0.oldHolding == true && $0.longTerm == true }
                lines.append(Acc(oldLong, all, fx: fx).line(.AT_OLD_LONG, fx: fx))
                // Altbestand ≤ 1 Jahr: Freigrenze 440 € (§ 31 Abs. 3 EStG a. F.) — nicht prüfbar, sobald ein Gewinn, ein
                // Devisenkurs oder die Haltedauer fehlt (wie Android)
                let old = rows.filter { $0.oldHolding == true }
                let spec = Acc(old.filter { $0.longTerm == false }, all, fx: fx)
                let specStatus: TaxLimitStatus
                if !fx || spec.unknown > 0 || spec.fxMissing > 0 || old.contains(where: { $0.longTerm == nil }) {
                    specStatus = .unknown
                } else if spec.target < 0 {
                    specStatus = .loss
                } else if spec.target <= atSpeculationLimitEur {
                    specStatus = .below
                } else {
                    specStatus = .reached
                }
                lines.append(TaxSummaryLine(line: .AT_OLD_SPECULATION_LIMIT, usdt: nil, target: atSpeculationLimitEur,
                                            status: specStatus))
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
            let f = TaxTemplate.caInclusion
            let u = PortfolioDecimal.multiply(net.usdt + deniedUsdt, f)
            let tg: Decimal? = fx ? PortfolioDecimal.multiply(net.target + deniedTarget, f) : nil
            lines.append(TaxSummaryLine(line: .CA_TAXABLE, usdt: u, target: tg, incomplete: netIncomplete,
                                        noFx: netFx || deniedFx))
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
            let u = discounted { $0.gainUsdt }
            let tg: Decimal? = fx ? discounted { $0.gainTarget } : nil
            lines.append(TaxSummaryLine(line: .AU_DISCOUNTED, usdt: u, target: tg, incomplete: netIncomplete, noFx: netFx))
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
        return TaxYearSummary(template: template, method: method, period: period, currency: currency, fxAvailable: fx,
                              lines: lines, walletFrom: walletFrom)
    }
}
