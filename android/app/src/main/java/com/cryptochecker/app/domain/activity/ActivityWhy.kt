package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.domain.convert.CurrencyConversion
import kotlin.math.abs
import kotlin.math.sqrt

// «Warum bewegt sich das?» (Teil von [ActivityAnalyzer]): Gründe je Paar (Markt vs. Coin, Volumen, Hebel, Volatilität,
// Stimmung), Lage (30-Tage-Hoch/-Tief, drei Tage) und «nicht live». Erweiterungsfunktionen, damit
// `ActivityAnalyzer.explain(…)` usw. bleiben.

/**
 * Ordnet ein Paar ein: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung; dazu Lage
 * (30-Tage-Hoch/-Tief, drei Tage), Funding-Verlauf und Herkunft. Bewegungen werden an der
 * eigenen Schwankung gemessen ([RelativeMove]); Markt = BTC, ETH und der breite Markt
 * ([BroadMarket]). Stablecoins ([StablePeg]): Abweichung von 1:1 statt Markt und Schwankung.
 */
fun ActivityAnalyzer.explain(input: WhyInput): WhyReport {
    val now = input.now
    // Nicht mehr gehandelt: nichts ausgeben (keine Kennzahlen, keine Gründe)
    if (!input.marketLive) return notLive(now)
    // Veraltete, leere oder unplausibel flache Reihen zählen als «keine Daten» (siehe CandleSeries)
    val candles = CandleSeries.usable(input.candles, now, input.tickerChange24h)
    val change1h = changeOver(candles, 1, now)
    // 24 h wie Änderung und Merkliste (Ticker des Paars), sonst aus den Kerzen
    val change24h = CandleSeries.change24h(input.tickerChange24h, changeOver(candles, 24, now))
    val stats = candles?.let { hourStats(it) }
    val reference = CandleSeries.usable(input.referenceCandles, now, null)
    val stable = StablePeg.isPegged(input.baseAsset, input.quoteAsset)
    val isBtc = input.baseAsset.trim().equals("BTC", ignoreCase = true)
    // Referenzen (BTCUSDT, ETHUSDT, breiter Markt) sind in USD: nur bei USD-Quote vergleichbar
    val usdQuote = isUsdQuote(input.quoteAsset)

    // Deutliche Bewegung des Coins: relativ zur eigenen Schwankung vor dem 24-h-Fenster
    val coinThreshold = RelativeMove.coinThreshold(RelativeMove.sigmaBefore24h(candles, now))
    val coinMoves = change24h != null && abs(change24h) >= coinThreshold
    // Stunde schwankt stärker als üblich: |z| ≥ 2 und mindestens 0,5 % (kein Staub)
    val volatile = stats != null && abs(stats.zScore) >= VOLATILITY_HIGH_Z &&
        abs(stats.movePercent) >= VOLATILITY_MIN_MOVE_PERCENT

    val reasons = ArrayList<Reason>()

    // 1) Markt vs. Coin — bei Stablecoins stattdessen die Abweichung von 1:1
    if (stable) {
        StablePeg.deviationPercent(candles?.last()?.close)?.let { deviation ->
            val notable = StablePeg.isNotable(deviation)
            reasons += Reason(
                ReasonKind.PEG, if (notable) ReasonTone.WARNING else ReasonTone.NEUTRAL, deviation,
                strong = notable,
            )
        }
    } else if (!usdQuote) {
        // Quote nicht USD (z. B. ETH/BTC, SOL/EUR): Kerzen und Referenzen nicht vergleichbar — kein Markt-Grund
    } else if (isBtc) {
        if (change24h != null) {
            // Bitcoin ist selbst der Markt: Schwelle aus seiner eigenen Schwankung
            val threshold = RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(candles, now))
            reasons += Reason(
                kind = ReasonKind.MARKET_LEADER,
                tone = toneOf(change24h),
                value = change24h,
                secondary = changeOver(reference, 24, now),
                strong = abs(change24h) >= RelativeMove.STRONG_FACTOR * threshold,
                threshold = threshold,
            )
        }
    } else if (change24h != null) {
        val refs = marketRefs(input, reference, now)
        if (refs.isNotEmpty()) reasons += marketReason(change24h, coinThreshold, refs)
    }

    // 2) Volumen
    stats?.volumeRatio?.let { ratio ->
        val moving = volatile || coinMoves
        val approximate = stats.volumeApproximate
        reasons += when {
            ratio >= VOLUME_HIGH_RATIO -> Reason(
                ReasonKind.VOLUME_HIGH, ReasonTone.WARNING, ratio,
                strong = ratio >= VOLUME_SPIKE_RATIO, approximate = approximate,
            )
            ratio <= VOLUME_LOW_RATIO && moving ->
                Reason(ReasonKind.VOLUME_LOW, ReasonTone.WARNING, ratio, approximate = approximate)
            else -> Reason(ReasonKind.VOLUME_NORMAL, ReasonTone.NEUTRAL, ratio, approximate = approximate)
        }
    }

    // 3) Hebel (Futures)
    input.fundingPercent?.let { funding ->
        val oi = input.openInterestChangePercent
        // Sprung nur bei einheitlicher Richtung gegenüber den älteren Messungen (OiSeries)
        val oiJump = oi != null && input.openInterestConsistent && abs(oi) >= OI_JUMP_PERCENT
        // Angezeigt wird der Satz der Börse, verglichen auf 8 Stunden umgerechnet
        val interval = fundingIntervalHours(input.fundingSource, input.fundingIntervalHours)
        val per8h = fundingPer8h(funding, interval)
        reasons += when {
            per8h >= FUNDING_EXTREME_PERCENT -> Reason(
                ReasonKind.LEVERAGE_LONGS, ReasonTone.WARNING, funding, oi,
                strong = per8h >= FUNDING_STRONG_PERCENT || oiJump, intervalHours = interval,
            )
            per8h <= -FUNDING_EXTREME_PERCENT -> Reason(
                ReasonKind.LEVERAGE_SHORTS, ReasonTone.WARNING, funding, oi,
                strong = per8h <= -FUNDING_STRONG_PERCENT || oiJump, intervalHours = interval,
            )
            else -> Reason(
                ReasonKind.LEVERAGE_BALANCED, ReasonTone.NEUTRAL, funding, oi, strong = oiJump, intervalHours = interval,
            )
        }
    }

    // 4) Volatilität (nicht bei Stablecoins: dort zählt die Abweichung von 1:1)
    if (stats != null && !stable) {
        val factor = abs(stats.zScore)
        reasons += if (volatile) {
            Reason(
                ReasonKind.VOLATILITY_HIGH, toneOf(stats.movePercent), factor, stats.movePercent,
                strong = factor >= PRICE_Z_THRESHOLD,
            )
        } else {
            Reason(ReasonKind.VOLATILITY_NORMAL, ReasonTone.NEUTRAL, factor, stats.movePercent)
        }
    }

    // 5) Stimmung
    input.fearGreed?.let { value ->
        val level = fearGreedLevel(value)
        val extreme = level == FearGreedLevel.EXTREME_FEAR || level == FearGreedLevel.EXTREME_GREED
        reasons += Reason(
            kind = ReasonKind.SENTIMENT,
            tone = if (extreme) ReasonTone.WARNING else ReasonTone.NEUTRAL,
            value = value.toDouble(),
            secondary = input.fearGreedYesterday?.let { (value - it).toDouble() },
        )
    }

    // Lage (Hoch/Tief) nur mit Kerzen und nicht bei Stablecoins
    val withRange = candles != null && !stable
    return WhyReport(
        price = candles?.last()?.close,
        change1h = change1h,
        change24h = change24h,
        // Stabil sortiert: auffällige zuerst, sonst in obiger Reihenfolge
        reasons = reasons.sortedByDescending { it.strong }.take(MAX_REASONS),
        hasMarketData = candles != null,
        dataTime = now,
        high30d = if (withRange) high30d(input.dailyCandles, now) else null,
        // Referenz ist BTCUSDT (bei Bitcoin selbst ETH — dann ohnehin kein Satz); nur bei USD-Quote
        btcLink = if (stable || !usdQuote) null else BtcCorrelation.link(input.baseAsset, candles, reference),
        low30d = if (withRange) low30d(input.dailyCandles, now) else null,
        position3d = if (withRange) position3d(candles, now) else null,
        fundingTrend = if (input.fundingPercent != null) {
            FundingTrendRules.trend(input.fundingHistory, input.fundingPercent, input.fundingSource, now, input.fundingIntervalHours)
        } else {
            null
        },
        oiMinutes = input.openInterestMinutes.takeIf { input.openInterestChangePercent != null },
        oiConsistent = input.openInterestConsistent,
        candleTime = candles?.last()?.openTime,
        stable = stable,
        sources = input.sources,
    )
}

/**
 * Quote USD oder ein USD-Stablecoin ([CurrencyConversion.classify] → USD)? Nur dann sind die Kerzen
 * des Paars mit den USD-Referenzen vergleichbar. Ohne Quote (unbekannt) wie bisher USD.
 */
fun ActivityAnalyzer.isUsdQuote(quoteAsset: String?): Boolean {
    val code = CurrencyConversion.normalize(quoteAsset)
    return code.isEmpty() || CurrencyConversion.classify(code, emptyList()) == CurrencyConversion.QuoteKind.USD
}

/** Eine Markt-Referenz: 24-h-Veränderung in % und ihre Schwelle. */
private data class RefMove(val ref: MarketRef, val change: Double, val threshold: Double)

/**
 * Markt-Referenzen für einen Coin (nicht Bitcoin): BTC (aus [reference]), ETH (nicht für ETH
 * selbst) mit Schwellen aus ihrer eigenen Schwankung, dazu der breite Markt mit der festen
 * Schwelle [MARKET_MOVE_PERCENT] (ohne Kerzen keine Schwankung).
 */
private fun ActivityAnalyzer.marketRefs(input: WhyInput, reference: List<HourCandle>?, now: Long): List<RefMove> {
    val out = ArrayList<RefMove>(3)
    changeOver(reference, 24, now)?.let {
        out += RefMove(MarketRef.BTC, it, RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(reference, now)))
    }
    if (!input.baseAsset.trim().equals("ETH", ignoreCase = true)) {
        val eth = CandleSeries.usable(input.ethCandles, now, null)
        changeOver(eth, 24, now)?.let {
            out += RefMove(MarketRef.ETH, it, RelativeMove.marketThreshold(RelativeMove.sigmaBefore24h(eth, now)))
        }
    }
    input.broadMarket?.change24h?.takeIf { it.isFinite() }?.let {
        out += RefMove(MarketRef.BROAD, it, MARKET_MOVE_PERCENT)
    }
    return out
}


/** Erste Referenz mit dem grössten Wert von [score] (bei Gleichstand die frühere — wie in Swift). */
private fun ActivityAnalyzer.firstMax(refs: List<RefMove>, score: (RefMove) -> Double): RefMove {
    var best = refs.first()
    for (r in refs.drop(1)) if (score(r) > score(best)) best = r
    return best
}

private fun ActivityAnalyzer.sameSign(a: Double, b: Double): Boolean = (a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0)

/**
 * Markt vs. Coin über 24 h. Eine Referenz bewegt sich deutlich, wenn |Veränderung| ≥ ihre Schwelle.
 *  - gegen den Markt: der Coin bewegt sich deutlich, ALLE deutlich bewegten Referenzen in die Gegenrichtung
 *  - marktweit: mindestens eine Referenz bewegt sich deutlich (gezeigt: die deutlichste, gleiche Richtung bevorzugt)
 *  - marktweit (schwach): der Coin bewegt sich deutlich, eine Referenz ähnlich ([SIMILAR_RATIO])
 *  - nur dieser Coin: der Coin bewegt sich deutlich, kein Markt ähnlich
 *  - sonst ruhig
 */
private fun ActivityAnalyzer.marketReason(coin: Double, coinThreshold: Double, refs: List<RefMove>): Reason {
    val moving = refs.filter { abs(it.change) >= it.threshold }
    val coinMoves = abs(coin) >= coinThreshold
    val display = refs.firstOrNull { it.ref == MarketRef.BTC } ?: refs.first()
    fun ratio(r: RefMove): Double = abs(r.change) / r.threshold
    if (coinMoves && moving.isNotEmpty() && moving.all { sameSign(it.change, -coin) }) {
        val m = firstMax(moving, ::ratio)
        return Reason(
            ReasonKind.AGAINST_MARKET, toneOf(coin), coin, m.change,
            strong = true, ref = m.ref, threshold = coinThreshold,
        )
    }
    if (moving.isNotEmpty()) {
        val same = moving.filter { sameSign(it.change, coin) }
        val d = firstMax(same.ifEmpty { moving }, ::ratio)
        return Reason(
            ReasonKind.MARKET_WIDE, toneOf(d.change), d.change, coin,
            strong = abs(d.change) >= RelativeMove.STRONG_FACTOR * d.threshold, ref = d.ref, threshold = d.threshold,
        )
    }
    if (coinMoves) {
        val similar = refs.filter { sameSign(it.change, coin) && abs(it.change) >= SIMILAR_RATIO * abs(coin) }
        if (similar.isNotEmpty()) {
            val d = firstMax(similar) { abs(it.change) }
            return Reason(
                ReasonKind.MARKET_WIDE, toneOf(d.change), d.change, coin,
                strong = false, ref = d.ref, threshold = d.threshold,
            )
        }
        return Reason(
            ReasonKind.COIN_ONLY, toneOf(coin), coin, display.change,
            strong = true, ref = display.ref, threshold = coinThreshold,
        )
    }
    return Reason(
        ReasonKind.MARKET_CALM, ReasonTone.NEUTRAL, display.change, coin,
        ref = display.ref, threshold = display.threshold,
    )
}

/**
 * Höchster Kurs der letzten [HIGH_DAYS] Tage aus Tageskerzen (letzte = laufender Tag);
 * null bei weniger als 30 Tagen, einer veralteten Reihe oder ungültigen Werten.
 */
fun ActivityAnalyzer.high30d(daily: List<HourCandle>?, now: Long): Double? {
    val days = recentDays(daily, now, HIGH_DAYS) ?: return null
    val high = days.maxOf { it.high }
    return high.takeIf { it.isFinite() && it > 0.0 }
}

/** Tiefster Kurs der letzten [LOW_DAYS] Tage, gleiche Regeln wie [high30d]. */
fun ActivityAnalyzer.low30d(daily: List<HourCandle>?, now: Long): Double? {
    val days = recentDays(daily, now, LOW_DAYS) ?: return null
    // Ungültige Tiefs (≤ 0) zählen nicht, sonst stünde jeder Kurs «weit über dem Tief»
    if (days.any { !(it.low > 0.0) || !it.low.isFinite() }) return null
    return days.minOf { it.low }
}

/** Die letzten [count] Tageskerzen; null bei zu wenigen oder einer veralteten/künftigen Reihe. */
private fun ActivityAnalyzer.recentDays(daily: List<HourCandle>?, now: Long, count: Int): List<HourCandle>? {
    if (daily == null || daily.size < count) return null
    val lastOpen = daily.last().openTime
    // Reihe endet vor über zwei Tagen (oder liegt in der Zukunft): kein Hoch/Tief
    if (now - lastOpen > 2 * DAY_MILLIS || lastOpen - now > DAY_MILLIS) return null
    return daily.takeLast(count)
}

/**
 * Lage in den letzten [RANGE_HOURS] Stunden (Stundenkerzen, inkl. laufender), nur wenn auffällig:
 *  - «−8 % seit dem 3-Tage-Hoch» / «+6 % seit dem 3-Tage-Tief»: Abstand mindestens
 *    max([RANGE_FLOOR_PERCENT], [RANGE_SIGMA_K] · σ₁ₕ · √72); beides → der grössere
 *  - sonst nahe am 3-Tage-Hoch/-Tief: höchstens [RANGE_NEAR_PERCENT] entfernt, bei einer Spanne
 *    von mindestens [RANGE_MIN_SPAN_PERCENT]
 * null bei weniger als [RANGE_MIN_HOURS] Stunden Verlauf oder ungültigen Werten.
 */
fun ActivityAnalyzer.position3d(candles: List<HourCandle>, now: Long): Position3d? {
    val start = now - RANGE_HOURS * HOUR_MILLIS
    val window = candles.filter { it.openTime >= start }
    if (window.size < RANGE_MIN_HOURS || window.first().openTime > now - RANGE_MIN_HOURS * HOUR_MILLIS) return null
    val high = window.maxOf { it.high }
    val low = window.minOf { it.low }
    val price = window.last().close
    if (!(high > 0.0) || !(low > 0.0) || !(price > 0.0) || !high.isFinite() || !low.isFinite() || !price.isFinite()) {
        return null
    }
    val fromHigh = minOf((price / high - 1.0) * 100.0, 0.0)
    val fromLow = maxOf((price / low - 1.0) * 100.0, 0.0)
    val span = (high / low - 1.0) * 100.0
    val sigma = RelativeMove.hourlySigma(candles, now)
    val threshold = if (sigma == null) {
        RANGE_FLOOR_PERCENT
    } else {
        maxOf(RANGE_FLOOR_PERCENT, RANGE_SIGMA_K * sigma * sqrt(RANGE_HOURS.toDouble()))
    }
    // Zuerst der deutliche Abstand (sagt mehr als «nahe am Tief» nach einem Einbruch), dann die Nähe
    val down = -fromHigh >= threshold
    val up = fromLow >= threshold
    return when {
        down && (!up || -fromHigh >= fromLow) -> Position3d(Position3dKind.BELOW_HIGH, fromHigh, threshold)
        up -> Position3d(Position3dKind.ABOVE_LOW, fromLow, threshold)
        span < RANGE_MIN_SPAN_PERCENT -> null
        -fromHigh <= RANGE_NEAR_PERCENT -> Position3d(Position3dKind.NEAR_HIGH, fromHigh, threshold)
        fromLow <= RANGE_NEAR_PERCENT -> Position3d(Position3dKind.NEAR_LOW, fromLow, threshold)
        else -> null
    }
}

/** Leerer Bericht für Paare, die nicht mehr gehandelt werden. */
fun ActivityAnalyzer.notLive(now: Long): WhyReport = WhyReport(
    price = null,
    change1h = null,
    change24h = null,
    reasons = emptyList(),
    hasMarketData = false,
    dataTime = now,
)

private fun ActivityAnalyzer.toneOf(change: Double): ReasonTone = when {
    change >= 0.005 -> ReasonTone.UP
    change <= -0.005 -> ReasonTone.DOWN
    else -> ReasonTone.NEUTRAL
}
