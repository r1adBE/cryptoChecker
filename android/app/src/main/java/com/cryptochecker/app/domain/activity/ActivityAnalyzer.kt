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

    /**
     * Melden, wenn etwas Neues dazukam und die letzte Meldung über 60 Min. her ist. Liegt die letzte
     * Meldung in der Zukunft (Uhr vor- und wieder zurückgestellt), gilt das als «her» — sonst bliebe
     * die Meldung bis zu jenem Zeitpunkt stumm.
     */
    fun shouldNotify(enabled: Boolean, newKinds: Set<SignalKind>, lastNotifiedAt: Long, now: Long): Boolean =
        enabled && newKinds.isNotEmpty() && now - lastNotifiedAt !in 0 until NOTIFY_INTERVAL_MILLIS

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
     * Ähnlich genug, dass «nur dieser Coin» nicht stimmt: Eine Referenz bewegt sich in dieselbe
     * Richtung und mindestens halb so stark wie der Coin.
     */
    const val SIMILAR_RATIO = 0.5

    // Gründe je Paar ([explain]), Lage und «nicht live»: ActivityWhy.kt (Erweiterungsfunktionen)
}
