import Foundation

// Zeilen der Steuer-Exporte: realisierte Gewinne und Einkünfte je Steuerjahr — wie `TaxExportRows.kt` (Android).

/// Eine Zeile «Realisierte Gewinne»: eine Veräusserung (`PortfolioDisposal`; FIFO je Posten, Ø je Verkauf).
/// Beträge exakt, gerundet wird erst im Text; nil = unbekannt (Feld leer).
struct TaxGainRow: Equatable, Sendable {
    let coin: String
    /// Datum des Zugangs (Ortszeit); Ø: nil.
    let acquiredDate: LocalDay?
    let soldDate: LocalDay
    /// Kalendertage zwischen Kauf- und Verkaufsdatum; Ø: nil.
    let holdingDays: Int?
    /// Länger als ein Jahr gehalten (`TaxExport.isLongTerm`); Ø: nil.
    let longTerm: Bool?
    let amount: Decimal
    let costUsdt: Decimal?
    let proceedsUsdt: Decimal?
    let gainUsdt: Decimal?
    /// Einstand in der Anzeigewährung: Summe der Herkunftsteile (`PortfolioDisposal.costParts`) je zum Kurs ihres Datums —
    /// FIFO: Erwerb und zugeschlagene Umbuchungsgebühren je zu ihrem Datum (5000 USDT 2018 zu 1.00 + 100 USDT 2024 zu 0.85
    /// = 5085 CHF), Ø: laufender Ø; ohne Teile Einstand × Kurs am Kaufdatum. nil, wenn ein nötiger Kurs fehlt.
    let costTarget: Decimal?
    /// Erlös × Devisenkurs am Verkaufsdatum.
    let proceedsTarget: Decimal?
    /// `proceedsTarget` − `costTarget`.
    let gainTarget: Decimal?
    /// Ein benötigter Devisenkurs fehlt.
    let fxMissing: Bool
    let feeLeg: Bool
    let feeValueMissing: Bool
    let method: CostMethod
    /// Mehr verkauft als erfasst: Teil ohne erfassten Zugang (`PortfolioDisposal.uncovered`).
    var uncovered = false
    /// Veräusserung durch einen Tausch (`PortfolioDisposal.swap`).
    var swap = false
    /// `.FIFO_WALLET`: Wallet (`PortfolioDisposal.wallet`); sonst nil.
    var wallet: String? = nil
    // ---- Steuer-Vorlagen (`TaxEngines`); Tatsachen, keine Schlüsse — wie Android ----
    /// UK: Zuordnung (`PortfolioDisposal.match`).
    var match: TaxMatch? = nil
    /// CA: nicht abziehbarer Verlust (USDT, ≥ 0) und × Devisenkurs am Verkaufsdatum.
    var adjustmentUsdt: Decimal? = nil
    var adjustmentTarget: Decimal? = nil
    /// AT: Altbestand (true) / Neubestand (false).
    var oldHolding: Bool? = nil
    /// FR: Erwerbspreis-Summe unvollständig.
    var costIncomplete = false
    /// FR: Gesamtwert des Portfolios vor der Abtretung, Abtretungspreis vor Gebühren (USDT / Anzeigewährung).
    var portfolioValueUsdt: Decimal? = nil
    var portfolioValueTarget: Decimal? = nil
    var grossProceedsUsdt: Decimal? = nil
    var grossProceedsTarget: Decimal? = nil
    /// FR: Gesamtwert samt Guthaben beobachteter Adressen (`PortfolioDisposal.addressValueIncluded`).
    var addressValueIncluded = false
    /// Einstand aus Herkunftsteilen (Pool/Durchschnitt) — mit `.AVERAGE` zusammen `pooled`.
    var pooledParts = false
    /// Gebühr als Aufwand (`PortfolioDisposal.feeExpense`): Einstand = Aufwand, Erlös 0; sonst nil.
    var feeExpense: PortfolioFeeExpense? = nil
    /// Gebühr als Veräusserung ohne Erlös (`PortfolioLedger.isFeeDisposal`, CSV-Zeile «Fee»): Vermerk «Gebühr».
    var feeRow = false
    /// Schenkung als Veräusserung (`PortfolioDisposal.gift`, UK/CA/AU): Vermerk «Schenkung».
    var gift = false
    /// Zahlung mit Krypto (`PortfolioDisposal.payment`): Vermerk «Zahlung mit Krypto».
    var payment = false
    /// Wert geschätzt (`PortfolioDisposal.valueEstimated`, Tagesschluss): Vermerk, zählt nicht als «unvollständig».
    var valueEstimated = false
    /// UK/CA: Schenkung an Ehe-/Lebenspartner (`PortfolioDisposal.spouseGift`): Erlös = Einstand, Vermerk.
    var spouseGift = false
    /// Auszahlungsgebühr in USDT/Fiat mit `quoteFeeIsInfo` (UK, US, CA, AU, JP) bzw. bei `.FR_PTA`: kein Verlust, nur
    /// Information — nicht in den Summen, Vermerk `TaxCsvTexts.withdrawalFeeInfo`. Wie Android.
    var feeInfoOnly = false
    /// Mit USDT/USD-Tagesschluss umgerechnet (`TaxExport.rateOn` `usdtUsd`), aber an einem nötigen Tag ohne Schluss: dort
    /// USDT = 1 USD als Näherung — Vermerk `TaxCsvTexts.usdtAssumed`, nie still. Wie Android.
    var usdtApproximate = false
    /// UK: Erlös vor Gebühren (`PortfolioDisposal.proceedsBeforeFeesUsdt`) / × Devisenkurs am Verkaufsdatum; sonst nil.
    var proceedsBeforeFeesUsdt: Decimal? = nil
    var proceedsBeforeFeesTarget: Decimal? = nil

    /// Einstand aus einem Durchschnitt (Herkunftsteile je Kaufdatum umgerechnet) — wie `pooled` (Android); ein
    /// Gebühr-Aufwand ist kein Durchschnitt.
    var pooled: Bool { (method == .AVERAGE && feeExpense == nil) || pooledParts }

    var costMissing: Bool { costUsdt == nil }
    var proceedsMissing: Bool { proceedsUsdt == nil }
    /// Einstand, Erlös und Gewinn in USDT bekannt (zählt in der Summe).
    var completeUsdt: Bool { costUsdt != nil && proceedsUsdt != nil && gainUsdt != nil }
    /// Einstand, Erlös und Gewinn in der Anzeigewährung bekannt (zählt in der Summe).
    var completeTarget: Bool { costTarget != nil && proceedsTarget != nil && gainTarget != nil }
}

/// Eine Zeile «Einkünfte»: Belohnung (`.REWARD`) mit Wert beim Erhalt.
struct TaxIncomeRow: Equatable, Sendable {
    let date: LocalDay
    let coin: String
    let amount: Decimal
    /// Menge × Wert je Coin beim Erhalt; nil = Wert fehlt.
    let valueUsdt: Decimal?
    /// `valueUsdt` × Devisenkurs des Tags.
    let valueTarget: Decimal?
    let fxMissing: Bool
    /// Zins (Lending, `TaxEngines.interestReward`) — AT: beim Zufluss Einkunft zum Sondersteuersatz (`TaxLine.AT_INTEREST`).
    var interest = false
    /// Wert ohne Preis in der Datei zum Tagesschluss geschätzt (`PortfolioLedger.valueRewardsAtDayClose`): Vermerk.
    var estimated = false
    /// Umrechnung mit USDT = 1 USD als Näherung (kein USDT/USD-Tagesschluss, `TaxGainRow.usdtApproximate`): Vermerk.
    var usdtApproximate = false

    var valueMissing: Bool { valueUsdt == nil }
}

extension TaxExport {

    static func gainRows(_ trades: [PortfolioTx], method: CostMethod, year: Int, timeZone: TimeZone,
                         currency: String, rates: [Int: Decimal]?) -> [TaxGainRow] {
        gainRows(trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone,
                 currency: currency, rates: rates)
    }

    /// Zeilen «Realisierte Veräusserungen» des Steuerjahrs `period` nach `method` — wie `gainRows` (Android): Einstand mit
    /// Herkunftsteilen je zum Kurs ihres Datums, sonst zum Kurs des Kaufdatums; Erlös, nicht abziehbarer Verlust,
    /// Gesamtwert und Abtretungspreis zum Kurs des Verkaufsdatums.
    static func gainRows(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                         currency: String, rates: [Int: Decimal]?,
                         priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil,
                         walletFrom: LocalDay? = nil, usdtUsd: [Int: Decimal]? = nil) -> [TaxGainRow] {
        let fx = fxAt(rates, timeZone: timeZone, currency: currency, usdtUsd: usdtUsd)
        let sold = disposals(in: trades, method: method, period: period, timeZone: timeZone, priceAt: priceAt,
                             fx: fx, rules: rules, walletFrom: walletFrom)
        return gainRows(of: sold, trades: trades, method: method, timeZone: timeZone, currency: currency,
                        rates: rates, rules: rules, usdtUsd: usdtUsd)
    }

    /// USDT → Steuerwährung zu einem Zeitpunkt (CA: Superficial Loss in der Steuerwährung, dieselben Kurse wie die Zeilen).
    static func fxAt(_ rates: [Int: Decimal]?, timeZone: TimeZone, currency: String,
                     usdtUsd: [Int: Decimal]?) -> (Int64) -> Decimal? {
        { t in rateOn(rates, date: localDay(t, timeZone: timeZone), currency: currency, usdtUsd: usdtUsd) }
    }

    /// Zeilen aus den schon gerechneten Veräusserungen `disposals` des Steuerjahrs (`gainRows` oben, `GainsRun`).
    static func gainRows(of disposals: [PortfolioDisposal], trades: [PortfolioTx], method: TaxMethod,
                         timeZone: TimeZone, currency: String, rates: [Int: Decimal]?, rules: TaxEventRules?,
                         usdtUsd: [Int: Decimal]?) -> [TaxGainRow] {
        // Verkäufe, die Gebühren sind (`PortfolioLedger.isFeeDisposal`) — einmal gesammelt
        let feeSales = Set(trades.filter { PortfolioLedger.isFeeDisposal($0) }.map(\.id))
        // Transaktionen ohne Verkauf (Umbuchung, Auszahlung, Tausch): ihre Veräusserungen ohne Tausch-Vermerk sind
        // Gebühren in Krypto (Netzwerkgebühr nach `networkFeeIsDisposal`, FR Gebühr-Abtretung) — Vermerk «Gebühr»
        let nonSales = Set(trades.filter { $0.type == .TRANSFER || $0.type.isWithdrawalLike || $0.type == .SWAP }.map(\.id))
        // Auszahlungsgebühr USDT/Fiat nur Information (`TaxEventRules.quoteFeeIsInfo`); FR ebenso — die Gebühr mindert den
        // Gewinn nicht (art. 150 VH bis), wie in der Jahresübersicht, sonst stünde sie in der Summe der Gewinne-CSV
        let feeInfo = (rules ?? .of(method)).quoteFeeIsInfo || method == .FR_PTA
        // Herkunftsteile: Kurs und USDT-Näherung je Teil, je ein Objekt für alle Zeilen (grosse Ansichten eines Pools
        // rechnet `weightedSum`/`anyTime` so ohne Auslesen)
        let partRate = PortfolioPartWeight { t in
            TaxExport.rateOn(rates, date: TaxExport.localDay(t, timeZone: timeZone), currency: currency,
                             usdtUsd: usdtUsd)
        }
        let partUsdtApproximate = PortfolioPartTest { t in
            TaxExport.usdtApproximateOn(usdtUsd, date: TaxExport.localDay(t, timeZone: timeZone))
        }
        return disposals.map { d -> TaxGainRow in
            let sold = localDay(d.sellTime, timeZone: timeZone)
            let acquired = d.acquiredTime.map { localDay($0, timeZone: timeZone) }
            let saleRate = rateOn(rates, date: sold, currency: currency, usdtUsd: usdtUsd)
            func atSale(_ v: Decimal?) -> Decimal? {
                guard let v, let saleRate else { return nil }
                return PortfolioDecimal.multiply(v, saleRate)
            }
            // Herkunftsteile je zum Kurs ihres Datums (fehlt einer: leer); FIFO-Posten zum Kurs des Kaufdatums
            let costTarget: Decimal? = {
                guard let c = d.costUsdt else { return nil }
                if !d.costParts.isEmpty { return d.costParts.weightedSum(partRate) }
                if let acquired {
                    return rateOn(rates, date: acquired, currency: currency, usdtUsd: usdtUsd)
                        .map { PortfolioDecimal.multiply(c, $0) }
                }
                // Einstand 0 (z. B. Belohnung zum Wert 0): nichts umzurechnen
                if c == 0 { return 0 }
                return saleRate.map { PortfolioDecimal.multiply(c, $0) }
            }()
            let costFxMissing = d.costUsdt != nil && costTarget == nil
            // Schenkung an Ehe-/Lebenspartner: kein Gewinn/Verlust auch in der Steuerwährung (TCGA 1992 s. 58, ITA 73(1)) —
            // der Erlös gilt als genau der umgerechnete Einstand
            let proceedsTarget: Decimal? = d.spouseGift ? costTarget : atSale(d.proceedsUsdt)
            let gainTarget: Decimal? = {
                guard let c = costTarget, let p = proceedsTarget else { return nil }
                return p - c
            }()
            var row = TaxGainRow(
                coin: d.coin,
                acquiredDate: acquired ?? d.matchedTime.map { localDay($0, timeZone: timeZone) },
                soldDate: sold,
                holdingDays: acquired.map { $0.days(until: sold) },
                longTerm: acquired.map { isLongTerm(acquired: $0, sold: sold) },
                amount: d.amount,
                costUsdt: d.costUsdt,
                proceedsUsdt: d.proceedsUsdt,
                gainUsdt: d.gainUsdt,
                costTarget: costTarget,
                proceedsTarget: proceedsTarget,
                gainTarget: gainTarget,
                fxMissing: costFxMissing || (!d.spouseGift && d.proceedsUsdt != nil && saleRate == nil),
                feeLeg: d.feeLeg,
                feeValueMissing: d.feeValueMissing,
                method: d.method,
                uncovered: d.uncovered,
                swap: d.swap,
                wallet: d.wallet
            )
            row.match = d.match
            row.adjustmentUsdt = d.adjustmentUsdt
            row.adjustmentTarget = atSale(d.adjustmentUsdt)
            row.oldHolding = d.oldHolding
            row.costIncomplete = d.costIncomplete
            row.portfolioValueUsdt = d.portfolioValueUsdt
            row.portfolioValueTarget = atSale(d.portfolioValueUsdt)
            row.grossProceedsUsdt = d.grossProceedsUsdt
            row.grossProceedsTarget = atSale(d.grossProceedsUsdt)
            row.addressValueIncluded = d.addressValueIncluded
            // Durchschnitt/Pool (Herkunftsteile ohne Posten-Datum); FIFO-Posten tragen ihre Teile ohne diesen Vermerk
            row.pooledParts = !d.costParts.isEmpty && d.acquiredTime == nil
            row.feeExpense = d.feeExpense
            // Schenkung als Veräusserung ist keine Gebühr, auch wenn die Transaktion kein Verkauf ist
            row.feeRow = !d.feeLeg && d.feeExpense == nil && !d.gift && !d.payment
                && (feeSales.contains(d.sellTxId) || (!d.swap && nonSales.contains(d.sellTxId)))
            row.gift = d.gift
            row.payment = d.payment
            row.valueEstimated = d.valueEstimated
            row.spouseGift = d.spouseGift
            // `.TRANSFER`-Zeilen gibt es nur mit dieser Regel (`TaxEngines.disposals`)
            row.feeInfoOnly = d.feeExpense == .TRANSFER || (feeInfo && d.feeExpense == .WITHDRAWAL)
            // USDT = 1 als Näherung an einem der Tage, deren Kurs die Zeile braucht (Verkauf, Kauf, Herkunftsteile)
            let usdtDays = [sold] + (acquired.map { [$0] } ?? [])
            row.usdtApproximate = usdtDays.contains { usdtApproximateOn(usdtUsd, date: $0) }
                || d.costParts.anyTime(partUsdtApproximate)
            row.proceedsBeforeFeesUsdt = d.proceedsBeforeFeesUsdt
            row.proceedsBeforeFeesTarget = atSale(d.proceedsBeforeFeesUsdt)
            return row
        }
    }

    static func incomeRows(_ trades: [PortfolioTx], year: Int, timeZone: TimeZone, currency: String,
                           rates: [Int: Decimal]?) -> [TaxIncomeRow] {
        incomeRows(trades, period: calendar(year), timeZone: timeZone, currency: currency, rates: rates)
    }

    /// Belohnungen des Steuerjahrs `period` mit Wert beim Erhalt (Menge × Wert je Coin), zum Kurs des Tags. Ohne Wert in
    /// der Datei mit `priceAt` zum Tagesschluss geschätzt (`PortfolioLedger.valueRewardsAtDayClose`, `estimated`); ohne
    /// Kurs bleibt der Wert leer. Wie Android.
    static func incomeRows(_ trades: [PortfolioTx], period: TaxPeriod, timeZone: TimeZone, currency: String,
                           rates: [Int: Decimal]?, priceAt: ((String, Int64) -> Decimal?)? = nil,
                           usdtUsd: [Int: Decimal]? = nil) -> [TaxIncomeRow] {
        PortfolioLedger.valueRewardsAtDayClose(rewards(in: trades, period: period, timeZone: timeZone), price: priceAt).map { t -> TaxIncomeRow in
            let date = localDay(t.time, timeZone: timeZone)
            let value = t.validPriceExact.map { t.amountExact * $0 }
            let rate = rateOn(rates, date: date, currency: currency, usdtUsd: usdtUsd)
            let target: Decimal? = {
                guard let value, let rate else { return nil }
                return PortfolioDecimal.multiply(value, rate)
            }()
            return TaxIncomeRow(date: date, coin: PortfolioCalculator.normalizeCoin(t.coin), amount: t.amountExact,
                                valueUsdt: value, valueTarget: target, fxMissing: value != nil && rate == nil,
                                interest: t.type == .REWARD && t.interest, estimated: t.valueEstimated,
                                usdtApproximate: value != nil && usdtApproximateOn(usdtUsd, date: date))
        }
    }
}
