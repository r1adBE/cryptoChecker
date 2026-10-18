package com.cryptochecker.app.domain.portfolio

/**
 * Gewinn/Verlust über einen Zeitraum für den Gesamtwert-Kopf des Portfolio-Tabs («Gewinn 7 T»,
 * Zeile «Gewinn 1 J», «Gewinn heute») und das Portfolio-Widget (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioPeriodChange.swift`).
 *
 * [gain] kennt die Transaktionen im Zeitraum:
 *   Gewinn = Wert am Ende − Wert am Anfang − Zuflüsse im Zeitraum,
 * Zuflüsse nach [PortfolioLedger.flow]: Käufe zu ihrem Einstand (Menge × Preis + Gebühr USDT + Wert der
 * Gebühr im dritten Coin) − Verkäufe zu ihrem Erlös (Menge × Preis − Gebühren); Belohnungen und
 * Einzahlungen wie Käufe (Wert beim Erhalt bzw. Preis), Auszahlungen wie Verkäufe (zum erfassten Preis,
 * sonst zum MARKTKURS ihres Zeitpunkts: «7 T»/«1 J» Tagesschluss ihres UTC-Tags — nach dem letzten Schluss
 * der aktuelle Kurs —, «Heute» der aktuelle Kurs; ohne Marktkurs fehlt der Coin), Umbuchungen neutral (nur ihre Gebühren in USDT/drittem Coin), Gebühr-Abgänge im
 * Gebühr-Coin wie ein Verkauf zum Gebühr-Preis. Ein Kauf in der Woche zählt also nicht als Gewinn, ein
 * Verkauf nicht als Verlust; eine Gebühr im Coin verringert die Menge und damit den Wert am Ende. Ist ein
 * Zufluss unbekannt (Kauf/Verkauf ohne Preis; ohne Marktkurs des Tags auch die Fälle unten),
 * fehlt der Coin im Zeitraum und steht in [TodayChange.skipped]. Prozent = Gewinn ÷ (Wert am Anfang + Zugänge im
 * Zeitraum). Einzahlung, Belohnung und Zugang aus Tausch ohne Wert sowie eine Gebühr im dritten Coin ohne Preis
 * zählen zum MARKTKURS ihres Zeitpunkts (neutral wie eine Auszahlung ohne Preis) — eine einzelne Buchung ohne Preis
 * lässt den Coin also nicht mehr aus «Heute/7 T/1 J» fallen. Belohnungen im Zeitraum (Wert
 * beim Erhalt) stehen zusätzlich in [TodayChange.rewardsUsd] — für «Belohnungen +X» unter der Zahl.
 * Wert am Anfang = Bestand am Stichtag (Transaktionen bis Ende des UTC-Tags) × Schluss der
 * UTC-Tageskerze am Kalendertag [DAYS] Tage vor heute oder der letzte davor — EINE Tagesgrenze (UTC) wie der
 * Wertverlauf ([PortfolioHistory.build]): ein Kauf um 00:30 Zürich-Sommerzeit (22:30 UTC) gehört in beiden zum Vortag.
 *
 * [change] ist die reine Kursänderung des HEUTIGEN Bestands (Σ Menge × (Kurs jetzt − Schluss am
 * Stichtag)); ohne Käufe/Verkäufe im Zeitraum sind beide gleich.
 *
 * Ein Coin ohne Kurs am Anfang fehlt, die übrigen zählen (wie «Seit Kauf» und der Wertverlauf): Braucht
 * ein Coin den Kurs am Anfang (Bestand am Stichtag oder Transaktion ohne Preis im Zeitraum; ausser USDT)
 * und fehlen seine Kerzen, passen sie nicht zum aktuellen Kurs ([PricePlausibility.closesMatchLive]) oder
 * beginnen sie erst nach dem Stichtag, fällt nur dieser Coin heraus und steht in [TodayChange.skipped].
 * Stablecoins ohne Schluss am Stichtag gelten 1, solange ihr aktueller Kurs höchstens 0.1 % von 1
 * abweicht ([PortfolioStables.fallbackWithoutClose]). Coins ohne aktuellen Kurs fehlen wie im Gesamtwert.
 * null nur, wenn kein Coin zählt. Bestände werden exakt summiert (wie die Position); nur die Bewertung
 * mit Börsenkursen ist Gleitkomma.
 *
 * Adress-Guthaben: Adress-Einträge in [trades] ([AddressBalance.withAddresses]) zählen NICHT (nie
 * rückwirkend). Nur die beobachtete Reihe [AddressBalanceHistory] (Parameter `addresses`) zählt — ab ihrer
 * ersten Beobachtung, Änderungen als Zu-/Abfluss zum Marktkurs (wie oben). Für «Heute» muss der Bestand
 * des Aufrufers ([todayFromCoinChanges]) dazu passen: eigener Bestand ([CoinPosition.ledgerHoldingsExact])
 * plus zuletzt beobachtetes Guthaben ([AddressBalanceHistory.latestByCoin]) — ohne Reihe nur der eigene.
 */
object PortfolioPeriodChange {

    /** «7 T»: so viele Tage vor heute liegt der Stichtag. */
    const val DAYS = 7

    /** «1 J» (Zeile unter dem Gesamtwert, wie das Widget mit Zeitraum «1 J»): Stichtag vor so vielen Tagen. */
    const val YEAR_DAYS = 365

    /**
     * @param holdings Menge je offenem Coin (Grossschreibung)
     * @param current aktuelle USDT-Kurse je Coin (Stablecoin-Regel schon angewandt, siehe [PortfolioCalculator])
     * @param closes Tagesschluss in USDT je Coin und UTC-Tag
     * @param baseDay Stichtag (Tage seit 1970-01-01), z. B. heute − [DAYS]
     * @return Veränderung in USDT und Prozent; null = unbekannt
     */
    fun change(
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        closes: Map<String, Map<Long, Double>>,
        baseDay: Long,
    ): TodayChange? {
        var then = 0.0
        var now = 0.0
        var counted = 0
        val skipped = ArrayList<String>()
        for ((rawCoin, amount) in holdings) {
            if (!(amount > PortfolioCalculator.EPS) || amount.isInfinite()) continue
            val coin = PortfolioCalculator.normalizeCoin(rawCoin)
            val live = (current[coin] ?: current[rawCoin])?.takeIf { it > 0.0 && it.isFinite() } ?: continue
            // Kurs am Anfang unbekannt: nur dieser Coin fehlt
            val past = startClose(coin, live, closes, baseDay)
            if (past == null) {
                skipped += coin
                continue
            }
            then += amount * past
            now += amount * live
            counted++
        }
        if (counted == 0) return null
        val change = now - then
        return TodayChange(change, if (then > PortfolioCalculator.EPS) change / then * 100.0 else null, skipped.sorted())
    }

    /**
     * «Heute» als Kursänderung des heutigen Bestands aus der Veränderung je Coin [coinChanges] (gleiche
     * %-Basis): Kurs am Anfang = Kurs ÷ (1 + %/100). Coins ohne Veränderung fehlen ganz — weder im Betrag
     * noch in der Basis (kein flach aufgefüllter Kurs, der das Prozent verwässert) — und stehen in
     * [TodayChange.skipped]; USDT zählt 1 → 1. Danach [todayAdjusted] für Käufe/Verkäufe: so entsteht
     * EINE Zahl für Karte, Widget und Portfolio-Alarm. null = kein Coin mit Veränderung.
     */
    fun todayFromCoinChanges(
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        coinChanges: Map<String, Double>,
    ): TodayChange? {
        var then = 0.0
        var now = 0.0
        var counted = 0
        val skipped = ArrayList<String>()
        for ((rawCoin, amount) in holdings) {
            if (!(amount > PortfolioCalculator.EPS) || amount.isInfinite()) continue
            val coin = PortfolioCalculator.normalizeCoin(rawCoin)
            val live = (current[coin] ?: current[rawCoin])?.takeIf { it > 0.0 && it.isFinite() } ?: continue
            val start = if (!PortfolioStables.needsQuote(coin)) {
                1.0
            } else {
                val pct = (coinChanges[coin] ?: coinChanges[rawCoin])?.takeIf { it.isFinite() && it > -100.0 }
                if (pct == null) {
                    skipped += coin
                    continue
                }
                live / (1.0 + pct / 100.0)
            }
            then += amount * start
            now += amount * live
            counted++
        }
        if (counted == 0) return null
        val change = now - then
        return TodayChange(change, if (then > PortfolioCalculator.EPS) change / then * 100.0 else null, skipped.sorted())
    }

    /**
     * «24h» je Position (Zeilen im Portfolio-Tab und im Widget): MEIN Gewinn/Verlust auf diesem Coin seit
     * [windowStart] — dieselbe Rechnung wie «Gewinn heute» ([todayFromCoinChanges] + [todayAdjusted]), für
     * jeden offenen Coin allein: Wert jetzt − Wert am Anfang − Zuflüsse seither (ein Kauf heute zählt zu
     * seinem Preis, kein Gewinn; ein Verkauf heute zu seinem Erlös). Prozent = Betrag ÷ (Wert am Anfang +
     * Zugänge). Die Summe über alle Coins ist «Gewinn heute». Fehlt der Kurs am Anfang (keine Veränderung,
     * ausser ganz im Zeitraum gekauft), der aktuelle Kurs oder ein Zufluss (Transaktion ohne Preis), fehlt
     * der Coin in der Karte («—»).
     * @param holdings Menge je offenem Coin; Ergebnis je Coin in Grossschreibung, Betrag in USDT
     */
    fun todayByCoin(
        holdings: Map<String, Double>,
        trades: List<PortfolioTrade>,
        current: Map<String, Double>,
        coinChanges: Map<String, Double>,
        windowStart: Long,
        addresses: List<AddressBalanceHistory> = emptyList(),
        method: CostMethod = CostMethod.DEFAULT,
    ): Map<String, TodayChange> {
        val out = HashMap<String, TodayChange>()
        for ((rawCoin, amount) in holdings) {
            if (!(amount > PortfolioCalculator.EPS) || amount.isInfinite()) continue
            val coin = PortfolioCalculator.normalizeCoin(rawCoin)
            // Ohne Veränderung: 0 mit «fehlt» — zählt nur, wenn der Coin ganz im Zeitraum dazukam
            val raw = todayFromCoinChanges(mapOf(coin to amount), current, coinChanges)
                ?: TodayChange(0.0, null, listOf(coin))
            val adjusted = todayAdjusted(raw, trades, current, coinChanges, windowStart, only = coin, addresses = addresses, method = method) ?: continue
            if (coin in adjusted.skipped || !adjusted.amountUsd.isFinite()) continue
            out[coin] = adjusted
        }
        return out
    }

    private const val DAY_MILLIS = 86_400_000L

    /** Beginn des Zeitraums für [baseDay]: Ende des UTC-Tags (Schluss der Tageskerze). */
    fun windowStart(baseDay: Long): Long = (baseDay + 1) * DAY_MILLIS

    /**
     * Gewinn/Verlust seit dem Stichtag [baseDay], der Käufe und Verkäufe kennt (siehe oben).
     * @param trades alle Transaktionen
     * @param current aktuelle USDT-Kurse je Coin (Stablecoin-Regel schon angewandt)
     * @param closes Tagesschluss in USDT je Coin und UTC-Tag
     * @param fx Umrechnung in die Anzeigewährung je Tag ([PortfolioHistoryFx.rateFunction]: Tag → Kurs USD → Währung,
     *   ab heute der aktuelle); null = USDT. Dann wie der umgerechnete Wertverlauf ([PortfolioHistoryFx.convert]): Wert
     *   am Anfang zum Kurs des Stichtags, jeder Zufluss (und die Belohnungen) zum Kurs seines UTC-Tags, Wert jetzt zum
     *   heutigen Kurs, Prozent auf die ebenso umgerechnete Basis — so zeigen Kopf und Kurve dieselbe «7 T»-Zahl (inkl.
     *   Devisenkurs). Ergebnis dann in der Anzeigewährung.
     * @return Gewinn in USDT (bzw. mit [fx] in der Anzeigewährung) und Prozent; null = unbekannt
     */
    fun gain(
        trades: List<PortfolioTrade>,
        current: Map<String, Double>,
        closes: Map<String, Map<Long, Double>>,
        baseDay: Long,
        addresses: List<AddressBalanceHistory> = emptyList(),
        /** Einstandsmethode: Bestände wie die Karte ([PortfolioHoldingsBook]). */
        method: CostMethod = CostMethod.DEFAULT,
        fx: ((day: Long) -> Double)? = null,
    ): TodayChange? {
        // Kauf/Verkauf ohne Preis im Zeitraum: Zufluss unbekannt → Coin auslassen («—»-Hinweis), statt ihn
        // zum Anfangskurs anzunehmen (ergab oft eine glatte 0 und liess die USDT-Gebühr weg)
        val parts = parts(
            trades, current, windowStart(baseDay), addresses = addresses, method = method,
            marketPrice = { coin, live, time -> marketClose(coin, live, closes, PortfolioHistory.epochDayOfUtcMillis(time)) },
            rates = fx?.let { rate ->
                Rates(rate(baseDay), rate(Long.MAX_VALUE)) { time -> rate(PortfolioHistory.epochDayOfUtcMillis(time)) }
            } ?: Rates.NONE,
        ) { coin, live ->
            startClose(coin, live, closes, baseDay)
        }
        return parts.result()
    }

    /**
     * «Gewinn heute»: die Kursänderung des heutigen Bestands [change] (USDT, aus der Momentaufnahme)
     * um die Käufe und Verkäufe seit [windowStart] berichtigt — gleiche Regel wie [gain]. Kurs am
     * Anfang je Coin aus der Kursveränderung [coinChanges] (gleiche %-Basis): Kurs ÷ (1 + %/100).
     * Coins ohne diesen Kurs fehlen wie in der Kursänderung (ausser ein ganz im Zeitraum gekaufter
     * Coin: Wert jetzt − Einstand). Ein Kauf/Verkauf ohne Preis im Zeitraum lässt den Coin ganz fehlen
     * (wie «7 T»: Zufluss unbekannt) — er kommt in [TodayChange.skipped], seine Kursänderung fällt aus
     * dem Betrag. Ohne Transaktionen im Zeitraum unverändert. [only]: nur dieser Coin ([todayByCoin]).
     */
    fun todayAdjusted(
        change: TodayChange?,
        trades: List<PortfolioTrade>,
        current: Map<String, Double>,
        coinChanges: Map<String, Double>,
        windowStart: Long,
        only: String? = null,
        addresses: List<AddressBalanceHistory> = emptyList(),
        method: CostMethod = CostMethod.DEFAULT,
    ): TodayChange? {
        if (change == null) return null
        // Marktkurs einer Auszahlung ohne Preis / Adress-Änderung heute: der aktuelle Kurs
        val market: (String, Double?, Long) -> Double? = { coin, live, _ ->
            if (!PortfolioStables.needsQuote(coin)) 1.0 else live
        }
        val parts = parts(trades, current, windowStart, only, addresses, method, market) { coin, live ->
            when {
                !PortfolioStables.needsQuote(coin) -> 1.0
                live == null -> null
                else -> coinChanges[coin]?.takeIf { it.isFinite() && it > -100.0 }?.let { live / (1.0 + it / 100.0) }
            }
        }
        if (parts.windowTrades == 0) return change
        if (parts.counted == 0) return null
        // Ausgelassene Coins (Transaktion ohne Preis) auch aus der Kursänderung nehmen
        val amount = change.amountUsd + parts.gain - parts.held - parts.skippedHeld
        val base = parts.start + parts.buys
        // Gezählte Coins (z. B. ganz heute gekauft, ohne Veränderung) fehlen nicht mehr
        val skipped = (change.skipped.filter { it !in parts.countedCoins } + parts.skipped).distinct().sorted()
        return TodayChange(amount, if (base > PortfolioCalculator.EPS) amount / base * 100.0 else null, skipped, parts.rewards)
    }

    /**
     * Umrechnung der Teilsummen von [gain] ([gain] `fx`): Wert am Anfang zu [start], Wert jetzt zu [end], Zuflüsse zum
     * Kurs ihres Zeitpunkts ([flow], ms → Kurs). [NONE] = USDT.
     */
    private class Rates(val start: Double, val end: Double, val flow: (Long) -> Double) {
        companion object {
            val NONE = Rates(1.0, 1.0) { 1.0 }
        }
    }

    /** Teilsummen über alle Coins (USDT bzw. umgerechnet, [Rates]). */
    private class Parts {
        /** Σ Gewinn je Coin (Wert Ende − Wert Anfang − Zuflüsse). */
        var gain = 0.0
        /** Σ Bestand am Stichtag × Kurs am Anfang. */
        var start = 0.0
        /** Σ Käufe im Zeitraum (Einstand). */
        var buys = 0.0
        /** Σ heutiger Bestand × (Kurs jetzt − Kurs am Anfang) — die reine Kursänderung derselben Coins. */
        var held = 0.0
        var counted = 0
        var windowTrades = 0
        /** Coins ohne Kurs am Anfang (fehlen in der Summe). */
        val skipped = ArrayList<String>()
        /** Gezählte Coins. */
        val countedCoins = HashSet<String>()
        /** Σ reine Kursänderung der wegen Transaktion ohne Preis ausgelassenen Coins (soweit Kurs am Anfang bekannt). */
        var skippedHeld = 0.0
        /** Σ Wert beim Erhalt der Belohnungen im Zeitraum (gezählte Coins). */
        var rewards = 0.0

        fun result(): TodayChange? {
            if (counted == 0) return null
            val base = start + buys
            return TodayChange(gain, if (base > PortfolioCalculator.EPS) gain / base * 100.0 else null, skipped.sorted(), rewards)
        }
    }

    /**
     * @param startPrice Kurs am Anfang je Coin (live = aktueller Kurs, null ohne Bestand/Kurs); null = unbekannt —
     *   dann fehlt nur dieser Coin ([Parts.skipped])
     * @param marketPrice Marktkurs zum Zeitpunkt (ms) für Auszahlungen ohne Preis und Adress-Änderungen
     *   ([PortfolioLedger.needsMarketPrice]); null = unbekannt (Coin fehlt)
     */
    private fun parts(
        trades: List<PortfolioTrade>,
        current: Map<String, Double>,
        windowStart: Long,
        only: String? = null,
        addresses: List<AddressBalanceHistory> = emptyList(),
        method: CostMethod = CostMethod.DEFAULT,
        marketPrice: (coin: String, live: Double?, time: Long) -> Double?,
        rates: Rates = Rates.NONE,
        startPrice: (coin: String, live: Double?) -> Double?,
    ): Parts {
        val out = Parts()
        // Adress-Einträge der Eingabe nie rückwirkend; nur die beobachtete Reihe ([AddressBalanceHistory]). Tausch ohne
        // Wert (und die übrigen Fälle von [PortfolioLedger.valueAtDayClose]) zum Marktkurs [marketPrice] wie die Karte:
        // EIN Wert für abgegebenen und erhaltenen Coin statt jede Seite zu ihrem eigenen Kurs
        val valid = PortfolioLedger.valueAtDayClose(
            trades.filter { it.amount.signum() > 0 && !AddressBalance.isAddressTrade(it.id) } +
                AddressBalanceHistory.trades(addresses),
        ) { coin, time ->
            marketPrice(coin, current[coin]?.takeIf { it > 0.0 && it.isFinite() }, time)
                ?.takeIf { it > 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) }
        }
        // Alle Arten und Gebühren im dritten Coin über [PortfolioLedger] (Bestand und Zuflüsse wie der Rechner)
        for (coin in PortfolioLedger.coins(valid)) {
            if (only != null && coin != only) continue
            val events = PortfolioLedger.events(coin, valid)
            // Bestände exakt wie die Position nach der Methode ([PortfolioHoldingsBook]); erst zum Bewerten als Double
            val book = PortfolioHoldingsBook(method)
            for (e in events) {
                if (e.trade.time > windowStart) break
                book.apply(e)
            }
            val startExact = book.total
            val inWindow = events.filter { it.trade.time > windowStart }
            val live = current[coin]?.takeIf { it > 0.0 && it.isFinite() }
            // Zuflüsse im Zeitraum; null = einer davon unbekannt (z. B. Kauf ohne Preis)
            var flows: MutableList<Pair<PortfolioLedgerEvent, Double>>? = ArrayList()
            for (e in inWindow) {
                val market = if (PortfolioLedger.needsMarketPrice(e)) marketPrice(coin, live, e.trade.time) else null
                // Gebühr im dritten Coin ohne Preis: Marktkurs des Gebühr-Coins (sein aktueller Kurs für «nach dem letzten Schluss»)
                val feeCoin = e.trade.thirdFeeCoin
                val feeMarket = if (feeCoin != null && PortfolioLedger.needsFeeMarketPrice(e)) {
                    marketPrice(feeCoin, current[feeCoin]?.takeIf { it > 0.0 && it.isFinite() }, e.trade.time)
                } else null
                // Abgänge gekappt auf den Topf des Schritts (FIFO je Wallet: seine Wallet)
                val f = PortfolioLedger.flow(e, book.capFor(e), market, feeMarket, book.method)
                if (f == null) flows = null else flows?.add(e to f)
                book.apply(e)
            }
            val endExact = book.total
            val startHoldings = if (PortfolioDecimal.isDust(startExact)) 0.0 else startExact.toDouble()
            val endHoldings = if (PortfolioDecimal.isDust(endExact)) 0.0 else endExact.toDouble()
            if (startHoldings == 0.0 && endHoldings == 0.0 && inWindow.isEmpty()) continue

            // Coins ohne aktuellen Kurs fehlen wie im Gesamtwert
            if (endHoldings > 0.0 && live == null) continue
            val p0 = startPrice(coin, live)?.takeIf { it >= 0.0 && it.isFinite() }
            if (flows == null) {
                // Zufluss unbekannt → Coin auslassen («—»-Hinweis), statt ihn zum Anfangskurs anzunehmen
                out.skipped += coin
                out.windowTrades += inWindow.size
                if (p0 != null && live != null) out.skippedHeld += endHoldings * (live - p0)
                continue
            }
            if (startHoldings > 0.0 && p0 == null) {
                out.skipped += coin
                continue
            }

            var gain = endHoldings * (live ?: 0.0) * rates.end - startHoldings * (p0 ?: 0.0) * rates.start
            for ((e, raw) in flows) {
                // Zufluss zum Kurs seines Tags (USDT: 1)
                val f = raw * rates.flow(e.trade.time)
                gain -= f
                // Basis des Prozents: Zugänge (Kauf, Belohnung, Einzahlung, Zugang aus Tausch, Gebühren einer Umbuchung);
                // die abgebende Seite eines Tauschs zählt wie ein Verkauf nicht
                if (!e.feeLeg && !e.trade.type.isOutflow && (e.swapIn || e.trade.type != PortfolioTxType.SWAP)) out.buys += f
                if (!e.feeLeg && e.trade.type == PortfolioTxType.REWARD) out.rewards += f
            }
            out.gain += gain
            out.start += startHoldings * (p0 ?: 0.0) * rates.start
            if (p0 != null && live != null) out.held += endHoldings * (live - p0)
            out.windowTrades += inWindow.size
            out.counted++
            out.countedCoins += coin
        }
        return out
    }

    /**
     * Marktkurs am UTC-Tag [day] (Auszahlung ohne Preis, Adress-Änderung): Schluss dieses Tags oder der letzte
     * davor; nach dem letzten Schluss der aktuelle Kurs [live] (die heutige Kerze fehlt oft noch). Gleiche
     * Identitätsprüfung wie der Kurs am Anfang; null = unbekannt.
     */
    private fun marketClose(coin: String, live: Double?, closes: Map<String, Map<Long, Double>>, day: Long): Double? {
        if (!PortfolioStables.needsQuote(coin)) return 1.0
        val sorted = closes[coin].orEmpty().entries
            .filter { it.value > 0.0 && it.value.isFinite() }
            .sortedBy { it.key }
            .map { it.key to it.value }
        if (sorted.isEmpty()) return live?.let { PortfolioStables.fallbackWithoutClose(coin, it) }
        if (live != null && !PricePlausibility.closesMatchLive(sorted, live)) return null
        if (live != null && day > sorted.last().first) return PortfolioStables.price(coin, live)
        return PortfolioStables.price(coin, PortfolioHistory.closeOnOrBefore(sorted, day))
    }

    /**
     * Marktkurs von [coin] zum Zeitpunkt [time] (ms) aus den Tagesschlusskursen [closes] (nach dem letzten Schluss
     * der aktuelle Kurs [live]) — gleiche Regel wie eine Auszahlung ohne Preis in [gain]. Für
     * [PortfolioCalculator.summarize] (`withdrawalPrice`, «Ausgebucht»); null = unbekannt.
     */
    fun marketPriceAt(coin: String, live: Double?, closes: Map<String, Map<Long, Double>>, time: Long): Double? {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        return marketClose(symbol, live, closes, PortfolioHistory.epochDayOfUtcMillis(time))
    }

    /** Kurs am Anfang aus den Tagesschlusskursen (gleiche Regeln wie [change]); null = unbekannt. */
    private fun startClose(coin: String, live: Double?, closes: Map<String, Map<Long, Double>>, baseDay: Long): Double? {
        if (!PortfolioStables.needsQuote(coin)) return 1.0
        val sorted = closes[coin].orEmpty().entries
            .filter { it.value > 0.0 && it.value.isFinite() }
            .sortedBy { it.key }
            .map { it.key to it.value }
        if (sorted.isEmpty() && !PortfolioStables.isStable(coin)) return null
        if (live != null && !PricePlausibility.closesMatchLive(sorted, live)) return null
        val close = PortfolioHistory.closeOnOrBefore(sorted, baseDay)
        return if (close == null) {
            live?.let { PortfolioStables.fallbackWithoutClose(coin, it) }
        } else {
            PortfolioStables.price(coin, close)
        }
    }
}
