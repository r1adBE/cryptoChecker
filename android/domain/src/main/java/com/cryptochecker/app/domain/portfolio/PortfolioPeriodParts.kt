// Teilsummen von «Gewinn 7 T/1 J/heute» je Coin (Teil von `PortfolioPeriodChange`): Bestand am Stichtag, Zuflüsse im
// Zeitraum und Umrechnung — eigene Datei, die Regeln stehen bei [PortfolioPeriodChange].
package com.cryptochecker.app.domain.portfolio

/** Teilsummen für [PortfolioPeriodChange.gain] und [PortfolioPeriodChange.todayAdjusted]. */
object PortfolioPeriodParts {

    /**
     * Umrechnung der Teilsummen von [PortfolioPeriodChange.gain] (`fx`): Wert am Anfang zu [start], Wert jetzt zu [end], Zuflüsse zum
     * Kurs ihres Zeitpunkts ([flow], ms → Kurs). [NONE] = USDT.
     */
    class Rates(val start: Double, val end: Double, val flow: (Long) -> Double) {
        companion object {
            val NONE = Rates(1.0, 1.0) { 1.0 }
        }
    }

    /** Teilsummen über alle Coins (USDT bzw. umgerechnet, [Rates]). */
    class Parts {
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
    fun parts(
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
        // Alle Arten und Gebühren im dritten Coin über [PortfolioLedger] (Bestand und Zuflüsse wie der Rechner); alle
        // Coins: Schritte einmal je Coin gesammelt ([PortfolioLedger.eventsByCoin]), nur ein Coin: nur seine
        val byCoin = if (only == null) PortfolioLedger.eventsByCoin(valid) else null
        for (coin in PortfolioLedger.coins(valid)) {
            if (only != null && coin != only) continue
            val events = byCoin?.get(coin) ?: PortfolioLedger.events(coin, valid)
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
}
