package com.cryptochecker.app.domain.portfolio

import kotlin.math.abs

/** Kurs eines Coins (USDT) zu einer Zeit (Epoch-ms). */
data class TimedPrice(val time: Long, val price: Double)

/** Veränderung über 24 Stunden in der Anzeigewährung; [percent] null ohne Ausgangswert. */
data class PortfolioChange(val amount: Double, val percent: Double?)

/**
 * Stündlicher 24-h-Wertverlauf des Portfolio-Widgets (reines Kotlin, getestet in
 * PortfolioWidgetSeriesTest; Swift-Gegenstück `PortfolioWidgetSeries.swift`).
 *
 * Quelle sind Stundenkurse, die ohnehin schon vorliegen — Mini-Charts der Merkliste
 * ([fromHourlyCloses]), Kerzen der Einzel-Widgets und die eigenen Kursaufnahmen — je Coin
 * gemerkt ([merge], höchstens einer je Stunde, [KEEP_MILLIS] lang). Das Widget selbst
 * fragt dafür nichts im Netz ab.
 *
 * Wert je Stunde = Σ heutiger Bestand × Kurs zu dieser Stunde. Fehlt einem Coin der Kurs,
 * zählt er mit dem aktuellen Kurs (flach). Eine Stunde gilt nur, wenn Coins mit Kurs
 * mindestens [MIN_COVERAGE] des heutigen Werts ausmachen; sonst fällt sie weg.
 * Bleiben weniger als [MIN_POINTS] Punkte, gibt es keinen Stundenverlauf (dann die
 * gespeicherten Aufnahmen bzw. «Verlauf folgt»); bei der Tages-Basis reichen [MIN_DAY_POINTS].
 * Fehlen einem Coin die Stundenkurse ([needsCandles]), lädt [candleCoins] einmal je Stunde
 * seine Stundenkerzen nach (PortfolioSnapshotUpdater).
 */
object PortfolioWidgetSeries {

    const val HOUR_MILLIS = 3_600_000L
    const val WINDOW_MILLIS = 24 * HOUR_MILLIS

    /** So lange werden Kurse je Coin gemerkt (etwas mehr als das Fenster). */
    const val KEEP_MILLIS = 26 * HOUR_MILLIS

    /** Ein Kurs zählt für eine Stunde, wenn er höchstens so viel älter ist. */
    const val TOLERANCE_MILLIS = 90 * 60_000L

    /** Mindestanteil des Werts mit echtem Kurs, damit eine Stunde zählt. */
    const val MIN_COVERAGE = 0.8

    /** Darunter kein Flächen-Chart (statt eines groben Dreiecks). */
    const val MIN_POINTS = 6

    /** Höchstens so viele Stunden hat ein Tag (Ende der Sommerzeit: 25). */
    private const val MAX_DAY_HOURS = 25

    /** Tages-Basis: ab so vielen Punkten (Tagesbeginn und jetzt) wächst der Chart über den Tag. */
    const val MIN_DAY_POINTS = 2

    /** Weniger Stunden mit Kurs in den letzten 24 h: Stundenkerzen für den Coin laden. */
    const val CANDLE_MIN_HOURS = 20

    /** Je Coin höchstens ein Kerzen-Abruf in dieser Zeit (auch wenn keine Quelle ihn kennt). */
    const val CANDLE_RETRY_MILLIS = HOUR_MILLIS

    /** Höchstens so viele Coins je Aktualisierung, die grössten zuerst. */
    const val CANDLE_MAX_COINS = 8

    /** Erst ab dieser Spanne gilt der Verlauf als «24 h» (Veränderung, Beschriftung). */
    const val DAY_MIN_SPAN_MILLIS = 20 * HOUR_MILLIS

    private fun valid(price: Double?): Double? = price?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Schlusskurse ohne Zeiten (Mini-Chart: Stundenkerzen, die letzte läuft noch) mit Zeiten
     * versehen: der letzte zur Abrufzeit [fetchedAt], davor je eine Stunde früher.
     */
    fun fromHourlyCloses(closes: List<Double>, fetchedAt: Long): List<TimedPrice> {
        if (fetchedAt <= 0L) return emptyList()
        val last = closes.size - 1
        return closes.mapIndexedNotNull { i, close ->
            valid(close)?.let { TimedPrice(fetchedAt - (last - i) * HOUR_MILLIS, it) }
        }
    }

    /**
     * Kerzen mit Startzeit: Schluss am Ende der Stunde, die laufende Kerze zur Abrufzeit.
     * @param candles Paare (Startzeit, Schlusskurs)
     */
    fun fromCandles(candles: List<Pair<Long, Double>>, fetchedAt: Long): List<TimedPrice> =
        candles.mapNotNull { (open, close) ->
            val price = valid(close) ?: return@mapNotNull null
            val end = open + HOUR_MILLIS
            TimedPrice(if (fetchedAt > 0L) minOf(end, fetchedAt) else end, price)
        }

    /**
     * Gemerkte und neue Kurse zusammenführen: je Coin (Grossschreibung) höchstens ein Kurs
     * je Stunde — der jüngste —, nur die letzten [KEEP_MILLIS], nichts aus der Zukunft.
     * Aufsteigend nach Zeit; Coins ohne Kurs fallen weg.
     */
    fun merge(
        stored: Map<String, List<TimedPrice>>,
        fresh: Map<String, List<TimedPrice>>,
        now: Long,
    ): Map<String, List<TimedPrice>> {
        val out = HashMap<String, List<TimedPrice>>()
        for (coin in stored.keys + fresh.keys) {
            val key = coin.trim().uppercase()
            if (key.isEmpty()) continue
            val byHour = HashMap<Long, TimedPrice>()
            val all = (out[key].orEmpty()) + stored[coin].orEmpty() + fresh[coin].orEmpty()
            for (p in all) {
                if (valid(p.price) == null || p.time > now || now - p.time > KEEP_MILLIS) continue
                val hour = Math.floorDiv(p.time, HOUR_MILLIS)
                val current = byHour[hour]
                if (current == null || p.time >= current.time) byHour[hour] = p
            }
            if (byHour.isNotEmpty()) out[key] = byHour.values.sortedBy { it.time }
        }
        return out
    }

    /** Jüngster Kurs, der höchstens [TOLERANCE_MILLIS] vor [time] liegt (nicht danach); sonst null. */
    fun priceAt(prices: List<TimedPrice>?, time: Long): Double? {
        if (prices.isNullOrEmpty()) return null
        var best: TimedPrice? = null
        for (p in prices) {
            if (p.time > time || time - p.time > TOLERANCE_MILLIS) continue
            if (best == null || p.time > best.time) best = p
        }
        return best?.let { valid(it.price) }
    }

    /**
     * Stündlicher Wertverlauf des heutigen Bestands in der Anzeigewährung: Stunden
     * [now] − 24 h … [now] − 1 h (nur Stunden mit Abdeckung ≥ [MIN_COVERAGE]) und als
     * letzter Punkt der aktuelle Wert. [stables] (USD-Stablecoins) gelten als abgedeckt
     * (Kurs ≈ 1, flach ist richtig). Ohne offenen Bestand mit Kurs: leer.
     * @param holdings Menge je Coin
     * @param current aktueller USDT-Kurs je Coin
     * @param prices gemerkte Kurse je Coin ([merge])
     */
    fun hourly(
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        prices: Map<String, List<TimedPrice>>,
        now: Long,
        fxRate: Double,
        stables: Set<String>,
    ): List<PortfolioValuePoint> =
        pointsAt((24 downTo 1).map { now - it * HOUR_MILLIS }, holdings, current, prices, now, fxRate, stables)

    /**
     * Tages-Basis der %-Änderung: Wertverlauf seit Tagesbeginn [dayStart] — Punkte zu
     * [dayStart], [dayStart] + 1 h, … vor [now] (gleiche Abdeckungsregel wie [hourly]) und als
     * letzter Punkt der aktuelle Wert. Der erste Punkt fehlt, wenn zum Tagesbeginn kein Kurs bekannt ist.
     */
    fun hourlySince(
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        prices: Map<String, List<TimedPrice>>,
        dayStart: Long,
        now: Long,
        fxRate: Double,
        stables: Set<String>,
    ): List<PortfolioValuePoint> {
        if (dayStart > now) return emptyList()
        val times = generateSequence(dayStart) { it + HOUR_MILLIS }.takeWhile { it < now }.take(MAX_DAY_HOURS).toList()
        return pointsAt(times, holdings, current, prices, now, fxRate, stables)
    }

    /** Wertpunkte zu den Zeiten [times] (nur mit Abdeckung ≥ [MIN_COVERAGE]) und der aktuelle Wert. */
    private fun pointsAt(
        times: List<Long>,
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        prices: Map<String, List<TimedPrice>>,
        now: Long,
        fxRate: Double,
        stables: Set<String>,
    ): List<PortfolioValuePoint> {
        val open = holdings.mapNotNull { (coin, amount) ->
            val price = valid(current[coin]) ?: return@mapNotNull null
            if (!(amount > PortfolioCalculator.EPS) || amount.isInfinite()) return@mapNotNull null
            Triple(coin, amount, price)
        }
        val total = open.sumOf { (_, amount, price) -> amount * price }
        if (open.isEmpty() || !(total > 0.0)) return emptyList()
        val points = ArrayList<PortfolioValuePoint>(times.size + 1)
        for (t in times) {
            var covered = 0.0
            var value = 0.0
            for ((coin, amount, price) in open) {
                val then = if (coin.uppercase() in stables) price else priceAt(prices[coin.uppercase()], t)
                if (then != null) {
                    covered += amount * price
                    value += amount * then
                } else {
                    value += amount * price
                }
            }
            if (covered / total >= MIN_COVERAGE) points += PortfolioValuePoint(t, value * fxRate)
        }
        points += PortfolioValuePoint(now, total * fxRate)
        return points
    }

    /** Verlauf gross genug für den Flächen-Chart? */
    fun drawable(points: List<PortfolioValuePoint>): Boolean = points.count { it.value.isFinite() } >= MIN_POINTS

    /**
     * Tages-Basis: Chart schon ab [MIN_DAY_POINTS] Punkten — er beginnt beim Tagesbeginn und
     * füllt sich über den Tag (feste Zeitachse bis zum Tagesende), statt bis zum Morgen
     * «Verlauf folgt» zu zeigen.
     */
    fun drawableDay(points: List<PortfolioValuePoint>): Boolean = points.count { it.value.isFinite() } >= MIN_DAY_POINTS

    /** Hat [prices] weniger als [CANDLE_MIN_HOURS] Stunden mit Kurs in den 24 h vor [now]? */
    fun needsCandles(prices: List<TimedPrice>?, now: Long): Boolean =
        prices.orEmpty()
            .filter { valid(it.price) != null && it.time <= now && now - it.time <= WINDOW_MILLIS }
            .map { Math.floorDiv(it.time, HOUR_MILLIS) }
            .toSet().size < CANDLE_MIN_HOURS

    /**
     * Coins, für die jetzt Stundenkerzen geladen werden: aus [coins] (Reihenfolge = Vorrang,
     * z. B. nach Wert), ohne [stables], nur mit Lücken ([needsCandles]) und wenn der letzte
     * Versuch ([lastAttempt], Grossschreibung) mindestens [CANDLE_RETRY_MILLIS] her ist;
     * höchstens [CANDLE_MAX_COINS]. Ergebnis in Grossschreibung.
     */
    fun candleCoins(
        coins: List<String>,
        prices: Map<String, List<TimedPrice>>,
        lastAttempt: Map<String, Long>,
        now: Long,
        stables: Set<String>,
    ): List<String> = coins.asSequence()
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() && it !in stables }
        .distinct()
        .filter { coin -> lastAttempt[coin]?.let { now - it in 0 until CANDLE_RETRY_MILLIS } != true }
        .filter { needsCandles(prices[it], now) }
        .take(CANDLE_MAX_COINS)
        .toList()

    /** Deckt der Verlauf rund einen Tag ab (erster Punkt mindestens [DAY_MIN_SPAN_MILLIS] vor dem letzten)? */
    fun coversDay(points: List<PortfolioValuePoint>): Boolean =
        points.size >= 2 && points.last().time - points.first().time >= DAY_MIN_SPAN_MILLIS

    /**
     * Veränderung über den Verlauf: letzter gegen ersten Wert. Nur wenn er [coversDay]
     * und mindestens [MIN_POINTS] Punkte hat; sonst null.
     */
    fun change(points: List<PortfolioValuePoint>): PortfolioChange? {
        if (!drawable(points) || !coversDay(points)) return null
        val first = points.first().value
        val last = points.last().value
        if (!first.isFinite() || !last.isFinite()) return null
        val amount = last - first
        return PortfolioChange(amount, if (first > 0.0) amount / first * 100.0 else null)
    }

    /**
     * Kursveränderung je Coin über 24 h in Prozent: aktueller Kurs gegen den ältesten
     * gemerkten Kurs zwischen [now] − 25 h und [now] − 20 h. Coins ohne solchen Kurs fehlen.
     */
    fun coinChanges(
        current: Map<String, Double>,
        prices: Map<String, List<TimedPrice>>,
        now: Long,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((coin, price) in current) {
            val nowPrice = valid(price) ?: continue
            val then = prices[coin.uppercase()].orEmpty()
                .filter { now - it.time in DAY_MIN_SPAN_MILLIS..(WINDOW_MILLIS + HOUR_MILLIS) }
                .minByOrNull { it.time }
                ?.let { valid(it.price) } ?: continue
            out[coin] = (nowPrice / then - 1.0) * 100.0
        }
        return out
    }

    /**
     * Tages-Basis: Veränderung seit Tagesbeginn — nur wenn der erste Punkt genau [dayStart] ist
     * (Wert zum Tagesbeginn bekannt) und danach mindestens der aktuelle folgt; sonst null.
     */
    fun changeSince(points: List<PortfolioValuePoint>, dayStart: Long): PortfolioChange? {
        if (points.size < 2 || points.first().time != dayStart) return null
        val first = points.first().value
        val last = points.last().value
        if (!first.isFinite() || !last.isFinite()) return null
        val amount = last - first
        return PortfolioChange(amount, if (first > 0.0) amount / first * 100.0 else null)
    }

    /** Kursveränderung je Coin seit Tagesbeginn: aktueller Kurs gegen den Kurs zu [dayStart] ([priceAt]). */
    fun coinChangesSince(
        current: Map<String, Double>,
        prices: Map<String, List<TimedPrice>>,
        dayStart: Long,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((coin, price) in current) {
            val nowPrice = valid(price) ?: continue
            val then = priceAt(prices[coin.uppercase()], dayStart) ?: continue
            out[coin] = (nowPrice / then - 1.0) * 100.0
        }
        return out
    }

    /** Richtung für Farbe und Pfeil: 1 steigend, −1 fallend, 0 unverändert (unter einem halben Rappen/Cent). */
    fun direction(amount: Double): Int = when {
        abs(amount) < 0.005 -> 0
        amount > 0 -> 1
        else -> -1
    }
}
