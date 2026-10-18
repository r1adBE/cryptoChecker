package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Lot
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Queue
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Slice
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.before
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.carry
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.consume
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.insert
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.spread

/**
 * FIFO-Buchhaltung eines Coins (reines Kotlin, Spiegel: `Shared/Portfolio/PortfolioFifo.swift`):
 *  - Kauf, Belohnung, Einzahlung: neuer Posten (erhaltene Menge, Einstand Menge × Preis + Gebühr USDT + Wert
 *    der Gebühr im dritten Coin, Erwerb, Id, Art). Ohne Preis: Posten mit unbekanntem Einstand.
 *  - Reihenfolge der Posten: nach Erwerb ([PortfolioTrade.acquisitionTime] — bei einer Einzahlung mit
 *    ursprünglichem Kaufdatum dieses), dann Zeit der Transaktion, dann Id. Eine Einzahlung von früher gekaufter
 *    Coins reiht sich also VOR jüngere Käufe ein; Verkäufe, Auszahlungen und Gebühren verbrauchen immer die
 *    ältesten ERWORBENEN Posten. Haltedauer ab dem Erwerb.
 *  - Verkauf (auch Damit bezahlt, [PortfolioTxType.PAYMENT], zum Marktwert): verbraucht Posten, älteste zuerst (Menge + Gebühr im Coin). Die Gebühr im Coin gehört zur Veräusserung:
 *    zuerst die verkaufte Menge, DANACH die Gebühr-Coins aus den nächsten Posten (FIFO in einem Zug). Erlös nur nach
 *    VERKAUFTER Menge verteilt; ein Teil aus Gebühr-Coins hat Erlös 0 und sein Einstand zählt als Kosten der Veräusserung
 *    (Verlust in DIESEM Posten mit dessen Haltedauer) — so stimmt die Aufteilung kurz-/langfristig (DE-Praxis: die
 *    Gebühr ist Teil der Veräusserung). Je Teil Gewinn = Erlösanteil − Einstand des Teils; Teile aus Posten ohne Kaufpreis (oder
 *    Verkauf ohne Preis) haben unbekannten Gewinn — die bekannten zählen, «realisiert unvollständig» wie bisher.
 *    Mehr verkauft als erfasst: Bestand und Kennzahlen gekappt (markiert, [PortfolioBookResult.oversold]); für
 *    den Steuer-Export wird der ungedeckte Rest trotzdem eine Veräusserung mit seinem Erlösanteil, ohne
 *    Kaufpreis ([PortfolioDisposal.uncovered]) — so fehlt kein Verkauf.
 *  - Auszahlung: verbraucht Posten, älteste zuerst, OHNE Gewinn (kein Verkauf, keine Veräusserung); Einstand
 *    der verbrauchten Posten in [PortfolioBookResult.withdrawals]. Gebühr USDT/dritter Coin: Aufwand ([PortfolioFeeExpenses]).
 *  - Umbuchung: nichts bewegt sich, Posten behalten ihr Datum. Netzwerkgebühr im Coin verbraucht Menge,
 *    älteste zuerst; deren bekannter Einstand geht auf den ältesten verbleibenden Posten MIT Kaufpreis über
 *    (Summe des Einstands bleibt wie beim Ø). Ohne solchen Posten (übrig nur Posten ohne Kaufpreis) geht er nicht still
 *    verloren: Er geht auf den nächsten später hinzukommenden Posten mit Kaufpreis, sonst [PortfolioLostFeeCost] /
 *    [CoinPosition.lostFeeCostUsdt] (kein realisierter Verlust). Begründung: Die Gebühr ist
 *    Teil der Kosten, die Coins zu halten bzw. zu verschieben — sie ist keine Veräusserung (kein Erlös) und
 *    kein Verlust im Jahr der Umbuchung, sondern erhöht den Einstand der übrigen Coins; sie landet beim
 *    ältesten Posten, damit sie mit dem nächsten Verkauf realisiert wird. Gebühr USDT + Wert der Gebühr
 *    im dritten Coin verteilen sich nach Menge auf alle Posten; der Anteil von Posten ohne Kaufpreis gehört
 *    zu deren unbekanntem Einstand (wie beim Ø) und wird als [PortfolioUnassignedFee] vermerkt, nicht still verworfen.
 *  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt ([PortfolioCostPart]: Erwerb,
 *    zugeschlagene Umbuchungsgebühr zum Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen);
 *    ein Abgang nimmt die Teile anteilig mit ([PortfolioDisposal.costParts]) — der Steuer-Export rechnet so jeden Teil
 *    zum Devisenkurs SEINES Datums um (Posten 2018 + Umbuchungsgebühr 2024: je zum eigenen Kurs). US-Übergang
 *    ([TaxEngines.usTransition]): Anfangsposten tragen nur ihr Erwerbsdatum (Steuerwährung USD, Kurs 1).
 *  - Gebühr im dritten Coin: im Gebühr-Coin eine Veräusserung (FIFO) zum Gebühr-Preis ([PortfolioDisposal.feeLeg]);
 *    Gewinn nur mit Gebühr-Preis bekannt. In der Haupttransaktion zählt ihr Wert zum Einstand (Kauf) bzw.
 *    mindert den Erlös (Verkauf).
 *  - Tausch ([PortfolioTxType.SWAP]): im abgegebenen Coin eine Veräusserung wie ein Verkauf (Menge + Gebühr im Coin,
 *    Erlös = Wert des Tauschs − Gebühr USDT − Wert der Gebühr im dritten Coin; Wert unbekannt → Erlös unbekannt); im
 *    erhaltenen Coin ein neuer Posten ([PortfolioTxType.SWAP]) mit erhaltener Menge (− Gebühr im erhaltenen Coin),
 *    Einstand = Wert des Tauschs (unbekannt → Posten ohne Kaufpreis), Erwerb = Zeit des Tauschs. Jede Gebühr zählt so
 *    genau einmal (wie Verkauf + Kauf mit Gebühr im erhaltenen Coin).
 *  - Je Wallet ([CostMethod.FIFO_WALLET], `perWallet`): je Wallet eine eigene Warteschlange ([PortfolioWallets]).
 *    Kauf, Belohnung, Einzahlung, Verkauf, Tausch, Auszahlung in der Wallet ihrer Börse; Gebühr-Abgang in der Wallet
 *    der Haupttransaktion. Umbuchung von A nach B: in A zuerst die Netzwerkgebühr (älteste Posten), dann die Menge;
 *    die verschobenen Teile kommen mit Kaufdatum und Einstand nach B; der Einstand der Netzwerkgebühr geht auf den
 *    ältesten verschobenen Teil mit Kaufpreis (sonst den ältesten in A, sonst in B), Gebühr USDT + Wert der Gebühr im dritten Coin
 *    nach Menge auf die verschobenen Teile. Reicht der Bestand einer Wallet nicht, wird NICHT aus anderen Wallets
 *    genommen: Verkauf/Tausch/Gebühr-Abgang → ungedeckter Rest ([PortfolioDisposal.uncovered]), Umbuchung → der Rest
 *    kommt nicht an; jedes Mal ein [PortfolioWalletShortfall] und [PortfolioBookResult.oversold].
 *  - Gebühr als Aufwand ([PortfolioFeeExpenses]): Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/
 *    Einzahlung bzw. erhaltener Coin eines Tauschs) → kein Posten, Aufwand realisiert; Gebühr USDT/dritter Coin einer
 *    Auszahlung → Aufwand realisiert.
 *  - Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet in der Warteschlange und geht auf den nächsten Posten MIT
 *    Kaufpreis, der später hinzukommt (Kauf, Tausch, Einzahlung, verschobener Posten); erst wenn bis zum Ende keiner
 *    kommt, steht er als [PortfolioLostFeeCost] da.
 *  - Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
 */
internal object PortfolioFifo {

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Kalendertage zwischen [from] und [to] in [zone] (Standard UTC — so im Feld `holdingDays` der
     * Veräusserungen, gleich wie iOS). Die Steuer-Liste ([TaxExport.gainRows]) rechnet mit derselben
     * Funktion in ihrer Zeitzone — beide Wege können sich nicht widersprechen.
     */
    fun holdingDays(from: Long, to: Long, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): Long =
        if (zone == java.time.ZoneOffset.UTC) {
            Math.floorDiv(to, DAY_MILLIS) - Math.floorDiv(from, DAY_MILLIS)
        } else {
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
                java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate(),
            )
        }

    /**
     * [seed]: Anfangsposten je Wallet ([PortfolioLot.wallet] = Anzeigename) vor den Ereignissen — US-Übergang
     * ([TaxEngines.usTransition]); sonst leer.
     */
    fun run(
        symbol: String,
        events: List<PortfolioLedgerEvent>,
        perWallet: Boolean = false,
        seed: List<PortfolioLot> = emptyList(),
    ): PortfolioBookResult {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val method = if (perWallet) CostMethod.FIFO_WALLET else CostMethod.FIFO
        val queues = LinkedHashMap<String, Queue>()
        val names = HashMap<String, String>()
        var realized = zero
        var unknownSold = zero
        var soldTotal = zero
        var withdrawn = zero
        var oversold = false
        var feeValueMissing = false
        var realizedFeeMissing = false
        val disposals = ArrayList<PortfolioDisposal>()
        val withdrawals = ArrayList<PortfolioWithdrawalCost>()
        val shortfalls = ArrayList<PortfolioWalletShortfall>()
        val lostFeeCosts = ArrayList<PortfolioLostFeeCost>()
        val unassignedFees = ArrayList<PortfolioUnassignedFee>()

        /** Umbuchungsgebühr-Anteil [usdt] auf Bestand ohne Kaufpreis: vermerkt ([PortfolioUnassignedFee]). */
        fun unassign(t: PortfolioTrade, usdt: BigDecimal) {
            if (usdt.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, usdt)
        }

        /** Warteschlange der Wallet [raw] (universell immer dieselbe). */
        fun queue(raw: String?): Queue {
            val key = if (perWallet) PortfolioWallets.key(raw) else PortfolioWallets.UNSPECIFIED
            if (perWallet && key !in names) names[key] = raw?.trim().orEmpty()
            return queues.getOrPut(key) { Queue(key) }
        }

        /** Anzeigename der Wallet von [q] (nur je Wallet, sonst null). */
        fun walletOf(q: Queue): String? = if (perWallet) names[q.key] ?: PortfolioWallets.UNSPECIFIED else null

        /** Abgang [requested] grösser als der Bestand von [q]: markiert, je Wallet mit Fehlmenge. */
        fun check(q: Queue, requested: BigDecimal, t: PortfolioTrade, feeLeg: Boolean) {
            if (requested <= q.held) return
            oversold = true
            if (perWallet) {
                shortfalls += PortfolioWalletShortfall(symbol, walletOf(q) ?: PortfolioWallets.UNSPECIFIED, t.id, t.time, t.type, feeLeg, requested - q.held.max(zero))
            }
        }

        /**
         * Veräusserung: Erlös [proceeds] (null = unbekannt) des ganzen Abgangs nach Menge auf die Teile [slices] und
         * den ungedeckten Rest [uncovered] verteilt; [sold] = verkaufte Menge ohne Gebühr im Coin, gedeckter Teil
         * (für «unbekannt verkauft»).
         */
        fun dispose(
            t: PortfolioTrade,
            q: Queue,
            slices: List<Slice>,
            uncovered: BigDecimal,
            proceeds: BigDecimal?,
            sold: BigDecimal,
            feeLeg: Boolean,
            feeMissing: Boolean,
            swap: Boolean = false,
            /** Je Teil die VERKAUFTE Menge (ohne Gebühr-Coins), Erlös nur danach; null = nach Menge (alles verkauft). */
            soldParts: List<BigDecimal>? = null,
            /** Verkaufte Menge im ungedeckten Rest (mit [soldParts]). */
            uncoveredSold: BigDecimal = uncovered,
        ) {
            val removed = slices.fold(zero) { acc, s -> acc + s.amount }
            val total = removed + uncovered
            if (total.signum() <= 0) return
            val wallet = walletOf(q)
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            val gift = !feeLeg && t.fromGift
            val payment = !feeLeg && t.type == PortfolioTxType.PAYMENT && !t.fromGift
            val estimated = !feeLeg && t.valueEstimated
            val weights = soldParts ?: slices.map { it.amount }
            val weightTotal = weights.fold(zero) { acc, w -> acc + w } + (if (soldParts != null) uncoveredSold else uncovered)
            var proceedsLeft = proceeds
            // Letzter Teil MIT Gewicht bekommt den Rest: Summe der Anteile = Erlös, exakt
            val lastWeighted = when {
                (if (soldParts != null) uncoveredSold else uncovered).signum() > 0 -> slices.size
                else -> weights.indexOfLast { it.signum() > 0 }
            }
            slices.forEachIndexed { i, s ->
                val w = weights[i]
                val share = proceeds?.let {
                    when {
                        w.signum() <= 0 -> zero
                        i == lastWeighted -> proceedsLeft ?: it.multiply(w).divide(weightTotal, mc)
                        else -> it.multiply(w).divide(weightTotal, mc)
                    }
                }
                if (share != null) proceedsLeft = proceedsLeft?.minus(share)
                val gain = if (share != null && s.cost != null) share - s.cost else null
                if (gain != null) {
                    realized += gain
                } else if (soldParts != null) {
                    unknownSold += w
                } else {
                    unknownSold += if (s.amount.compareTo(removed) == 0) sold else sold.multiply(s.amount).divide(removed, mc)
                }
                disposals += PortfolioDisposal(
                    coin = symbol, sellTxId = t.id, sellTime = t.time,
                    lotTxId = s.lot.txId, acquiredTime = s.lot.acquired, lotKind = s.lot.kind,
                    amount = s.amount, costUsdt = s.cost, proceedsUsdt = share, gainUsdt = gain,
                    holdingDays = holdingDays(s.lot.acquired, t.time),
                    feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                    costParts = s.parts,
                    wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
                )
            }
            if (uncovered.signum() > 0) {
                // Mehr abgegeben als erfasst: Erlös bekannt, Kaufpreis fehlt (nicht im realisierten Gewinn)
                disposals += PortfolioDisposal(
                    coin = symbol, sellTxId = t.id, sellTime = t.time,
                    lotTxId = null, acquiredTime = null, lotKind = null,
                    amount = uncovered, costUsdt = null, proceedsUsdt = proceedsLeft, gainUsdt = null,
                    holdingDays = null, feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                    uncovered = true, wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
                )
            }
        }

        /** Gebühr als Aufwand ([PortfolioFeeExpenses]): realisiert −Aufwand, eine Veräusserung mit Erlös 0. */
        fun expense(e: PortfolioLedgerEvent, q: Queue, kind: PortfolioFeeExpense, x: PortfolioFeeExpenses.Expense?) {
            if (x == null) return
            realized -= x.usdt
            if (x.feeValueMissing) feeValueMissing = true
            disposals += PortfolioFeeExpenses.disposal(symbol, e, kind, x, method, walletOf(q))
        }

        // Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet
        for (l in seed) {
            if (l.amount.signum() > 0) insert(queue(l.wallet), Lot(l.txId, l.acquiredTime, l.acquiredTime, l.kind, l.amount, l.costUsdt))
        }

        for (e in events) {
            val t = e.trade
            val amount = t.amount
            if (amount.signum() <= 0) continue
            if (e.feeLeg) {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                val q = queue(PortfolioWallets.of(e))
                val fee = t.thirdFeeAmount
                check(q, fee, t, feeLeg = true)
                val slices = consume(q, fee)
                val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                dispose(t, q, slices, fee - removed, t.thirdFeePrice?.let { fee * it }, removed, feeLeg = true, feeMissing = false)
                soldTotal += removed
                continue
            }
            if (e.swapIn) {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
                // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
                val received = t.swapReceived
                if (received.signum() <= 0) {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                insert(queue(t.exchange), Lot(t.id, t.time, t.time, PortfolioTxType.SWAP, received, t.swapValue))
                continue
            }
            // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt ([PortfolioLedger.salePrice])
            val price = PortfolioLedger.salePrice(t)
            val feeValue = t.thirdFeeValue
            when (t.type) {
                PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                    val received = t.received
                    if (received.signum() <= 0) {
                        // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
                        expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                        continue
                    }
                    val cost = price?.let { amount * it + t.quoteFee + (feeValue ?: zero) }
                    if (price != null && feeValue == null) feeValueMissing = true
                    insert(queue(t.exchange), Lot(t.id, t.acquisitionTime, t.time, t.type, received, cost))
                }
                PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                    // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                    val swap = t.type == PortfolioTxType.SWAP
                    val q = queue(t.exchange)
                    val requested = amount + t.coinFee
                    check(q, requested, t, feeLeg = false)
                    val slices = consume(q, requested)
                    val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                    // Zuerst die verkaufte Menge, danach die Gebühr-Coins (FIFO in einem Zug): verkaufter Teil je Posten
                    var before = zero
                    val soldParts = slices.map { s ->
                        val part = (amount - before).max(zero).min(s.amount)
                        before += s.amount
                        part
                    }
                    val sold = soldParts.fold(zero) { acc, p -> acc + p }
                    // Erlös des ganzen Abgangs (Tausch: sein Wert) nur nach verkaufter Menge verteilt (Gebühr-Coins: Erlös 0)
                    // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                    val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { amount * it }
                    val proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }
                    val feeMissing = gross != null && feeValue == null
                    if (feeMissing && removed.signum() > 0) realizedFeeMissing = true
                    if (feeMissing) feeValueMissing = true
                    dispose(
                        t, q, slices, requested - removed, proceeds, sold, feeLeg = false, feeMissing = feeMissing, swap = swap,
                        soldParts = soldParts, uncoveredSold = amount - sold,
                    )
                    soldTotal += sold
                }
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                    // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
                    val q = queue(t.exchange)
                    val requested = amount + t.coinFee
                    check(q, requested, t, feeLeg = false)
                    if (q.held.signum() <= 0) continue
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                    expense(e, q, PortfolioFeeExpense.WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, q.held))
                    val slices = consume(q, requested)
                    val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                    val sold = if (removed.compareTo(requested) == 0) amount else removed.multiply(amount).divide(requested, mc)
                    withdrawn += sold
                    withdrawals += PortfolioWithdrawalCost(
                        trade = t, sold = sold, removed = removed,
                        pricedRemoved = slices.filter { it.cost != null }.fold(zero) { acc, s -> acc + s.amount },
                        cost = slices.fold(zero) { acc, s -> acc + (s.cost ?: zero) },
                    )
                }
                PortfolioTxType.TRANSFER -> {
                    val src = queue(t.transferFrom)
                    val dst = queue(t.transferTo)
                    val coinFee = t.coinFee
                    if (src !== dst) {
                        // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
                        check(src, amount + coinFee, t, feeLeg = false)
                        if (src.held.signum() <= 0) continue
                        // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
                        val carried = consume(src, coinFee)
                        val moved = consume(src, amount).map { s ->
                            Lot(s.lot.txId, s.lot.acquired, s.lot.time, s.lot.kind, s.amount, s.cost, s.parts)
                        }
                        // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil
                        // ohne Kaufpreis wird vermerkt
                        if (feeValue == null && moved.any { it.cost != null }) feeValueMissing = true
                        unassign(t, spread(moved, t.quoteFee + (feeValue ?: zero), moved.fold(zero) { acc, l -> acc + l.amount }, t.time))
                        // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
                        // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
                        val target = moved.firstOrNull { it.cost != null } ?: src.lots.firstOrNull { it.cost != null }
                            ?: dst.lots.firstOrNull { it.cost != null }
                        carry(symbol, target, if (moved.isEmpty()) src else dst, t, carried)
                        moved.forEach { insert(dst, it) }
                        continue
                    }
                    val q = src
                    val h = q.held
                    if (h.signum() <= 0) continue
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der Anteil der
                    // Posten ohne Kaufpreis wird vermerkt
                    if (feeValue == null && q.lots.any { it.cost != null }) feeValueMissing = true
                    unassign(t, spread(q.lots, t.quoteFee + (feeValue ?: zero), h, t.time))
                    // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten
                    // Posten mit Kaufpreis über (Begründung oben: Kosten des Haltens, keine Veräusserung)
                    if (coinFee > h) check(q, coinFee, t, feeLeg = false)
                    if (coinFee.signum() > 0) {
                        val carried = consume(q, coinFee)
                        carry(symbol, q.lots.firstOrNull { it.cost != null }, q, t, carried)
                    }
                }
            }
        }

        var priced = zero
        var unpriced = zero
        var cost = zero
        val outLots = ArrayList<PortfolioLot>()
        val openParts = ArrayList<PortfolioCostPart>()
        // Gebühr-Einstand, für den bis zum Ende kein Posten mit Kaufpreis kam: vermerkt (zeitlich)
        queues.values.flatMapTo(lostFeeCosts) { it.pendingLost }
        lostFeeCosts.sortWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId })
        for (q in queues.values) {
            for (l in q.lots) {
                val lotCost = l.cost
                if (lotCost != null) { priced += l.amount; cost += lotCost; openParts += l.parts } else unpriced += l.amount
                outLots += PortfolioLot(symbol, l.txId, l.acquired, l.kind, l.amount, l.cost, walletOf(q))
            }
        }
        return PortfolioBookResult(
            priced = priced, unpriced = unpriced, cost = cost, realized = realized,
            unknownSold = unknownSold, soldTotal = soldTotal, withdrawn = withdrawn, oversold = oversold,
            feeValueMissing = feeValueMissing, realizedFeeMissing = realizedFeeMissing,
            lots = outLots,
            disposals = disposals,
            withdrawals = withdrawals,
            shortfalls = shortfalls,
            lostFeeCosts = lostFeeCosts,
            unassignedFees = unassignedFees,
            openParts = openParts,
        )
    }
}
