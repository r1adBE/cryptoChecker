package com.cryptochecker.app.domain.activity

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reine Auswertung ohne Android und ohne Netz: aus Stundenkerzen, Funding Rate
 * und Open Interest werden Signale («hier passiert gerade etwas») und Gründe
 * («warum bewegt sich das?») — als Daten mit Art und Zahlen. Texte macht die
 * Oberfläche, damit alles übersetzbar und testbar bleibt.
 */
object ActivityAnalyzer {

    const val HOUR_MILLIS = 60 * 60_000L

    /** Vergleichsstunden für Volatilität und Volumen. */
    const val WINDOW_HOURS = 24

    /** 24 Vergleichsrenditen brauchen 25 Schlusskurse + letzte abgeschlossene + laufende Stunde. */
    const val MIN_CANDLES = WINDOW_HOURS + 3

    // --- Signale ---
    const val PRICE_Z_THRESHOLD = 3.0
    /** Mindestbewegung, damit ruhige Coins (Stablecoins) nicht bei Kleinigkeiten melden. */
    const val PRICE_MIN_MOVE_PERCENT = 1.5
    const val PRICE_STRONG_Z = 5.0
    const val PRICE_STRONG_MOVE_PERCENT = 5.0
    const val VOLUME_SPIKE_RATIO = 3.0
    const val VOLUME_STRONG_RATIO = 6.0
    /** Funding in % je Periode (Binance/Bybit: meist 8 h). */
    const val FUNDING_EXTREME_PERCENT = 0.05
    const val FUNDING_STRONG_PERCENT = 0.1
    const val OI_JUMP_PERCENT = 10.0
    const val OI_STRONG_PERCENT = 20.0
    const val OI_MIN_AGE_MILLIS = 30 * 60_000L
    const val OI_MAX_AGE_MILLIS = 6 * HOUR_MILLIS

    /** Untergrenze der Standardabweichung (in %), sonst teilt ein völlig flacher Verlauf durch 0. */
    const val MIN_SIGMA_PERCENT = 0.01

    const val SIGNAL_TTL_MILLIS = 1 * HOUR_MILLIS

    // ⚡-Signale: bewusst strenger als die Einordnung im «Warum»-Blatt,
    // damit nur wirklich Auffälliges markiert wird (v16.2.2 nachgeschärft).
    const val SIGNAL_PRICE_Z = 3.5
    const val SIGNAL_PRICE_MIN_MOVE_PERCENT = 2.5
    const val SIGNAL_PRICE_STRONG_Z = 5.0
    const val SIGNAL_PRICE_STRONG_MOVE_PERCENT = 6.0
    const val SIGNAL_VOLUME_RATIO = 5.0
    const val SIGNAL_VOLUME_STRONG_RATIO = 10.0
    const val SIGNAL_FUNDING_PERCENT = 0.1
    const val SIGNAL_FUNDING_STRONG_PERCENT = 0.2
    const val SIGNAL_OI_PERCENT = 15.0
    const val SIGNAL_OI_STRONG_PERCENT = 25.0
    const val ANALYSIS_INTERVAL_MILLIS = 10 * 60_000L
    const val NOTIFY_INTERVAL_MILLIS = 60 * 60_000L

    // --- Gründe ---
    const val MARKET_MOVE_PERCENT = 1.5
    const val COIN_MOVE_PERCENT = 3.0
    const val MARKET_STRONG_PERCENT = 3.0
    const val VOLUME_HIGH_RATIO = 2.0
    const val VOLUME_LOW_RATIO = 0.6
    const val VOLATILITY_HIGH_Z = 2.0
    const val MAX_REASONS = 5

    /** Stärkstes zuerst, bei gleicher Stärke nach Art. */
    val SIGNAL_ORDER: Comparator<ActivitySignal> =
        compareByDescending<ActivitySignal> { it.severity.ordinal }.thenBy { it.kind.ordinal }

    // ---------------- Kennzahlen ----------------

    /**
     * Letzte abgeschlossene Stunde (vorletzte Kerze) gegen die 24 Stunden davor.
     * Renditen Schluss zu Schluss in %, z = (r − Mittel) / Standardabweichung
     * (Stichprobe, n − 1). null bei zu wenig oder ungültigen Kerzen.
     */
    fun hourStats(candles: List<HourCandle>): HourStats? {
        if (candles.size < MIN_CANDLES) return null
        val last = candles.size - 2
        val first = last - WINDOW_HOURS
        fun ret(i: Int): Double? {
            val prev = candles[i - 1].close
            val cur = candles[i].close
            return if (prev > 0.0 && cur > 0.0) (cur / prev - 1.0) * 100.0 else null
        }

        val window = (first until last).map { ret(it) ?: return null }
        val move = ret(last) ?: return null
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / (window.size - 1)
        val sigma = maxOf(sqrt(variance), MIN_SIGMA_PERCENT)

        val avgVolume = (first until last).sumOf { candles[it].volume } / WINDOW_HOURS
        val ratio = if (avgVolume > 0.0) candles[last].volume / avgVolume else null

        return HourStats(
            movePercent = move,
            zScore = (move - mean) / sigma,
            volumeRatio = ratio,
            candleOpenTime = candles[last].openTime,
        )
    }

    /**
     * Kurs zu einem Zeitpunkt, innerhalb einer abgeschlossenen Kerze linear
     * zwischen Eröffnung und Schluss geschätzt. Ab der laufenden Kerze: letzter Kurs.
     */
    fun priceAt(candles: List<HourCandle>, time: Long): Double? {
        if (candles.isEmpty() || time < candles.first().openTime) return null
        val lastIndex = candles.lastIndex
        if (time >= candles[lastIndex].openTime) return candles[lastIndex].close
        val candle = candles.lastOrNull { it.openTime <= time } ?: return null
        val fraction = ((time - candle.openTime).toDouble() / HOUR_MILLIS).coerceIn(0.0, 1.0)
        return candle.open + (candle.close - candle.open) * fraction
    }

    /** Veränderung des letzten Kurses gegenüber vor [hours] Stunden, in %. */
    fun changeOver(candles: List<HourCandle>?, hours: Int, now: Long): Double? {
        if (candles.isNullOrEmpty()) return null
        val current = candles.last().close
        val past = priceAt(candles, now - hours * HOUR_MILLIS) ?: return null
        return if (past > 0.0 && current > 0.0) (current / past - 1.0) * 100.0 else null
    }

    /**
     * Open-Interest-Vergleich mit der gespeicherten Messung. Verglichen wird nur
     * gegen eine Messung, die 30 Min.–6 Std. alt ist. Jüngere bleibt stehen
     * (sonst wäre der Abstand immer nur 10 Min.), ältere oder fehlende wird ersetzt.
     */
    fun oiChange(previous: OiSample?, currentUnits: Double?, now: Long): OiUpdate {
        if (currentUnits == null || currentUnits <= 0.0) return OiUpdate(null, null, store = false)
        if (previous == null || previous.units <= 0.0) return OiUpdate(null, null, store = true)
        val age = now - previous.time
        return when {
            age < 0 || age > OI_MAX_AGE_MILLIS -> OiUpdate(null, null, store = true)
            age < OI_MIN_AGE_MILLIS -> OiUpdate(null, null, store = false)
            else -> OiUpdate(
                changePercent = (currentUnits / previous.units - 1.0) * 100.0,
                minutes = (age / 60_000L).toInt(),
                store = true,
            )
        }
    }

    // ---------------- Signale ----------------

    /** Alle Signale eines Paars, stärkstes zuerst. */
    fun signals(
        stats: HourStats?,
        fundingPercent: Double?,
        oiChangePercent: Double?,
        oiMinutes: Int?,
        now: Long,
    ): List<ActivitySignal> {
        val out = ArrayList<ActivitySignal>()
        if (stats != null) {
            val z = abs(stats.zScore)
            val move = abs(stats.movePercent)
            if (z >= SIGNAL_PRICE_Z && move >= SIGNAL_PRICE_MIN_MOVE_PERCENT) {
                out += ActivitySignal(
                    kind = SignalKind.PRICE_MOVE,
                    severity = if (z >= SIGNAL_PRICE_STRONG_Z || move >= SIGNAL_PRICE_STRONG_MOVE_PERCENT)
                        SignalSeverity.STRONG else SignalSeverity.NOTABLE,
                    value = stats.movePercent,
                    factor = z,
                    seenAt = now,
                )
            }
            val ratio = stats.volumeRatio
            if (ratio != null && ratio >= SIGNAL_VOLUME_RATIO) {
                out += ActivitySignal(
                    kind = SignalKind.VOLUME_SPIKE,
                    severity = if (ratio >= SIGNAL_VOLUME_STRONG_RATIO) SignalSeverity.STRONG else SignalSeverity.NOTABLE,
                    value = ratio,
                    seenAt = now,
                )
            }
        }
        if (oiChangePercent != null && abs(oiChangePercent) >= SIGNAL_OI_PERCENT) {
            out += ActivitySignal(
                kind = SignalKind.OPEN_INTEREST_JUMP,
                severity = if (abs(oiChangePercent) >= SIGNAL_OI_STRONG_PERCENT) SignalSeverity.STRONG
                else SignalSeverity.NOTABLE,
                value = oiChangePercent,
                factor = oiMinutes?.toDouble(),
                seenAt = now,
            )
        }
        if (fundingPercent != null && abs(fundingPercent) >= SIGNAL_FUNDING_PERCENT) {
            out += ActivitySignal(
                kind = SignalKind.FUNDING_EXTREME,
                severity = if (abs(fundingPercent) >= SIGNAL_FUNDING_STRONG_PERCENT) SignalSeverity.STRONG
                else SignalSeverity.NOTABLE,
                value = fundingPercent,
                seenAt = now,
            )
        }
        return out.sortedWith(SIGNAL_ORDER)
    }

    /** Neuer Bericht plus die Arten, die vorher nicht aktiv waren (für die Meldung). */
    data class Merge(val report: ActivityReport, val newKinds: Set<SignalKind>)

    /**
     * Frische Signale ersetzen gleichartige alte; alte, die nicht mehr erkannt
     * werden, bleiben bis 1 Std. nach dem letzten Erkennen stehen.
     */
    fun merge(previous: ActivityReport?, fresh: List<ActivitySignal>, now: Long): Merge {
        val before = previous?.active(now).orEmpty()
        val freshKinds = fresh.map { it.kind }.toSet()
        val kept = before.filter { it.kind !in freshKinds }
        val signals = (fresh.map { it.copy(seenAt = now) } + kept).sortedWith(SIGNAL_ORDER)
        return Merge(
            report = ActivityReport(signals, computedAt = now),
            newKinds = freshKinds - before.map { it.kind }.toSet(),
        )
    }

    /** Melden, wenn etwas Neues dazukam und die letzte Meldung über 60 Min. her ist. */
    fun shouldNotify(enabled: Boolean, newKinds: Set<SignalKind>, lastNotifiedAt: Long, now: Long): Boolean =
        enabled && newKinds.isNotEmpty() && now - lastNotifiedAt >= NOTIFY_INTERVAL_MILLIS

    /** Ist die letzte Prüfung älter als 10 Min. (oder gibt es keine)? */
    fun isDue(report: ActivityReport?, now: Long): Boolean =
        report == null || now - report.computedAt !in 0 until ANALYSIS_INTERVAL_MILLIS

    // ---------------- Gründe ----------------

    fun fearGreedLevel(value: Int): FearGreedLevel = when {
        value < 25 -> FearGreedLevel.EXTREME_FEAR
        value < 45 -> FearGreedLevel.FEAR
        value <= 55 -> FearGreedLevel.NEUTRAL
        value <= 75 -> FearGreedLevel.GREED
        else -> FearGreedLevel.EXTREME_GREED
    }

    /** Ordnet ein Paar ein: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung. */
    fun explain(input: WhyInput): WhyReport {
        val now = input.now
        val candles = input.candles?.takeIf { it.size >= 2 }
        val change1h = changeOver(candles, 1, now)
        val change24h = changeOver(candles, 24, now)
        val stats = candles?.let { hourStats(it) }
        val reference24h = changeOver(input.referenceCandles, 24, now)

        val reasons = ArrayList<Reason>()

        // 1) Markt vs. Coin
        if (input.baseAsset.equals("BTC", ignoreCase = true)) {
            if (change24h != null) {
                reasons += Reason(
                    kind = ReasonKind.MARKET_LEADER,
                    tone = toneOf(change24h),
                    value = change24h,
                    secondary = reference24h,
                    strong = abs(change24h) >= MARKET_STRONG_PERCENT,
                )
            }
        } else if (change24h != null && reference24h != null) {
            reasons += marketReason(coin = change24h, btc = reference24h)
        }

        // 2) Volumen
        stats?.volumeRatio?.let { ratio ->
            val moving = abs(stats.zScore) >= VOLATILITY_HIGH_Z ||
                (change24h != null && abs(change24h) >= COIN_MOVE_PERCENT)
            reasons += when {
                ratio >= VOLUME_HIGH_RATIO -> Reason(
                    ReasonKind.VOLUME_HIGH, ReasonTone.WARNING, ratio,
                    strong = ratio >= VOLUME_SPIKE_RATIO,
                )
                ratio <= VOLUME_LOW_RATIO && moving -> Reason(ReasonKind.VOLUME_LOW, ReasonTone.WARNING, ratio)
                else -> Reason(ReasonKind.VOLUME_NORMAL, ReasonTone.NEUTRAL, ratio)
            }
        }

        // 3) Hebel (Futures)
        input.fundingPercent?.let { funding ->
            val oi = input.openInterestChangePercent
            val oiJump = oi != null && abs(oi) >= OI_JUMP_PERCENT
            reasons += when {
                funding >= FUNDING_EXTREME_PERCENT -> Reason(
                    ReasonKind.LEVERAGE_LONGS, ReasonTone.WARNING, funding, oi,
                    strong = funding >= FUNDING_STRONG_PERCENT || oiJump,
                )
                funding <= -FUNDING_EXTREME_PERCENT -> Reason(
                    ReasonKind.LEVERAGE_SHORTS, ReasonTone.WARNING, funding, oi,
                    strong = funding <= -FUNDING_STRONG_PERCENT || oiJump,
                )
                else -> Reason(ReasonKind.LEVERAGE_BALANCED, ReasonTone.NEUTRAL, funding, oi, strong = oiJump)
            }
        }

        // 4) Volatilität
        stats?.let {
            val factor = abs(it.zScore)
            reasons += if (factor >= VOLATILITY_HIGH_Z) Reason(
                ReasonKind.VOLATILITY_HIGH, toneOf(it.movePercent), factor, it.movePercent,
                strong = factor >= PRICE_Z_THRESHOLD,
            ) else Reason(ReasonKind.VOLATILITY_NORMAL, ReasonTone.NEUTRAL, factor, it.movePercent)
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

        return WhyReport(
            price = candles?.last()?.close,
            change1h = change1h,
            change24h = change24h,
            // Stabil sortiert: auffällige zuerst, sonst in obiger Reihenfolge
            reasons = reasons.sortedByDescending { it.strong }.take(MAX_REASONS),
            hasMarketData = candles != null,
            dataTime = now,
        )
    }

    private fun marketReason(coin: Double, btc: Double): Reason {
        val btcMoves = abs(btc) >= MARKET_MOVE_PERCENT
        val coinMoves = abs(coin) >= COIN_MOVE_PERCENT
        return when {
            btcMoves && coinMoves && (coin > 0) != (btc > 0) ->
                Reason(ReasonKind.AGAINST_MARKET, toneOf(coin), coin, btc, strong = true)
            btcMoves ->
                Reason(ReasonKind.MARKET_WIDE, toneOf(btc), btc, coin, strong = abs(btc) >= MARKET_STRONG_PERCENT)
            coinMoves ->
                Reason(ReasonKind.COIN_ONLY, toneOf(coin), coin, btc, strong = true)
            else ->
                Reason(ReasonKind.MARKET_CALM, ReasonTone.NEUTRAL, btc, coin)
        }
    }

    private fun toneOf(change: Double): ReasonTone = when {
        change >= 0.005 -> ReasonTone.UP
        change <= -0.005 -> ReasonTone.DOWN
        else -> ReasonTone.NEUTRAL
    }
}
