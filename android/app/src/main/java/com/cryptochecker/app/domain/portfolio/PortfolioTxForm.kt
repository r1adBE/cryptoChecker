package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Entscheide des Erfassen-Blatts (reines Kotlin, testbar; Spiegel: `Shared/Portfolio/PortfolioTxForm.swift`).
 *
 *  - Preis vorbelegen: Der vorbelegte Preis ist der HEUTIGE Kurs und gilt nur für eine Transaktion von
 *    heute (Ortszeit). Wird ein anderer Tag gewählt und steht noch der vorbelegte Preis im Feld, wird er
 *    geleert (sonst würde ein alter Kauf zum heutigen Kurs gespeichert); darunter der Hinweis «Kurs vom
 *    Kauftag eintragen». Ein getippter Preis bleibt. Zurück auf heute mit leerem Feld: wieder vorbelegt.
 *    Beim Bearbeiten nie vorbelegen.
 *  - Verkauf über den Bestand: Menge + Gebühr im Coin grösser als der Bestand → Warnung (blockiert das
 *    Speichern nicht; der Rechner kappt den Verkauf und markiert ihn).
 *  - Gebühr ≥ erhaltene Menge ([feeConsumesReceived]): Fehler, Speichern gesperrt.
 */
object PortfolioTxForm {

    /** Was mit dem Preisfeld geschieht, wenn das Datum wechselt. */
    enum class PriceAction { KEEP, CLEAR, PREFILL }

    /** Darf der heutige Kurs ins Preisfeld (neue Transaktion von heute)? */
    fun mayPrefill(isEdit: Boolean, isToday: Boolean): Boolean = !isEdit && isToday

    /**
     * Neues Datum gewählt ([isToday]: liegt es auf dem heutigen Tag, Ortszeit):
     *  - Bearbeiten: nie etwas ändern.
     *  - Anderer Tag, im Feld steht noch der vorbelegte Kurs ([priceAuto]): leeren.
     *  - Heute, Feld leer: wieder mit dem heutigen Kurs vorbelegen (falls einer bekannt ist).
     *  - Sonst (getippter Preis): behalten.
     */
    fun onDateChange(isEdit: Boolean, isToday: Boolean, priceAuto: Boolean, priceBlank: Boolean): PriceAction = when {
        isEdit -> PriceAction.KEEP
        !isToday && priceAuto && !priceBlank -> PriceAction.CLEAR
        isToday && priceBlank -> PriceAction.PREFILL
        else -> PriceAction.KEEP
    }

    /** Hinweis «Kurs vom Kauftag eintragen» unter dem Preisfeld: neue Transaktion, nicht von heute. */
    fun showPastPriceHint(isEdit: Boolean, isToday: Boolean): Boolean = !isEdit && !isToday

    /** Gebühr im Coin (Einheit [PortfolioFeeUnit.COIN], > 0), sonst 0. */
    fun coinFee(fee: BigDecimal?, feeUnit: PortfolioFeeUnit): BigDecimal =
        if (feeUnit == PortfolioFeeUnit.COIN && fee != null && fee.signum() > 0) fee else BigDecimal.ZERO

    /**
     * Verkauf grösser als der Bestand? Abgegeben werden Menge + Gebühr im Coin (wie im Rechner,
     * [PortfolioCalculator]). Ohne Bestand (kein Verkauf, Coin ungültig) oder ohne Menge: nein.
     */
    fun sellExceeds(amount: BigDecimal?, fee: BigDecimal?, feeUnit: PortfolioFeeUnit, holdings: BigDecimal?): Boolean {
        if (amount == null || holdings == null) return false
        return amount + coinFee(fee, feeUnit) > holdings
    }

    /**
     * Gebühr verbraucht die ganze erhaltene Menge — nicht speicherbar (Fehler am Gebührenfeld; der Rechner bucht
     * bestehende Daten als Aufwand, [PortfolioFeeExpenses]): Kauf/Belohnung/Einzahlung mit Gebühr im Coin ≥ Menge,
     * Tausch mit Gebühr im erhaltenen Coin [receivedCoinFee] ≥ erhaltener Menge [toAmount]. Fehlt ein Wert: nein.
     */
    fun feeConsumesReceived(
        type: PortfolioTxType,
        amount: BigDecimal?,
        fee: BigDecimal?,
        feeUnit: PortfolioFeeUnit,
        toAmount: BigDecimal? = null,
        receivedCoinFee: BigDecimal? = null,
    ): Boolean = when {
        type.isInflow -> {
            val f = coinFee(fee, feeUnit)
            amount != null && amount.signum() > 0 && f.signum() > 0 && f >= amount
        }
        type == PortfolioTxType.SWAP ->
            toAmount != null && toAmount.signum() > 0 && receivedCoinFee != null && receivedCoinFee.signum() > 0 && receivedCoinFee >= toAmount
        else -> false
    }

    /** Die Warnung nennt die Gebühr, wenn eine Gebühr im Coin mitzählt. */
    fun sellExceedsIncludesFee(fee: BigDecimal?, feeUnit: PortfolioFeeUnit): Boolean = coinFee(fee, feeUnit).signum() > 0

    // ---- Arten (Build 46, Regeln in [PortfolioLedger]) ----

    /**
     * Reihenfolge der Arten im Blatt: Kauf, Verkauf, Umbuchung, Belohnung, Einzahlung, Auszahlung, Verschenkt, Damit
     * bezahlt (Build 47, gleich nach der Auszahlung), Tausch.
     */
    val TYPES: List<PortfolioTxType> = listOf(
        PortfolioTxType.BUY, PortfolioTxType.SELL, PortfolioTxType.TRANSFER,
        PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT, PortfolioTxType.WITHDRAWAL,
        PortfolioTxType.GIFT, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP,
    )

    /** Felder je Art. */
    data class Fields(
        /** Preis je Coin (Umbuchung: keiner — der Einstand wandert mit). */
        val price: Boolean,
        /** Gebühr mit Wahl USDT | Coin (Kauf, Verkauf). */
        val feeWithUnit: Boolean,
        /** Gebühr nur im Coin (Auszahlung, Umbuchung: Netzwerkgebühr). */
        val coinFeeOnly: Boolean,
        /** Gebühr in einem dritten Coin. */
        val thirdCoinFee: Boolean,
        /** Von / Nach (Umbuchung). */
        val route: Boolean,
        /** Börse (alles ausser Umbuchung). */
        val exchange: Boolean,
        /** Bestand nimmt ab: Warnung, wenn mehr als vorhanden. */
        val outflow: Boolean,
    )

    fun fields(type: PortfolioTxType): Fields = when (type) {
        PortfolioTxType.BUY, PortfolioTxType.SELL ->
            Fields(price = true, feeWithUnit = true, coinFeeOnly = false, thirdCoinFee = true, route = false, exchange = true, outflow = type == PortfolioTxType.SELL)
        PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT ->
            Fields(price = true, feeWithUnit = false, coinFeeOnly = false, thirdCoinFee = false, route = false, exchange = true, outflow = false)
        // Verschenkt: wie eine Auszahlung (Marktwert je Coin optional, Netzwerkgebühr im Coin)
        PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT ->
            Fields(price = true, feeWithUnit = false, coinFeeOnly = true, thirdCoinFee = true, route = false, exchange = true, outflow = true)
        // Damit bezahlt: Marktwert je Coin (Erlös = Menge × Wert, kein Geld erhalten); Gebühren wie beim Verkauf
        PortfolioTxType.PAYMENT ->
            Fields(price = true, feeWithUnit = true, coinFeeOnly = false, thirdCoinFee = true, route = false, exchange = true, outflow = true)
        PortfolioTxType.TRANSFER ->
            Fields(price = false, feeWithUnit = false, coinFeeOnly = true, thirdCoinFee = true, route = true, exchange = false, outflow = true)
        // Tausch: statt Preis der Wert des Tauschs ([swapValue]); Gebühr USDT/Coin, dazu dritter Coin oder erhaltener Coin
        PortfolioTxType.SWAP ->
            Fields(price = false, feeWithUnit = true, coinFeeOnly = false, thirdCoinFee = true, route = false, exchange = true, outflow = true)
    }

    /**
     * Tausch: Wert in USDT (gesamt) aus den Eingaben — ein eingegebener Gesamtwert [valueUsdt] (≥ 0) zuerst, sonst
     * USDT je abgegebenem Coin × [amount], sonst USDT je erhaltenem Coin × [toAmount]; null = unbekannt (wird so
     * gespeichert: Erlös und Einstand des neuen Postens unbekannt, «unvollständig»).
     */
    fun swapValue(
        amount: BigDecimal?,
        priceUsdt: BigDecimal?,
        toAmount: BigDecimal?,
        toPriceUsdt: BigDecimal?,
        valueUsdt: BigDecimal? = null,
    ): BigDecimal? {
        valueUsdt?.takeIf { it.signum() >= 0 }?.let { return it }
        if (amount != null && amount.signum() > 0 && priceUsdt != null && priceUsdt.signum() >= 0) return amount * priceUsdt
        if (toAmount != null && toAmount.signum() > 0 && toPriceUsdt != null && toPriceUsdt.signum() >= 0) return toAmount * toPriceUsdt
        return null
    }

    /** Einheit der Gebühr beim Speichern: Arten nur mit Coin-Gebühr immer [PortfolioFeeUnit.COIN]. */
    fun feeUnitFor(type: PortfolioTxType, chosen: PortfolioFeeUnit): PortfolioFeeUnit =
        if (fields(type).coinFeeOnly) PortfolioFeeUnit.COIN else chosen

    /**
     * Abgang grösser als der Bestand? Verkauf und Auszahlung: Menge + Gebühr im Coin; Umbuchung: Menge
     * (muss da sein, um sie zu verschieben) + Netzwerkgebühr. Zugänge: nie.
     */
    fun outflowExceeds(type: PortfolioTxType, amount: BigDecimal?, fee: BigDecimal?, feeUnit: PortfolioFeeUnit, holdings: BigDecimal?): Boolean {
        if (!fields(type).outflow) return false
        return sellExceeds(amount, fee, feeUnitFor(type, feeUnit), holdings)
    }

    /** Zustand der Gebühr im dritten Coin aus den Eingaben. */
    enum class ThirdFee {
        /** Nichts eingegeben. */
        NONE,
        /** Coin und Menge, Preis fehlt: wird gespeichert, Hinweis «unvollständig». */
        INCOMPLETE,
        COMPLETE,
        /** Coin fehlt/ungültig/gleich dem Coin der Transaktion oder Menge fehlt/≤ 0: nicht speicherbar. */
        INVALID,
    }

    fun thirdFee(feeCoin: String, coin: String, amount: BigDecimal?, amountBlank: Boolean, price: BigDecimal?, priceBlank: Boolean): ThirdFee {
        val c = PortfolioCalculator.normalizeCoin(feeCoin)
        if (c.isEmpty() && amountBlank && priceBlank) return ThirdFee.NONE
        val coinOk = c.isNotEmpty() && c.length <= 15 && c.all { it.isLetterOrDigit() } && c != PortfolioCalculator.normalizeCoin(coin)
        if (!coinOk || amount == null || amount.signum() <= 0) return ThirdFee.INVALID
        if (!priceBlank && (price == null || price.signum() < 0)) return ThirdFee.INVALID
        return if (priceBlank) ThirdFee.INCOMPLETE else ThirdFee.COMPLETE
    }

    /** Preis des Gebühr-Coins mit dem aktuellen Kurs vorbelegen: nur bei einer neuen Transaktion von heute und leerem Feld. */
    fun mayPrefillFeeCoinPrice(isEdit: Boolean, isToday: Boolean, priceBlank: Boolean): Boolean = !isEdit && isToday && priceBlank

    /**
     * Wert beim Erhalt (Belohnung, Einzahlung) bzw. Marktwert (Verschenkt, Damit bezahlt) für einen vergangenen Tag: geprüften
     * Tagesschluss vorbelegen (markiert), wenn das Feld leer ist (neue Transaktion, nicht von heute). Kauf/Verkauf wie
     * bisher nur mit Hinweis.
     */
    fun mayPrefillHistoricClose(type: PortfolioTxType, isEdit: Boolean, isToday: Boolean, priceBlank: Boolean): Boolean =
        (type == PortfolioTxType.REWARD || type == PortfolioTxType.DEPOSIT || type == PortfolioTxType.GIFT ||
            type == PortfolioTxType.PAYMENT) &&
            !isEdit && !isToday && priceBlank

    /** Von/Nach: getrimmt, höchstens 40 Zeichen (wie die Börse). */
    const val MAX_ROUTE = 40

    // ---- Tausch und Eingabe in der Anzeigewährung (Build 46) ----

    /** Tausch: erhaltener Coin gültig (1–15 Buchstaben/Ziffern) und ≠ dem abgegebenen Coin. */
    fun swapToCoinValid(toCoin: String, coin: String): Boolean {
        val c = PortfolioCalculator.normalizeCoin(toCoin)
        return c.length in 1..15 && c.all { it.isLetterOrDigit() } && c != PortfolioCalculator.normalizeCoin(coin)
    }

    /**
     * Tausch: Gebühr im erhaltenen Coin ([feeCoin] = [toCoin]) — mindert nur die erhaltene Menge, braucht keinen Preis
     * (kein «unvollständig»).
     */
    fun feeInReceivedCoin(type: PortfolioTxType, feeCoin: String, toCoin: String): Boolean {
        if (type != PortfolioTxType.SWAP) return false
        val c = PortfolioCalculator.normalizeCoin(feeCoin)
        return c.isNotEmpty() && c == PortfolioCalculator.normalizeCoin(toCoin)
    }

    /**
     * Ausdrücklich eingegebener Preis 0 ([entered], schon gelesen) nicht speicherbar? Nur bei einer importierten
     * Transaktion ([imported], CSV): dort ist ein Verkauf zum Preis 0 das Kennzeichen der Gebühr-Zeile
     * ([PortfolioLedger.isFeeDisposal]) und würde still zum Tageskurs bewertet. Von Hand erfasst ist 0 ein echter Preis
     * (z. B. wertlosen Coin zu 0 verkaufen, Build 47).
     */
    fun priceZero(entered: BigDecimal?, imported: Boolean): Boolean = imported && entered != null && entered.signum() == 0

    /**
     * Eingabe in der Anzeigewährung (CHF/EUR/…) → USDT: Betrag ÷ Devisenkurs USD → Währung ([usdToFiat] > 0; USDT
     * gilt als USD), 34 Stellen ([PortfolioDecimal.MC]). null ohne Betrag oder Kurs.
     */
    fun fiatToUsdt(fiat: BigDecimal?, usdToFiat: BigDecimal?): BigDecimal? {
        if (fiat == null || usdToFiat == null || usdToFiat.signum() <= 0) return null
        return fiat.divide(usdToFiat, PortfolioDecimal.MC).stripTrailingZeros()
    }

    /** USDT → Anzeigewährung (Umschalten des Felds): Betrag × Kurs; null ohne Betrag oder Kurs. */
    fun usdtToFiat(usdt: BigDecimal?, usdToFiat: BigDecimal?): BigDecimal? {
        if (usdt == null || usdToFiat == null || usdToFiat.signum() <= 0) return null
        return usdt.multiply(usdToFiat).round(PortfolioDecimal.MC).stripTrailingZeros()
    }

    /**
     * Bestand von [wallet] aus den offenen Posten ([PortfolioCalculator.openLots], [CostMethod.FIFO_WALLET]): Summe der
     * Posten derselben Wallet ([PortfolioWallets.key]; leer = «ohne Angabe»).
     */
    fun walletHoldings(lots: List<PortfolioOpenLot>, wallet: String?): BigDecimal {
        val key = PortfolioWallets.key(wallet)
        return lots.filter { PortfolioWallets.key(it.wallet) == key }.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
    }
}
