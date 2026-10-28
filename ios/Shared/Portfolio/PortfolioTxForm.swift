import Foundation

/// Entscheide des Erfassen-Blatts — wie `PortfolioTxForm.kt` (Android).
///
///  - Preis vorbelegen: Der vorbelegte Preis ist der HEUTIGE Kurs und gilt nur für eine Transaktion von
///    heute (Ortszeit). Wird ein anderer Tag gewählt und steht noch der vorbelegte Preis im Feld, wird er
///    geleert (sonst würde ein alter Kauf zum heutigen Kurs gespeichert); darunter der Hinweis «Kurs vom
///    Kauftag eintragen». Ein getippter Preis bleibt. Zurück auf heute mit leerem Feld: wieder vorbelegt.
///    Beim Bearbeiten nie vorbelegen.
///  - Verkauf über den Bestand: Menge + Gebühr im Coin grösser als der Bestand → Warnung (blockiert das
///    Speichern nicht; der Rechner kappt den Verkauf und markiert ihn).
///  - Gebühr ≥ erhaltene Menge (`feeConsumesReceived`): Fehler, Speichern gesperrt.
enum PortfolioTxForm {

    /// Was mit dem Preisfeld geschieht, wenn das Datum wechselt.
    enum PriceAction: Equatable { case keep, clear, prefill }

    /// Darf der heutige Kurs ins Preisfeld (neue Transaktion von heute)?
    static func mayPrefill(isEdit: Bool, isToday: Bool) -> Bool { !isEdit && isToday }

    /// Neues Datum gewählt (`isToday`: liegt es auf dem heutigen Tag, Ortszeit):
    ///  - Bearbeiten: nie etwas ändern.
    ///  - Anderer Tag, im Feld steht noch der vorbelegte Kurs (`priceAuto`): leeren.
    ///  - Heute, Feld leer: wieder mit dem heutigen Kurs vorbelegen (falls einer bekannt ist).
    ///  - Sonst (getippter Preis): behalten.
    static func onDateChange(isEdit: Bool, isToday: Bool, priceAuto: Bool, priceBlank: Bool) -> PriceAction {
        if isEdit { return .keep }
        if !isToday && priceAuto && !priceBlank { return .clear }
        if isToday && priceBlank { return .prefill }
        return .keep
    }

    /// Hinweis «Kurs vom Kauftag eintragen» unter dem Preisfeld: neue Transaktion, nicht von heute.
    static func showPastPriceHint(isEdit: Bool, isToday: Bool) -> Bool { !isEdit && !isToday }

    /// Gebühr im Coin (Einheit `.COIN`, > 0), sonst 0.
    static func coinFee(_ fee: Decimal?, unit: PortfolioFeeUnit) -> Decimal {
        guard unit == .COIN, let fee, fee > 0 else { return 0 }
        return fee
    }

    /// Verkauf grösser als der Bestand? Abgegeben werden Menge + Gebühr im Coin (wie im Rechner,
    /// `PortfolioCalculator`). Ohne Bestand (kein Verkauf, Coin ungültig) oder ohne Menge: nein.
    static func sellExceeds(amount: Decimal?, fee: Decimal?, unit: PortfolioFeeUnit, holdings: Decimal?) -> Bool {
        guard let amount, let holdings else { return false }
        return amount + coinFee(fee, unit: unit) > holdings
    }

    /// Gebühr verbraucht die ganze erhaltene Menge — nicht speicherbar (Fehler am Gebührenfeld; der Rechner bucht
    /// bestehende Daten als Aufwand, `PortfolioFeeExpenses`): Kauf/Belohnung/Einzahlung mit Gebühr im Coin ≥ Menge,
    /// Tausch mit Gebühr im erhaltenen Coin `receivedCoinFee` ≥ erhaltener Menge `toAmount`. Fehlt ein Wert: nein.
    /// Wie Android.
    static func feeConsumesReceived(type: PortfolioTxType, amount: Decimal?, fee: Decimal?, unit: PortfolioFeeUnit,
                                    toAmount: Decimal? = nil, receivedCoinFee: Decimal? = nil) -> Bool {
        if type.isInflow {
            let f = coinFee(fee, unit: unit)
            guard let amount, amount > 0 else { return false }
            return f > 0 && f >= amount
        }
        if type == .SWAP {
            guard let toAmount, toAmount > 0, let receivedCoinFee, receivedCoinFee > 0 else { return false }
            return receivedCoinFee >= toAmount
        }
        return false
    }

    /// Die Warnung nennt die Gebühr, wenn eine Gebühr im Coin mitzählt.
    static func sellExceedsIncludesFee(_ fee: Decimal?, unit: PortfolioFeeUnit) -> Bool { coinFee(fee, unit: unit) > 0 }

    // MARK: Arten (Regeln in `PortfolioLedger`)

    /// Reihenfolge der Arten im Blatt: Kauf, Verkauf, Umbuchung, Belohnung, Einzahlung, Auszahlung, Verschenkt, Damit bezahlt
    /// (gleich nach der Auszahlung), Tausch.
    static let types: [PortfolioTxType] = [.BUY, .SELL, .TRANSFER, .REWARD, .DEPOSIT, .WITHDRAWAL, .GIFT, .PAYMENT, .SWAP]

    /// Felder je Art — wie `PortfolioTxForm.Fields` (Kotlin).
    struct Fields: Equatable {
        /// Preis je Coin (Umbuchung: keiner — der Einstand wandert mit).
        let price: Bool
        /// Gebühr mit Wahl USDT | Coin (Kauf, Verkauf).
        let feeWithUnit: Bool
        /// Gebühr nur im Coin (Auszahlung, Umbuchung: Netzwerkgebühr).
        let coinFeeOnly: Bool
        /// Gebühr in einem dritten Coin.
        let thirdCoinFee: Bool
        /// Von / Nach (Umbuchung).
        let route: Bool
        /// Börse (alles ausser Umbuchung).
        let exchange: Bool
        /// Bestand nimmt ab: Warnung, wenn mehr als vorhanden.
        let outflow: Bool
    }

    static func fields(_ type: PortfolioTxType) -> Fields {
        switch type {
        case .BUY, .SELL:
            return Fields(price: true, feeWithUnit: true, coinFeeOnly: false, thirdCoinFee: true, route: false,
                          exchange: true, outflow: type == .SELL)
        case .REWARD, .DEPOSIT:
            return Fields(price: true, feeWithUnit: false, coinFeeOnly: false, thirdCoinFee: false, route: false,
                          exchange: true, outflow: false)
        case .WITHDRAWAL, .GIFT:
            // Verschenkt: wie eine Auszahlung (Marktwert je Coin optional, Netzwerkgebühr im Coin)
            return Fields(price: true, feeWithUnit: false, coinFeeOnly: true, thirdCoinFee: true, route: false,
                          exchange: true, outflow: true)
        case .PAYMENT:
            // Damit bezahlt: Marktwert je Coin (Erlös = Menge × Wert, kein Geld erhalten); Gebühren wie beim Verkauf
            return Fields(price: true, feeWithUnit: true, coinFeeOnly: false, thirdCoinFee: true, route: false,
                          exchange: true, outflow: true)
        case .TRANSFER:
            return Fields(price: false, feeWithUnit: false, coinFeeOnly: true, thirdCoinFee: true, route: true,
                          exchange: false, outflow: true)
        case .SWAP:
            // Tausch: statt Preis der Wert des Tauschs (`swapValue`); Gebühr USDT/Coin, dazu dritter Coin oder erhaltener Coin
            return Fields(price: false, feeWithUnit: true, coinFeeOnly: false, thirdCoinFee: true, route: false,
                          exchange: true, outflow: true)
        }
    }

    /// Tausch: Wert in USDT (gesamt) aus den Eingaben — eingegebener Gesamtwert zuerst, sonst USDT je abgegebenem Coin ×
    /// Menge, sonst USDT je erhaltenem Coin × erhaltene Menge; nil = unbekannt. Wie `swapValue` (Kotlin).
    static func swapValue(amount: Decimal?, price: Decimal?, toAmount: Decimal?, toPrice: Decimal?,
                          value: Decimal? = nil) -> Decimal? {
        if let value, value >= 0 { return value }
        if let amount, amount > 0, let price, price >= 0 { return PortfolioDecimal.multiply(amount, price) }
        if let toAmount, toAmount > 0, let toPrice, toPrice >= 0 { return PortfolioDecimal.multiply(toAmount, toPrice) }
        return nil
    }

    /// Einheit der Gebühr beim Speichern: Arten nur mit Coin-Gebühr immer `.COIN`.
    static func feeUnit(for type: PortfolioTxType, chosen: PortfolioFeeUnit) -> PortfolioFeeUnit {
        fields(type).coinFeeOnly ? .COIN : chosen
    }

    /// Abgang grösser als der Bestand? Verkauf, Auszahlung: Menge + Gebühr im Coin; Umbuchung: Menge +
    /// Netzwerkgebühr. Zugänge: nie.
    static func outflowExceeds(type: PortfolioTxType, amount: Decimal?, fee: Decimal?, unit: PortfolioFeeUnit,
                               holdings: Decimal?) -> Bool {
        guard fields(type).outflow else { return false }
        return sellExceeds(amount: amount, fee: fee, unit: feeUnit(for: type, chosen: unit), holdings: holdings)
    }

    /// Zustand der Gebühr im dritten Coin aus den Eingaben.
    enum ThirdFee: Equatable {
        /// Nichts eingegeben.
        case none
        /// Coin und Menge, Preis fehlt: wird gespeichert, Hinweis «unvollständig».
        case incomplete
        case complete
        /// Coin fehlt/ungültig/gleich dem Coin der Transaktion oder Menge fehlt/≤ 0: nicht speicherbar.
        case invalid
    }

    static func thirdFee(feeCoin: String, coin: String, amount: Decimal?, amountBlank: Bool, price: Decimal?,
                         priceBlank: Bool) -> ThirdFee {
        let c = PortfolioCalculator.normalizeCoin(feeCoin)
        if c.isEmpty && amountBlank && priceBlank { return .none }
        let coinOk = !c.isEmpty && c.count <= 15 && c.allSatisfy { $0.isLetter || $0.isNumber }
            && c != PortfolioCalculator.normalizeCoin(coin)
        guard coinOk, let amount, amount > 0 else { return .invalid }
        if !priceBlank {
            guard let price, price >= 0 else { return .invalid }
        }
        return priceBlank ? .incomplete : .complete
    }

    /// Preis des Gebühr-Coins mit dem aktuellen Kurs vorbelegen: nur neue Transaktion von heute, leeres Feld.
    static func mayPrefillFeeCoinPrice(isEdit: Bool, isToday: Bool, priceBlank: Bool) -> Bool {
        !isEdit && isToday && priceBlank
    }

    /// Wert beim Erhalt (Belohnung, Einzahlung) bzw. Marktwert (Verschenkt, Damit bezahlt) für einen vergangenen Tag: geprüften
    /// Tagesschluss vorbelegen (markiert), wenn das Feld leer ist. Kauf/Verkauf nur mit Hinweis.
    static func mayPrefillHistoricClose(type: PortfolioTxType, isEdit: Bool, isToday: Bool, priceBlank: Bool) -> Bool {
        (type == .REWARD || type == .DEPOSIT || type == .GIFT || type == .PAYMENT) && !isEdit && !isToday && priceBlank
    }

    /// Von/Nach: höchstens 40 Zeichen (wie die Börse).
    static let maxRoute = 40

    // MARK: Tausch und Eingabe in der Anzeigewährung

    /// Tausch: erhaltener Coin gültig (1–15 Buchstaben/Ziffern) und ≠ dem abgegebenen Coin — wie `swapToCoinValid` (Kotlin).
    static func swapToCoinValid(_ toCoin: String, coin: String) -> Bool {
        let c = PortfolioCalculator.normalizeCoin(toCoin)
        return (1...15).contains(c.count) && c.allSatisfy { $0.isLetter || $0.isNumber }
            && c != PortfolioCalculator.normalizeCoin(coin)
    }

    /// Tausch: Gebühr im erhaltenen Coin (`feeCoin` = `toCoin`) — mindert nur die erhaltene Menge, braucht keinen Preis.
    static func feeInReceivedCoin(type: PortfolioTxType, feeCoin: String, toCoin: String) -> Bool {
        guard type == .SWAP else { return false }
        let c = PortfolioCalculator.normalizeCoin(feeCoin)
        return !c.isEmpty && c == PortfolioCalculator.normalizeCoin(toCoin)
    }

    /// Ausdrücklich eingegebener Preis 0 (`entered`, schon gelesen) nicht speicherbar? Nur bei einer importierten
    /// Transaktion (`imported`, CSV): dort ist ein Verkauf zum Preis 0 das Kennzeichen der Gebühr-Zeile
    /// (`PortfolioLedger.isFeeDisposal`). Von Hand erfasst ist 0 ein echter Preis (z. B. wertlosen Coin verkaufen).
    /// Wie Android.
    static func priceZero(_ entered: Decimal?, imported: Bool) -> Bool {
        guard imported, let entered else { return false }
        return entered == 0
    }

    /// Faktor USDT → Anzeigewährung für die Eingabe (wie CSV-Import, Steuer-Export
    /// `TaxExport.rateOn` und Live-Kurse): Devisenkurs USD → Währung `usdToFiat` × USDT/USD `usdtUsd` (heute der Live-Kurs,
    /// sonst der Tagesschluss DES Tags). Ohne gültigen, plausiblen USDT/USD-Kurs (`PortfolioStables.usdtUsd`) × 1
    /// (Näherung). `fiatToUsdt`/`usdtToFiat` rechnen mit diesem Faktor. nil ohne Devisenkurs — wie `entryRate` (Kotlin).
    static func entryRate(usdToFiat: Decimal?, usdtUsd: Decimal?) -> Decimal? {
        guard let usdToFiat, usdToFiat > 0 else { return nil }
        guard let usdt = plausibleUsdt(usdtUsd) else { return usdToFiat }
        return PortfolioDecimal.multiply(usdToFiat, usdt)
    }

    /// Rechnet `entryRate` mit USDT = 1 (Devisenkurs da, USDT/USD fehlt oder ist unplausibel)? Dann nennt das Blatt
    /// «USDT = 1 angenommen» wie Summen und Steuer-Export — die Näherung soll nie still sein. Wie Android.
    static func entryUsdtAssumed(usdToFiat: Decimal?, usdtUsd: Decimal?) -> Bool {
        guard let usdToFiat, usdToFiat > 0 else { return false }
        return plausibleUsdt(usdtUsd) == nil
    }

    private static func plausibleUsdt(_ usdtUsd: Decimal?) -> Decimal? {
        guard let usdt = usdtUsd, usdt > 0,
              !PortfolioStables.usdtUsd(PortfolioDecimal.double(usdt)).approximate else { return nil }
        return usdt
    }

    /// Eingabe in der Anzeigewährung → USDT: Betrag ÷ Faktor USDT → Währung (> 0, aus `entryRate`: Devisenkurs ×
    /// USDT/USD), 34 Stellen. nil ohne Betrag oder Kurs — wie `fiatToUsdt` (Kotlin).
    static func fiatToUsdt(_ fiat: Decimal?, usdToFiat: Decimal?) -> Decimal? {
        guard let fiat, let usdToFiat, usdToFiat > 0 else { return nil }
        return PortfolioDecimal.divide(fiat, usdToFiat)
    }

    /// USDT → Anzeigewährung (Umschalten des Felds): Betrag × Kurs; nil ohne Betrag oder Kurs.
    static func usdtToFiat(_ usdt: Decimal?, usdToFiat: Decimal?) -> Decimal? {
        guard let usdt, let usdToFiat, usdToFiat > 0 else { return nil }
        return PortfolioDecimal.multiply(usdt, usdToFiat)
    }

    /// Bestand von `wallet` aus den offenen Posten (`.FIFO_WALLET`): Summe der Posten derselben Wallet
    /// (`PortfolioWallets.key`; leer = «ohne Angabe») — wie `walletHoldings` (Kotlin).
    static func walletHoldings(_ lots: [PortfolioOpenLot], wallet: String?) -> Decimal {
        let key = PortfolioWallets.key(wallet)
        return lots.filter { PortfolioWallets.key($0.wallet) == key }.reduce(Decimal(0)) { $0 + $1.amount }
    }

    // MARK: Prüfung der Eingaben (wie `PortfolioTxForm.check`, Kotlin)

    /// Gelesene Eingaben des Erfassen-Blatts: Texte schon in Zahlen umgewandelt (Dezimalzeichen der Region, Eingabe in
    /// der Anzeigewährung schon in USDT umgerechnet); `…Blank` = Feld leer. Das Lesen bleibt im Blatt, die Regeln stehen
    /// hier (`check`) — wie `PortfolioTxForm.Entry` (Kotlin).
    struct Entry: Equatable {
        var type: PortfolioTxType
        /// Coin wie getippt (normalisiert wird in `check`).
        var coinQuery: String
        /// Menge > 0; nil = leer, unlesbar oder ≤ 0.
        var amount: Decimal?
        var priceBlank = true
        /// Preis wie eingegeben (USDT bzw. Anzeigewährung); nil = leer oder unlesbar.
        var priceEntered: Decimal?
        /// Preis in USDT; nil = leer, unlesbar oder ohne Devisenkurs.
        var price: Decimal?
        /// Herkunft aus dem CSV-Import: ein Preis 0 ist nicht speicherbar (`priceZero`).
        var imported = false
        var feeBlank = true
        /// Gebühr gelesen (jede Art); nil = leer oder unlesbar.
        var fee: Decimal?
        /// Gewählte Einheit der Gebühr (vor `feeUnit(for:chosen:)`).
        var feeUnit: PortfolioFeeUnit = .QUOTE
        /// Gebühr im dritten Coin: Coin wie getippt, Menge und Preis gelesen.
        var feeCoin = ""
        var feeCoinAmountBlank = true
        var feeCoinAmount: Decimal?
        var feeCoinPriceBlank = true
        var feeCoinPrice: Decimal?
        /// Tausch: erhaltener Coin wie getippt, erhaltene Menge > 0 (nil = leer, unlesbar oder ≤ 0).
        var toCoinQuery = ""
        var toAmount: Decimal?
        var swapValueBlank = true
        /// Tausch: Wert in USDT; nil = leer, unlesbar oder ohne Devisenkurs.
        var swapValue: Decimal?
    }

    /// Ergebnis von `check` — wie `PortfolioTxForm.Check` (Kotlin).
    struct Check: Equatable {
        let fields: Fields
        /// Coin normalisiert.
        let coin: String
        /// 1–15 Buchstaben/Ziffern (`PortfolioPriceSource.isSymbol`).
        let coinValid: Bool
        /// Ausdrücklich 0 eingegeben bei einer importierten Transaktion (`priceZero`).
        let priceZero: Bool
        /// Art ohne Preis, Feld leer (Preis unbekannt) oder lesbar und nicht `priceZero`.
        let priceValid: Bool
        let toCoin: String
        /// Tausch: erhaltener Coin gültig und ≠ dem abgegebenen; sonst true.
        let toCoinValid: Bool
        /// Tausch: erhaltene Menge > 0; sonst true.
        let toAmountValid: Bool
        /// Tausch: Wert leer oder ≥ 0; sonst true.
        let swapValueValid: Bool
        /// Die Art kennt eine Gebühr (mit Einheit oder nur im Coin).
        let hasFee: Bool
        /// Gebühr dieser Art; nil = leer, unlesbar oder Art ohne Gebühr.
        let fee: Decimal?
        let feeValid: Bool
        /// Einheit der Gebühr beim Speichern (`feeUnit(for:chosen:)`).
        let feeUnit: PortfolioFeeUnit
        /// Tausch: Gebühr im erhaltenen Coin — braucht keinen Preis.
        let feeInReceived: Bool
        /// Gebühr im dritten Coin; Art ohne dieses Feld: `.none`.
        let thirdFee: ThirdFee
        /// Gebühr verbraucht die ganze erhaltene Menge (`feeConsumesReceived`).
        let feeConsumes: Bool
        /// Alles speicherbar.
        let valid: Bool
    }

    /// Prüfung des Erfassen-Blatts: Coin, Menge (> 0), Preis (leer = unbekannt; Preis 0 nur bei importierten
    /// Transaktionen abgelehnt), Gebühr (leer = keine), Gebühr im dritten Coin (im erhaltenen Coin eines Tauschs ohne
    /// Preis vollständig), Tausch (erhaltener Coin und Menge, Wert leer oder ≥ 0) und Gebühr ≥ erhaltener Menge — wie
    /// `PortfolioTxForm.check` (Kotlin).
    static func check(_ e: Entry) -> Check {
        let fields = Self.fields(e.type)
        let isSwap = e.type == .SWAP
        let coin = PortfolioCalculator.normalizeCoin(e.coinQuery)
        let coinValid = PortfolioPriceSource.isSymbol(coin)
        let zero = fields.price && priceZero(e.priceEntered, imported: e.imported)
        let priceValid = !fields.price || e.priceBlank || (e.price != nil && !zero)
        let toCoin = PortfolioCalculator.normalizeCoin(e.toCoinQuery)
        let toCoinValid = !isSwap || swapToCoinValid(e.toCoinQuery, coin: coin)
        let toAmountValid = !isSwap || e.toAmount != nil
        let swapValueValid = !isSwap || e.swapValueBlank || (e.swapValue.map { $0 >= 0 } ?? false)
        let hasFee = fields.feeWithUnit || fields.coinFeeOnly
        let fee: Decimal? = !hasFee || e.feeBlank ? nil : e.fee
        let feeValid = !hasFee || e.feeBlank || fee != nil
        let unit = feeUnit(for: e.type, chosen: e.feeUnit)
        let feeInReceived = feeInReceivedCoin(type: e.type, feeCoin: e.feeCoin, toCoin: e.toCoinQuery)
        var third: ThirdFee = .none
        if fields.thirdCoinFee {
            let state = thirdFee(feeCoin: e.feeCoin, coin: coin, amount: e.feeCoinAmount, amountBlank: e.feeCoinAmountBlank,
                                 price: e.feeCoinPrice, priceBlank: e.feeCoinPriceBlank)
            third = feeInReceived && state == .incomplete ? .complete : state
        }
        let consumes = feeConsumesReceived(type: e.type, amount: e.amount, fee: fee, unit: unit, toAmount: e.toAmount,
                                           receivedCoinFee: feeInReceived ? e.feeCoinAmount : nil)
        let valid = coinValid && e.amount != nil && priceValid && feeValid && third != .invalid
            && toCoinValid && toAmountValid && swapValueValid && !consumes
        return Check(fields: fields, coin: coin, coinValid: coinValid, priceZero: zero, priceValid: priceValid,
                     toCoin: toCoin, toCoinValid: toCoinValid, toAmountValid: toAmountValid,
                     swapValueValid: swapValueValid, hasFee: hasFee, fee: fee, feeValid: feeValid, feeUnit: unit,
                     feeInReceived: feeInReceived, thirdFee: third, feeConsumes: consumes, valid: valid)
    }
}
