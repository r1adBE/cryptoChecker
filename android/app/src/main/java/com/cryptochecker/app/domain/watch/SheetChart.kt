package com.cryptochecker.app.domain.watch

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
 * 30 × 1 Tag), Gitter und Beschriftungen. Hier nur, was das Blatt zusätzlich braucht:
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
}

/** Ergebnis für das Blatt: gar nicht zeigen, keine Kerzen oder fertige Kerzen. */
sealed interface SheetChartResult {
    /** DEX-Paar oder Kürzel, die die Kerzenquelle nie liefern kann: Chart-Block ausblenden. */
    data object Unsupported : SheetChartResult

    /** Keine Quelle lieferte passende Kerzen: «Keine Kursdaten für dieses Paar». */
    data object NoData : SheetChartResult

    /** Kerzen in der Quote des Paars; [converted] = aus der USDT-Reihe umgerechnet. */
    data class Ready(val candles: List<WidgetCandle>, val converted: Boolean) : SheetChartResult
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
     *    gehören Kerzen und Kurs wohl nicht zum selben Coin.
     *  - Umrechnung: USDT-Reihe so skaliert, dass der letzte Schluss dem Kurs des Paars
     *    entspricht (Verlauf in Prozent wie [DayChange.fromSeries]); ohne Kurs nicht möglich.
     */
    fun accept(candles: List<WidgetCandle>?, request: SheetCandleRequest, price: Double?): List<WidgetCandle>? {
        val clean = candles.orEmpty().filter {
            it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0
        }
        if (clean.size < 2) return null
        val last = clean.last().close
        val p = price?.takeIf { it.isFinite() && it > 0.0 }
        if (!request.convert) {
            if (p != null && abs(p / last - 1.0) > DayChange.MAX_PRICE_GAP) return null
            return clean
        }
        val factor = (p ?: return null) / last
        return clean.map {
            WidgetCandle(it.openTime, it.open * factor, it.high * factor, it.low * factor, it.close * factor)
        }
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
}

/**
 * Kerzen des Blatts je Paar und Zeitraum im Speicher: 5 Minuten (24 h) bzw.
 * 30 Minuten (7 und 30 Tage), siehe [SheetChartRange.cacheMillis]. Nur Erfolge.
 */
class SheetChartCache(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = ConcurrentHashMap<String, Pair<Long, SheetChartResult.Ready>>()

    fun get(base: String, quote: String, range: SheetChartRange): SheetChartResult.Ready? {
        val k = key(base, quote, range)
        val (time, value) = entries[k] ?: return null
        val age = now() - time
        if (age in 0 until range.cacheMillis) return value
        entries.remove(k)
        return null
    }

    fun put(base: String, quote: String, range: SheetChartRange, value: SheetChartResult.Ready) {
        entries[key(base, quote, range)] = now() to value
    }

    private fun key(base: String, quote: String, range: SheetChartRange): String =
        "${base.trim().uppercase()}|${quote.trim().uppercase()}|${range.name}"
}
