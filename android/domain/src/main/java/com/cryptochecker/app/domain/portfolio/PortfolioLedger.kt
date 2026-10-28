package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Ein Schritt im Verlauf eines Coins: eine eigene Transaktion, ([feeLeg]) die Gebühr einer
 * Transaktion eines ANDEREN Coins, die in diesem Coin bezahlt wurde ([PortfolioTrade.thirdFeeCoin]), oder
 * ([swapIn]) der Zugang eines Tauschs in seinem erhaltenen Coin ([PortfolioTrade.swapToCoin]).
 */
data class PortfolioLedgerEvent(val trade: PortfolioTrade, val feeLeg: Boolean, val swapIn: Boolean = false) {
    /** Zugang (Kauf, Belohnung, Einzahlung, Zugang aus Tausch) — Basis «Zugänge» im Zeitraum. */
    val isAcquisition: Boolean get() = !feeLeg && (swapIn || trade.type.isInflow)
}

/**
 * Gemeinsame Regeln für Bestand und Zuflüsse aller Transaktionsarten (reines Kotlin, testbar; Spiegel:
 * `Shared/Portfolio/PortfolioLedger.swift`). Genutzt von [PortfolioCalculator], [PortfolioPeriodChange],
 * [PortfolioHistory] und [CutoffExport], damit Bestand und Zuflüsse überall gleich zählen.
 *
 * Bestand nach einem Schritt ([holdingsAfter], nie unter 0, Abgänge auf den Bestand gekappt):
 *  - Kauf, Belohnung, Einzahlung: + Menge − Gebühr im Coin.
 *  - Verkauf, Damit bezahlt, Verschenkt, Auszahlung: − (Menge + Gebühr im Coin).
 *  - Umbuchung: − Gebühr im Coin (Netzwerkgebühr); die Menge selbst bleibt im Portfolio.
 *  - Tausch: im abgegebenen Coin − (Menge + Gebühr im Coin), im erhaltenen Coin ([PortfolioLedgerEvent.swapIn])
 *    + (erhaltene Menge − Gebühr im erhaltenen Coin).
 *  - Gebühr-Abgang (Gebühr im dritten Coin): − Gebühr.
 *
 * Zufluss in USDT ([flow]; + = Geld hinein, − = heraus) für «Heute/7 T/1 J» und den Wertverlauf —
 * Gewinn = Wert Ende − Wert Anfang − Σ Zuflüsse:
 *  - Kauf, Belohnung, Einzahlung: + (Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin).
 *    Belohnung zählt also zum Wert beim Erhalt als Zufluss (kein Gewinn im Zeitraum), erst die
 *    spätere Kursbewegung. Kauf ohne Preis: unbekannt. Belohnung ohne Wert: zum MARKTKURS ihres Zeitpunkts
 *    (neutral wie eine Einzahlung ohne Preis — eine Belohnung von 0.00001 BTC ohne Wert lässt den Coin nicht
 *    aus «Heute/7 T/1 J» fallen); Einstand und Steuer-Einkünfte bleiben ohne Wert.
 *  - Verkauf: − (verkaufte Menge × Preis − Gebühr USDT − Wert der Gebühr im dritten Coin); ohne Preis
 *    unbekannt; ohne Bestand 0. Reicht der Bestand nicht, zählt der gedeckte Anteil des GANZEN Erlöses (samt Gebühren)
 *    nach der Einstandsmethode — genau wie «realisiert» und Steuer ([coveredFraction]): FIFO (beide) zuerst die
 *    verkaufte Menge, dann die Gebühr-Coins (gedeckt verkauft / Menge); Ø abgegeben / (Menge + Gebühr im Coin).
 *  - Gebühr-Zeile ([isFeeDisposal], CSV «Fee»): Veräusserung zum Tageskurs — wie ein Verkauf zum MARKTKURS dieses
 *    Zeitpunkts, dessen Erlös das Portfolio verlässt (Abfluss = Menge × Kurs, gedeckter Anteil wie dort): der Verlauf
 *    springt am Gebühr-Tag nicht, realisiert = Marktwert − Einstand wie im Steuer-Export. Ohne Marktkurs unbekannt.
 *  - Damit bezahlt ([PortfolioTxType.PAYMENT]): wie ein Verkauf zum Marktwert je Coin; ohne Marktwert zum MARKTKURS
 *    dieses Zeitpunkts; ohne Marktkurs unbekannt. Verschenkt ([PortfolioTxType.GIFT]): wie eine Auszahlung.
 *  - Auszahlung: wie ein Verkauf zum Preis der Auszahlung, wenn einer erfasst ist, sonst zum MARKTKURS
 *    dieses Zeitpunkts ([flow] `marketPrice`: Tagesschluss bzw. Kurs der Zeitraum-Bewertung) — so entsteht
 *    kein Gewinn/Verlust aus dem Abgang; ohne Marktkurs unbekannt (Coin fehlt im Zeitraum). Reicht der Bestand nicht,
 *    zählen Wert UND Gebühren nur zum gedeckten Anteil (abgegeben / (Menge + Gebühr im Coin)) wie beim Verkauf.
 *  - Einzahlung ohne Preis: zum MARKTKURS ihres Zeitpunkts wie eine Adress-Beobachtung — eingebrachtes Geld,
 *    kein Gewinn; der Coin fehlt nicht ein Jahr lang im Zeitraum. Der Einstand bleibt unbekannt («Seit
 *    Kauf», Steuer). Ohne Marktkurs unbekannt.
 *  - Adress-Einträge ([AddressBalance.isAddressTrade], Einzahlung/Auszahlung ohne Preis aus
 *    [AddressBalanceHistory.trades]): zum Marktkurs (kein Gewinn aus Guthaben-Änderungen).
 *  - Umbuchung: + (Gebühr USDT + Wert der Gebühr im dritten Coin) — sie stecken im Einstand; die
 *    Netzwerkgebühr im Coin senkt nur die Menge (Wert am Ende). Ohne Bestand in der Quelle 0 (wie im Rechner).
 *  - Gebühr-Abgang: − (Gebühr × Gebühr-Preis), ohne Bestand 0; ohne Gebühr-Preis zum MARKTKURS des Gebühr-Coins
 *    ([needsMarketPrice]); ohne Marktkurs unbekannt.
 *  - Tausch (neutral: der Wert wandert von einem Coin zum anderen): abgegebener Coin − (abgegebene Menge × Wert je
 *    Coin − Gebühr USDT − Wert der Gebühr im dritten Coin) wie ein Verkauf (gedeckter Anteil wie dort), erhaltener
 *    Coin + Wert des Tauschs wie ein Kauf (eine Gebühr im erhaltenen Coin senkt nur die Menge). Ohne Wert zum MARKTKURS
 *    des jeweiligen Coins (abgegeben: Menge × Kurs, erhalten: erhaltene Menge vor Gebühr × Kurs); ohne Marktkurs
 *    unbekannt. Tausch ohne gültigen Zugang ([PortfolioTrade.swapToCoin] null: erhaltener Coin fehlt/gleich, Menge ≤ 0):
 *    unbekannt (der Wert ginge sonst still verloren).
 *  - Gebühr im dritten Coin ohne Preis: in der Haupttransaktion zum MARKTKURS des Gebühr-Coins bewertet
 *    ([needsFeeMarketPrice], `feeMarketPrice` von [flow]); ohne Marktkurs ist ihr Zufluss unbekannt (beim Tausch nur
 *    im abgegebenen Coin). Einstand/Erlös im Rechner bleiben «ohne Wert der Gebühr» ([CoinPosition.feeValueMissing]).
 * Unbekannt (null) lässt den Coin im Zeitraum fehlen (wie ein Kauf ohne Preis) — nur noch, wenn auch kein Marktkurs
 * des Tags da ist; Karte, Zeilen, Widget und Alarm nennen ihn gleich («ohne …»).
 */
object PortfolioLedger {

    /**
     * Reihenfolge der Schritte: Zeit, bei gleicher Zeit Zugänge ([PortfolioLedgerEvent.isAcquisition]: Kauf, Einzahlung,
     * Belohnung, Zugang aus Tausch) vor Abgängen, dann Id. Sonst buchte ein Export «neueste zuerst» (Id = Dateireihenfolge)
     * den Verkauf vor dem Kauf derselben Sekunde (Bestand fehlt, Einstand unbekannt).
     */
    val ORDER: Comparator<PortfolioLedgerEvent> =
        compareBy<PortfolioLedgerEvent> { it.trade.time }.thenBy { if (it.isAcquisition) 0 else 1 }.thenBy { it.trade.id }

    /** Schritte eines Coins zeitlich ([ORDER]), samt Gebühr-Abgängen anderer Transaktionen. */
    fun events(coin: String, trades: List<PortfolioTrade>): List<PortfolioLedgerEvent> {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        val out = ArrayList<PortfolioLedgerEvent>()
        for (t in trades) {
            if (PortfolioCalculator.normalizeCoin(t.coin) == symbol) out += PortfolioLedgerEvent(t, false)
            else if (t.swapToCoin == symbol) out += PortfolioLedgerEvent(t, false, swapIn = true)
            else if (t.thirdFeeCoin == symbol) out += PortfolioLedgerEvent(t, true)
        }
        return out.sortedWith(ORDER)
    }

    /**
     * [events] aller Coins in einem Durchgang (Reihenfolge der Coins wie [coins]): je Coin dieselbe Liste wie
     * [events] — ohne für jeden Coin alle Transaktionen zu durchlaufen.
     */
    fun eventsByCoin(trades: List<PortfolioTrade>): Map<String, List<PortfolioLedgerEvent>> {
        val out = LinkedHashMap<String, ArrayList<PortfolioLedgerEvent>>()
        for (c in coins(trades)) out[c] = ArrayList()
        for (t in trades) {
            out.getValue(PortfolioCalculator.normalizeCoin(t.coin)) += PortfolioLedgerEvent(t, false)
            t.swapToCoin?.let { out.getValue(it) += PortfolioLedgerEvent(t, false, swapIn = true) }
            t.thirdFeeCoin?.let { out.getValue(it) += PortfolioLedgerEvent(t, true) }
        }
        return out.mapValues { (_, list) -> list.sortedWith(ORDER) }
    }

    /** Alle Coins: zuerst die der Transaktionen, dann die erhaltenen Coins eines Tauschs, dann die Gebühr-Coins (normalisiert, ohne Doppel). */
    fun coins(trades: List<PortfolioTrade>): List<String> {
        val out = LinkedHashSet<String>()
        trades.forEach { out += PortfolioCalculator.normalizeCoin(it.coin) }
        trades.forEach { t -> t.swapToCoin?.let { out += it } }
        trades.forEach { t -> t.thirdFeeCoin?.let { out += it } }
        return out.toList()
    }

    /**
     * Gebühr-Zeile (CSV «Fee», [com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping]), gespeichert als Verkauf
     * mit eigenem Merker ([PortfolioTrade.feeRow] — ein von Hand erfasster Verkauf zum Preis 0 bleibt ein Verkauf eines
     * wertlosen Coins): Veräusserung zum TAGESKURS — Erlös = Menge × Tagesschluss ihres Tags ([valueFeeDisposals]),
     * realisiert = Marktwert − Einstand; ohne Kurs Erlös unbekannt («unvollständig», nie 0). Steuer-Export: Vermerk
     * «Gebühr» ([TaxGainRow.feeRow]). Verlauf und «Heute/7 T/1 J» ([flow]): Abfluss zum Marktkurs — über die ganze
     * Haltedauer Gewinn/Verlust-Kurve = «Seit Kauf» + realisiert, kein Sprung am Gebühr-Tag.
     */
    fun isFeeDisposal(t: PortfolioTrade): Boolean = t.type == PortfolioTxType.SELL && t.feeRow

    /** Verkaufspreis für Einstand/Erlös: Gebühr-Zeile ohne Tageskurs = unbekannt (null), sonst [PortfolioTrade.validPrice]. */
    fun salePrice(t: PortfolioTrade): BigDecimal? = if (isFeeDisposal(t)) null else t.validPrice

    /**
     * Gebühr-Zeilen ([isFeeDisposal]) zum Tageskurs: Preis = [price] (Coin, Zeit) > 0, sonst null (Erlös unbekannt).
     * Danach rechnen alle Methoden sie wie einen Verkauf zu diesem Preis. Ohne Gebühr-Zeilen dieselbe Liste.
     */
    fun valueFeeDisposals(trades: List<PortfolioTrade>, price: ((String, Long) -> BigDecimal?)?): List<PortfolioTrade> {
        if (trades.none(::isFeeDisposal)) return trades
        return trades.map { t ->
            if (!isFeeDisposal(t)) t
            // Bewertet ist sie ein gewöhnlicher Verkauf zu diesem Preis (der Merker gilt nur für die Rohdaten)
            else t.copy(
                priceUsdt = price?.invoke(PortfolioCalculator.normalizeCoin(t.coin), t.time)?.takeIf { it.signum() > 0 },
                feeRow = false,
            )
        }
    }

    /**
     * Braucht [t] einen Tagesschluss für [valueAtDayClose]? Gebühr-Zeile, Damit bezahlt ohne Marktwert, Tausch mit
     * gültigem Zugang ohne Wert. (Verschenkt erst nach [TaxEngines.applyRules], dann als Damit bezahlt.)
     */
    fun needsDayClose(t: PortfolioTrade): Boolean = isFeeDisposal(t) ||
        (t.type == PortfolioTxType.PAYMENT && t.validPrice == null) ||
        (t.type == PortfolioTxType.SWAP && t.swapToCoin != null && t.swapValue == null)

    /**
     * (Coin, Zeit), für die [valueAtDayClose] einen Tagesschluss braucht (Coins normalisiert): Gebühr-Zeile, Damit bezahlt
     * und Verschenkt (Schenkung als Veräusserung, [TaxEngines.applyRules]) ohne Marktwert der Coin selbst, Tausch beide Coins (abgegeben
     * zuerst, sonst erhalten); dazu Belohnungen ohne Wert und der Gebühr-Coin jeder Transaktion mit Gebühr im dritten Coin
     * ohne Preis für den Steuer-Export ([valueRewardsAtDayClose], [valueThirdFeesAtDayClose]).
     */
    fun dayCloseNeeds(trades: List<PortfolioTrade>): Set<Pair<String, Long>> {
        val out = LinkedHashSet<Pair<String, Long>>()
        for (t in trades) {
            val gift = t.type == PortfolioTxType.GIFT && t.validPrice == null
            if (thirdFeeNeedsDayClose(t)) t.thirdFeeCoin?.let { out += it to t.time }
            if (t.amount.signum() <= 0 || !(needsDayClose(t) || gift || rewardNeedsDayClose(t))) continue
            out += PortfolioCalculator.normalizeCoin(t.coin) to t.time
            t.swapToCoin?.takeIf { t.type == PortfolioTxType.SWAP }?.let { out += it to t.time }
        }
        return out
    }

    /**
     * Werte zum Tagesschluss [price] (Coin, Zeit; > 0, sonst unbekannt) — dieselbe Regel für Karte (`feePrice`/
     * `withdrawalPrice`) und Steuer-Export (`priceAt`):
     *  - Gebühr-Zeile ([valueFeeDisposals]): Preis = Tagesschluss, sonst Erlös unbekannt.
     *  - Damit bezahlt ohne Marktwert: Preis = Tagesschluss, [PortfolioTrade.valueEstimated].
     *  - Tausch ohne Wert (z. B. Kraken Krypto↔Krypto): Wert = abgegebene Menge × Tagesschluss des abgegebenen
     *    Coins, sonst erhaltene Menge × Tagesschluss des erhaltenen Coins — Erlös des abgegebenen und Einstand des
     *    erhaltenen Coins, [PortfolioTrade.valueEstimated]. Mit erfasstem Wert unverändert.
     * Ohne Kurs bleibt der Wert unbekannt. Ohne solche Transaktionen dieselbe Liste.
     */
    fun valueAtDayClose(trades: List<PortfolioTrade>, price: ((String, Long) -> BigDecimal?)?): List<PortfolioTrade> {
        if (trades.none(::needsDayClose)) return trades
        fun close(coin: String, time: Long): BigDecimal? =
            price?.invoke(PortfolioCalculator.normalizeCoin(coin), time)?.takeIf { it.signum() > 0 }
        return trades.map { t ->
            when {
                !needsDayClose(t) -> t
                // Bewertet ist sie ein gewöhnlicher Verkauf (der Merker gilt nur für die Rohdaten, wie bis v4 der Preis 0)
                isFeeDisposal(t) -> t.copy(priceUsdt = close(t.coin, t.time), feeRow = false)
                t.type == PortfolioTxType.PAYMENT -> close(t.coin, t.time)?.let { t.copy(priceUsdt = it, valueEstimated = true) } ?: t
                else -> {
                    val value = close(t.coin, t.time)?.let { t.amount * it }
                        ?: t.swapToCoin?.let { to -> close(to, t.time)?.let { c -> t.toAmount?.multiply(c) } }
                    value?.let { t.copy(swapValueUsdt = it, valueEstimated = true) } ?: t
                }
            }
        }
    }

    /** Belohnung ohne Wert (Kraken-/Binance-Import kennen keinen Preis), gültige Menge — für [valueRewardsAtDayClose]. */
    fun rewardNeedsDayClose(t: PortfolioTrade): Boolean =
        t.type == PortfolioTxType.REWARD && t.validPrice == null && t.amount.signum() > 0

    /**
     * Nur Steuer-Export ([TaxEngines.disposals], [TaxExport.incomeRows]): Belohnung ohne Wert ([rewardNeedsDayClose]) zum
     * Tagesschluss ihres Tags [price] (> 0) — Wert je Coin = Kurs, [PortfolioTrade.valueEstimated] (Vermerk «Wert
     * geschätzt»). So zählen die Einkünfte, und der spätere Verkauf hat einen Einstand statt «unvollständig» aus der Summe
     * zu fallen. Ohne Kurs bleibt sie ohne Wert (Einkünfte und Verkauf «unvollständig», beides vermerkt). Karte und
     * Wertverlauf bleiben wie oben (Einstand ohne Wert, Zufluss zum Marktkurs). Ohne solche Belohnungen dieselbe Liste.
     */
    fun valueRewardsAtDayClose(trades: List<PortfolioTrade>, price: ((String, Long) -> BigDecimal?)?): List<PortfolioTrade> {
        if (price == null || trades.none(::rewardNeedsDayClose)) return trades
        return trades.map { t ->
            if (!rewardNeedsDayClose(t)) t
            else price(PortfolioCalculator.normalizeCoin(t.coin), t.time)?.takeIf { it.signum() > 0 }
                ?.let { t.copy(priceUsdt = it, valueEstimated = true) } ?: t
        }
    }

    /** Transaktion jeder Art (gültige Menge) mit Gebühr im dritten Coin ohne Preis — für [valueThirdFeesAtDayClose]. */
    fun thirdFeeNeedsDayClose(t: PortfolioTrade): Boolean =
        t.amount.signum() > 0 && t.thirdFeeCoin != null && t.thirdFeePrice == null

    /**
     * Nur Steuer-Export ([TaxEngines.disposals], [TaxEngines.outflows]), eine Regel für alle Arten: Gebühr im dritten Coin
     * ohne Preis ([thirdFeeNeedsDayClose]) zum Tagesschluss des Gebühr-Coins am UTC-Tag der Transaktion [price] (≥ 0) —
     * Gebühr-Preis = Kurs, [PortfolioTrade.feePriceEstimated]: Vermerk «Wert geschätzt» an der Zeile der Gebühr-Coins und an
     * jeder Zeile, deren Wert die Gebühr enthält (Erlös eines Verkaufs/Tauschs, Aufwand einer Auszahlung). Die Gebühr steckt
     * so im Einstand eines Zugangs bzw. einer Umbuchung und mindert den Erlös eines Verkaufs/Tauschs. Ohne Kurs unverändert;
     * die Karte rechnet ohne geschätzte Preise — beide «ohne Wert der Gebühr» ([CoinPosition.feeValueMissing]).
     */
    fun valueThirdFeesAtDayClose(trades: List<PortfolioTrade>, price: ((String, Long) -> BigDecimal?)?): List<PortfolioTrade> {
        if (price == null || trades.none(::thirdFeeNeedsDayClose)) return trades
        return trades.map { t ->
            val coin = t.thirdFeeCoin
            if (coin == null || !thirdFeeNeedsDayClose(t)) t
            else price(coin, t.time)?.takeIf { it.signum() >= 0 }
                ?.let { t.copy(feeCoinPrice = it, feePriceEstimated = true) } ?: t
        }
    }

    /** Bestand nach [e] (siehe oben), exakt; ungültige Menge ändert nichts. */
    fun holdingsAfter(holdings: BigDecimal, e: PortfolioLedgerEvent): BigDecimal {
        val t = e.trade
        if (t.amount.signum() <= 0) return holdings
        val h = holdings.max(BigDecimal.ZERO)
        if (e.feeLeg) return h - t.thirdFeeAmount.min(h)
        if (e.swapIn) return h + t.swapReceived
        return when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> h + t.received
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.GIFT, PortfolioTxType.WITHDRAWAL,
            PortfolioTxType.SWAP -> h - (t.amount + t.coinFee).min(h)
            PortfolioTxType.TRANSFER -> h - t.coinFee.min(h)
        }
    }

    /**
     * Braucht [e] einen Marktkurs SEINES Coins für [flow]? Auszahlung, Verschenkt/bezahlt, Einzahlung und Belohnung ohne Preis (auch
     * Adress-Einträge), Tausch ohne Wert (beide Seiten, je Kurs ihres Coins), Gebühr-Abgang ohne Gebühr-Preis,
     * Gebühr-Zeile ([isFeeDisposal]).
     */
    fun needsMarketPrice(e: PortfolioLedgerEvent): Boolean {
        val t = e.trade
        if (e.feeLeg) return t.thirdFeePrice == null
        if (t.type == PortfolioTxType.SWAP) return t.swapValue == null
        // Gebühr-Zeile: zum Tageskurs
        if (isFeeDisposal(t)) return true
        if (t.validPrice != null) return false
        return t.type == PortfolioTxType.WITHDRAWAL || t.type == PortfolioTxType.GIFT || t.type == PortfolioTxType.PAYMENT ||
            t.type == PortfolioTxType.DEPOSIT || t.type == PortfolioTxType.REWARD
    }

    /**
     * Braucht [e] den Marktkurs des GEBÜHR-Coins ([PortfolioTrade.thirdFeeCoin]) für [flow] (`feeMarketPrice`)? Gebühr
     * in einem dritten Coin ohne Preis, auf der Seite der Haupttransaktion (nicht der Zugang eines Tauschs).
     */
    fun needsFeeMarketPrice(e: PortfolioLedgerEvent): Boolean =
        !e.feeLeg && !e.swapIn && e.trade.thirdFeeCoin != null && e.trade.thirdFeePrice == null

    /**
     * Zufluss von [e] in USDT bei Bestand [holdingsBefore] (siehe oben); null = unbekannt.
     * [marketPrice] = Marktkurs des Coins zum Zeitpunkt von [e] (nur nötig, wenn [needsMarketPrice]);
     * [feeMarketPrice] = Marktkurs des Gebühr-Coins dann (nur nötig, wenn [needsFeeMarketPrice]). Gerechnet wird exakt
     * ([BigDecimal]: Menge × Kurs, Gebühren, Anteil) und erst das Ergebnis als Double zurückgegeben — Börsenkurse kommen
     * als Double und gehen über ihre kürzeste Darstellung ein. [method] = Einstandsmethode des Buchs
     * ([PortfolioHoldingsBook.method]): bestimmt den gedeckten Anteil eines Verkaufs/Tauschs über den Bestand hinaus
     * ([coveredFraction]).
     */
    fun flow(
        e: PortfolioLedgerEvent,
        holdingsBefore: BigDecimal,
        marketPrice: Double? = null,
        feeMarketPrice: Double? = null,
        method: CostMethod = CostMethod.DEFAULT,
    ): Double? = flowExact(e, holdingsBefore, marketPrice, feeMarketPrice, method)?.toDouble()

    /** [flow] exakt; null = unbekannt. */
    fun flowExact(
        e: PortfolioLedgerEvent,
        holdingsBefore: BigDecimal,
        marketPrice: Double? = null,
        feeMarketPrice: Double? = null,
        method: CostMethod = CostMethod.DEFAULT,
    ): BigDecimal? {
        val t = e.trade
        if (t.amount.signum() <= 0) return BigDecimal.ZERO
        val h = holdingsBefore.max(BigDecimal.ZERO)
        if (e.feeLeg) {
            // Ohne Gebühr-Preis zum Marktkurs des Gebühr-Coins (neutral wie eine Auszahlung ohne Preis)
            val feePrice = t.thirdFeePrice ?: exactPrice(marketPrice) ?: return null
            val removed = t.thirdFeeAmount.min(h)
            return if (removed.signum() > 0) (removed * feePrice).negate() else BigDecimal.ZERO
        }
        val market = exactPrice(marketPrice)
        if (e.swapIn) {
            // Zugang aus Tausch: zum Wert des Tauschs, sonst erhaltene Menge (vor Gebühr) × Marktkurs; ohne gültigen Zugang unbekannt
            if (t.swapToCoin == null) return null
            t.swapValue?.let { return it }
            return market?.let { m -> t.toAmount?.multiply(m) }
        }
        // Umbuchung ohne Bestand (in ihrer Quelle): wie im Rechner ([PortfolioFifo], Ø) ohne Wirkung — auch ihre Gebühren
        if (t.type == PortfolioTxType.TRANSFER && h.signum() <= 0) return BigDecimal.ZERO
        // Gebühr im dritten Coin ohne Preis: zum Marktkurs des Gebühr-Coins
        val feeValue = t.thirdFeeValue
            ?: exactPrice(feeMarketPrice)?.let { t.thirdFeeAmount * it }
            ?: return null
        // Gebühr-Zeile zum Marktkurs (Abfluss wie ein Verkauf, dessen Erlös das Portfolio verlässt)
        val price = if (isFeeDisposal(t)) market else t.validPrice ?: market?.takeIf { needsMarketPrice(e) }
        return when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                if (price == null) return null
                t.amount * price + t.quoteFee + feeValue
            }
            // Damit bezahlt: Wert verlässt das Portfolio wie ein Verkauf (ohne Marktwert zum Marktkurs)
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT -> {
                if (price == null) return null
                // Nichts mehr da: der Verkauf zählt nicht, auch nicht seine Gebühr — wie «realisiert»
                val share = coveredFraction(t, h, method) ?: return BigDecimal.ZERO
                partOf(t.amount * price - t.quoteFee - feeValue, share).negate()
            }
            // Verschenkt: wie eine Auszahlung (Wert verlässt das Portfolio zum Marktwert)
            PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                val share = withdrawnFraction(t, h) ?: return BigDecimal.ZERO
                if (price == null) return null
                // Wert und Gebühren zum gedeckten Anteil wie beim Verkauf (ungedeckter Rest: nichts abgegeben)
                partOf(t.amount * price - t.quoteFee - feeValue, share).negate()
            }
            PortfolioTxType.TRANSFER -> t.quoteFee + feeValue
            PortfolioTxType.SWAP -> {
                // Abgegebener Coin wie ein Verkauf: zum Wert des Tauschs, sonst zum Marktkurs; gedeckter Anteil wie dort
                val share = coveredFraction(t, h, method) ?: return BigDecimal.ZERO
                // Zugang kaputt (kein gültiger erhaltener Coin): unbekannt statt still verloren
                if (t.swapToCoin == null) return null
                val gross = t.swapValue ?: market?.let { t.amount * it } ?: return null
                partOf(gross - t.quoteFee - feeValue, share).negate()
            }
        }
    }

    /** Börsenkurs (≥ 0, endlich) exakt über seine kürzeste Darstellung; sonst null. */
    private fun exactPrice(price: Double?): BigDecimal? = price?.takeIf { it >= 0.0 && it.isFinite() }?.let { BigDecimal.valueOf(it) }

    /** [value] × Anteil [fraction] (ganz gedeckt: unverändert). */
    private fun partOf(value: BigDecimal, fraction: Double): BigDecimal =
        if (fraction == 1.0) value else value.multiply(BigDecimal.valueOf(fraction), PortfolioDecimal.MC)

    /**
     * Gedeckter Anteil (0…1] des ganzen Erlöses eines Verkaufs/Tauschs bei Bestand [holdings] — dieselbe Aufteilung wie
     * «realisiert» und Steuer: FIFO (beide) verbraucht zuerst die verkaufte Menge, dann die Gebühr-Coins ([PortfolioFifo]:
     * Erlös nach verkaufter Menge) → min(Menge, abgegeben) / Menge; Ø ([CostMethod.AVERAGE]) nach abgegebener Menge →
     * abgegeben / (Menge + Gebühr im Coin). Ganz gedeckt exakt 1; null = nichts abgegeben.
     */
    fun coveredFraction(t: PortfolioTrade, holdings: BigDecimal, method: CostMethod): Double? {
        val requested = t.amount + t.coinFee
        val removed = requested.min(holdings.max(BigDecimal.ZERO))
        if (removed.signum() <= 0 || requested.signum() <= 0) return null
        if (removed.compareTo(requested) == 0) return 1.0
        return if (method.isFifo) {
            t.amount.min(removed).divide(t.amount, PortfolioDecimal.MC).toDouble()
        } else {
            removed.divide(requested, PortfolioDecimal.MC).toDouble()
        }
    }

    /**
     * Gedeckter Anteil (0…1] einer Auszahlung bei Bestand [holdings]: abgegeben / (Menge + Gebühr im Coin) — wie die
     * ausgezahlte Menge im Rechner (anteilig); gilt auch für ihre Gebühren ([PortfolioFeeExpenses.withdrawal]). Ganz
     * gedeckt exakt 1; null = nichts abgegeben.
     */
    fun withdrawnFraction(t: PortfolioTrade, holdings: BigDecimal): Double? = withdrawnShare(t, holdings)?.let {
        if (it.compareTo(BigDecimal.ONE) == 0) 1.0 else it.toDouble()
    }

    /** [withdrawnFraction] exakt; null = nichts abgegeben. */
    fun withdrawnShare(t: PortfolioTrade, holdings: BigDecimal): BigDecimal? {
        val requested = t.amount + t.coinFee
        val removed = requested.min(holdings.max(BigDecimal.ZERO))
        if (removed.signum() <= 0 || requested.signum() <= 0) return null
        return if (removed.compareTo(requested) == 0) BigDecimal.ONE else removed.divide(requested, PortfolioDecimal.MC)
    }
}
