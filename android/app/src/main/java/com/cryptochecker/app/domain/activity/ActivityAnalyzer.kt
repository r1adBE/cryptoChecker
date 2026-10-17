package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.data.remote.FuturesSources
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
    /** Funding in % je 8 Stunden (stündliches Funding wird umgerechnet: [fundingPer8h]). */
    const val FUNDING_EXTREME_PERCENT = 0.05
    const val FUNDING_STRONG_PERCENT = 0.1
    const val OI_JUMP_PERCENT = 10.0
    const val OI_STRONG_PERCENT = 20.0
    const val OI_MIN_AGE_MILLIS = 30 * 60_000L
    const val OI_MAX_AGE_MILLIS = 6 * HOUR_MILLIS

    /**
     * Höchstens so viele der 24 Vergleichsstunden dürfen ganz ohne Handel sein (ein Viertel),
     * sonst ist das Volumen-Verhältnis unzuverlässig und entfällt.
     */
    const val MAX_EMPTY_VOLUME_HOURS = WINDOW_HOURS / 4

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

    /** Funding-Schwellen gelten pro so vielen Stunden (wie [com.cryptochecker.app.domain.alarm.DerivativesAlarm.FUNDING_BASIS_HOURS]). */
    const val FUNDING_BASIS_HOURS = 8

    /**
     * Abrechnungsintervall in Stunden, wenn es nicht [FUNDING_BASIS_HOURS] ist; sonst null. [reported] =
     * Intervall des Kontrakts laut Börse (Binance 1/4/8, Bybit, OKX …), sonst das übliche der Quelle
     * (Hyperliquid: 1, [FuturesSources.fundingIntervalHours]). Unbekanntes Intervall: wie 8 Std. behandelt —
     * nur für die Einschätzung «auffällig» im Blatt, nie für Alarme ([DerivativesAlarm.fundingPer8h]).
     */
    fun fundingIntervalHours(source: String?, reported: Int? = null): Int? =
        (reported?.takeIf { it in 1..24 } ?: FuturesSources.fundingIntervalHours(source) ?: FUNDING_BASIS_HOURS)
            .takeIf { it != FUNDING_BASIS_HOURS }

    /**
     * Funding für den Vergleich mit den Schwellen auf [FUNDING_BASIS_HOURS] Stunden umgerechnet, damit
     * Gleiches mit Gleichem verglichen wird (stündlicher Satz zählt achtfach). [intervalHours] null = 8.
     */
    fun fundingPer8h(percent: Double, intervalHours: Int?): Double {
        val hours = intervalHours?.takeIf { it > 0 } ?: FUNDING_BASIS_HOURS
        return percent * FUNDING_BASIS_HOURS / hours
    }
    const val ANALYSIS_INTERVAL_MILLIS = 10 * 60_000L
    const val NOTIFY_INTERVAL_MILLIS = 60 * 60_000L

    // --- Gründe ---
    const val MARKET_MOVE_PERCENT = 1.5
    const val COIN_MOVE_PERCENT = 3.0
    const val MARKET_STRONG_PERCENT = 3.0
    const val VOLUME_HIGH_RATIO = 2.0
    const val VOLUME_LOW_RATIO = 0.6
    const val VOLATILITY_HIGH_Z = 2.0

    /**
     * «Schwankung höher als üblich» braucht neben |z| ≥ [VOLATILITY_HIGH_Z] auch eine Stunden-
     * Bewegung von mindestens 0,5 % — sonst meldet ein sehr ruhiger Coin schon Staub.
     */
    const val VOLATILITY_MIN_MOVE_PERCENT = 0.5
    const val MAX_REASONS = 5

    /** «Nähe zum Tief»: gleiche Tage wie das Hoch. */
    const val LOW_DAYS = 30

    /** Kerzen für «Warum?»: 72 Stunden Lage + 24-h-Fenster + Vergleichsstunden davor, mit Reserve. */
    const val WHY_HOURLY_LIMIT = 100

    // --- Lage in den letzten drei Tagen ---
    /** Fenster in Stunden. */
    const val RANGE_HOURS = 72

    /** Mindestens so viele Stunden Verlauf, sonst keine Aussage. */
    const val RANGE_MIN_HOURS = 48

    /** Bis zu diesem Abstand (in %) «nahe am 3-Tage-Hoch/-Tief». */
    const val RANGE_NEAR_PERCENT = 1.0

    /** «Nahe am Hoch/Tief» nur, wenn die Spanne der drei Tage mindestens so gross ist (in %). */
    const val RANGE_MIN_SPAN_PERCENT = 3.0

    /** Untergrenze für «−8 % seit dem 3-Tage-Hoch» (in %). */
    const val RANGE_FLOOR_PERCENT = 5.0

    /** Abstand auffällig ab k · σ₁ₕ · √72 (mindestens [RANGE_FLOOR_PERCENT]). */
    const val RANGE_SIGMA_K = 2.0

    /** «Nähe zum Hoch»: so viele Tage zurück. */
    const val HIGH_DAYS = 30
    const val DAY_MILLIS = 24 * HOUR_MILLIS

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

        // Volumen: Umsatz in der Quote, wenn jede Kerze ihn hat (Binance), sonst die gehandelte
        // Menge in Coins (Coinbase u. a.) — dann nur genähert. Zu viele Stunden ganz ohne Handel:
        // kein Verhältnis (ein Schnitt über leere Stunden wäre bedeutungslos).
        val quote = (first..last).all { candles[it].quoteVolume?.let { q -> q.isFinite() && q >= 0.0 } == true }
        fun vol(i: Int): Double = if (quote) candles[i].quoteVolume ?: 0.0 else candles[i].volume
        val emptyHours = (first until last).count { !(vol(it) > 0.0) }
        val avgVolume = (first until last).sumOf { vol(it) } / WINDOW_HOURS
        val ratio = if (avgVolume > 0.0 && emptyHours <= MAX_EMPTY_VOLUME_HOURS) vol(last) / avgVolume else null

        return HourStats(
            movePercent = move,
            zScore = (move - mean) / sigma,
            volumeRatio = ratio?.takeIf { it.isFinite() },
            candleOpenTime = candles[last].openTime,
            volumeApproximate = !quote,
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

    /**
     * Alle Signale eines Paars, stärkstes zuerst. Die Schwellen kommen aus der
     * [sensitivity] (Standard = bisherige Werte) — für Karte und Meldungen gleich.
     * [fundingSource] = Quelle der Funding Rate (stündliches Funding zählt achtfach, [fundingPer8h]).
     */
    fun signals(
        stats: HourStats?,
        fundingPercent: Double?,
        oiChangePercent: Double?,
        oiMinutes: Int?,
        now: Long,
        sensitivity: ActivitySensitivity = ActivitySensitivity.NORMAL,
        fundingSource: String? = null,
        /** Intervall des Kontrakts laut Börse ([com.cryptochecker.app.data.remote.FuturesInfo.fundingIntervalHours]). */
        fundingIntervalHours: Int? = null,
    ): List<ActivitySignal> {
        // Kandidaten mit allen Werten; ob und wie stark sie zählen, entscheiden die Schwellen
        val candidates = ArrayList<ActivitySignal>()
        if (stats != null) {
            candidates += ActivitySignal(
                kind = SignalKind.PRICE_MOVE,
                severity = SignalSeverity.NOTABLE,
                value = stats.movePercent,
                factor = abs(stats.zScore),
                seenAt = now,
            )
            stats.volumeRatio?.let { ratio ->
                candidates += ActivitySignal(SignalKind.VOLUME_SPIKE, SignalSeverity.NOTABLE, ratio, seenAt = now)
            }
        }
        if (oiChangePercent != null) {
            candidates += ActivitySignal(
                kind = SignalKind.OPEN_INTEREST_JUMP,
                severity = SignalSeverity.NOTABLE,
                value = oiChangePercent,
                factor = oiMinutes?.toDouble(),
                seenAt = now,
            )
        }
        if (fundingPercent != null) {
            // Wert der Börse (Anzeige), Intervall für den Vergleich auf 8 Stunden
            candidates += ActivitySignal(
                SignalKind.FUNDING_EXTREME, SignalSeverity.NOTABLE, fundingPercent,
                factor = fundingIntervalHours(fundingSource, fundingIntervalHours)?.toDouble(), seenAt = now,
            )
        }
        return applySensitivity(candidates, sensitivity)
    }

    /**
     * Beurteilt Signale neu nach der [sensitivity]: was unter den Schwellen liegt, fällt
     * weg, die Stärke wird neu bestimmt. So wirkt eine geänderte Einstellung sofort auch
     * auf gespeicherte Signale (Karte, ⚡ an den Zeilen); stärkstes zuerst.
     */
    fun applySensitivity(signals: List<ActivitySignal>, sensitivity: ActivitySensitivity): List<ActivitySignal> {
        val thresholds = SignalThresholds.of(sensitivity)
        return signals.mapNotNull { signal ->
            thresholds.severityOf(signal)?.let { signal.copy(severity = it) }
        }.sortedWith(SIGNAL_ORDER)
    }

    /** Neuer Bericht plus die Arten, die vorher nicht aktiv waren (für die Meldung). */
    data class Merge(val report: ActivityReport, val newKinds: Set<SignalKind>)

    /**
     * Frische Signale ersetzen gleichartige alte; alte, die nicht mehr erkannt
     * werden, bleiben bis 1 Std. nach dem letzten Erkennen stehen.
     */
    fun merge(
        previous: ActivityReport?,
        fresh: List<ActivitySignal>,
        now: Long,
        sensitivity: ActivitySensitivity = ActivitySensitivity.NORMAL,
    ): Merge {
        // Alte Signale nach der aktuellen Empfindlichkeit: nach «Weniger» zählt nur noch, was dort auffällt
        val before = applySensitivity(previous?.active(now).orEmpty(), sensitivity)
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

    /**
     * Arten für die Meldung: neue plus die zurückgehaltenen [pending] (Meldung kam zuletzt nicht an),
     * soweit sie im Bericht noch aktiv sind.
     */
    fun noticeKinds(newKinds: Set<SignalKind>, pending: Set<SignalKind>, report: ActivityReport, now: Long): Set<SignalKind> {
        if (pending.isEmpty()) return newKinds
        val active = report.active(now).map { it.kind }.toSet()
        return newKinds + pending.intersect(active)
    }

    /**
     * Zurückzuhaltende Arten nach der Meldung — gleiche Regel wie bei den Kursalarmen
     * (QuietHours.mayFire): Fällige Meldung ([notify]) nicht zustellbar bzw. nicht abgesetzt
     * ([posted] = false) → [kinds] bleiben offen und nichts gilt als gemeldet; sonst nichts offen.
     */
    fun pendingAfter(kinds: Set<SignalKind>, notify: Boolean, posted: Boolean): Set<SignalKind> =
        if (notify && !posted) kinds else emptySet()

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

    /**
     * Ordnet ein Paar ein: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung; dazu Lage
     * (30-Tage-Hoch/-Tief, drei Tage), Funding-Verlauf und Herkunft. Bewegungen werden an der
     * eigenen Schwankung gemessen ([RelativeMove]); Markt = BTC, ETH und der breite Markt
     * ([BroadMarket]). Stablecoins ([StablePeg]): Abweichung von 1:1 statt Markt und Schwankung.
     */
    fun explain(input: WhyInput): WhyReport {
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
            // Referenz ist BTCUSDT (bei Bitcoin selbst ETH — dann ohnehin kein Satz)
            btcLink = if (stable) null else BtcCorrelation.link(input.baseAsset, candles, reference),
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

    /** Eine Markt-Referenz: 24-h-Veränderung in % und ihre Schwelle. */
    private data class RefMove(val ref: MarketRef, val change: Double, val threshold: Double)

    /**
     * Markt-Referenzen für einen Coin (nicht Bitcoin): BTC (aus [reference]), ETH (nicht für ETH
     * selbst) mit Schwellen aus ihrer eigenen Schwankung, dazu der breite Markt mit der festen
     * Schwelle [MARKET_MOVE_PERCENT] (ohne Kerzen keine Schwankung).
     */
    private fun marketRefs(input: WhyInput, reference: List<HourCandle>?, now: Long): List<RefMove> {
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

    /**
     * Ähnlich genug, dass «nur dieser Coin» nicht stimmt: Eine Referenz bewegt sich in dieselbe
     * Richtung und mindestens halb so stark wie der Coin.
     */
    const val SIMILAR_RATIO = 0.5

    /** Erste Referenz mit dem grössten Wert von [score] (bei Gleichstand die frühere — wie in Swift). */
    private fun firstMax(refs: List<RefMove>, score: (RefMove) -> Double): RefMove {
        var best = refs.first()
        for (r in refs.drop(1)) if (score(r) > score(best)) best = r
        return best
    }

    private fun sameSign(a: Double, b: Double): Boolean = (a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0)

    /**
     * Markt vs. Coin über 24 h. Eine Referenz bewegt sich deutlich, wenn |Veränderung| ≥ ihre Schwelle.
     *  - gegen den Markt: der Coin bewegt sich deutlich, ALLE deutlich bewegten Referenzen in die Gegenrichtung
     *  - marktweit: mindestens eine Referenz bewegt sich deutlich (gezeigt: die deutlichste, gleiche Richtung bevorzugt)
     *  - marktweit (schwach): der Coin bewegt sich deutlich, eine Referenz ähnlich ([SIMILAR_RATIO])
     *  - nur dieser Coin: der Coin bewegt sich deutlich, kein Markt ähnlich
     *  - sonst ruhig
     */
    private fun marketReason(coin: Double, coinThreshold: Double, refs: List<RefMove>): Reason {
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
    fun high30d(daily: List<HourCandle>?, now: Long): Double? {
        val days = recentDays(daily, now, HIGH_DAYS) ?: return null
        val high = days.maxOf { it.high }
        return high.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Tiefster Kurs der letzten [LOW_DAYS] Tage, gleiche Regeln wie [high30d]. */
    fun low30d(daily: List<HourCandle>?, now: Long): Double? {
        val days = recentDays(daily, now, LOW_DAYS) ?: return null
        // Ungültige Tiefs (≤ 0) zählen nicht, sonst stünde jeder Kurs «weit über dem Tief»
        if (days.any { !(it.low > 0.0) || !it.low.isFinite() }) return null
        return days.minOf { it.low }
    }

    /** Die letzten [count] Tageskerzen; null bei zu wenigen oder einer veralteten/künftigen Reihe. */
    private fun recentDays(daily: List<HourCandle>?, now: Long, count: Int): List<HourCandle>? {
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
    fun position3d(candles: List<HourCandle>, now: Long): Position3d? {
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
    fun notLive(now: Long): WhyReport = WhyReport(
        price = null,
        change1h = null,
        change24h = null,
        reasons = emptyList(),
        hasMarketData = false,
        dataTime = now,
    )

    private fun toneOf(change: Double): ReasonTone = when {
        change >= 0.005 -> ReasonTone.UP
        change <= -0.005 -> ReasonTone.DOWN
        else -> ReasonTone.NEUTRAL
    }
}
