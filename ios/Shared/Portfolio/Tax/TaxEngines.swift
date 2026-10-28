import Foundation

// Rechnung der Steuer-Vorlagen — wie `TaxEngines.kt` (Android), dort beschrieben: UK Section-104-Pool mit Same-Day- und
// 30-Tage-Regel (Netzwerkgebühr im Coin = Veräusserung, HMRC CRYPTO22280), CA ACB mit Superficial Loss, FR prix total
// d'acquisition (Auszahlung kürzt die PTA, BOFiP § 100; Gebühr in Krypto = Abtretung, § 50 bzw. 30-10 § 70 Rem. 1),
// JP 総平均法, AT Alt-/Neubestand je Coin und Wallet (Belohnung im Neubestand mit Anschaffungskosten 0, Zins zum Wert beim
// Zufluss; Gebühren im Neubestand nicht abzugsfähig — § 27a Abs. 4 Z 2, § 20 Abs. 2 EStG, BMF-Info Kryptowährungen).
// Netzwerkgebühr ohne Rest mit Kaufpreis (AT; CA/JP nur ohne Vorlage-Regeln): `PortfolioLostFeeCost`; Anteil einer
// Umbuchungsgebühr auf Bestand ohne Kaufpreis bzw. Menge über Bestand − Netzwerkgebühr im Pool (UK/CA ohne
// Vorlage-Regeln, `TaxPool.addFee`): `PortfolioUnassignedFee`. Gebühr-Zeile (CSV «Fee»)
// zum Tageskurs `priceAt` (alle Vorlagen, `PortfolioLedger.valueAtDayClose`; ebenso Verschenkt/bezahlt ohne Marktwert und
// Tausch ohne Wert). Damit bezahlt (`.PAYMENT`) = Veräusserung zum Marktwert wie ein Verkauf (alle Vorlagen);
// Schenkung und Netzwerkgebühr nach den Regeln der Vorlage (`TaxEventRules`, `applyRules`): Verschenkt nur für
// UK/CA/AU eine Veräusserung, an Ehe-/Lebenspartner in UK/CA ohne Gewinn/Verlust (TCGA 1992 s. 58, ITA 73(1));
// Netzwerkgebühr in Krypto einer Umbuchung/Auszahlung bei UK/US/CA/AU/JP eine eigene Veräusserung zum Marktwert.
// FR: Gesamtwert samt beobachteter Adressen. UK: Einzahlung mit früherem Kaufdatum «gleicher Tag»/«30 Tage» am
// Kaufdatum, Rest erst ab Buchungstag im Pool. CA: ein Ersatz-Zugang deckt nur einen nicht abziehbaren Verlust (verbraucht).
// FR: Verkauf über den Bestand — nur der gedeckte Anteil hat Einstand, der Rest eine eigene Zeile ohne Einstand.
// FR: Auszahlung über den Bestand — nur die gedeckte Menge kürzt die PTA, danach ist sie unvollständig. UK: Auszahlung
// zwischen zwei Abgängen desselben Tags erst nach deren Pool-Teil. Geschätzter Wert im Einstand (`PortfolioTx.costEstimated`)
// tragen Posten und Töpfe jeder Rechnung weiter — Vermerk «Wert geschätzt» an jeder Veräusserung daraus.

enum TaxEngines {

    /// Veräusserungen nach `method` — wie `TaxEngines.disposals` (Android); `timeZone` = Kalendertage (UK, CA, JP, AT),
    /// `priceAt` = USDT-Kurs eines Coins zu einem Zeitpunkt (`.FR_PTA`: Gesamtwert und Gebühren in Krypto; mit
    /// `TaxEventRules.networkFeeIsDisposal`: Netzwerkgebühren; alle: Gebühr-Zeilen zum Tageskurs); `fx` = USDT →
    /// Steuerwährung zu einem Zeitpunkt (`.CA_ACB`: Superficial Loss in CAD entschieden; nil bzw. ein Kurs fehlt = in USDT).
    /// `rules`: Schenkung und Netzwerkgebühr nach der Vorlage (`TaxTemplate.eventRules`, `applyRules`); nil = nach der
    /// Rechnung (`TaxEventRules.of`).
    static func disposals(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                          priceAt: ((String, Int64) -> Decimal?)? = nil,
                          fx: ((Int64) -> Decimal?)? = nil,
                          rules: TaxEventRules? = nil,
                          walletFrom: LocalDay? = nil) -> [PortfolioDisposal] {
        let applied = rules ?? .of(method)
        let input = applyRules(dayCloseValued(trades, priceAt), rules: applied, priceAt: priceAt)
        let rows: [PortfolioDisposal]
        if let cm = method.costMethod {
            let valued = PortfolioLedger.valueAtDayClose(input, price: priceAt)
            // US-Übergang (`TaxTemplate.walletFrom`): universell bis vor diesen Tag
            if let walletFrom, cm == .FIFO_WALLET {
                rows = usTransition(valued, walletFrom: walletFrom, timeZone: timeZone)
            } else {
                rows = PortfolioCalculator.disposals(valued, method: cm)
            }
        } else {
            rows = run(input, method: method, timeZone: timeZone, priceAt: priceAt, fx: fx).disposals
        }
        guard applied.quoteFeeIsInfo else { return rows }
        // Gebühr USDT einer Umbuchung: nicht im Einstand (`applyRules`), aber als Zeile zur Information
        let fees = transferFeeRows(trades)
        if fees.isEmpty { return rows }
        // Stabil sortiert wie Android (`sortedWith`)
        return (rows + fees).enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// `quoteFeeIsInfo`: je Umbuchung mit Gebühr USDT eine Zeile `.TRANSFER` (Menge 0, «Einstand» = Gebühr, Erlös 0) — der
    /// Export führt sie nur zur Information, nicht in den Summen. Wie Android.
    private static func transferFeeRows(_ trades: [PortfolioTx]) -> [PortfolioDisposal] {
        AddressBalance.split(trades).real
            .filter { $0.type == .TRANSFER && $0.amountExact > 0 && $0.quoteFeeExact > 0 }
            .map { t in
                PortfolioFeeExpenses.disposal(PortfolioCalculator.normalizeCoin(t.coin),
                                              PortfolioLedgerEvent(trade: t, feeLeg: false), .TRANSFER,
                                              PortfolioFeeExpenses.Expense(usdt: t.quoteFeeExact, feeValueMissing: false),
                                              method: .FIFO)
            }
    }

    /// US-Übergang 1.1.2025 (Rev. Proc. 2024-28, Safe Harbor) — wie Android `usTransition`: vor `walletFrom` (Ortszeit)
    /// FIFO über alle Wallets, dessen Restposten den Wallets zugeordnet (`allocate`), ab `walletFrom` FIFO je Wallet.
    static func usTransition(_ trades: [PortfolioTx], walletFrom: LocalDay, timeZone: TimeZone) -> [PortfolioDisposal] {
        let real = AddressBalance.split(trades).real
        let all = PortfolioLedger.eventsByCoin(real).flatMap { entry -> [PortfolioDisposal] in
            let before = entry.events.filter { day($0.trade.time, timeZone) < walletFrom }
            let after = entry.events.filter { !(day($0.trade.time, timeZone) < walletFrom) }
            let universal = PortfolioFifo.run(entry.coin, events: before)
            let held = PortfolioFifo.run(entry.coin, events: before, perWallet: true).lots
            let seed = allocate(entry.coin, universal: universal.lots, held: held)
            return universal.disposals + PortfolioFifo.run(entry.coin, events: after, perWallet: true, seed: seed).disposals
        }
        // Stabil sortiert wie Android (`sortedWith`)
        return all.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Zuordnung der universellen Restposten zu den Wallets — Regel wie Android `allocate`: gehaltene Einheiten je Wallet
    /// (`held`) nach Erwerb, Id, Wallet sortiert; Restposten, älteste zuerst, füllen sie der Reihe nach (geteilt, Einstand
    /// anteilig); Überschuss in die Wallet der letzten Einheit (ohne: «ohne Angabe»).
    static func allocate(_ coin: String, universal: [PortfolioLot], held: [PortfolioLot]) -> [PortfolioLot] {
        let slots = held.filter { $0.amount > 0 }.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.acquiredTime != y.acquiredTime { return x.acquiredTime < y.acquiredTime }
            if x.txId != y.txId { return x.txId < y.txId }
            let kx = PortfolioWallets.key(x.wallet), ky = PortfolioWallets.key(y.wallet)
            if kx != ky { return kx < ky }
            return a.offset < b.offset
        }.map(\.element)
        let fallback = slots.last?.wallet ?? PortfolioWallets.unspecified
        let lots = universal.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.acquiredTime != y.acquiredTime { return x.acquiredTime < y.acquiredTime }
            if x.txId != y.txId { return x.txId < y.txId }
            return a.offset < b.offset
        }.map(\.element)
        var out: [PortfolioLot] = []
        var si = 0
        var slotLeft: Decimal = slots.first?.amount ?? 0
        for lot in lots {
            var left = lot.amount
            var costLeft = lot.costUsdt
            while left > 0 {
                let slot: PortfolioLot? = si < slots.count ? slots[si] : nil
                let qty = slot == nil ? left : min(left, slotLeft)
                let c: Decimal? = costLeft.map { qty == left ? $0 : PortfolioDecimal.divide($0, qty, by: left) }
                out.append(PortfolioLot(coin: coin, txId: lot.txId, acquiredTime: lot.acquiredTime, kind: lot.kind,
                                        amount: qty, costUsdt: c, wallet: slot?.wallet ?? fallback, estimated: lot.estimated))
                left -= qty
                if let c, let cl = costLeft { costLeft = cl - c }
                if slot != nil {
                    slotLeft -= qty
                    if slotLeft <= 0 {
                        si += 1
                        slotLeft = si < slots.count ? slots[si].amount : 0
                    }
                }
            }
        }
        return out
    }

    /// Art einer Belohnung ist ein Zins (Lending, Zinsen) — aus der Notiz bzw. der Art im CSV; AT rechnet ihn mit
    /// Anschaffungskosten = Wert beim Zufluss statt 0. Wie Android `interestReward` (von Hand: keine Unterart → 0). Nur ganze
    /// Wörter (auch «LendingInterest», «interest_payment»): «Verzinsung», «Zinseszins» oder «keine Verzinsung» sind kein Zins.
    static func interestReward(_ text: String?) -> Bool {
        guard let text, !text.isEmpty else { return false }
        return words(text.precomposedStringWithCanonicalMapping).contains { interestWords.contains($0) }
    }

    /// Wörter in Kleinbuchstaben: Trenner ist alles ausser Buchstaben/Ziffern und der Wechsel klein → gross (wie Android).
    private static func words(_ text: String) -> [String] {
        var out: [String] = []
        var word = ""
        var prevLower = false
        for c in text {
            if !(c.isLetter || c.isNumber) {
                if !word.isEmpty { out.append(word.lowercased()); word = "" }
                prevLower = false
                continue
            }
            if prevLower && c.isUppercase, !word.isEmpty { out.append(word.lowercased()); word = "" }
            word.append(c)
            prevLower = c.isLowercase
        }
        if !word.isEmpty { out.append(word.lowercased()) }
        return out
    }

    private static let interestWords: Set<String> = [
        "interest", "interests", "lending", "zins", "zinsen", "zinsertrag", "zinserträge", "zinsgutschrift",
        "interet", "interets", "intérêt", "intérêts",
    ]

    /// Vorgänge ohne Verkauf nach den Regeln der Vorlage umschreiben (vor jeder Rechnung) — wie Android `applyRules`:
    ///  - Verschenkt an Ehe-/Lebenspartner mit `spouseGiftNeutral` (UK, CA): bleibt eine Schenkung mit Merker
    ///    (`PortfolioTx.isGiftToSpouse`) — die eigenen Rechnungen machen daraus eine Veräusserung ohne Gewinn/Verlust;
    ///  - sonst Verschenkt mit `giftIsDisposal` (UK, CA, AU): wie `.PAYMENT` mit `fromGift` (Veräusserung zum Marktwert);
    ///    ohne: wie eine Auszahlung. Der Partner-Merker fällt dabei weg — Vorlagen ohne die Regel ignorieren ihn;
    ///  - mit `networkFeeIsDisposal`: Netzwerkgebühr in Krypto einer Umbuchung, Auszahlung oder Schenkung (Coin und dritter
    ///    Coin) wird eine eigene Veräusserung der Gebühr-Coins zum Marktwert (`networkFeeSales`);
    ///  - mit `quoteFeeIsInfo`: die Gebühr USDT einer Umbuchung erhöht den Einstand nicht (sie fällt weg;
    ///    `disposals` führt sie als Zeile zur Information).
    static func applyRules(_ trades: [PortfolioTx], rules: TaxEventRules,
                           priceAt: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        guard trades.contains(where: { $0.type == .GIFT }) || rules.networkFeeIsDisposal || rules.quoteFeeIsInfo else {
            return trades
        }
        var out: [PortfolioTx] = []
        out.reserveCapacity(trades.count)
        for t in trades {
            var g = t
            if rules.quoteFeeIsInfo && t.type == .TRANSFER && t.quoteFeeExact > 0 {
                g.feeExact = nil
                g.feeUnit = nil
            }
            if t.type == .GIFT && !(rules.spouseGiftNeutral && t.isGiftToSpouse) {
                if rules.giftIsDisposal {
                    g.type = .PAYMENT
                    g.fromGift = true
                }
                g.giftToSpouse = false
            }
            if rules.networkFeeIsDisposal { networkFeeSales(g, priceAt, &out) } else { out.append(g) }
        }
        return out
    }

    /// Netzwerkgebühr in Krypto einer Umbuchung (eigene Wallets), Auszahlung oder Schenkung als eigene Veräusserung — wie
    /// Android `networkFeeSales`: je Gebühr-Coin ein Verkauf der Gebühr-Menge zum Marktwert (erfasster Preis bzw.
    /// Gebühr-Preis, Stablecoin 1, sonst `priceAt` = Tagesschluss des UTC-Tags der Transaktion, `TaxDayClose`, Vermerk
    /// «Wert geschätzt»; ohne Kurs Erlös unbekannt) mit Id und Zeit der Transaktion und ihrer Quell-Wallet (Umbuchung: «von», sonst die Börse) — VOR
    /// ihr in die Liste, damit die Gebühr-Coins (stabil sortiert) die Quelle zuerst verlassen. Die Transaktion verliert diese Gebühr: keine Mengen-
    /// oder Einstandsänderung der Umbuchung, kein Einstand-Zuschlag und kein Aufwand der Gebühr im dritten Coin (sonst
    /// zählte ihr Wert doppelt). Die Gebühr USDT bleibt.
    private static func networkFeeSales(_ t: PortfolioTx, _ priceAt: ((String, Int64) -> Decimal?)?,
                                        _ out: inout [PortfolioTx]) {
        guard t.type == .TRANSFER || t.type.isWithdrawalLike, t.amountExact > 0 else {
            out.append(t)
            return
        }
        let wallet = t.type == .TRANSFER ? t.transferFrom : t.exchange
        // Kurs zum Tagesschluss (kein erfasster Preis, kein Stablecoin) bzw. schon geschätzter Gebühr-Preis: «Wert geschätzt»
        func sale(_ coin: String, _ qty: Decimal, _ recorded: Decimal?, estimated: Bool) -> PortfolioTx {
            let price = feePrice(coin, recorded, t.time, priceAt)
            let hasRecorded = recorded.map { $0 >= 0 } ?? false
            let atClose = price != nil && !hasRecorded && PortfolioStables.needsQuote(coin)
            var s = PortfolioTx(id: t.id, coin: coin, type: .SELL, amountExact: qty,
                                priceExact: price, time: t.time, exchange: wallet)
            s.valueEstimated = estimated || atClose
            return s
        }
        var rest = t
        if t.coinFeeExact > 0 {
            out.append(sale(PortfolioCalculator.normalizeCoin(t.coin), t.coinFeeExact, t.validPriceExact, estimated: false))
            rest.feeExact = nil
            rest.feeUnit = nil
        }
        if let c = t.thirdFeeCoin {
            // Gebühr-Preis schon zum Tagesschluss gesetzt (`dayCloseValued`): Merker mitgeben
            out.append(sale(c, t.thirdFeeAmount, t.thirdFeePrice, estimated: t.feePriceEstimated))
            rest.feeCoin = nil
            rest.feeCoinAmountExact = nil
            rest.feeCoinPriceExact = nil
        }
        out.append(rest)
    }

    /// Einstand von Netzwerkgebühren ohne Rest mit Kaufpreis der eigenen Rechnungen, zeitlich — wie Android;
    /// Einstandsmethoden des Portfolios: leer (dort `PortfolioCalculator.ledger`). Mit `networkFeeIsDisposal` ist die
    /// Gebühr eine Veräusserung — dann bleibt kein Einstand liegen.
    static func lostFeeCosts(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                             rules: TaxEventRules? = nil) -> [PortfolioLostFeeCost] {
        if method.costMethod != nil { return [] }
        let input = applyRules(trades, rules: rules ?? .of(method), priceAt: nil)
        return run(input, method: method, timeZone: timeZone, priceAt: nil).lostFeeCosts
    }

    /// Umbuchungsgebühren auf Bestand ohne Kaufpreis (`PortfolioUnassignedFee`) der eigenen Rechnungen, zeitlich — wie
    /// Android; Einstandsmethoden des Portfolios: leer (dort `PortfolioCalculator.ledger`). Nur Pool-Rechnungen mit Gebühr
    /// im Einstand (UK/CA ohne `quoteFeeIsInfo`); JP, AT und FR rechnen Umbuchungsgebühren nicht zum Einstand.
    static func unassignedFees(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                               rules: TaxEventRules? = nil) -> [PortfolioUnassignedFee] {
        if method.costMethod != nil { return [] }
        let input = applyRules(trades, rules: rules ?? .of(method), priceAt: nil)
        return run(input, method: method, timeZone: timeZone, priceAt: nil).unassignedFees
    }

    /// `lostFeeCosts` und `unassignedFees` aus EINER Rechnung (gleiche Eingabe, gleiches Ergebnis wie beide einzeln).
    static func feeNotes(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone, rules: TaxEventRules? = nil)
        -> (lostFeeCosts: [PortfolioLostFeeCost], unassignedFees: [PortfolioUnassignedFee]) {
        if method.costMethod != nil { return ([], []) }
        let input = applyRules(trades, rules: rules ?? .of(method), priceAt: nil)
        let result = run(input, method: method, timeZone: timeZone, priceAt: nil)
        return (result.lostFeeCosts, result.unassignedFees)
    }

    /// `.FR_PTA`: Abgänge ohne Verkauf mit Kürzung der Erwerbspreis-Summe, zeitlich; sonst leer.
    static func outflows(_ trades: [PortfolioTx], method: TaxMethod,
                         priceAt: ((String, Int64) -> Decimal?)? = nil) -> [TaxOutflow] {
        guard method == .FR_PTA else { return [] }
        let input = applyRules(dayCloseValued(trades, priceAt), rules: .of(method), priceAt: priceAt)
        return run(input, method: method, timeZone: TimeZone(identifier: "UTC") ?? .current, priceAt: priceAt).outflows
    }

    /// Nur Steuer-Export, vor jeder Rechnung: Belohnungen ohne Wert und jede Gebühr im dritten Coin ohne Preis zum
    /// Tagesschluss (`PortfolioLedger.valueRewardsAtDayClose`, `valueThirdFeesAtDayClose`) — wie Android.
    private static func dayCloseValued(_ trades: [PortfolioTx], _ priceAt: ((String, Int64) -> Decimal?)?) -> [PortfolioTx] {
        PortfolioLedger.valueThirdFeesAtDayClose(PortfolioLedger.valueRewardsAtDayClose(trades, price: priceAt),
                                                 price: priceAt)
    }

    /// Ergebnis einer Rechnung: Veräusserungen, vermerkter Gebühr-Einstand, nicht zugeordnete Gebühren, FR-Abgänge ohne Verkauf.
    private struct TaxRun {
        let disposals: [PortfolioDisposal]
        let lostFeeCosts: [PortfolioLostFeeCost]
        let unassignedFees: [PortfolioUnassignedFee]
        let outflows: [TaxOutflow]
    }

    private static func run(_ trades: [PortfolioTx], method: TaxMethod, timeZone: TimeZone,
                            priceAt: ((String, Int64) -> Decimal?)?, fx: ((Int64) -> Decimal?)? = nil) -> TaxRun {
        // Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs (ohne Kurs: unbekannt)
        let real = PortfolioLedger.valueAtDayClose(AddressBalance.split(trades).real, price: priceAt)
        var out: [PortfolioDisposal] = []
        var lost: [PortfolioLostFeeCost] = []
        var unassigned: [PortfolioUnassignedFee] = []
        var outflows: [TaxOutflow] = []
        switch method {
        case .UK_POOL:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += ukPool(entry.coin, steps(entry.events), timeZone, &lost, &unassigned)
            }
        case .CA_ACB:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += caAcb(entry.coin, steps(entry.events), timeZone, &lost, &unassigned, fx)
            }
        case .JP_TOTAL_AVERAGE:
            for entry in PortfolioLedger.eventsByCoin(real) {
                out += jpTotal(entry.coin, steps(entry.events), timeZone, &lost)
            }
        case .FR_PTA:
            // FR: Gesamtwert samt beobachteter Adressen ohne Einstand (`AddressBalance.isAddressPart`); Eröffnungsposten
            // stecken schon in `real` (Erwerbspreis-Summe und Bestand)
            out = frPta(real, priceAt, &outflows, addressTrades: trades.filter { AddressBalance.isAddressPart($0.id) })
        case .AT_MOVING_AVERAGE:
            out = atSplit(real, timeZone, &lost)
        case .FIFO, .FIFO_WALLET, .AVERAGE:
            out = []
        }
        // Gebühr als Aufwand wie bei den Einstandsmethoden (`PortfolioFeeExpenses`); FR: Abtretungspreis 0. FR und AT
        // (Neubestand) behandeln einen Tausch steuerneutral — dort kein Aufwand für den verbrauchten Zugang eines Tauschs
        let swaps = method != .FR_PTA && method != .AT_MOVING_AVERAGE
        var byId: [Int64: PortfolioTx] = [:]
        for t in real { byId[t.id] = t }
        let atNewFrom = startMillis(TaxTemplate.atNewFrom, timeZone)
        // FR: die Gebühr im dritten Coin einer Auszahlung ist schon eine eigene Abtretung (frPta) — als Aufwand bleibt nur
        // die Gebühr USDT, zum gedeckten Anteil wie jeder Auszahlungsaufwand
        let fr = method == .FR_PTA
        for entry in PortfolioLedger.eventsByCoin(real) {
            for var d in PortfolioFeeExpenses.of(entry.coin, entry.events, swaps: swaps, withdrawalThirdFee: !fr) {
                // AT Neubestand: eine Gebühr, die den ganzen Zugang verbraucht, ist nur Anschaffungsnebenkosten — nicht
                // anzusetzen (§ 27a Abs. 4 Z 2 EStG), also kein abziehbarer Verlust
                if method == .AT_MOVING_AVERAGE, d.feeExpense == .CONSUMED,
                   (byId[d.sellTxId]?.acquisitionTime ?? d.sellTime) >= atNewFrom {
                    continue
                }
                if fr {
                    // FR: der ganze Kaufpreis samt Gebühr steckt schon in der Erwerbspreis-Summe (frPta) — eine
                    // Verlust-Zeile für den verbrauchten Zugang zählte ihn ein zweites Mal
                    if d.feeExpense == .CONSUMED { continue }
                    d.grossProceedsUsdt = 0
                }
                out.append(d)
            }
        }
        // Stabil sortiert wie Android (`sortedWith`)
        let sorted: [PortfolioDisposal] = out.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        let lostSorted: [PortfolioLostFeeCost] = lost.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        let unassignedSorted: [PortfolioUnassignedFee] = unassigned.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
        return TaxRun(disposals: sorted, lostFeeCosts: lostSorted, unassignedFees: unassignedSorted, outflows: outflows)
    }

    /// Kurs eines Gebühr-Coins: erfasster Preis `recorded`, Stablecoin 1, sonst `priceAt`; nil = unbekannt. Wie Android.
    private static func feePrice(_ coin: String, _ recorded: Decimal?, _ time: Int64,
                                 _ priceAt: ((String, Int64) -> Decimal?)?) -> Decimal? {
        if let p = recorded, p >= 0 { return p }
        if !PortfolioStables.needsQuote(coin) { return 1 }
        return priceAt?(coin, time).flatMap { $0 >= 0 ? $0 : nil }
    }
}
