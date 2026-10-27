package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * Wertverlauf des Portfolios aus den Transaktionen (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioHistory.swift`):
 *  - EINE Tagesgrenze: der UTC-Tag, wie die Tageskerzen und «7 T/1 J» ([PortfolioPeriodChange.windowStart]).
 *    Bestand je Coin und Tag = alle Transaktionen bis zum Ende des UTC-Tags ([utcDayEnd]); Zuflüsse liegen auf dem
 *    UTC-Tag ihrer Zeit. Sonst lag ein Kauf um 00:30 Zürich-Sommerzeit (22:30 UTC des Vortags) im Bestand am Anfang
 *    von «7 T», aber nach dem Anfang der Kurve — ein Scheingewinn. Die Achse darf lokale Daten zeigen (der Tag eines
 *    Punkts ist der Kalendertag seiner Kerze); `dayEndMillis` der Aufrufer zählt für die Zuordnung nicht mehr.
 *    Verkäufe werden wie in [PortfolioCalculator] beim Bestand gekappt.
 *  - Wert = Σ Bestand × Tagesschlusskurs (UTC-Tageskerze desselben Kalendertags) × [fxRate].
 *    Fehlt an einem Tag die Kerze, gilt der letzte bekannte Schluss davor; vor der ersten
 *    Kerze zählt der Coin nicht.
 *  - Heute: Bestand nach allen Transaktionen × aktueller Kurs. Ein heute gehaltener Coin ohne
 *    aktuellen Kurs fehlt (Token nicht bestätigt, steht unter «ohne …»); nur Coins ohne heutigen
 *    Bestand behalten ihre Reihe ungeprüft (kein aktueller Kurs zum Vergleichen, heute kein Wert).
 *  - Stablecoins nach [PortfolioStables]: USDT = 1, andere Stablecoins ihr Tagesschluss (ohne
 *    Schluss kein Wert, wie jeder Coin). Coins ausser USDT ganz ohne Kerzen fehlen und stehen in [PortfolioHistorySeries.skipped] — ebenso,
 *    wenn der jüngste Schluss so weit vom aktuellen Kurs abweicht, dass es ein anderer Token sein
 *    muss (Faktor 3, [PricePlausibility.closesMatchLive]; keine 25-%-Regel).
 *  - [fxRate] gilt für alle Punkte gleich; Tageskurse je Punkt siehe [PortfolioHistoryFx].
 *  - Beginn = erster Transaktionstag oder Beginn des Zeitraums, je nachdem, was später ist
 *    ([startDay]); «Seit 1. Kauf» ohne Begrenzung auf eine Zahl Jahre, nur frühestens ab
 *    [DATA_START_EPOCH_DAY] — davor gibt es keine Tageskerzen ([isCapped]).
 *  - Tageskerzen: mindestens [MAX_DAYS] je Coin, für «Seit 1. Kauf» so viele wie nötig
 *    ([candleDays]), abgefragt in Stücken von höchstens [MAX_CANDLES_PER_REQUEST] ([candleChunks]).
 *  - Gewinn/Verlust ([PortfolioHistorySeries.pnlPoints], die Kurve der Karte): je Tag Wert − netto
 *    investiert, Zuflüsse nach [PortfolioLedger.flow] (wie «7 T»). Im Zeitraum ab 0 am ersten Tag, «Seit
 *    1. Kauf» ab 0 vor dem ersten Kauf. Ein Coin fehlt darin (und steht in
 *    [PortfolioHistorySeries.pnlSkipped]), wenn ein nötiger Zufluss unbekannt ist (Kauf/Verkauf
 *    ohne Preis im Zeitraum, «Seit 1. Kauf» überhaupt) oder er an einem gehaltenen Tag keinen Kurs hat.
 *    Auszahlung, Einzahlung, Belohnung und Zugang aus Tausch ohne Preis sowie eine Gebühr im dritten Coin ohne Preis
 *    zum Marktkurs ihres Tags (Schluss, heute der aktuelle Kurs; [PortfolioLedger.flow]): kein Gewinn/Verlust aus
 *    dem Abgang bzw. Zugang; ohne Marktkurs fehlt der Coin. Die Kurve enthält damit die
 *    Wertänderung ausgebuchter Coins bis zur Auszahlung ([PortfolioHistorySeries.withdrawalsInRange]).
 *  - Adress-Guthaben: Adress-Einträge in den Transaktionen ([AddressBalance.withAddresses]) zählen NICHT
 *    (nie rückwirkend). Nur die beobachtete Reihe ([AddressBalanceHistory], Parameter `addresses`): vor der
 *    ersten Beobachtung nichts, danach Bestand wie beobachtet; Änderungen sind Zu-/Abflüsse zum Marktkurs
 *    ihres Tags — ein neu eingegangener Betrag ist nie Gewinn.
 */
object PortfolioHistory {

    /** Tageskerzen je Coin mindestens (eine Abfrage mit diesem Limit deckt «1 J» samt heute ab). */
    const val MAX_DAYS = 366

    /**
     * Erster Tag mit Kursdaten (17.08.2017, Tage seit 1970-01-01): Binance-Tageskerzen gibt es erst ab
     * da (BTCUSDT) — «Seit 1. Kauf» beginnt frühestens hier. Mehr Tage brauchen nur mehr Abfragen zu
     * je [MAX_CANDLES_PER_REQUEST] Kerzen ([candleChunks]); später gelistete Coins brechen früher ab.
     */
    const val DATA_START_EPOCH_DAY = 17_395L

    /** Höchstens so viele Tageskerzen liefert die Kerzen-Quelle je Abfrage. */
    const val MAX_CANDLES_PER_REQUEST = 1000

    private const val DAY_MILLIS = 86_400_000L

    fun isStable(coin: String): Boolean = PortfolioStables.isStable(coin)

    /** UTC-Tag einer Kerzen-Eröffnungszeit (ms). */
    fun epochDayOfUtcMillis(millis: Long): Long = Math.floorDiv(millis, DAY_MILLIS)

    /**
     * Heutiger UTC-Tag — EINE Tagesgrenze für «7 T/1 J», Wertverlauf und die Erwerbstage der Karte; das lokale Datum lag
     * zwischen Mitternacht lokal und UTC einen Tag daneben (Stichtag und Kurve um einen Tag verschoben).
     */
    fun utcToday(now: Long = System.currentTimeMillis()): Long = epochDayOfUtcMillis(now)

    /**
     * Letzte Millisekunde des UTC-Tags [day] — die EINE Tagesgrenze von Verlauf und «7 T/1 J» (wie die Tageskerzen).
     * Das Steuerjahr ([TaxExport.localDate]) und der Stichtag ([CutoffExport.endOfDayMillis]) nehmen dagegen das
     * rechtliche Datum in der Zeitzone des Geräts.
     */
    fun utcDayEnd(day: Long): Long = (day + 1) * DAY_MILLIS - 1

    /**
     * Bestand eines Coins nach jeder Transaktion: (Zeit, Bestand danach), zeitlich
     * aufsteigend (bei Gleichstand nach Id). Ungültige Mengen werden übersprungen.
     * Gebühr im Coin wie im Rechner: Kauf + (Menge − Gebühr), Verkauf − (Menge + Gebühr); übrige Arten
     * und Gebühr-Abgänge nach [PortfolioHoldingsBook] (Einstandsmethode [method], FIFO je Wallet je Wallet gekappt).
     */
    fun holdingsTimeline(
        coin: String,
        trades: List<PortfolioTrade>,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<Pair<Long, Double>> = holdingsTimelineExact(coin, trades, method).map { (time, holdings) -> time to holdings.toDouble() }

    /** [holdingsTimeline] exakt ([PortfolioDecimal]): der Verlauf multipliziert damit, ohne Gleitkomma-Rundung der Menge. */
    fun holdingsTimelineExact(
        coin: String,
        trades: List<PortfolioTrade>,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<Pair<Long, BigDecimal>> =
        // Alle Arten und Gebühren im dritten Coin wie der Rechner ([PortfolioHoldingsBook])
        holdingsTimelineOf(PortfolioLedger.events(coin, trades), method)

    /** [holdingsTimelineExact] aus den schon gesammelten Schritten [events] eines Coins ([PortfolioLedger.eventsByCoin]). */
    fun holdingsTimelineOf(events: List<PortfolioLedgerEvent>, method: CostMethod): List<Pair<Long, BigDecimal>> {
        val book = PortfolioHoldingsBook(method)
        val out = ArrayList<Pair<Long, BigDecimal>>(events.size)
        for (e in events) {
            if (e.trade.amount.signum() <= 0) continue
            book.apply(e)
            out += e.trade.time to book.total
        }
        return out
    }

    /** Exakter Bestand zum Zeitpunkt [atMillis] (einschliesslich) aus [holdingsTimelineExact]. */
    private fun holdingsAtExact(timeline: List<Pair<Long, BigDecimal>>, atMillis: Long): BigDecimal {
        var result = BigDecimal.ZERO
        for ((time, holdings) in timeline) {
            if (time > atMillis) break
            result = holdings
        }
        return result
    }

    /** Bestand zum Zeitpunkt [atMillis] (einschliesslich) aus [holdingsTimeline]. */
    fun holdingsAt(timeline: List<Pair<Long, Double>>, atMillis: Long): Double {
        var result = 0.0
        for ((time, holdings) in timeline) {
            if (time > atMillis) break
            result = holdings
        }
        return result
    }

    /**
     * Verlauf für [range] bis [todayEpochDay].
     * @param closes Tagesschluss in USDT je Coin (Grossschreibung) und UTC-Tag; fehlt ein Coin
     *   (oder ist seine Liste leer), hat er keinen Verlauf.
     * @param livePrices aktuelle USDT-Kurse für den heutigen Punkt.
     * @param dayEndMillis nicht mehr für die Zuordnung verwendet (UTC-Tage, [utcDayEnd]); bleibt für ältere Aufrufer.
     * @param fxRate Umrechnung USDT → Anzeigewährung (1 = keine).
     * @param ranges Eröffnung/Hoch/Tief der Tageskerzen je Coin und UTC-Tag (optional): Damit bekommt
     *   jeder Punkt [PortfolioHistoryPoint.open]/[PortfolioHistoryPoint.high]/[PortfolioHistoryPoint.low]
     *   = Σ Bestand am Tagesende × Eröffnung/Hoch/Tief des Coins. Näherung: Hoch und Tief der Coins
     *   fallen kaum je auf denselben Zeitpunkt — die Summe ist eine obere bzw. untere SCHRANKE des
     *   tatsächlichen Tageshochs/-tiefs des Portfolios, kein gemessener Wert. Coins ohne Kerze an dem
     *   Tag (oder USDT) zählen mit ihrem Tageswert in allen dreien.
     * @param addresses beobachtete Adress-Guthaben (siehe oben); Adress-Einträge in [trades] zählen nicht.
     */
    fun build(
        trades: List<PortfolioTrade>,
        closes: Map<String, Map<Long, Double>>,
        livePrices: Map<String, Double>,
        range: PortfolioHistoryRange,
        todayEpochDay: Long,
        @Suppress("UNUSED_PARAMETER") dayEndMillis: (Long) -> Long = { utcDayEnd(it) },
        fxRate: Double = 1.0,
        ranges: Map<String, Map<Long, PortfolioDayRange>> = emptyMap(),
        addresses: List<AddressBalanceHistory> = emptyList(),
        /** Einstandsmethode: Bestände wie die Karte ([PortfolioHoldingsBook]). */
        method: CostMethod = CostMethod.DEFAULT,
    ): PortfolioHistorySeries {
        // EINE Tagesgrenze: UTC-Tag (wie Tageskerzen und «7 T/1 J»), nicht die lokale Zeitzone des Aufrufers
        val dayEndMillis: (Long) -> Long = { utcDayEnd(it) }
        // Adress-Einträge nie rückwirkend; nur die beobachtete Reihe. Tausch ohne Wert (und die übrigen Fälle von
        // [PortfolioLedger.valueAtDayClose]) zum selben Tageskurs wie die Karte ([PortfolioPeriodChange.marketPriceAt]):
        // EIN Wert für beide Seiten, sonst bewerteten Kurve und Karte denselben Tausch verschieden
        val valid = PortfolioLedger.valueAtDayClose(
            trades.filter { it.amount.signum() > 0 && !AddressBalance.isAddressTrade(it.id) } +
                AddressBalanceHistory.trades(addresses),
        ) { coin, time ->
            PortfolioPeriodChange.marketPriceAt(coin, livePrices[coin], closes, time)
                ?.takeIf { it > 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) }
        }
        if (valid.isEmpty()) return PortfolioHistorySeries.EMPTY
        val rate = fxRate.takeIf { it > 0.0 && it.isFinite() } ?: 1.0

        // Erster Tag mit einer Transaktion (UTC): kleinster Tag, dessen Ende nach ihr liegt
        val firstTime = valid.minOf { it.time }
        val start = startDay(range, firstTime, todayEpochDay, dayEndMillis)
        val capped = isCapped(range, firstTime, todayEpochDay, dayEndMillis)

        val coins = PortfolioLedger.coins(valid).sorted()
        // Schritte je Coin einmal gesammelt (Bestände, Zuflüsse und Gewinn/Verlust nutzen dieselben)
        val events = PortfolioLedger.eventsByCoin(valid)
        // Bestände exakt (Wert = Menge × Kurs ohne Gleitkomma-Rundung der Menge); als Double nur für die Prüfungen «gehalten»
        val exactTimelines = coins.associateWith { holdingsTimelineOf(events.getValue(it), method) }
        val timelines = exactTimelines.mapValues { (_, list) -> list.map { (time, holdings) -> time to holdings.toDouble() } }

        // Coins, die im Zeitraum etwas hielten
        val startMillis = dayEndMillis(start - 1) + 1
        val held = coins.filter { coin -> heldWithin(timelines.getValue(coin), startMillis) }

        // Je Coin die Kerzen aufsteigend, für «letzter bekannter Schluss» (USDT braucht keine).
        // Identitätsprüfung: liegt der jüngste Schluss um mehr als Faktor 3 neben dem aktuellen
        // Kurs, gehört die Reihe wohl zu einem anderen Token und zählt nicht (grosse echte
        // Tagesbewegungen bleiben drin; der heutige Punkt nimmt den aktuellen Kurs).
        // Heute gehaltener Coin ohne aktuellen Kurs: Token nicht bestätigt → fehlt (strenge Prüfung);
        // ohne heutigen Bestand gibt es keinen Kurs zum Vergleichen, die Reihe bleibt.
        val sortedCloses = held.filter { PortfolioStables.needsQuote(it) }.associateWith { coin ->
            val live = livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() }
            val heldToday = (timelines.getValue(coin).lastOrNull()?.second ?: 0.0) > 0.0
            closes[coin].orEmpty().entries.filter { it.value > 0.0 && it.value.isFinite() }
                .sortedBy { it.key }.map { it.key to it.value }
                .takeIf { (live == null && !heldToday) || PricePlausibility.closesMatchLive(it, live) }
                .orEmpty()
        }
        // Nur USDT fehlt nie (immer 1); alle anderen Coins ohne (passende) Kerzen schon — auch
        // Stablecoins wie USDC (kein stilles 1)
        val skipped = held.filter { PortfolioStables.needsQuote(it) && sortedCloses[it].isNullOrEmpty() }
        val included = held.filter { it !in skipped }
        if (included.isEmpty()) {
            return PortfolioHistorySeries(emptyList(), skipped, null, null, false, capped)
        }

        val dayCount = (todayEpochDay - start + 1).toInt().coerceAtLeast(1)
        // Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief; null = nicht gehalten oder ohne Kurs
        val cells = HashMap<String, Array<DayCell?>>()
        // Gehalten, aber an einem Tag ohne Kurs (vor der ersten Kerze): fehlt im Gewinn/Verlust
        val unpriced = HashSet<String>()
        for (coin in included) {
            val timeline = exactTimelines.getValue(coin)
            val series = sortedCloses[coin].orEmpty()
            val row = arrayOfNulls<DayCell>(dayCount)
            for (index in 0 until dayCount) {
                val day = start + index
                val isToday = day == todayEpochDay
                val amount = if (isToday) timeline.lastOrNull()?.second ?: BigDecimal.ZERO else holdingsAtExact(timeline, dayEndMillis(day))
                if (amount.signum() <= 0) continue
                val market = when {
                    isToday -> livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() } ?: closeOnOrBefore(series, day)
                    else -> closeOnOrBefore(series, day)
                }
                // Stablecoin-Regel: USDT 1, andere Stablecoins Kurs des Tags, sonst kein Wert
                val price = PortfolioStables.price(coin, market)
                if (price == null) {
                    unpriced += coin
                    continue
                }
                // Kerze genau dieses Tags (nicht fortgeschrieben); Hoch/Tief schliessen den Tageswert ein
                val dayRange = if (PortfolioStables.needsQuote(coin) && series.isNotEmpty()) {
                    ranges[coin]?.get(day)?.takeIf { it.valid }
                } else null
                // Menge × Kurs exakt (Kurs über seine kürzeste Darstellung); Double erst in der Summe je Tag ([sumCells])
                fun times(p: Double): BigDecimal = amount.multiply(BigDecimal.valueOf(p))
                row[index] = if (dayRange != null) {
                    DayCell(times(price), times(dayRange.open), times(max(dayRange.high, price)), times(min(dayRange.low, price)), true)
                } else {
                    val v = times(price)
                    DayCell(v, v, v, v, false)
                }
            }
            cells[coin] = row
        }

        val points = sumCells(included, cells, start, dayCount, rate)
        val first = points.first().value
        val last = points.last().value
        val change = if (points.size >= 2) last - first else null
        val percent = if (change != null && first > PortfolioCalculator.EPS) change / first * 100.0 else null
        val tradesInRange = valid.any { it.time > dayEndMillis(start) }
        // Marktkurs eines Tags (Auszahlung/Belohnung ohne Preis, Adress-Änderung, Gebühr im dritten Coin ohne Preis):
        // heute der aktuelle Kurs, sonst der Schluss. Ein Gebühr-Coin ohne Bestand im Zeitraum: seine Kerzen mit
        // derselben Identitätsprüfung
        val market: (String, Long) -> Double? = { coin, day ->
            val live = livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() }
            val series = sortedCloses[coin] ?: closes[coin].orEmpty().entries.filter { it.value > 0.0 && it.value.isFinite() }
                .sortedBy { it.key }.map { it.key to it.value }
                .takeIf { live == null || PricePlausibility.closesMatchLive(it, live) }.orEmpty()
            PortfolioStables.price(coin, if (day == todayEpochDay && live != null) live else closeOnOrBefore(series, day))
        }
        val flows = cashFlows(events, included, start, todayEpochDay, dayEndMillis, rate, market, method)

        // Gewinn/Verlust: nur Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen. Ab 0 mit allen Zuflüssen
        // auch, wenn der erste Kauf erst im Zeitraum liegt (junges Portfolio): sonst fehlte der erste Tag (Kursbewegung
        // und Gebühren des ersten Kaufs), der Kopf «7 T/1 J» ([PortfolioPeriodChange.gain]) zählt ihn
        val sinceFirst = range == PortfolioHistoryRange.SINCE_FIRST || start > todayEpochDay - range.days
        val pnlFlows = ArrayList<PortfolioCashFlow>()
        val pnlSkipped = ArrayList<String>()
        val pnlCoins = ArrayList<String>()
        for (coin in included) {
            val coinFlows = if (coin in unpriced) null else pnlFlows(
                coin, events.getValue(coin), start, todayEpochDay, dayEndMillis, rate, sinceFirst, market, method,
            )
            if (coinFlows == null) {
                pnlSkipped += coin
            } else {
                pnlCoins += coin
                pnlFlows += coinFlows
            }
        }
        val pnlValues = if (pnlCoins.isEmpty()) emptyList() else sumCells(pnlCoins, cells, start, dayCount, rate)
        val rangeEnd = dayEndMillis(start)
        val withdrawalsInRange = valid.any { t ->
            t.type.isWithdrawalLike && !AddressBalance.isAddressTrade(t.id) &&
                PortfolioCalculator.normalizeCoin(t.coin) in pnlCoins && (sinceFirst || t.time > rangeEnd)
        }
        return PortfolioHistorySeries(
            points, skipped, change, percent, tradesInRange, capped, flows,
            pnlValues = pnlValues,
            pnlFlows = pnlFlows.sortedBy { it.epochDay },
            pnlSkipped = pnlSkipped.sorted(),
            pnlSinceFirst = sinceFirst,
            withdrawalsInRange = withdrawalsInRange,
        )
    }

    /** Wert eines Coins an einem Tag (USDT) mit Eröffnung/Hoch/Tief; [withRange] = aus einer Tageskerze. */
    class DayCell(val value: BigDecimal, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val withRange: Boolean)
}
