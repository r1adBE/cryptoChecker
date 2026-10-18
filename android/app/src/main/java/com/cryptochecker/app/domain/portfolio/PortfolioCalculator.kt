package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Buchhaltung je Coin nach der Einstandsmethode [CostMethod]: FIFO (Standard) in [PortfolioFifo], dort beschrieben;
 * Durchschnittskosten ([CostMethod.AVERAGE], «Ø») wie folgt:
 *  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten — dazu die Gebühren ANDERER
 *    Transaktionen, die in diesem Coin bezahlt wurden ([PortfolioLedger.events], «Gebühr-Abgang»).
 *  - Gerechnet wird exakt mit [BigDecimal] ([PortfolioDecimal]): Bestand, Einstand, Gebühren und
 *    realisierter Gewinn summieren sich ohne Gleitkomma-Fehler (1 Satoshi = 0.00000001 BTC bleibt
 *    1 Satoshi). Nur Teilungen (Ø, anteiliger Verkauf) runden auf 34 gültige Stellen.
 *  - Kauf, Belohnung, Einzahlung mit Preis: Einstand += Menge × Preis + Gebühr USDT + Wert der Gebühr im
 *    dritten Coin, Bestand mit Preis += erhaltene Menge (Menge − Gebühr im Coin). Ø = Einstand / Bestand
 *    mit Preis. Ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
 *  - Verkauf (auch Damit bezahlt, [PortfolioTxType.PAYMENT], zum Marktwert): Bestand sinkt um Menge + Gebühr im Coin (nie unter 0; ein Verkauf grösser als der
 *    Bestand wird gekappt und markiert). Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt.
 *    Realisiert += Anteil MIT Kaufpreis × (Menge × Verkaufspreis − Gebühr USDT − Wert der Gebühr im
 *    dritten Coin) − Einstand des abgegebenen Teils, wenn der Verkaufspreis bekannt ist. Der Anteil ohne
 *    Kaufpreis (und Verkäufe ohne Preis) hat unbekannten Gewinn/Verlust und zählt separat
 *    ([CoinPosition.realizedUnknownAmount]).
 *  - Auszahlung: wie ein Verkauf abgegeben (Menge + Gebühr im Coin, anteilig, Einstand zum Ø), aber KEIN
 *    realisierter Gewinn. Gebühr USDT + Wert der Gebühr im dritten Coin: realisierter Aufwand ([PortfolioFeeExpenses],
 *    Steuer-Export «Gebühr Auszahlung»; mehr ausgezahlt als vorhanden: zum gedeckten Anteil) — wie im Zeitraum, wo der
 *    Abfluss um die Gebühr kleiner ist.
 *  - Gebühr-Zeile ([PortfolioLedger.isFeeDisposal], CSV «Fee»): Verkauf zum Tageskurs ([summarize] bewertet sie mit
 *    `withdrawalPrice`); ohne Kurs Erlös unbekannt (Menge in [CoinPosition.realizedUnknownAmount]).
 *  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung, erhaltener Coin eines Tauschs): kein
 *    Bestand, der Einstand bzw. Wert des Tauschs ist realisierter Aufwand ([PortfolioFeeExpenses]).
 *  - Umbuchung: Menge und Einstand bleiben. Gebühr im Coin (Netzwerk) verringert nur die Menge (beide
 *    Teile anteilig); ihr Einstand bleibt im Rest — der Ø steigt (bleibt kein Teil mit Kaufpreis: vermerkt in
 *    [CoinPosition.lostFeeCostUsdt]). Gebühr USDT + Wert der Gebühr im dritten
 *    Coin erhöhen den Einstand des Teils mit Kaufpreis (Anteil mit Preis / Bestand; der Rest gehört zum
 *    unbekannten Einstand und wird als [PortfolioUnassignedFee] vermerkt — ohne Teil mit Kaufpreis die ganze Gebühr).
 *  - Gebühr-Abgang im Gebühr-Coin (z. B. BNB): Bestand sinkt um die Gebühr (gekappt, markiert), beide
 *    Teile anteilig mit ihrem Einstand — wie eine Veräusserung: realisiert += Anteil mit Kaufpreis ×
 *    Gebühr × Gebühr-Preis − abgegebener Einstand. Ohne Gebühr-Preis: Menge als «unbekannter Gewinn».
 *  - Tausch: im abgegebenen Coin wie ein Verkauf mit Erlös = Wert des Tauschs − Gebühr USDT − Wert der Gebühr im
 *    dritten Coin (Wert unbekannt: Gewinn unbekannt); im erhaltenen Coin wie ein Kauf: Einstand += Wert des Tauschs,
 *    Bestand += erhaltene Menge − Gebühr im erhaltenen Coin (Wert unbekannt: Teil «ohne Preis»).
 *  - Gebühr im dritten Coin ohne Preis: Die Haupttransaktion bekommt keine Gebühr in Einstand/Erlös und
 *    wird markiert ([CoinPosition.feeValueMissing], beim Verkauf auch [CoinPosition.realizedFeeMissing]).
 *  - Gewinn/Verlust je Coin nur, wenn der ganze Restbestand einen Kaufpreis hat. Die Summe
 *    ([PortfolioSummary.unrealized]) zählt nur Coins mit Einstand und Kurs.
 *  - Aktueller Kurs nach der Stablecoin-Regel ([PortfolioStables]): USDT immer 1, andere
 *    Stablecoins ihr Marktkurs, ohne Kurs kein Wert (wie jeder Coin: «—», «ohne Kurs»).
 */
object PortfolioCalculator {

    /** Darunter gilt eine Menge als null. */
    const val EPS = 1e-12

    fun normalizeCoin(coin: String): String = coin.trim().uppercase()

    /**
     * Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins zählen nur mit ihren Gebühren in diesem Coin)
     * nach [method]. Adress-Guthaben ([AddressBalance.isAddressTrade]) zählen nur als eigener Teil mit
     * ([CoinPosition.addressHoldingsExact]).
     */
    fun position(
        coin: String,
        trades: List<PortfolioTrade>,
        currentPrice: Double?,
        method: CostMethod = CostMethod.DEFAULT,
    ): CoinPosition = ledger(coin, trades, currentPrice, method).position

    /**
     * Wie [position], dazu offene Posten (nur FIFO) und Veräusserungen für den Steuer-Export.
     * Adress-Guthaben: Einträge mit negativer Id ([AddressBalance.withAddresses]) sind KEINE Posten — ihr
     * Guthaben steht als eigener Teil in [CoinPosition.addressHoldingsExact]: Menge und Wert zählen mit,
     * Einstand, Ø, «Seit Kauf», realisiert und Steuer nur aus den eigenen Transaktionen.
     */
    fun ledger(
        coin: String,
        trades: List<PortfolioTrade>,
        currentPrice: Double?,
        method: CostMethod = CostMethod.DEFAULT,
        /** Marktkurs für Gebühr-Zeilen (Tageskurs, wie [summarize] `withdrawalPrice`); null = Erlös unbekannt. */
        feePrice: ((coin: String, time: Long) -> Double?)? = null,
    ): CoinLedger {
        val symbol = normalizeCoin(coin)
        val (real, address) = AddressBalance.split(feeValued(trades, feePrice))
        return ledgerOf(symbol, PortfolioLedger.events(symbol, real), address[symbol], currentPrice, method)
    }

    /**
     * Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs [price] (> 0, endlich), sonst
     * unbekannt ([PortfolioLedger.valueAtDayClose]).
     */
    private fun feeValued(trades: List<PortfolioTrade>, price: ((String, Long) -> Double?)?): List<PortfolioTrade> =
        PortfolioLedger.valueAtDayClose(trades, price?.let { f ->
            { coin: String, time: Long -> f(coin, time)?.takeIf { it > 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) } }
        })

    /** [ledger] aus den schon gesammelten Schritten [events] des Coins (ohne Adress-Einträge). */
    private fun ledgerOf(
        symbol: String,
        events: List<PortfolioLedgerEvent>,
        addressAmount: BigDecimal?,
        currentPrice: Double?,
        method: CostMethod,
    ): CoinLedger {
        val book = when (method) {
            CostMethod.FIFO -> PortfolioFifo.run(symbol, events)
            CostMethod.FIFO_WALLET -> PortfolioFifo.run(symbol, events, perWallet = true)
            CostMethod.AVERAGE -> average(symbol, events)
        }
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val priced = book.priced
        val unpriced = book.unpriced
        val cost = book.cost

        val ledgerHoldings = (priced + unpriced).let { if (PortfolioDecimal.isDust(it)) zero else it }
        val addressHoldings = addressAmount?.takeIf { it.signum() > 0 } ?: zero
        val holdings = ledgerHoldings + addressHoldings
        // Stablecoin-Regel: USDT = 1, andere Stablecoins Marktkurs, sonst kein Wert ([PortfolioStables])
        val price = PortfolioStables.price(symbol, currentPrice)
        val open = holdings.signum() > 0
        val ledgerOpen = ledgerHoldings.signum() > 0
        val priceMissing = ledgerOpen && unpriced.signum() > 0
        val avgCost = if (priced.signum() > 0) cost.divide(priced, mc).toDouble() else null
        val costBasisExact = if (ledgerOpen && !priceMissing) cost else null
        val costBasis = costBasisExact?.toDouble()
        // Einstand des Teils mit Kaufpreis: «Seit Kauf» auch dann, wenn nur ein Teil ohne Kaufpreis ist
        val pricedHoldings = if (ledgerOpen) priced else zero
        val pricedCostExact = if (pricedHoldings.signum() > 0) cost else null
        // Wert und unrealisierter Gewinn exakt (Kurs über seine kürzeste Darstellung); Double nur für die Anzeige.
        // Wert = ganzer Bestand (mit Adressen), «Seit Kauf» nur auf den eigenen Teil mit Kaufpreis.
        val priceExact = price?.let { PortfolioDecimal.of(it) }
        val valueExact = priceExact?.let { holdings * it }
        val value = valueExact?.toDouble()
        // Ganz mit Kaufpreis: ganzer eigener Bestand (wie bisher, Staub inklusive), sonst nur der Teil mit Kaufpreis
        val pricedValueExact = priceExact?.let { (if (priceMissing) pricedHoldings else ledgerHoldings) * it }
        val unrealizedExact = if (pricedCostExact != null && pricedValueExact != null) pricedValueExact - pricedCostExact else null
        val unrealized = unrealizedExact?.toDouble()
        val unrealizedPercent = if (unrealizedExact != null && pricedCostExact != null && pricedCostExact.signum() > 0) {
            unrealizedExact.divide(pricedCostExact, mc).toDouble() * 100.0
        } else null

        val position = CoinPosition(
            coin = symbol,
            holdings = holdings.toDouble(),
            avgCost = avgCost,
            costBasis = costBasis,
            currentPrice = price,
            value = value,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = book.realized.toDouble(),
            priceMissing = priceMissing,
            oversold = book.oversold,
            tradeCount = events.count { !it.feeLeg },
            realizedUnknownAmount = if (PortfolioDecimal.isDust(book.unknownSold)) 0.0 else book.unknownSold.toDouble(),
            soldAmount = if (PortfolioDecimal.isDust(book.soldTotal)) 0.0 else book.soldTotal.toDouble(),
            holdingsExact = holdings,
            costBasisExact = costBasisExact,
            realizedExact = book.realized,
            pricedHoldingsExact = pricedHoldings,
            valueExact = valueExact,
            unrealizedExact = unrealizedExact,
            feeValueMissing = book.feeValueMissing,
            realizedFeeMissing = book.realizedFeeMissing,
            withdrawnAmount = if (PortfolioDecimal.isDust(book.withdrawn)) 0.0 else book.withdrawn.toDouble(),
            addressHoldingsExact = addressHoldings,
            addressValueExact = priceExact?.let { addressHoldings * it },
            method = method,
            lostFeeCostUsdt = book.lostFeeCosts.fold(zero) { acc, l -> acc + l.costUsdt },
            unassignedFeeUsdt = book.unassignedFees.fold(zero) { acc, f -> acc + f.usdt },
            // Aus denselben Veräusserungen wie der Steuer-Export: ein Teil ohne Einstand/Erlös → unvollständig
            disposalsIncomplete = book.disposals.any { it.costUsdt == null || it.proceedsUsdt == null || it.feeValueMissing },
            pricedCostExact = pricedCostExact,
        )
        // Offene Posten für die Anzeige: FIFO je Posten, Ø zusammengefasst (Teil mit / ohne Kaufpreis)
        val openLots = if (method.isFifo) {
            book.lots.map { PortfolioOpenLot(symbol, it.txId, it.acquiredTime, it.kind, it.amount, it.costUsdt, it.wallet, aggregated = false) }
        } else {
            listOfNotNull(
                PortfolioOpenLot(symbol, null, null, null, priced, cost, null, aggregated = true).takeIf { priced.signum() > 0 && ledgerOpen },
                PortfolioOpenLot(symbol, null, null, null, unpriced, null, null, aggregated = true).takeIf { unpriced.signum() > 0 && ledgerOpen },
            )
        }
        return CoinLedger(
            position, book.lots, book.disposals, book.withdrawals, openLots, book.shortfalls, book.lostFeeCosts, book.unassignedFees,
        )
    }

    /**
     * Offene Posten eines Coins nach [method] ([PortfolioOpenLot]): FIFO und FIFO je Wallet die tatsächlichen Posten
     * (Kaufdatum, Restmenge, Rest-Einstand, Wallet; auch ohne Kaufpreis), Ø zusammengefasst. Ohne Adress-Guthaben.
     */
    fun openLots(coin: String, trades: List<PortfolioTrade>, method: CostMethod = CostMethod.DEFAULT): List<PortfolioOpenLot> =
        ledger(coin, trades, null, method).openLots

    /**
     * [CostMethod.FIFO_WALLET]: alle Abgänge über den Bestand ihrer Wallet ([PortfolioWalletShortfall]), zeitlich, dann
     * Id, Coin. Leer bei den anderen Methoden. Für den Hinweis «Bestand in … reicht nicht – fehlt eine Umbuchung?».
     */
    fun walletShortfalls(trades: List<PortfolioTrade>, method: CostMethod = CostMethod.FIFO_WALLET): List<PortfolioWalletShortfall> {
        if (method != CostMethod.FIFO_WALLET) return emptyList()
        val real = AddressBalance.split(trades).first
        return PortfolioLedger.eventsByCoin(real).entries
            .flatMap { (coin, events) -> PortfolioFifo.run(coin, events, perWallet = true).shortfalls }
            .sortedWith(compareBy<PortfolioWalletShortfall> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /**
     * Alle Veräusserungen (Steuer-Export) nach [method]: zeitlich (Verkauf), dann Id des Verkaufs, Coin; je
     * Verkauf die Posten in FIFO-Reihenfolge. Adress-Guthaben kommen nicht vor.
     */
    fun disposals(trades: List<PortfolioTrade>, method: CostMethod = CostMethod.DEFAULT): List<PortfolioDisposal> {
        val real = AddressBalance.split(trades).first
        // Schritte je Coin einmal gesammelt (nicht je Coin über alle Transaktionen)
        val byCoin = PortfolioLedger.eventsByCoin(real)
        return byCoin.entries
            .flatMap { (coin, events) -> ledgerOf(coin, events, null, null, method).disposals }
            .sortedWith(compareBy<PortfolioDisposal> { it.sellTime }.thenBy { it.sellTxId }.thenBy { it.coin })
    }

    /** Wertänderung ausgebuchter Coins bis zur Auszahlung: bekannter Teil und ob etwas fehlt. */
    data class WithdrawnGain(val amount: BigDecimal, val incomplete: Boolean, val count: Int)

    /**
     * Wertänderung ausgebuchter Coins: je Auszahlung (Wert beim Abgang − Einstand der verbrauchten Posten bzw.
     * zum Ø). Wert beim Abgang = ausgezahlte Menge × Preis der Auszahlung (sonst [marketPrice], Kurs zum
     * Zeitpunkt). Gebühr USDT und Wert der Gebühr im dritten Coin zählen NICHT hier, sondern als realisierter
     * Aufwand ([PortfolioFeeExpenses]); zusammen ergibt das den Abfluss im Wertverlauf ([PortfolioLedger.flow]).
     * Gezählt wird nur der Teil mit Kaufpreis (Wert anteilig); fehlt ein Teil oder ein Kurs:
     * [WithdrawnGain.incomplete]. Diese Wertänderung steckt in der Kurve des Wertverlaufs (Kursbewegung, solange
     * gehalten), aber nicht in «Seit Kauf» und «realisiert».
     */
    fun withdrawnGain(
        withdrawals: List<PortfolioWithdrawalCost>,
        marketPrice: ((coin: String, time: Long) -> Double?)? = null,
    ): WithdrawnGain {
        var sum = BigDecimal.ZERO
        var incomplete = false
        var count = 0
        for (w in withdrawals) {
            if (w.removed.signum() <= 0) continue
            count++
            val t = w.trade
            val price = t.validPrice
                ?: marketPrice?.invoke(normalizeCoin(t.coin), t.time)?.takeIf { it >= 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) }
            if (price == null || w.pricedRemoved.signum() <= 0) { incomplete = true; continue }
            if (w.pricedRemoved < w.removed) incomplete = true
            // Gebühren: realisierter Aufwand, nicht hier
            val value = w.sold * price
            val pricedValue = if (w.pricedRemoved.compareTo(w.removed) == 0) value
            else value.multiply(w.pricedRemoved).divide(w.removed, PortfolioDecimal.MC)
            sum += pricedValue - w.cost
        }
        return WithdrawnGain(sum, incomplete, count)
    }

    /**
     * Eigener Bestand je Coin (Grossschreibung) aus den Transaktionen mit Zeit ≤ [time] nach [method] — dieselben
     * Mengen wie [CoinPosition.ledgerHoldingsExact] der Karte ([PortfolioHoldingsBook]: FIFO je Wallet kappt Abgänge
     * auf die Wallet, ungedeckter Rest nimmt nichts aus anderen Wallets). Adress-Einträge zählen nicht; Staub
     * ([PortfolioDecimal.isDust]) = 0. Nur Coins mit Bestand > 0, Reihenfolge wie [PortfolioLedger.coins].
     */
    fun holdingsAt(
        trades: List<PortfolioTrade>,
        time: Long = Long.MAX_VALUE,
        method: CostMethod = CostMethod.DEFAULT,
    ): Map<String, BigDecimal> {
        val real = trades.filter { it.time <= time && !AddressBalance.isAddressTrade(it.id) }
        val out = LinkedHashMap<String, BigDecimal>()
        for ((coin, events) in PortfolioLedger.eventsByCoin(real)) {
            val book = PortfolioHoldingsBook(method)
            events.forEach { book.apply(it) }
            if (book.total.signum() > 0 && !PortfolioDecimal.isDust(book.total)) out[coin] = book.total
        }
        return out
    }

    /**
     * Gesamtsicht nach [method]; [prices] = aktueller USDT-Kurs je Coin (Grossschreibung). Adress-Guthaben
     * zählen im Gesamtwert, aber nicht in Einstand und «Seit Kauf» — ein Coin nur aus Adressen fehlt dort
     * ohne «ohne Kaufpreis»-Hinweis ([missingCostCoins] nennt nur eigene Bestände ohne Kaufpreis).
     */
    fun summarize(
        trades: List<PortfolioTrade>,
        prices: Map<String, Double>,
        method: CostMethod = CostMethod.DEFAULT,
        /**
         * Kurs eines Coins zum Zeitpunkt einer Auszahlung ohne Preis ([withdrawnGain]) bzw. einer Gebühr-Zeile
         * ([PortfolioLedger.valueFeeDisposals]); null = nur erfasste Preise.
         */
        withdrawalPrice: ((coin: String, time: Long) -> Double?)? = null,
    ): PortfolioSummary {
        // Gebühr-Zeilen zum Tageskurs (gleicher Kurs wie der Abfluss im Verlauf); ohne Kurs Erlös unbekannt
        val trades = feeValued(trades, withdrawalPrice)
        // Auch Coins, die nur als Gebühr-Coin vorkommen (Bestand 0, ggf. «mehr als vorhanden»).
        // Schritte je Coin einmal gesammelt (statt je Coin über alle Transaktionen)
        val (real, address) = AddressBalance.split(trades)
        val byCoin = PortfolioLedger.eventsByCoin(real)
        val ledgers = PortfolioLedger.coins(trades)
            .map { coin -> ledgerOf(coin, byCoin[coin].orEmpty(), address[coin], prices[coin], method) }
        val positions = ledgers.map { it.position }
        val withdrawn = withdrawnGain(ledgers.flatMap { it.withdrawals }, withdrawalPrice)

        val open = positions.filter { it.isOpen }
            .sortedWith(
                compareByDescending<CoinPosition> { it.value ?: -1.0 }.thenBy { it.coin }
            )
        // Ganz verkaufte Positionen bleiben sichtbar, auch wenn der Gewinn/Verlust 0 oder unbekannt ist
        val closed = positions.filter {
            !it.isOpen && (it.realizedExact.signum() != 0 || it.soldAmount > 0.0 || it.withdrawnAmount > 0.0)
        }
            .sortedBy { it.coin }

        // Exakt summiert (Wert je Coin als BigDecimal), Double nur für die Anzeige
        val totalValue = open.fold(BigDecimal.ZERO) { acc, p -> acc + (p.valueExact ?: BigDecimal.ZERO) }.toDouble()
        val missingCurrent = open.filter { it.value == null }.map { it.coin }
        // Nur eigene Bestände ohne Kaufpreis; reine Adress-Guthaben haben keinen Einstand und fehlen nicht.
        // Ganz ohne Kaufpreis: fehlt in «Seit Kauf»; nur ein Teil ohne: zählt mit dem Teil mit Kaufpreis.
        val missingCost = open.filter { it.pricedCostExact == null && it.ledgerHoldingsExact.signum() > 0 }.map { it.coin }
        val partialCost = open.filter { it.priceMissing && it.pricedCostExact != null && it.unpricedHoldingsExact.signum() > 0 }
            .map { PortfolioUnpricedPart(it.coin, it.unpricedHoldingsExact) }
        val withCost = open.filter { it.costBasis != null }
        val investedExact = if (withCost.isEmpty()) null else withCost.fold(BigDecimal.ZERO) { acc, p -> acc + (p.costBasisExact ?: BigDecimal.ZERO) }
        val invested = investedExact?.toDouble()
        // Gewinn seit Kauf über die Teile mit Kaufpreis der Coins mit Kurs; die übrigen nennt der Hinweis
        // («ohne 1 Coin (kein Kaufpreis)», «ohne 0.5 BTC ohne Kaufpreis», «Kein aktueller Kurs für …»).
        // Prozent auf den Einstand dieser Teile.
        val included = open.filter { it.unrealizedExact != null && it.pricedCostExact != null }
        // Exakt summiert (Einstand und Gewinn je Coin als BigDecimal), Double nur für die Anzeige
        val includedCost = included.fold(BigDecimal.ZERO) { acc, p -> acc + (p.pricedCostExact ?: BigDecimal.ZERO) }
        val unrealizedExact = if (included.isEmpty()) null
        else included.fold(BigDecimal.ZERO) { acc, p -> acc + (p.unrealizedExact ?: BigDecimal.ZERO) }
        val unrealized = unrealizedExact?.toDouble()
        val unrealizedPercent = if (unrealizedExact != null && includedCost.signum() > 0) {
            unrealizedExact.divide(includedCost, PortfolioDecimal.MC).toDouble() * 100.0
        } else null

        val realizedExact = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.realizedExact }
        return PortfolioSummary(
            open = open,
            closed = closed,
            totalValue = totalValue,
            invested = invested,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = realizedExact.toDouble(),
            costMissing = missingCost.isNotEmpty() || partialCost.isNotEmpty(),
            missingCurrentPrices = missingCurrent,
            missingCostCoins = missingCost,
            realizedIncomplete = positions.any { it.realizedIncomplete },
            investedExact = investedExact,
            realizedExact = realizedExact,
            feeValueMissingCoins = positions.filter { it.feeValueMissing }.map { it.coin }.sorted(),
            addressValue = open.fold(BigDecimal.ZERO) { acc, p -> acc + (p.addressValueExact ?: BigDecimal.ZERO) }.toDouble(),
            method = method,
            withdrawnGainExact = withdrawn.amount.takeIf { withdrawn.count > 0 && (it.signum() != 0 || !withdrawn.incomplete) },
            withdrawnGainIncomplete = withdrawn.incomplete,
            partialCostParts = partialCost,
            unassignedFeeUsdt = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.unassignedFeeUsdt },
        )
    }
}
