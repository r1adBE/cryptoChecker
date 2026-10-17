package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.domain.convert.CurrencyConversion
import kotlin.math.abs
import kotlin.math.sqrt

/** Markt-Referenz im «Warum?»-Blatt: Bitcoin, Ether oder der breite Markt (Top-Coins). */
enum class MarketRef { BTC, ETH, BROAD }

/** Wie die Futures-Quelle zum Paar steht (für die Herkunftsangabe). */
enum class FuturesSourceNote {
    /** Die erste Quelle des Paars war nicht erreichbar, eine andere Börse sprang ein. */
    FALLBACK,

    /** Spot-Paar oder Kontrakt einer anderen Börse: Kennzahlen des USDT-Perpetuals als Richtwert. */
    REFERENCE,
}

/**
 * Herkunft der Daten im «Warum?»-Blatt — für «Datenstand» unter «Beobachtung» und die Quellenzeile.
 * Reine Daten; Namen wie in [com.cryptochecker.app.domain.market.DataFreshness] («Binance», «Coinbase» …).
 */
data class WhySources(
    /** Anbieter der Stundenkerzen des Paars. */
    val candles: String? = null,
    /** Kerzen kommen nicht von der ersten Quelle der Kette (Binance), sondern von einer Ausweichquelle. */
    val candlesFallback: Boolean = false,
    /** Anbieter der Vergleichskerzen (BTC/ETH); nur genannt, wenn anders als [candles]. */
    val reference: String? = null,
    /** Futures-Quelle («Binance Futures», «Bybit», «OKX»). */
    val futures: String? = null,
    val futuresNote: FuturesSourceNote? = null,
    /** Zeitpunkt des Fear-&-Greed-Werts (Epoch-ms, Tagesbeginn laut alternative.me). */
    val fearGreedTime: Long? = null,
    /** Stand des breiten Markts ([BroadMarketSnapshot.time]); null = nicht genutzt. */
    val broadTime: Long? = null,
)

/** Lage in den letzten drei Tagen ([Position3d]). */
enum class Position3dKind { NEAR_HIGH, NEAR_LOW, BELOW_HIGH, ABOVE_LOW }

/**
 * Lage des Kurses in den letzten 72 Stunden, nur wenn auffällig.
 *
 * @param percent NEAR_HIGH/BELOW_HIGH: Abstand zum 3-Tage-Hoch in % (≤ 0); NEAR_LOW/ABOVE_LOW:
 *   Abstand zum 3-Tage-Tief in % (≥ 0)
 * @param threshold Schwelle, ab der ein Abstand auffällt (in %, relativ zur Schwankung)
 */
data class Position3d(val kind: Position3dKind, val percent: Double, val threshold: Double)

/**
 * Bewegungen relativ zur eigenen Schwankung (reines Kotlin, Swift-Spiegel `RelativeMove`).
 *
 * Eine 24-h-Bewegung gilt als deutlich, wenn sie mindestens k-mal so gross ist wie die übliche
 * 24-h-Schwankung des Coins (bzw. der Referenz) UND eine feste Untergrenze überschreitet:
 *
 *   Schwelle = max(Untergrenze, k · σ₁ₕ · √24)
 *
 * σ₁ₕ ist die Standardabweichung (Stichprobe) der Stunden-Renditen VOR dem bewerteten 24-h-Fenster
 * (höchstens 72, mindestens 24 Stunden) — so bläht die Bewegung selbst ihre Schwelle nicht auf.
 * √24 rechnet die Stunden- auf die Tagesschwankung um (Zufallsweg-Näherung). Gibt es zu wenig
 * Verlauf, gelten die bisherigen festen Schwellen.
 */
object RelativeMove {

    /** Mindestens so viele Stunden-Renditen für σ, sonst feste Schwelle. */
    const val MIN_RETURNS = 24

    /** Höchstens so viele (die jüngsten vor dem Fenster). */
    const val MAX_RETURNS = 72

    /** Vielfaches der üblichen Schwankung für «dieser Coin bewegt sich deutlich» (streng: Coin allein ist eine starke Aussage). */
    const val K_COIN = 2.0

    /** Vielfaches der üblichen Schwankung für «der Markt (BTC/ETH) bewegt sich deutlich». */
    const val K_MARKET = 1.5

    /** Untergrenze für den Coin über 24 h in % — darunter ist es Rauschen, auch bei sehr ruhigen Coins. */
    const val COIN_FLOOR_PERCENT = 2.0

    /** Untergrenze für den Markt über 24 h in %. */
    const val MARKET_FLOOR_PERCENT = 1.0

    /** Stark ab diesem Vielfachen der Schwelle (vorher 3 % bei 1,5 %). */
    const val STRONG_FACTOR = 2.0

    /** Tagesfenster der Bewegung in Stunden (√24 für die Umrechnung). */
    const val MOVE_HOURS = 24

    private const val HOUR_MILLIS = ActivityAnalyzer.HOUR_MILLIS

    /**
     * Standardabweichung (in %) der Stunden-Renditen abgeschlossener Kerzen, deren Stunde bis
     * [endAt] (Epoch-ms) zu Ende ist — die jüngsten bis [MAX_RETURNS]. Nur Renditen über genau
     * eine Stunde (Lücken zählen nicht). null bei weniger als [MIN_RETURNS] Renditen.
     */
    fun hourlySigma(candles: List<HourCandle>?, endAt: Long): Double? {
        if (candles == null || candles.size < 3) return null
        // Die letzte Kerze läuft noch
        val closed = candles.dropLast(1)
        val returns = ArrayList<Double>()
        for (i in closed.indices.reversed()) {
            if (i == 0 || returns.size >= MAX_RETURNS) break
            val cur = closed[i]
            val prev = closed[i - 1]
            if (cur.openTime + HOUR_MILLIS > endAt) continue
            if (cur.openTime - prev.openTime != HOUR_MILLIS) continue
            if (!(prev.close > 0.0) || !(cur.close > 0.0)) continue
            val r = (cur.close / prev.close - 1.0) * 100.0
            if (r.isFinite()) returns += r
        }
        if (returns.size < MIN_RETURNS) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        val sigma = sqrt(variance)
        return if (sigma.isFinite()) maxOf(sigma, ActivityAnalyzer.MIN_SIGMA_PERCENT) else null
    }

    /** σ vor dem 24-h-Fenster, das bei [now] endet. */
    fun sigmaBefore24h(candles: List<HourCandle>?, now: Long): Double? =
        hourlySigma(candles, now - MOVE_HOURS * HOUR_MILLIS)

    /**
     * Schwelle für eine 24-h-Bewegung: max([floor], [k] · σ · √24); ohne σ [fallback]
     * (die bisherige feste Schwelle).
     */
    fun threshold24h(sigma: Double?, k: Double, floor: Double, fallback: Double): Double =
        if (sigma == null) fallback else maxOf(floor, k * sigma * sqrt(MOVE_HOURS.toDouble()))

    /** Schwelle für den Coin (k = 2, Untergrenze 2 %, sonst 3 %). */
    fun coinThreshold(sigma: Double?): Double =
        threshold24h(sigma, K_COIN, COIN_FLOOR_PERCENT, ActivityAnalyzer.COIN_MOVE_PERCENT)

    /** Schwelle für eine Markt-Referenz mit Kerzen (k = 1,5, Untergrenze 1 %, sonst 1,5 %). */
    fun marketThreshold(sigma: Double?): Double =
        threshold24h(sigma, K_MARKET, MARKET_FLOOR_PERCENT, ActivityAnalyzer.MARKET_MOVE_PERCENT)
}

/**
 * Stablecoins (reines Kotlin, Swift-Spiegel `StablePeg`): Ein Stablecoin gegen USD oder einen
 * anderen USD-Stablecoin (z. B. USDC/USDT) soll 1:1 stehen. Statt %-Bewegungen als Ausreisser zu
 * werten, beschreibt «Warum?» die Abweichung von 1:1; Markt-Vergleich, Schwankung und Lage
 * (Hoch/Tief) entfallen. Liste wie im Portfolio ([CurrencyConversion.USD_STABLES]).
 */
object StablePeg {

    /** Bis zu dieser Abweichung (in %) «nahe an der 1:1-Bindung». */
    const val NEAR_PERCENT = 0.1

    /** Ab dieser Abweichung (in %) ist der Faktor auffällig. */
    const val NOTABLE_PERCENT = 0.5

    /** Ab dieser Abweichung (in %) stark. */
    const val STRONG_PERCENT = 2.0

    /** Stablecoin gegen USD bzw. einen USD-Stablecoin? Gegen andere Währungen (USDT/TRY) nicht. */
    fun isPegged(baseAsset: String, quoteAsset: String?): Boolean {
        val base = CurrencyConversion.normalize(baseAsset)
        val quote = CurrencyConversion.normalize(quoteAsset)
        return base != "USD" && base in CurrencyConversion.USD_STABLES &&
            quote in CurrencyConversion.USD_STABLES
    }

    /** Abweichung von 1:1 in % (mit Vorzeichen); null ohne gültigen Kurs. */
    fun deviationPercent(price: Double?): Double? {
        if (price == null || !price.isFinite() || price <= 0.0) return null
        return (price - 1.0) * 100.0
    }

    /** Auffällige Abweichung? */
    fun isNotable(deviation: Double): Boolean = abs(deviation) >= NOTABLE_PERCENT
}

/** Rolle einer Quelle im «Warum?»-Blatt. */
enum class WhySourceRole { CANDLES, COMPARISON, FUTURES, BREADTH, FEAR_GREED }

/** Eine genannte Quelle: Rolle, Anbieter und ob sie als Ausweichquelle/Richtwert einsprang. */
data class WhySourceItem(val role: WhySourceRole, val provider: String, val note: FuturesSourceNote? = null)

/**
 * Herkunft und Stand im «Warum?»-Blatt (reines Kotlin, Swift-Spiegel `WhyFreshness`): welche
 * Quellen genannt werden und wie alt Fear & Greed bzw. der Open-Interest-Vergleich sind.
 */
object WhyFreshness {

    /** Anbieter von Fear & Greed. */
    const val FEAR_GREED_PROVIDER = "alternative.me"

    private const val DAY_MILLIS = ActivityAnalyzer.DAY_MILLIS

    /** Tage seit dem Fear-&-Greed-Wert in UTC-Tagen (alternative.me zählt UTC-Tage); 0 = heute. */
    fun fearGreedDaysAgo(time: Long, now: Long): Long = Math.floorDiv(now, DAY_MILLIS) - Math.floorDiv(time, DAY_MILLIS)

    /**
     * Kerzen als Ersatzquelle kennzeichnen? Ja, sobald sie nicht von der Börse des Paars ([markets] = Schlüssel
     * und Name) kommen — auch Binance (z. B. Phemex-, Bithumb- oder HTX-Paar). Wie der Hinweis im Aktionsblatt.
     */
    fun candlesFallback(markets: List<String>, provider: String?): Boolean =
        com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(markets, provider) != null

    /** Open-Interest-Vergleich: unter 90 Min. null (dann in Minuten nennen), sonst ganze Stunden (gerundet). */
    fun oiAgeHours(minutes: Int): Int? = if (minutes < 90) null else (minutes + 30) / 60

    /** Floss der breite Markt in den Markt-Vergleich ein (nicht bei Bitcoin selbst und Stablecoins)? */
    fun usesBreadth(report: WhyReport): Boolean =
        report.sources?.broadTime != null && report.reasons.any {
            it.kind == ReasonKind.MARKET_WIDE || it.kind == ReasonKind.COIN_ONLY ||
                it.kind == ReasonKind.AGAINST_MARKET || it.kind == ReasonKind.MARKET_CALM
        }

    /**
     * Genannte Quellen in fester Reihenfolge: Kerzen, Vergleichskerzen (nur wenn anderer Anbieter),
     * Futures, breiter Markt (nur wenn genutzt), Fear & Greed (nur mit Wert).
     */
    fun sources(report: WhyReport): List<WhySourceItem> {
        val s = report.sources ?: return emptyList()
        val out = ArrayList<WhySourceItem>()
        s.candles?.takeIf { report.hasMarketData }?.let {
            out += WhySourceItem(WhySourceRole.CANDLES, it, if (s.candlesFallback) FuturesSourceNote.FALLBACK else null)
        }
        s.reference?.let { out += WhySourceItem(WhySourceRole.COMPARISON, it) }
        val leverage = report.reasons.any {
            it.kind == ReasonKind.LEVERAGE_LONGS || it.kind == ReasonKind.LEVERAGE_SHORTS || it.kind == ReasonKind.LEVERAGE_BALANCED
        }
        s.futures?.takeIf { leverage }?.let { out += WhySourceItem(WhySourceRole.FUTURES, it, s.futuresNote) }
        if (usesBreadth(report)) out += WhySourceItem(WhySourceRole.BREADTH, BroadMarket.PROVIDER)
        if (report.reasons.any { it.kind == ReasonKind.SENTIMENT }) {
            out += WhySourceItem(WhySourceRole.FEAR_GREED, FEAR_GREED_PROVIDER)
        }
        return out
    }
}
