package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

// FR: Erwerbspreis-Summe (PTA) über das ganze Portfolio — Regeln in [TaxEngines].

fun TaxEngines.frPta(
    real: List<PortfolioTrade>,
    priceAt: ((String, Long) -> BigDecimal?)?,
    outflows: MutableList<TaxOutflow>,
    /** Adress-Einträge (beobachtete Adressen, «im Portfolio zählen»): Teil des Gesamtwerts, keine Abtretungen. */
    addressTrades: List<PortfolioTrade> = emptyList(),
): List<PortfolioDisposal> {
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC
    val books = LinkedHashMap<String, PortfolioHoldingsBook>()
    // Guthaben beobachteter Adressen je Coin bis zum laufenden Zeitpunkt (Einzahlung +, Auszahlung −, nie unter 0)
    val addrSorted = addressTrades.filter { it.amount.signum() > 0 }
        .sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })
    val addr = LinkedHashMap<String, BigDecimal>()
    var ai = 0
    fun advanceAddresses(time: Long) {
        while (ai < addrSorted.size && addrSorted[ai].time <= time) {
            val a = addrSorted[ai++]
            val c = PortfolioCalculator.normalizeCoin(a.coin)
            val d = if (a.type == PortfolioTxType.WITHDRAWAL) a.amount.negate() else a.amount
            addr[c] = ((addr[c] ?: zero) + d).max(zero)
        }
    }
    var pta = zero
    var ptaParts: List<PortfolioCostPart> = emptyList()
    var incomplete = false
    val out = ArrayList<PortfolioDisposal>()
    val events = mergedEvents(real)

    // Der zuletzt gerechnete Gesamtwert enthielt Guthaben beobachteter Adressen (für [PortfolioDisposal.addressValueIncluded])
    var valueHadAddresses = false

    /**
     * Gesamtwert aller Bestände unmittelbar vor [time] — eigene Bestände und Guthaben beobachteter Adressen (zum selben
     * Kurs: erfasst, Stablecoin, sonst priceAt); [coin] zu [price]; null = ein Kurs fehlt (Einstand leer, unvollständig).
     * Setzt [valueHadAddresses].
     */
    fun portfolioValue(coin: String, price: BigDecimal?, time: Long): BigDecimal? {
        var value: BigDecimal? = zero
        valueHadAddresses = false
        val coins = LinkedHashSet<String>(books.keys)
        coins += addr.keys
        for (c in coins) {
            val q = (books[c]?.total ?: zero) + (addr[c] ?: zero)
            if (q.signum() <= 0 || PortfolioDecimal.isDust(q)) continue
            if ((addr[c] ?: zero).signum() > 0) valueHadAddresses = true
            val p = when {
                c == coin -> price
                !PortfolioStables.needsQuote(c) -> BigDecimal.ONE
                else -> priceAt?.invoke(c, time)?.takeIf { it.signum() >= 0 }
            }
            value = if (p == null || value == null) null else value + q.multiply(p, mc)
        }
        return value
    }

    /** Kürzt die PTA um den Anteil [part] / [value] (gekappt auf 1); Rückgabe = Kürzung samt Herkunft. */
    fun reduce(part: BigDecimal, value: BigDecimal): Pair<BigDecimal, List<PortfolioCostPart>> {
        val f = part.divide(value, mc).min(BigDecimal.ONE)
        val c = if (f.compareTo(BigDecimal.ONE) == 0) pta else pta.multiply(f, mc)
        val parts = scaleParts(ptaParts, pta, c)
        ptaParts = scaleParts(ptaParts, pta, pta - c)
        pta -= c
        return c to parts
    }

    var i = 0
    while (i < events.size) {
        val t = events[i].second.trade
        var j = i
        while (j < events.size && events[j].second.trade.id == t.id) j++
        val coin = PortfolioCalculator.normalizeCoin(t.coin)
        advanceAddresses(t.time)
        if (t.amount.signum() > 0) {
            val price = t.validPrice
            val feeValue = t.thirdFeeValue
            // Kurs des Haupt-Coins für Gebühr und Abgang: Preis, Wert des Tauschs je Coin, Stablecoin 1, sonst priceAt
            val unit = price ?: t.swapValue?.divide(t.amount, mc)
                ?: BigDecimal.ONE.takeIf { !PortfolioStables.needsQuote(coin) }
                ?: priceAt?.invoke(coin, t.time)?.takeIf { it.signum() >= 0 }
            // Wert der Gebühr-Abtretungen dieser Transaktion: der Gesamtwert danach ist um so viel kleiner
            var spent = zero

            /**
             * Gebühr in Krypto ausserhalb eines Verkaufs: eigene Abtretung gegen eine Dienstleistung
             * (BOI-RPPM-PVBMC-30-10 § 70 Remarque 1) — Abtretungspreis = Wert der Gebühr, keine weiteren Gebühren.
             */
            fun feeCession(feeCoin: String, qty: BigDecimal, p: BigDecimal?, feeLeg: Boolean) {
                if (qty.signum() <= 0) return
                val held = books[feeCoin]?.total ?: zero
                // Nur der gedeckte Anteil ist eine Abtretung mit Einstand (wie im Verkaufspfad): eine Gebühr in einem nie
                // erfassten Coin darf die PTA nicht kürzen und keinen Gewinn ergeben
                val covered = qty.min(held.max(zero))
                if (covered.signum() > 0) {
                    val coveredGross = p?.let { covered.multiply(it, mc) }
                    val value = portfolioValue(feeCoin, p, t.time)?.let { it - spent }
                    val withAddresses = value != null && valueHadAddresses
                    val wasIncomplete = incomplete
                    var cost: BigDecimal? = null
                    var parts: List<PortfolioCostPart> = emptyList()
                    if (coveredGross != null && value != null && value.signum() > 0) {
                        val (c, pp) = reduce(coveredGross, value)
                        cost = c
                        parts = pp
                    } else {
                        incomplete = true
                    }
                    if (coveredGross != null) spent += coveredGross
                    out += PortfolioDisposal(
                        coin = feeCoin, sellTxId = t.id, sellTime = t.time,
                        lotTxId = null, acquiredTime = null, lotKind = null,
                        amount = covered, costUsdt = cost, proceedsUsdt = coveredGross,
                        gainUsdt = if (coveredGross != null && cost != null) coveredGross - cost else null,
                        holdingDays = null, feeLeg = feeLeg, feeValueMissing = false,
                        method = CostMethod.AVERAGE, costParts = parts,
                        costIncomplete = wasIncomplete || cost == null, portfolioValueUsdt = value, grossProceedsUsdt = coveredGross,
                        addressValueIncluded = withAddresses,
                    )
                }
                if (covered < qty) {
                    // Rest ohne erfassten Bestand: Wert bekannt, Einstand unbekannt — zählt nicht in der Summe, PTA unverändert
                    val rest = qty - covered
                    val restGross = p?.let { rest.multiply(it, mc) }
                    out += PortfolioDisposal(
                        coin = feeCoin, sellTxId = t.id, sellTime = t.time,
                        lotTxId = null, acquiredTime = null, lotKind = null,
                        amount = rest, costUsdt = null, proceedsUsdt = restGross, gainUsdt = null,
                        holdingDays = null, feeLeg = feeLeg, feeValueMissing = false,
                        method = CostMethod.AVERAGE, uncovered = true,
                        costIncomplete = true, grossProceedsUsdt = restGross,
                    )
                }
            }

            /** Gebühr im dritten Coin (ausser beim Verkauf): eigene Abtretung zum erfassten Preis bzw. priceAt. */
            fun thirdFeeCession() {
                val tc = t.thirdFeeCoin ?: return
                feeCession(tc, t.thirdFeeAmount, t.thirdFeePrice ?: priceAt?.invoke(tc, t.time)?.takeIf { it.signum() >= 0 }, true)
            }
            when (t.type) {
                PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                    // Gebühr im dritten Coin: Abtretung vor dem Zugang (Gesamtwert ohne den neuen Coin)
                    thirdFeeCession()
                    if (price != null) {
                        val c = t.amount * price + t.quoteFee + (feeValue ?: zero)
                        pta += c
                        if (c.signum() != 0) ptaParts = ptaParts + PortfolioCostPart(t.acquisitionTime, c)
                    } else {
                        // Ohne Preis (z. B. Einzahlung ohne Kaufpreis): zum Wert 0 (BOFiP § 90), aber unvollständig
                        incomplete = true
                    }
                }
                // Damit bezahlt: Abtretung zum Marktwert wie ein Verkauf (Zahlung mit Krypto = cession à titre onéreux)
                PortfolioTxType.SELL, PortfolioTxType.PAYMENT -> {
                    val held = books[coin]?.total ?: zero
                    // Eine Operation (BOFiP § 50): Preis vor Gebühren = (Menge + Gebühr im Coin) × Preis + Wert der Gebühr
                    // im dritten Coin; Gebühren = Gebühr USDT + dieselben Gebühr-Werte → Erlös = Menge × Preis − Gebühr USDT
                    val gross = price?.let { (t.amount + t.coinFee) * it + (feeValue ?: zero) }
                    val net = price?.let { t.amount * it - t.quoteFee }
                    // Über den erfassten Bestand hinaus: nur der gedeckte Anteil ist eine Abtretung mit Einstand
                    val requested = t.amount + t.coinFee
                    val removed = requested.min(held.max(zero))
                    val share = when {
                        removed.signum() <= 0 -> zero
                        removed.compareTo(requested) == 0 -> BigDecimal.ONE
                        else -> removed.divide(requested, mc)
                    }
                    fun part(v: BigDecimal?): BigDecimal? = v?.let { if (share.compareTo(BigDecimal.ONE) == 0) it else it.multiply(share, mc) }
                    val grossCovered = part(gross)
                    val netCovered = part(net)
                    val feeMissing = gross != null && feeValue == null
                    if (removed.signum() > 0) {
                        // Gesamtwert unmittelbar vor der Abtretung (verkaufter Coin zum Verkaufspreis)
                        val value = portfolioValue(coin, price, t.time)
                        val withAddresses = value != null && valueHadAddresses
                        val wasIncomplete = incomplete
                        var cost: BigDecimal? = null
                        var parts: List<PortfolioCostPart> = emptyList()
                        if (grossCovered != null && value != null && value.signum() > 0) {
                            val (c, p) = reduce(grossCovered, value)
                            cost = c
                            parts = p
                        } else {
                            // Formel nicht anwendbar: die Erwerbspreis-Summe danach ist nicht mehr gesichert
                            incomplete = true
                        }
                        out += PortfolioDisposal(
                            coin = coin, sellTxId = t.id, sellTime = t.time,
                            lotTxId = null, acquiredTime = null, lotKind = null,
                            amount = removed, costUsdt = cost, proceedsUsdt = netCovered,
                            gainUsdt = if (netCovered != null && cost != null) netCovered - cost else null,
                            holdingDays = null, feeLeg = false, feeValueMissing = feeMissing,
                            method = CostMethod.AVERAGE, costParts = parts,
                            // Ohne Gesamtwert (Kurs fehlt) ist schon diese Abtretung unvollständig
                            costIncomplete = wasIncomplete || cost == null, portfolioValueUsdt = value, grossProceedsUsdt = grossCovered,
                            addressValueIncluded = withAddresses,
                        )
                    }
                    if (removed < requested) {
                        // Rest ohne erfassten Bestand: Erlös bekannt, Einstand unbekannt — zählt nicht in der Summe
                        out += PortfolioDisposal(
                            coin = coin, sellTxId = t.id, sellTime = t.time,
                            lotTxId = null, acquiredTime = null, lotKind = null,
                            amount = requested - removed, costUsdt = null,
                            proceedsUsdt = net?.let { it - (netCovered ?: zero) }, gainUsdt = null,
                            holdingDays = null, feeLeg = false, feeValueMissing = feeMissing,
                            method = CostMethod.AVERAGE, uncovered = true,
                            costIncomplete = true, grossProceedsUsdt = gross?.let { it - (grossCovered ?: zero) },
                        )
                    }
                }
                // Verschenkt = Abtretung «à titre gratuit»: keine Veräusserung, PTA anteilig gekürzt wie die Auszahlung
                PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                    // Gebühren in Krypto zuerst als eigene Abtretungen
                    feeCession(coin, t.coinFee, unit, false)
                    thirdFeeCession()
                    // Abgang ohne Verkauf (Schenkung, Verlust, unbekannt): PTA anteilig kürzen (BOFiP § 100,
                    // «à titre gratuit»), Wert = Menge × Kurs (erfasst, Stablecoin oder priceAt)
                    val qty = t.amount
                    val v = unit?.let { qty.multiply(it, mc) }
                    val value = portfolioValue(coin, unit, t.time)?.let { it - spent }
                    var reduction: BigDecimal? = null
                    var reductionParts: List<PortfolioCostPart> = emptyList()
                    if (v != null && value != null && value.signum() > 0) {
                        val (c, parts) = reduce(v, value)
                        reduction = c
                        reductionParts = parts
                    } else {
                        // Kurs fehlt: Kürzung unbekannt, die PTA danach nicht mehr gesichert
                        incomplete = true
                    }
                    outflows += TaxOutflow(coin, t.id, t.time, qty, v, reduction, reductionParts)
                }
                // Tausch Krypto↔Krypto: aufgeschoben (sursis d'imposition), PTA unverändert — auch in einen Stablecoin
                // (USDT, USDC …): er bleibt im Gesamtwert, steuerbar erst sein Verkauf gegen Euro. Gebühren in Krypto
                // (abgegebener bzw. dritter Coin) sind eigene Abtretungen
                // Umbuchung zwischen eigenen Wallets: bleibt im Portfolio — Netzwerkgebühr (Coin, dritter Coin) dito
                PortfolioTxType.SWAP, PortfolioTxType.TRANSFER -> {
                    feeCession(coin, t.coinFee, unit, false)
                    thirdFeeCession()
                }
            }
        }
        for (k in i until j) {
            val (c, e) = events[k]
            books.getOrPut(c) { PortfolioHoldingsBook(CostMethod.FIFO) }.apply(e)
        }
        i = j
    }
    return out
}

/**
 * FR: Coins mit eigenem Bestand unmittelbar vor [t] in der Reihenfolge der Rechnung ([PortfolioLedger.ORDER]: Zeit, bei
 * gleicher Zeit Zugänge vor Abgängen, dann Id) — genau die Bestände, die [frPta] im Gesamtwert vor [t] zählt. Für die
 * Kursabfrage des Exports: ein Kauf derselben Millisekunde, den die Rechnung davor bucht, braucht ebenfalls einen Kurs
 * (eine Abfrage «bis [t] − 1 ms» liesse ihn aus, der Gesamtwert bliebe leer). [own] ohne Adress-Einträge.
 */
fun TaxEngines.frHeldBefore(own: List<PortfolioTrade>, t: PortfolioTrade): Set<String> {
    val key = PortfolioLedgerEvent(t, false)
    val before = own.filter { it.time <= t.time && PortfolioLedger.ORDER.compare(PortfolioLedgerEvent(it, false), key) < 0 }
    return PortfolioCalculator.holdingsAt(before, Long.MAX_VALUE, CostMethod.FIFO).keys
}

/**
 * [TaxMethod.FR_PTA]: Abgang ohne Verkauf (Auszahlung) — abgegebene Menge (ohne Gebühr: die ist eine Abtretung), Wert beim Abgang und
 * Kürzung der Erwerbspreis-Summe (USDT); null = Kurs fehlt (Kürzung unbekannt). [ptaReductionParts] = Herkunft der Kürzung
 * (Anteil der Erwerbspreis-Summe je Kaufdatum, Summe = [ptaReductionUsdt]) — in der Steuerwährung zum Kurs der Kaufdaten
 * wie der Einstand einer Abtretung, nicht zum Kurs des Abgangs.
 */
data class TaxOutflow(
    val coin: String,
    val txId: Long,
    val time: Long,
    val amount: BigDecimal,
    val valueUsdt: BigDecimal?,
    val ptaReductionUsdt: BigDecimal?,
    val ptaReductionParts: List<PortfolioCostPart> = emptyList(),
)
