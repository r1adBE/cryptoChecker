package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.portfolio.PortfolioStables
import kotlin.math.abs

/**
 * 24-h-Bezug aus Stundenkerzen (24 × 1 h, wie der Mini-Chart): Eröffnung der
 * ersten Kerze und Schluss der letzten (läuft in der Regel noch).
 */
data class DayReference(val open: Double, val lastClose: Double) {
    companion object {
        /** null, wenn ein Wert fehlt, nicht endlich oder nicht positiv ist. */
        fun of(open: Double?, lastClose: Double?): DayReference? {
            if (open == null || lastClose == null) return null
            if (!open.isFinite() || !lastClose.isFinite() || open <= 0.0 || lastClose <= 0.0) return null
            return DayReference(open, lastClose)
        }
    }
}

/**
 * Veränderung über 24 Stunden für Prozent-Änderung, Puls-Zeile und Widgets.
 *
 * Reihenfolge ([choose]):
 *  0. Rollende 24-h-Veränderung, die die Börse im Ticker mitliefert
 *     (`Ticker.change24hPercent`, siehe DEVELOPMENT.md (Repository-Hauptordner) «24 h change») — gilt für das Paar
 *     selbst, also schon in seiner Quote. Nur endlich und |x| < [MAX_TICKER_CHANGE].
 * Fehlt er, kommt der Bezug aus Stundenkerzen ([select]):
 *  1. Kerzen des Paars selbst (USD-artige Quotes teilen sich die USDT-Reihe des
 *     Mini-Charts): aktueller Kurs gegen die Eröffnung vor 24 h.
 *  2. Fiat-Quote ohne eigene Kerzen (z. B. BTC/CHF): USDT-Reihe, in Fiat umgerechnet mit dem
 *     Devisenkurs und USDT/USD an BEIDEN Enden (jetzt und vor 24 h); fehlt ein Devisenkurs, kein Wert — nie der
 *     USDT-Prozentwert als Fiat-Wert ([fromUsdtSeries]). Der Hinweis im Aktionsblatt nennt
 *     dann «Anbieter USDT» ([candleSourceName]).
 *  3. Sonst nichts (null → Anzeige «—»), nie die Veränderung seit der letzten Abfrage.
 */
object DayChange {

    /** Grösster plausibler Betrag (Prozent) für den 24-h-Wert aus dem Ticker. */
    const val MAX_TICKER_CHANGE = 10_000.0

    /** 24-h-Wert aus dem Ticker, wenn brauchbar (endlich, |x| < [MAX_TICKER_CHANGE]); sonst null. */
    fun fromTicker(change24hPercent: Double?): Double? =
        change24hPercent?.takeIf { it.isFinite() && abs(it) < MAX_TICKER_CHANGE }

    /** Kerzen nötig? Nur wenn der Ticker keinen brauchbaren 24-h-Wert hat. */
    fun needsCandles(tickerChange: Double?): Boolean = fromTicker(tickerChange) == null

    /**
     * Endgültiger Wert: Ticker vor Kerzen. [candles] wird nur ausgewertet, wenn der Ticker
     * nichts Brauchbares liefert (Kerzenwert mit den Prüfungen aus [select]).
     */
    inline fun choose(tickerChange: Double?, candles: () -> Double?): Double? =
        fromTicker(tickerChange) ?: candles()?.takeIf { it.isFinite() }

    /** Quotes, für die die USDT-Reihe des Mini-Charts gilt. */
    val USD_LIKE_QUOTES = setOf("USDT", "USD", "USDC", "FDUSD")

    /** Kerzen-Quote für ein Paar: USD-artige auf USDT (gleiche Reihe wie der Mini-Chart). */
    fun candleQuote(quote: String): String {
        val q = quote.trim().uppercase()
        return if (q in USD_LIKE_QUOTES) "USDT" else q
    }

    /**
     * Weicht der Kurs um mehr als diesen Anteil vom letzten Kerzenschluss ab, gehören
     * Kerzen und Kurs wohl nicht zum selben Coin (gleiches Kürzel, anderer Token).
     */
    const val MAX_PRICE_GAP = 0.25

    /** Aktueller Kurs gegen die 24-h-Eröffnung; null ohne Kurs oder bei unpassender Reihe. */
    fun fromPrice(price: Double?, reference: DayReference?): Double? {
        val ref = reference ?: return null
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (abs(p / ref.lastClose - 1.0) > MAX_PRICE_GAP) return null
        return (p - ref.open) / ref.open * 100.0
    }

    /** Nur der Verlauf der Reihe: letzter Schluss gegen Eröffnung. */
    fun fromSeries(reference: DayReference?): Double? {
        val ref = reference ?: return null
        return (ref.lastClose - ref.open) / ref.open * 100.0
    }

    /**
     * Anbieter der Kerzen, wenn er NICHT der Markt des Paars ist — z. B. Kraken-Paar ohne 24-h-Wert
     * im Ticker, Änderung aus Binance-Kerzen. Dann sagt das Aktionsblatt «Veränderung aus
     * Binance-Kerzen». Spot und Futures derselben Börse sind verschiedene Märkte: ein Binance-Perpetual
     * mit Spot-Kerzen heisst «Binance Spot», ein Spot-Paar mit Perp-Kerzen «Binance Futures».
     * Vergleich ohne Gross-/Kleinschreibung, Leer- und Satzzeichen («Binance.US» = «BinanceUS»).
     * @param exchanges Schlüssel und Name der Börse des Paars
     * @param provider Anbieter der Kerzen («Binance», «Binance.US», «Coinbase»); null = keine Kerzen
     * @return [provider], wenn fremd; sonst null
     */
    fun foreignCandleSource(exchanges: Collection<String>, provider: String?): String? {
        val p = provider?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val n = norm(p)
        val names = exchanges.map { norm(it) }
        if (names.any { it == n }) return null
        // Spot-Kerzen («Binance») an einem Futures-Markt derselben Börse: als Spot kennzeichnen
        return if (names.any { it == n + "futures" }) "$p Spot" else p
    }

    /** Was in der Merklisten-Zeile von einer anderen Börse kommt. */
    enum class RowSourceKind { CHANGE_AND_CHART, CHANGE, CHART }

    /** [chartProvider]: nur bei [RowSourceKind.CHANGE], wenn der Mini-Chart von einer DRITTEN Börse kommt. */
    data class RowSource(val kind: RowSourceKind, val provider: String, val chartProvider: String? = null)

    /**
     * Hinweis für die Merklisten-Zeile («Kraken · Änderung & Chart: Binance»): [change] = fremder Anbieter der
     * %-Änderung ([foreignCandleSource]), [chart] = fremder Anbieter des Mini-Charts; null = eigene Börse.
     * Dieselbe Ehrlichkeit wie «Richtwert» bei Futures-Daten, schon in der Liste statt erst im Aktionsblatt.
     */
    fun rowSource(change: String?, chart: String?): RowSource? = when {
        change != null && chart != null && change == chart -> RowSource(RowSourceKind.CHANGE_AND_CHART, change)
        // Änderung und Chart von verschiedenen fremden Börsen: beide nennen, nie den Chart verschweigen
        change != null -> RowSource(RowSourceKind.CHANGE, change, chart)
        chart != null -> RowSource(RowSourceKind.CHART, chart)
        else -> null
    }

    /**
     * Kleiner Marker «*» neben der %-Änderung der Merklisten-Zeile, wenn der GEZEIGTE Wert aus den Kerzen einer
     * anderen Börse kommt (z. B. Kraken-Paar, Binance-Kerzen); der Zeilensatz sagt dann «Veränderung aus
     * Binance-Kerzen», das Aktionsblatt erklärt es. Kein Marker ohne gezeigten Wert («—») und bei
     * «Seit letzter Aktualisierung» (dort rechnet die App selbst aus zwei Kursen, keine Kerzen).
     * @param foreignSource fremder Anbieter der Änderung ([foreignCandleSource]); null = eigene Börse/Ticker
     * @return Anbieter für Marker und Zeilensatz; null = kein Marker
     */
    fun rowChangeMarker(foreignSource: String?, shownChange: Double?, sinceLast: Boolean): String? =
        foreignSource?.takeIf { shownChange != null && shownChange.isFinite() && !sinceLast }

    /** Längster Börsenname an der %-Änderung der Merklisten-Zeile ([rowMarkerLabel]); länger wird mit «…» gekürzt. */
    const val ROW_LABEL_MAX = 10

    /**
     * Kurzer Börsenname direkt an der %-Änderung der Merklisten-Zeile («+2.4% · Binance» statt nur «*»): das erste Wort
     * des Anbieters aus [rowChangeMarker] («Binance USDT» → «Binance», «Binance Spot» → «Binance»), höchstens
     * [ROW_LABEL_MAX] Zeichen — so bricht die Zeile nicht um. Den vollen Namen nennen Tipp-Hinweis, Zeilensatz und
     * Aktionsblatt. Leer bei leerem Anbieter.
     */
    fun rowMarkerLabel(provider: String): String {
        val first = provider.trim().split(' ').firstOrNull { it.isNotEmpty() } ?: return ""
        return if (first.length <= ROW_LABEL_MAX) first else first.take(ROW_LABEL_MAX - 1) + "…"
    }

    /** Welche Kerzenreihe [select] nimmt (für den Quellen-Hinweis im Aktionsblatt). */
    enum class CandleSeries {
        /** Kerzen des Paars selbst (bzw. USDT-Reihe bei USD-artigen Quotes). */
        PAIR,
        /** Fiat-Quote ohne eigene Kerzen: USDT-Reihe des Basis-Assets (andere Quote!). */
        USDT,
        /** Keine passende Reihe. */
        NONE,
    }

    /** Reihe gemäss der Reihenfolge in [select] — eigene vor USDT (nur Fiat-Quotes). */
    fun series(pairReference: DayReference?, usdtReference: DayReference?, quoteIsFiat: Boolean): CandleSeries = when {
        pairReference != null -> CandleSeries.PAIR
        quoteIsFiat && usdtReference != null -> CandleSeries.USDT
        else -> CandleSeries.NONE
    }

    /**
     * Name der Kerzenquelle für den Hinweis «Veränderung aus …-Kerzen»: bei eigener Reihe der
     * Anbieter («Binance»), bei der USDT-Reihe eines Fiat-Paars Anbieter plus Quote
     * («Binance USDT»). Letzteres ist nie die Börse des Paars, [foreignCandleSource] meldet es
     * also immer — auch bei Binance BTC/EUR mit Binance-USDT-Kerzen (andere Quote, ohne
     * Wechselkursbewegung). null ohne Anbieter oder ohne Reihe.
     */
    fun candleSourceName(provider: String?, series: CandleSeries): String? {
        val p = provider?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (series) {
            CandleSeries.PAIR -> p
            CandleSeries.USDT -> "$p $USDT_QUOTE"
            CandleSeries.NONE -> null
        }
    }

    /** Kerzen-Quote der Mini-Charts (Ausweich-Reihe für Fiat-Quotes). */
    const val USDT_QUOTE = "USDT"

    /**
     * 24-h-Veränderung eines Fiat-Paars aus der USDT-Reihe: letzter Schluss × [usdtUsd] × [usdToQuote] (USDT/USD
     * und Fiat je USD jetzt) gegen Eröffnung × [usdtUsdAtOpen] × [usdToQuoteAtOpen] (vor 24 h) — so zählt ein
     * USDT-Depeg mit (BTC in USDT flach, USDT/USD 1.00 → 0.90: −10 % in Fiat). USDT/USD nach [usdtEnds]: beide Enden
     * gemessen (jetzt Live-Kurs, zur Eröffnung Tagesschluss jenes Tags) — sonst EIN Kurs für beide Enden (Live-Kurs,
     * sonst 1), nie gemessen gegen angenommen (wie der Chart, [SheetChart.usdtSeries]).
     * Fehlt einer der beiden Devisenkurse (null, nicht endlich, ≤ 0) → null: der USDT-Prozentwert ist
     * kein Fiat-Wert (Wechselkursbewegung fehlt), also lieber «—».
     * Kursabstand-Prüfung wie [fromPrice] ([MAX_PRICE_GAP]): weicht der Paarkurs (in Fiat) um mehr
     * als 25 % vom umgerechneten letzten Schluss ab, gehört die Reihe wohl zu einem anderen Token
     * → null. Ohne Paarkurs entfällt nur diese Prüfung.
     */
    fun fromUsdtSeries(
        price: Double?,
        reference: DayReference?,
        usdToQuote: Double?,
        usdToQuoteAtOpen: Double?,
        usdtUsd: Double? = null,
        usdtUsdAtOpen: Double? = null,
    ): Double? {
        val ref = reference ?: return null
        val fx = usdToQuote?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val fxAtOpen = usdToQuoteAtOpen?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val ends = usdtEnds(usdtUsd, usdtUsdAtOpen)
        val rate = fx * ends.now
        val rateAtOpen = fxAtOpen * ends.atOpen
        val p = price?.takeIf { it.isFinite() && it > 0.0 }
        if (p != null && abs(p / (ref.lastClose * rate) - 1.0) > MAX_PRICE_GAP) return null
        val open = ref.open * rateAtOpen
        return (ref.lastClose * rate - open) / open * 100.0
    }

    /** USDT/USD an beiden Enden; [approximate] = USDT = 1, [historyMissing] = beide Enden mit dem Live-Kurs. */
    data class UsdtEnds(val now: Double, val atOpen: Double, val approximate: Boolean, val historyMissing: Boolean)

    /**
     * USDT/USD für [fromUsdtSeries]: jetzt der Live-Kurs, zur Eröffnung der Tagesschluss — nur wenn BEIDE gemessen sind
     * ([PortfolioStables.usdtUsd], 0.05…3). Sonst EIN Kurs für beide Enden: der Live-Kurs ([historyMissing]), ohne ihn 1
     * ([approximate]) — so entsteht nie eine Veränderung aus «Eröffnung 1, jetzt live».
     */
    fun usdtEnds(usdtUsd: Double?, usdtUsdAtOpen: Double?): UsdtEnds {
        val now = PortfolioStables.usdtUsdLive(usdtUsd)
        val open = PortfolioStables.usdtUsd(usdtUsdAtOpen)
        if (!now.approximate && !open.approximate) return UsdtEnds(now.rate, open.rate, approximate = false, historyMissing = false)
        return UsdtEnds(now.rate, now.rate, approximate = now.approximate, historyMissing = !now.approximate)
    }

    /** Rechnet [fromUsdtSeries] mit USDT = 1 ([usdtEnds])? */
    fun usdtSeriesApproximate(usdtUsd: Double?, usdtUsdAtOpen: Double?): Boolean = usdtEnds(usdtUsd, usdtUsdAtOpen).approximate

    /** Fehlte der Tagesschluss zur Eröffnung, sodass beide Enden den Live-Kurs tragen ([usdtEnds])? */
    fun usdtSeriesHistoryMissing(usdtUsd: Double?, usdtUsdAtOpen: Double?): Boolean =
        usdtEnds(usdtUsd, usdtUsdAtOpen).historyMissing

    /**
     * Auswahl in fester Reihenfolge (siehe Klassenbeschreibung).
     * @param pairReference Kerzen des Paars in seiner Quote (bzw. USDT bei USD-artigen Quotes)
     * @param usdtReference USDT-Reihe des Basis-Assets (Mini-Chart), nur für Fiat-Quotes
     * @param quoteIsFiat Quote ist eine Landeswährung (EUR, CHF, …)
     * @param usdToQuote Fiat je USD jetzt ([fromUsdtSeries]); null = unbekannt
     * @param usdToQuoteAtOpen Fiat je USD zur Eröffnung der Reihe (vor 24 h bzw. Tagesbeginn); null = unbekannt
     * @param usdtUsd USDT/USD jetzt (Live-Kurs); null = 1 (Näherung)
     * @param usdtUsdAtOpen USDT/USD-Tagesschluss am Tag der Eröffnung; null = 1 (Näherung)
     */
    fun select(
        price: Double?,
        pairReference: DayReference?,
        usdtReference: DayReference?,
        quoteIsFiat: Boolean,
        usdToQuote: Double? = null,
        usdToQuoteAtOpen: Double? = null,
        usdtUsd: Double? = null,
        usdtUsdAtOpen: Double? = null,
    ): Double? {
        if (pairReference != null) return fromPrice(price, pairReference)
        if (quoteIsFiat) return fromUsdtSeries(price, usdtReference, usdToQuote, usdToQuoteAtOpen, usdtUsd, usdtUsdAtOpen)
        return null
    }
}
