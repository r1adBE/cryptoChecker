package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.widget.ChartLevels
import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartGeometry
import com.cryptochecker.app.widget.WidgetChartType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Zeitraum des Charts im Aktionsblatt eines Paars. Gleiche Namen wie `WidgetChartRange`
 * des Einzel-Widgets — von dort kommen Kerzenintervall, Anzahl (24 × 1 h, 42 × 4 h,
 * 30 × 1 Tag, 365 × 1 Tag nur im Blatt), Gitter und Beschriftungen. Hier nur, was das Blatt zusätzlich braucht:
 * Gültigkeit im Zwischenspeicher und die Vorlage für Datum/Uhrzeit beim Ziehen
 * (`DateFormat.getBestDateTimePattern` bzw. `setLocalizedDateFormatFromTemplate`).
 */
enum class SheetChartRange(val cacheMillis: Long, val timeTemplate: String) {
    /** «Di 14:00» */
    DAY(5 * 60_000L, "EEEjm"),
    /** «Di 14. Okt. 16:00» (4-h-Kerzen) */
    WEEK(30 * 60_000L, "EEEdMMMjm"),
    /** «Di 14. Okt.» (Tageskerzen) */
    MONTH(30 * 60_000L, "EEEdMMM"),
    /** «14. Okt. 2025» (365 Tageskerzen) */
    YEAR(60 * 60_000L, "dMMMy");

    /** Skelett für ICU/`DateFormatter`: «j» je nach 12/24-h-Einstellung als «H» bzw. «h». */
    fun timeSkeleton(is24: Boolean): String = timeTemplate.replace("j", if (is24) "H" else "h")
}

/** Umrechnung der Ausweich-Reihe (USDT → Fiat-Quote des Paars). */
enum class SheetChartFx {
    /** Keine Umrechnung: Kerzen des Paars selbst. */
    NONE,

    /** Je Kerze mit dem Devisenkurs ihres Tags («umgerechnet mit Tageskursen»). */
    DAILY,

    /** Ohne Tageskurse: ganze Reihe mit einem Faktor (aktueller Kurs) — «umgerechnet (aktueller Kurs)». */
    CURRENT,
}

/** Ergebnis für das Blatt: gar nicht zeigen, keine Kerzen oder fertige Kerzen. */
sealed interface SheetChartResult {
    /** DEX-Paar oder Kürzel, die die Kerzenquelle nie liefern kann: Chart-Block ausblenden. */
    data object Unsupported : SheetChartResult

    /** Keine Quelle lieferte passende Kerzen: «Keine Kursdaten für dieses Paar». */
    data object NoData : SheetChartResult

    /**
     * Kerzen in der Quote des Paars; [converted] = aus der USDT-Reihe umgerechnet.
     * Für die Zeile unter dem Chart: [provider] = Börse der Kerzen («Binance», «Coinbase» …),
     * [sourcePair] = abgefragte Reihe («BTC/USDT»), [fx] = Art der Umrechnung,
     * [fetchedAt] = Abfragezeit (Epoch-ms; 0 = unbekannt).
     */
    data class Ready(
        val candles: List<WidgetCandle>,
        val converted: Boolean,
        val provider: String? = null,
        val sourcePair: String? = null,
        val fx: SheetChartFx = SheetChartFx.NONE,
        val fetchedAt: Long = 0L,
    ) : SheetChartResult
}

/** Eine Kerzenabfrage: Paar oder Ausweich-Reihe gegen USDT ([convert] = umrechnen). */
data class SheetCandleRequest(val base: String, val quote: String, val convert: Boolean)

/**
 * Reine Regeln des Charts im Aktionsblatt (ohne Android), getestet in SheetChartTest.
 * Zeichnen: Geometrie des Einzel-Widgets ([WidgetChartGeometry]).
 */
object SheetChart {

    /** Markt-Schlüssel von DexScreener (wie im Hinzufügen-Tab): keine Börsen-Kerzen. */
    const val DEX_MARKET_KEY = "DexScreener"

    /** Kurzes Ticken nur beim Wechsel auf eine andere Kerze. */
    fun isNewCandle(previous: Int?, current: Int?): Boolean = current != null && previous != current

    /** Gleiche Regel wie die Kerzenquelle: nur Buchstaben und Ziffern. */
    private fun isAsset(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.all { it.isLetterOrDigit() }
    }

    /** Kann die Kerzenquelle das Paar überhaupt liefern? Sonst Block ganz ausblenden. */
    fun isSupported(marketKey: String, base: String, quote: String): Boolean =
        !marketKey.equals(DEX_MARKET_KEY, ignoreCase = true) && isAsset(base) && isAsset(quote)

    /**
     * Abfragen in fester Reihenfolge — wie der 24-h-Bezug ([DayChange.select]):
     *  1. das Paar selbst (USD-artige Quotes auf die USDT-Reihe, [DayChange.candleQuote]),
     *  2. bei Fiat-Quote ohne eigene Kerzen die USDT-Reihe des Basis-Assets, umgerechnet.
     */
    fun requests(base: String, quote: String, quoteIsFiat: Boolean): List<SheetCandleRequest> {
        val b = base.trim().uppercase()
        val q = DayChange.candleQuote(quote)
        val pair = SheetCandleRequest(b, q, convert = false)
        return if (quoteIsFiat && q != USDT) listOf(pair, SheetCandleRequest(b, USDT, convert = true)) else listOf(pair)
    }

    /**
     * Prüft und wandelt Kerzen einer Abfrage; null = nicht brauchbar (nächste Abfrage).
     *  - Paar: Weicht der Kurs mehr als [DayChange.MAX_PRICE_GAP] vom letzten Schluss ab,
     *    gehören Kerzen und Kurs wohl nicht zum selben Coin. Ohne Kurs des Paars nicht prüfbar → null.
     *  - Ausweich-Reihe (umrechnen): ZUERST gleicher Token — letzter Schluss × Devisenkurs (Tageskurs
     *    der letzten Kerze, sonst [currentRate]) darf höchstens [DayChange.MAX_PRICE_GAP] vom Kurs des
     *    Paars abweichen. Ohne Kurs des Paars oder ganz ohne Devisenkurs ist das nicht prüfbar → null
     *    (kein Chart statt einer blind auf den Kurs gezogenen fremden Reihe).
     *  - Umrechnung mit Tageskursen ([dailyRates], Tag seit 1970 (UTC) → Fiat je USD): jede Kerze
     *    × Kurs IHRES Tags (Stunden- und 4-h-Kerzen den Kurs ihres Tags; fehlender Tag → letzter
     *    Kurs davor, vor dem ersten Kurs der erste — [com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx.rateOn]).
     *    Dazu ein gemeinsamer Faktor, damit der letzte Schluss dem Kurs des Paars entspricht.
     *  - Ohne Tageskurse: USDT-Reihe mit einem Faktor so skaliert, dass der letzte Schluss dem Kurs
     *    des Paars entspricht (Verlauf in Prozent wie [DayChange.fromSeries]).
     * @param currentRate aktueller Devisenkurs Fiat je USD der Quote (nur für die Prüfung ohne Tageskurse)
     */
    fun accept(
        candles: List<WidgetCandle>?,
        request: SheetCandleRequest,
        price: Double?,
        dailyRates: Map<Long, Double>? = null,
        currentRate: Double? = null,
    ): List<WidgetCandle>? {
        val clean = candles.orEmpty().filter {
            it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0
        }
        if (clean.size < 2) return null
        val last = clean.last().close
        // Gleicher Token? Ohne Kurs des Paars nicht prüfbar (auch nicht beim Paar selbst)
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (!request.convert) {
            if (abs(p / last - 1.0) > DayChange.MAX_PRICE_GAP) return null
            return clean
        }
        val sorted = sortedRates(dailyRates)
        if (sorted.isEmpty()) {
            val rate = currentRate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            if (abs(p / (last * rate) - 1.0) > DayChange.MAX_PRICE_GAP) return null
            val factor = p / last
            // Umsatz in der Quote mit demselben Faktor (USDT → Quote des Paars, Näherung)
            return clean.map { it.scaled(factor) }
        }
        val rates = clean.map { rateFor(sorted, it.openTime) }
        val converted = last * rates.last()
        if (abs(p / converted - 1.0) > DayChange.MAX_PRICE_GAP) return null
        val anchor = p / converted
        return clean.mapIndexed { i, c -> c.scaled(rates[i] * anchor) }
    }

    /**
     * Mini-Chart der Merkliste: Die Reihe ist immer die USDT-Reihe des Basis-Assets (oft von einer
     * anderen Börse als das Paar, z. B. Binance für ein Kraken-Paar). Gezeigt nur bei gleichem Token
     * — wie [accept]: letzter Schluss gegen Kurs des Paars [price] × [quoteUsd] (USD je Einheit der
     * Quote, z. B. 1 für USDT, ~1.1 für EUR) höchstens [DayChange.MAX_PRICE_GAP] daneben.
     * Ohne Kurs oder ohne Umrechnungsfaktor nicht prüfbar → null (kein Mini-Chart).
     */
    fun sparklineFor(closes: List<Double>?, price: Double?, quoteUsd: Double?): List<Double>? {
        val values = closes?.takeIf { it.size >= 2 } ?: return null
        val last = values.last().takeIf { it.isFinite() && it > 0.0 } ?: return null
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val rate = quoteUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return values.takeIf { abs(p * rate / last - 1.0) <= DayChange.MAX_PRICE_GAP }
    }

    /** Art der Umrechnung für die Zeile unter dem Chart (wie [accept] sie anwendet). */
    fun fxMode(request: SheetCandleRequest, dailyRates: Map<Long, Double>?): SheetChartFx = when {
        !request.convert -> SheetChartFx.NONE
        sortedRates(dailyRates).isNotEmpty() -> SheetChartFx.DAILY
        else -> SheetChartFx.CURRENT
    }

    /**
     * Eigene Zeile unter dem Chart, wenn die Ausweich-Reihe mit EINEM heutigen Devisenkurs umgerechnet und am letzten
     * Punkt auf den Kurs des Paars gezogen ist ([SheetChartFx.CURRENT]): «Verlauf in USDT, mit heutigem Kurs in CHF
     * umgerechnet». Zurück (Währung der Reihe, Währung des Paars); null = Kerzen des Paars selbst oder Tageskurse.
     */
    fun todayRateCaption(fx: SheetChartFx, sourcePair: String?, quote: String): Pair<String, String>? {
        if (fx != SheetChartFx.CURRENT) return null
        val target = quote.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        val source = sourcePair?.substringAfterLast('/', "")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: USDT
        return source to target
    }

    /**
     * Vermerk «USDT = 1 angenommen» unter einem Fiat-Paar-Chart: die USDT-Reihe ist mit Devisenkursen USD → Fiat
     * umgerechnet ([SheetChartFx.DAILY] bzw. [SheetChartFx.CURRENT]), also mit USDT = 1 USD. Nur bei Umrechnung aus USDT.
     */
    fun usdtParityNote(fx: SheetChartFx, sourcePair: String?): Boolean {
        if (fx == SheetChartFx.NONE) return false
        val source = sourcePair?.substringAfterLast('/', "")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: USDT
        return source == USDT
    }

    /** Gültige Tageskurse aufsteigend nach Tag. */
    private fun sortedRates(dailyRates: Map<Long, Double>?): List<Pair<Long, Double>> =
        dailyRates.orEmpty().entries
            .filter { it.value.isFinite() && it.value > 0.0 }
            .map { it.key to it.value }
            .sortedBy { it.first }

    /** Kurs des (UTC-)Tags der Kerze; fehlt er, der letzte davor, vor dem ersten der erste. */
    private fun rateFor(sorted: List<Pair<Long, Double>>, openTime: Long): Double {
        val day = Math.floorDiv(openTime, DAY_MILLIS)
        return com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx.rateOn(sorted, day) ?: sorted.first().second
    }

    private fun WidgetCandle.scaled(factor: Double): WidgetCandle =
        copy(open = open * factor, high = high * factor, low = low * factor, close = close * factor,
            volume = volume?.times(factor))

    /** Tage seit 1970 (UTC), für die Devisen-Abfrage passend zu [accept]. */
    fun utcEpochDay(millis: Long): Long = Math.floorDiv(millis, DAY_MILLIS)

    /**
     * Umsatz nur genähert (Menge × Schluss), weil die Quelle keinen Quote-Umsatz liefert
     * (Coinbase): Balken heller, Wert mit «≈».
     */
    fun isVolumeApproximated(quoteVolume: Double?): Boolean =
        quoteVolume == null || !quoteVolume.isFinite() || quoteVolume < 0.0

    /** Ab dieser Abweichung des Live-Kurses (3 %) gilt der Zwischenspeicher als überholt. */
    const val PRICE_JUMP = 0.03

    /**
     * Ist der Kurs seit dem Zwischenspeichern gesprungen? [reference] = Kurs beim Speichern,
     * ersatzweise der letzte Schluss der gespeicherten Kerzen. Ungültige Werte: nein.
     */
    fun priceJumped(reference: Double?, price: Double?): Boolean {
        val r = reference?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        return abs(p / r - 1.0) > PRICE_JUMP
    }

    /** Mindesthöhe der Achse bei Stablecoins: ±0.5 % um die Mitte (1 % der Mitte). */
    const val STABLE_MIN_SPAN = 0.01

    /** Mindesthöhe sonst: ±0.1 % — nur für extrem ruhige Reihen (Rauschen statt Absturz). */
    const val MIN_SPAN = 0.002

    /**
     * Preisstufen des Blatts: wie das Widget ([WidgetChartGeometry.levels]), aber mindestens
     * [STABLE_MIN_SPAN] (Stablecoin) bzw. [MIN_SPAN] der Mitte hoch, um die Mitte gelegt —
     * kleine Peg-Wackler sehen so nicht wie ein Absturz aus.
     */
    fun axisLevels(candles: List<WidgetCandle>, type: WidgetChartType, stable: Boolean): ChartLevels =
        widenLevels(WidgetChartGeometry.levels(candles, type), if (stable) STABLE_MIN_SPAN else MIN_SPAN)

    /** Spanne von [levels] auf mindestens [minFraction] × Mitte aufziehen (mittig). */
    fun widenLevels(levels: ChartLevels, minFraction: Double): ChartLevels {
        val mid = (levels.low + levels.high) / 2.0
        if (!mid.isFinite() || mid <= 0.0) return levels
        val minSpan = mid * minFraction
        if (levels.high - levels.low >= minSpan) return levels
        return ChartLevels(low = mid - minSpan / 2.0, mid = mid, high = mid + minSpan / 2.0)
    }

    /**
     * «Stand» unter dem Chart: Abfragezeit, höchstens aber das Ende der letzten Kerze (endet die
     * Reihe früher, zählt ihr Ende). [fetchedAt] ≤ 0 = Ende der letzten Kerze.
     */
    fun stampMillis(lastOpenTime: Long, intervalMillis: Long, fetchedAt: Long): Long {
        val end = lastOpenTime + intervalMillis
        return if (fetchedAt > 0L) minOf(fetchedAt, end) else end
    }

    /** Skelett für den «Stand»: heute nur Uhrzeit, sonst Datum und Uhrzeit (Zeitzone [zone]). */
    fun stampTemplate(stamp: Long, now: Long, zone: java.time.ZoneId): String {
        val day = java.time.Instant.ofEpochMilli(stamp).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (day == today) "jm" else "dMMMjm"
    }

    /** Anteil der Volumen-Balken an der Höhe der Zeichenfläche (unten im Chart). */
    const val VOLUME_HEIGHT_FRACTION = 0.18f

    /**
     * Umsatz einer Kerze in der Quote: [quoteVolume] der Quelle (Binance), sonst genähert als
     * Menge [baseVolume] × Schluss [close] (Coinbase liefert nur die Menge). null ohne gültige Werte.
     */
    fun quoteVolume(baseVolume: Double, quoteVolume: Double?, close: Double): Double? {
        quoteVolume?.takeIf { it.isFinite() && it >= 0.0 }?.let { return it }
        if (!baseVolume.isFinite() || baseVolume < 0.0 || !close.isFinite() || close <= 0.0) return null
        return baseVolume * close
    }

    /**
     * Höhe der Volumen-Balken je Kerze als Anteil (0..1) des grössten Umsatzes; Kerzen ohne
     * gültigen Umsatz bekommen 0. null = ausblenden: weniger als zwei Kerzen mit Umsatz > 0.
     */
    fun volumeFractions(volumes: List<Double?>): List<Float>? {
        val valid = volumes.map { v -> v?.takeIf { it.isFinite() && it > 0.0 } }
        if (valid.count { it != null } < 2) return null
        val max = valid.maxOf { it ?: 0.0 }
        return valid.map { v -> if (v == null) 0f else (v / max).toFloat().coerceIn(0f, 1f) }
    }

    /** Kerze unter dem Finger: Kerze i belegt [plotLeft + i·slot, plotLeft + (i+1)·slot). */
    fun scrubIndex(x: Float, plotLeft: Float, slotWidth: Float, count: Int): Int? {
        if (count <= 0 || !(slotWidth > 0f) || x.isNaN()) return null
        val i = floor((x - plotLeft) / slotWidth).toInt()
        return i.coerceIn(0, count - 1)
    }

    /** Veränderung in Prozent gegen [start]; null ohne gültigen Start. */
    fun changePercent(start: Double, value: Double): Double? {
        if (!start.isFinite() || !value.isFinite() || start <= 0.0) return null
        return (value - start) / start * 100.0
    }

    /** Veränderung über den Zeitraum — gleiche Kennzahlen wie der Screenreader-Satz. */
    fun rangeChange(candles: List<WidgetCandle>, type: WidgetChartType): Double? =
        WidgetChartGeometry.summary(candles, type)?.changePercent

    /**
     * Zahl über dem Chart: bei 24h dieselbe wie die Änderung neben dem Kurs ([dayChange] =
     * `shownChange24h` aus dem Ticker), damit das Blatt nicht zwei verschiedene 24-h-Werte
     * zeigt; ohne diesen Wert und bei 7T/30T/1J die Veränderung aus den Kerzen ([rangeChange]).
     */
    fun headerChange(range: SheetChartRange, candles: List<WidgetCandle>, type: WidgetChartType, dayChange: Double?): Double? =
        if (range == SheetChartRange.DAY && dayChange != null && dayChange.isFinite()) dayChange
        else rangeChange(candles, type)

    /** Schluss der Kerze [index] gegen den Beginn des Zeitraums (wie [rangeChange]). */
    fun scrubChange(candles: List<WidgetCandle>, type: WidgetChartType, index: Int): Double? {
        val start = WidgetChartGeometry.summary(candles, type)?.start ?: return null
        val candle = candles.getOrNull(index) ?: return null
        return changePercent(start, candle.close)
    }

    /**
     * Linker Rand des Etiketts beim Ziehen: mittig über der Markierung [centerX], aber
     * ganz innerhalb von [minX]..[maxX]; breiter als die Fläche → am linken Rand.
     */
    fun labelLeft(centerX: Float, width: Float, minX: Float, maxX: Float): Float {
        val left = centerX - width / 2f
        val maxLeft = max(minX, maxX - width)
        return left.coerceIn(minX, maxLeft)
    }

    private const val USDT = "USDT"
    private const val DAY_MILLIS = 86_400_000L
}

/**
 * Kerzen des Blatts je Paar und Zeitraum im Speicher: 5 Minuten (24 h),
 * 30 Minuten (7 und 30 Tage) bzw. 60 Minuten (1 Jahr), siehe [SheetChartRange.cacheMillis]. Nur Erfolge.
 * Zusätzlich verworfen: von Hand aktualisiert ([invalidate], [invalidateAll]) oder der Live-Kurs
 * ist seit dem Speichern um mehr als [SheetChart.PRICE_JUMP] gesprungen ([SheetChart.priceJumped]).
 */
class SheetChartCache(private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val time: Long, val value: SheetChartResult.Ready, val price: Double?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * [price] = aktueller Kurs des Paars; null = Sprung nicht prüfen. [market] = Börse des Paars
     * (eigene Kerzen je Börse, z. B. `CandlePair.cacheKey`); leer = nur Kürzel.
     */
    fun get(base: String, quote: String, range: SheetChartRange, price: Double? = null, market: String = ""): SheetChartResult.Ready? {
        val k = key(base, quote, range, market)
        val entry = entries[k] ?: return null
        val age = now() - entry.time
        // Bezug: Kurs beim Speichern (Kerzen und Kurs dürfen je nach Börse leicht auseinanderliegen)
        val reference = entry.price ?: entry.value.candles.lastOrNull()?.close
        if (age in 0 until range.cacheMillis && !SheetChart.priceJumped(reference, price)) return entry.value
        entries.remove(k)
        return null
    }

    /** [price] = Kurs des Paars beim Laden (Bezug für den Sprung). */
    fun put(
        base: String, quote: String, range: SheetChartRange, value: SheetChartResult.Ready, price: Double? = null,
        market: String = "",
    ) {
        entries[key(base, quote, range, market)] = Entry(now(), value, price?.takeIf { it.isFinite() && it > 0.0 })
    }

    /** Alle Zeiträume eines Paars verwerfen (Aktualisieren im Blatt). */
    fun invalidate(base: String, quote: String, market: String = "") {
        val prefix = prefix(base, quote, market)
        entries.keys.removeAll { it.startsWith(prefix) }
    }

    /** Alles verwerfen (nach unten ziehen in der Merkliste). */
    fun invalidateAll() {
        entries.clear()
    }

    private fun prefix(base: String, quote: String, market: String): String =
        (if (market.isEmpty()) "" else "$market#") + "${base.trim().uppercase()}|${quote.trim().uppercase()}|"

    private fun key(base: String, quote: String, range: SheetChartRange, market: String): String =
        prefix(base, quote, market) + range.name
}
