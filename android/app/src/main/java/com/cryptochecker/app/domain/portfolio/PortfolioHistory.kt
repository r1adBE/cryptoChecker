package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zeitraum des Wertverlaufs im Portfolio-Tab ([days] = so viele Tage vor heute beginnt er höchstens). */
enum class PortfolioHistoryRange(val days: Int) {
    WEEK(7),
    MONTH(30),
    YEAR(365),

    /**
     * «Seit 1. Kauf»: ab der ersten Transaktion, ohne feste Zahl Tage (daher 0) — frühestens ab
     * [PortfolioHistory.DATA_START_EPOCH_DAY], dem ersten Tag mit Kursdaten (siehe [PortfolioHistory.startDay]).
     */
    SINCE_FIRST(0),
    ;

    companion object {
        /** Gespeicherter Name; unbekannt oder leer = 30 Tage. */
        fun fromName(name: String?): PortfolioHistoryRange = entries.firstOrNull { it.name == name } ?: MONTH
    }
}

/**
 * Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). [epochDay] = Tage seit 1970-01-01.
 * [open], [high] und [low] nur, wenn Tageskerzen mit Eröffnung/Hoch/Tief vorlagen (für die
 * Kerzen-Ansicht, siehe [PortfolioHistory.build] und [PortfolioCandles]); sonst null.
 */
data class PortfolioHistoryPoint(
    val epochDay: Long,
    val value: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
)

/** Eröffnung, Hoch und Tief einer UTC-Tageskerze in USDT (der Schluss steht bei den Schlusskursen). */
data class PortfolioDayRange(val open: Double, val high: Double, val low: Double) {
    /** Alle drei Werte endlich und grösser 0. */
    val valid: Boolean
        get() = open > 0.0 && high > 0.0 && low > 0.0 && open.isFinite() && high.isFinite() && low.isFinite()
}

/** Fertiger Verlauf eines Zeitraums. */
data class PortfolioHistorySeries(
    /** Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat. */
    val points: List<PortfolioHistoryPoint>,
    /** Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»). */
    val skipped: List<String>,
    /** Letzter minus erster Wert; null mit weniger als zwei Punkten. */
    val change: Double?,
    /** Änderung in Prozent des ersten Werts; null ohne Ausgangswert. */
    val changePercent: Double?,
    /** Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft — die Änderung ist dann nicht nur Kursbewegung. */
    val tradesInRange: Boolean,
    /** «Seit 1. Kauf» reicht nicht bis zum ersten Kauf zurück (Kursdaten erst ab [PortfolioHistory.DATA_START_EPOCH_DAY]). */
    val capped: Boolean = false,
    /**
     * Zuflüsse im Zeitraum (nach dem ersten Tag) der gezeichneten Coins, in der Einheit der Punkte:
     * Kauf + (Menge × Preis + Gebühr USDT), Verkauf − (Erlös − Gebühr USDT), wie [PortfolioPeriodChange.gain].
     * Übrige Arten nach [PortfolioLedger.flow]. null = unbekannt (z. B. Kauf/Verkauf ohne Preis im Zeitraum).
     */
    val cashFlows: List<PortfolioCashFlow>? = emptyList(),
    /**
     * Gewinn/Verlust: Wert der Coins, die dafür zählen (je Punkt, gleiche Tage wie [points], mit
     * Eröffnung/Hoch/Tief wie dort) — ohne [pnlSkipped]. Daraus mit [pnlFlows] die Kurve [pnlPoints].
     */
    val pnlValues: List<PortfolioHistoryPoint> = emptyList(),
    /**
     * Zuflüsse dieser Coins ([PortfolioLedger.flow]) je UTC-Tag: «Seit 1. Kauf» alle (vor dem ersten
     * Punkt auf dessen Tag gelegt), sonst die nach dem Ende des ersten Tags.
     */
    val pnlFlows: List<PortfolioCashFlow> = emptyList(),
    /** Coins im Wertverlauf, die im Gewinn/Verlust fehlen (Zufluss unbekannt oder Bestand ohne Kurs). */
    val pnlSkipped: List<String> = emptyList(),
    /** «Seit 1. Kauf»: Gewinn/Verlust ab 0 vor dem ersten Kauf (Wert − investiert), sonst ab dem ersten Tag. */
    val pnlSinceFirst: Boolean = false,
    /**
     * Im Zeitraum der Gewinn/Verlust-Kurve liegen eigene Auszahlungen ihrer Coins: Die Kurve enthält die
     * Wertänderung der ausgebuchten Coins, solange sie gehalten wurden (Abfluss zum Marktwert) — die Karte
     * («Seit Kauf», «realisiert») nicht. Kopf/Hinweis: «inkl. Wertänderung ausgebuchter Coins».
     */
    val withdrawalsInRange: Boolean = false,
) {
    /** Genug für eine Linie. */
    val hasChart: Boolean get() = points.size >= 2

    /**
     * Gewinn/Verlust je Tag in der Einheit der Punkte: Wert(d) − netto investiert(d). Netto investiert =
     * Σ [pnlFlows] bis Tag d, im Zeitraum zusätzlich der Wert am ersten Tag (die Kurve beginnt dort bei 0);
     * «Seit 1. Kauf» ab 0 vor dem ersten Kauf. Käufe/Verkäufe verschieben die Kurve also nicht, nur
     * Kursbewegungen der gehaltenen Coins. Eröffnung/Hoch/Tief um denselben Betrag verschoben.
     */
    val pnlPoints: List<PortfolioHistoryPoint>
        get() {
            if (pnlValues.isEmpty()) return emptyList()
            val anchor = if (pnlSinceFirst) 0.0 else pnlValues.first().value
            val flows = pnlFlows.sortedBy { it.epochDay }
            var next = 0
            var invested = anchor
            return pnlValues.map { p ->
                while (next < flows.size && flows[next].epochDay <= p.epochDay) invested += flows[next++].amount
                PortfolioHistoryPoint(p.epochDay, p.value - invested, p.open?.minus(invested), p.high?.minus(invested), p.low?.minus(invested))
            }
        }

    /** Genug Gewinn/Verlust-Punkte für eine Linie. */
    val hasPnlChart: Boolean get() = pnlValues.size >= 2

    /** Gewinn/Verlust über den Zeitraum (letzter Punkt von [pnlPoints]); null ohne Kurve. */
    val pnlChange: Double?
        get() = if (hasPnlChart) pnlPoints.last().value else null

    /**
     * Basis des Prozents, wie «Heute» und die Zeilen ([PortfolioPeriodChange]): im Zeitraum Wert am ersten Tag +
     * Zugänge danach (positive Zuflüsse: Käufe, Belohnungen, Einzahlungen samt Gebühren; Verkäufe NICHT
     * abgezogen), «Seit 1. Kauf» Σ aller Zugänge (alles eingesetzte Geld). null ohne Kurve.
     */
    val pnlCapital: Double?
        get() = if (hasPnlChart) {
            (if (pnlSinceFirst) 0.0 else pnlValues.first().value) + pnlFlows.filter { it.amount > 0.0 }.sumOf { it.amount }
        } else null

    /**
     * [pnlChange] in Prozent von [pnlCapital]; null, wenn diese Basis höchstens ~0 ist — dann nur der Betrag.
     */
    val pnlPercent: Double?
        get() {
            val change = pnlChange ?: return null
            val base = pnlCapital ?: return null
            return if (base > PortfolioCalculator.EPS) change / base * 100.0 else null
        }

    /**
     * Änderung ohne Käufe/Verkäufe (wie der Gesamtwert-Kopf): [change] − Σ [cashFlows]; null ohne
     * Verlauf oder wenn ein Zufluss unbekannt ist.
     */
    val netChange: Double?
        get() {
            val gross = change ?: return null
            val flows = cashFlows ?: return null
            return gross - flows.sumOf { it.amount }
        }

    /** [netChange] in Prozent von Wert am Anfang + Käufe im Zeitraum (wie «7 T»); null ohne Basis. */
    val netChangePercent: Double?
        get() {
            val net = netChange ?: return null
            val base = (points.firstOrNull()?.value ?: return null) +
                cashFlows.orEmpty().filter { it.amount > 0.0 }.sumOf { it.amount }
            return if (base > PortfolioCalculator.EPS) net / base * 100.0 else null
        }

    companion object {
        val EMPTY = PortfolioHistorySeries(emptyList(), emptyList(), null, null, false)
    }
}

/** Zufluss am (UTC-)Tag [epochDay] (Kauf positiv, Verkauf negativ), in der Einheit des Verlaufs. */
data class PortfolioCashFlow(val epochDay: Long, val amount: Double)

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
    internal fun holdingsTimelineExact(
        coin: String,
        trades: List<PortfolioTrade>,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<Pair<Long, BigDecimal>> {
        // Alle Arten und Gebühren im dritten Coin wie der Rechner ([PortfolioHoldingsBook])
        val events = PortfolioLedger.events(coin, trades)
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
        // Adress-Einträge nie rückwirkend; nur die beobachtete Reihe
        val valid = trades.filter { it.amount.signum() > 0 && !AddressBalance.isAddressTrade(it.id) } +
            AddressBalanceHistory.trades(addresses)
        if (valid.isEmpty()) return PortfolioHistorySeries.EMPTY
        val rate = fxRate.takeIf { it > 0.0 && it.isFinite() } ?: 1.0

        // Erster Tag mit einer Transaktion (UTC): kleinster Tag, dessen Ende nach ihr liegt
        val firstTime = valid.minOf { it.time }
        val start = startDay(range, firstTime, todayEpochDay, dayEndMillis)
        val capped = isCapped(range, firstTime, todayEpochDay, dayEndMillis)

        val coins = PortfolioLedger.coins(valid).sorted()
        // Bestände exakt (Wert = Menge × Kurs ohne Gleitkomma-Rundung der Menge); als Double nur für die Prüfungen «gehalten»
        val exactTimelines = coins.associateWith { holdingsTimelineExact(it, valid, method) }
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
        val flows = cashFlows(valid, included, start, todayEpochDay, dayEndMillis, rate, market, method)

        // Gewinn/Verlust: nur Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen
        val sinceFirst = range == PortfolioHistoryRange.SINCE_FIRST
        val pnlFlows = ArrayList<PortfolioCashFlow>()
        val pnlSkipped = ArrayList<String>()
        val pnlCoins = ArrayList<String>()
        for (coin in included) {
            val coinFlows = if (coin in unpriced) null else pnlFlows(
                coin, valid, start, todayEpochDay, dayEndMillis, rate, sinceFirst, market, method,
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
    private class DayCell(val value: BigDecimal, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val withRange: Boolean)

    /** Punkte je Tag als Summe der [coins] (× [rate]); Eröffnung/Hoch/Tief nur, wenn ein Coin eine Kerze hatte. */
    private fun sumCells(
        coins: List<String>,
        cells: Map<String, Array<DayCell?>>,
        start: Long,
        dayCount: Int,
        rate: Double,
    ): List<PortfolioHistoryPoint> {
        val out = ArrayList<PortfolioHistoryPoint>(dayCount)
        for (index in 0 until dayCount) {
            // Exakt summiert, als Double erst für den Punkt (Anzeige, Chart)
            var total = BigDecimal.ZERO
            var totalOpen = BigDecimal.ZERO
            var totalHigh = BigDecimal.ZERO
            var totalLow = BigDecimal.ZERO
            var withRange = false
            for (coin in coins) {
                val cell = cells[coin]?.get(index) ?: continue
                total += cell.value
                totalOpen += cell.open
                totalHigh += cell.high
                totalLow += cell.low
                if (cell.withRange) withRange = true
            }
            val day = start + index
            out += if (withRange) {
                PortfolioHistoryPoint(day, total.toDouble() * rate, totalOpen.toDouble() * rate, totalHigh.toDouble() * rate, totalLow.toDouble() * rate)
            } else {
                PortfolioHistoryPoint(day, total.toDouble() * rate)
            }
        }
        return out
    }

    /**
     * Zuflüsse eines Coins für den Gewinn/Verlust ([PortfolioLedger.flow]) in der Einheit des Verlaufs; null =
     * einer unbekannt (Coin fehlt, «ohne …»). Im Zeitraum die nach dem Ende von [start] (davor zählt der Wert
     * am ersten Tag); «Seit 1. Kauf» ([sinceFirst]) alle, frühere auf [start] gelegt. Auszahlungen ohne Preis
     * und Adress-Änderungen zum Marktkurs ihres (UTC-)Tags ([market]).
     */
    private fun pnlFlows(
        coin: String,
        trades: List<PortfolioTrade>,
        start: Long,
        todayEpochDay: Long,
        dayEndMillis: (Long) -> Long,
        rate: Double,
        sinceFirst: Boolean,
        market: (String, Long) -> Double?,
        method: CostMethod,
    ): List<PortfolioCashFlow>? {
        val rangeEnd = dayEndMillis(start)
        val out = ArrayList<PortfolioCashFlow>()
        val book = PortfolioHoldingsBook(method)
        for (e in PortfolioLedger.events(coin, trades)) {
            if (sinceFirst || e.trade.time > rangeEnd) {
                val day = localDay(e.trade.time, start, todayEpochDay, dayEndMillis)
                val price = if (PortfolioLedger.needsMarketPrice(e)) market(coin, day) else null
                val feePrice = if (PortfolioLedger.needsFeeMarketPrice(e)) market(e.trade.thirdFeeCoin!!, day) else null
                val amount = PortfolioLedger.flow(e, book.capFor(e), price, feePrice, book.method) ?: return null
                if (amount != 0.0 && amount.isFinite()) out += PortfolioCashFlow(day, amount * rate)
            }
            book.apply(e)
        }
        return out
    }

    /**
     * Zuflüsse der [coins] nach dem Ende von [start] (gleiche Regel wie [PortfolioPeriodChange.gain]: ein
     * Verkauf ohne Bestand zählt nicht); null, sobald einem davon der Preis fehlt. Tag = UTC-Tag der
     * Transaktion, höchstens [todayEpochDay].
     */
    private fun cashFlows(
        trades: List<PortfolioTrade>,
        coins: List<String>,
        start: Long,
        todayEpochDay: Long,
        dayEndMillis: (Long) -> Long,
        rate: Double,
        market: (String, Long) -> Double?,
        method: CostMethod,
    ): List<PortfolioCashFlow>? {
        val rangeEnd = dayEndMillis(start)
        val out = ArrayList<PortfolioCashFlow>()
        for (coin in coins) {
            val book = PortfolioHoldingsBook(method)
            for (e in PortfolioLedger.events(coin, trades)) {
                if (e.trade.time > rangeEnd) {
                    // Gleiche Zuflüsse wie «7 T» ([PortfolioLedger.flow]); unbekannt → keine Zuflüsse
                    val day = localDay(e.trade.time, start, todayEpochDay, dayEndMillis)
                    val price = if (PortfolioLedger.needsMarketPrice(e)) market(coin, day) else null
                    val feePrice = if (PortfolioLedger.needsFeeMarketPrice(e)) market(e.trade.thirdFeeCoin!!, day) else null
                    val amount = PortfolioLedger.flow(e, book.capFor(e), price, feePrice, book.method) ?: return null
                    if (amount != 0.0 && amount.isFinite()) {
                        out += PortfolioCashFlow(day, amount * rate)
                    }
                }
                book.apply(e)
            }
        }
        return out.sortedBy { it.epochDay }
    }

    /** Tag (UTC, [dayEndMillis]) von [millis] zwischen [from] und [to] (kleinster Tag, dessen Ende nicht davor liegt). */
    private fun localDay(millis: Long, from: Long, to: Long, dayEndMillis: (Long) -> Long): Long {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (dayEndMillis(mid) < millis) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Erster Tag des Verlaufs: erster Transaktionstag (UTC: kleinster Tag, dessen Ende nicht
     * vor [firstTradeMillis] liegt) oder Beginn des Zeitraums, je nachdem, was später ist;
     * nie nach [todayEpochDay]. «Seit 1. Kauf»: Beginn = erster Transaktionstag, frühestens
     * [DATA_START_EPOCH_DAY]. Tage nach UTC ([utcDayEnd], wie [build]); `dayEndMillis` bleibt für ältere Aufrufer.
     */
    fun startDay(
        range: PortfolioHistoryRange,
        firstTradeMillis: Long,
        todayEpochDay: Long,
        @Suppress("UNUSED_PARAMETER") dayEndMillis: (Long) -> Long = { utcDayEnd(it) },
    ): Long {
        val dayEndMillis: (Long) -> Long = { utcDayEnd(it) }
        var lo = if (range == PortfolioHistoryRange.SINCE_FIRST) {
            // Der lokale Tag liegt höchstens einen Tag neben dem UTC-Tag (Zeitzonen −12 h … +14 h)
            min(todayEpochDay, max(DATA_START_EPOCH_DAY, epochDayOfUtcMillis(firstTradeMillis) - 2))
        } else {
            todayEpochDay - range.days
        }
        var hi = todayEpochDay
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (dayEndMillis(mid) < firstTradeMillis) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** «Seit 1. Kauf» und der erste Kauf liegt vor dem ersten Tag mit Kursdaten ([DATA_START_EPOCH_DAY]). */
    fun isCapped(
        range: PortfolioHistoryRange,
        firstTradeMillis: Long,
        todayEpochDay: Long,
        @Suppress("UNUSED_PARAMETER") dayEndMillis: (Long) -> Long = { utcDayEnd(it) },
    ): Boolean = range == PortfolioHistoryRange.SINCE_FIRST && DATA_START_EPOCH_DAY <= todayEpochDay &&
        firstTradeMillis <= utcDayEnd(DATA_START_EPOCH_DAY - 1)

    /**
     * Tageskerzen je Coin für [range]: [MAX_DAYS] für 7 T, 30 T und 1 J (eine gemeinsame
     * Abfrage); «Seit 1. Kauf» bis zum Starttag samt einem Tag davor (Zeitzone), mindestens
     * [MAX_DAYS]. Ohne Transaktion ([firstTradeMillis] null) [MAX_DAYS].
     */
    fun candleDays(
        range: PortfolioHistoryRange,
        firstTradeMillis: Long?,
        todayEpochDay: Long,
        dayEndMillis: (Long) -> Long,
    ): Int {
        if (range != PortfolioHistoryRange.SINCE_FIRST || firstTradeMillis == null) return MAX_DAYS
        val start = startDay(range, firstTradeMillis, todayEpochDay, dayEndMillis)
        return max(MAX_DAYS, (todayEpochDay - start + 2).toInt())
    }

    /**
     * Abfragen für die letzten [days] UTC-Tage bis einschliesslich [todayUtcDay]: Stücke von
     * höchstens [perRequest] Tagen, aufsteigend und lückenlos; das letzte endet heute,
     * ein kürzeres Stück steht vorn. Leer bei [days] ≤ 0.
     */
    fun candleChunks(days: Int, todayUtcDay: Long, perRequest: Int = MAX_CANDLES_PER_REQUEST): List<LongRange> {
        if (days <= 0 || perRequest <= 0) return emptyList()
        val first = todayUtcDay - days + 1
        val out = ArrayList<LongRange>()
        var end = todayUtcDay
        while (end >= first) {
            val start = max(first, end - perRequest + 1)
            out += start..end
            end = start - 1
        }
        return out.reversed()
    }

    /** Bestand > 0 irgendwann ab [fromMillis] (Bestand davor oder eine spätere Transaktion mit Bestand). */
    private fun heldWithin(timeline: List<Pair<Long, Double>>, fromMillis: Long): Boolean {
        if (holdingsAt(timeline, fromMillis - 1) > 0.0) return true
        return timeline.any { (time, holdings) -> time >= fromMillis && holdings > 0.0 }
    }

    /**
     * Punkt unter dem Finger beim Ziehen über den Wertverlauf: Punkt i liegt bei
     * [left] + [width]·i/(count−1) → der nächstgelegene, an den Rändern der erste/letzte.
     * null ohne Punkte oder bei ungültigem x.
     */
    fun scrubIndex(x: Float, left: Float, width: Float, count: Int): Int? {
        if (count <= 0 || x.isNaN()) return null
        if (count == 1 || !(width > 0f)) return 0
        val i = ((x - left) / width * (count - 1)).roundToInt()
        return i.coerceIn(0, count - 1)
    }

    /** Schluss am Tag [day] oder der letzte davor; null vor der ersten Kerze. */
    internal fun closeOnOrBefore(sorted: List<Pair<Long, Double>>, day: Long): Double? {
        var lo = 0
        var hi = sorted.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].first <= day) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0) sorted[found].second else null
    }
}
