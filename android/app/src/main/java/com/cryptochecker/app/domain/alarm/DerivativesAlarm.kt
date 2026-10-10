package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import kotlin.math.abs

/**
 * Alarme für Perpetual-Futures: «Funding über/unter x %» (FUNDING_ABOVE/FUNDING_BELOW) und
 * «Open Interest steigt/fällt um x % in N Stunden» (OI_UP/OI_DOWN). Reines Kotlin, als
 * Unit-Test prüfbar (Spiegel: Shared/Services/DerivativesAlarm.swift, gemeinsame Fälle in
 * testdata/parity/alarms_derivatives.json).
 *
 * Wie Kursmarken: Der Alarm meldet beim Überschreiten der Schwelle (scharf = `referenceAt` 0,
 * gemeldet = Zeitpunkt > 0) und wird erst wieder scharf, wenn der Wert um die Hysterese auf
 * die andere Seite zurückgekehrt ist ([FUNDING_HYSTERESIS] Prozentpunkte beim Funding,
 * [OI_HYSTERESIS] Prozentpunkte beim Open Interest). Abklingzeit und «einmalig» wie sonst.
 *
 * Open Interest: Verlauf in Coins (USD-Wert / Kurs, damit eine reine Kursbewegung nicht
 * zählt) je Paar, [OI_RETENTION_MILLIS] lang; verglichen wird mit der jüngsten Messung, die
 * mindestens N Stunden alt ist (höchstens [oiMaxAgeMillis]), sonst mit der ältesten der letzten
 * N Stunden ([oiChangePercent]). Ohne Vergleichsmessung meldet der Alarm nicht.
 */
object DerivativesAlarm {

    /** Funding: Wiederscharfstellung 0,005 Prozentpunkte jenseits der Schwelle. */
    const val FUNDING_HYSTERESIS = 0.005

    /** Open Interest: Wiederscharfstellung 1 Prozentpunkt jenseits der Schwelle. */
    const val OI_HYSTERESIS = 1.0

    /** Wählbare Zeitfenster für Open Interest in Stunden. */
    val OI_WINDOWS = listOf(1, 4, 24)
    const val DEFAULT_OI_WINDOW_HOURS = 4

    /** Vorschläge beim Wechsel auf die Bedingung. */
    const val DEFAULT_FUNDING_PERCENT = 0.05
    const val DEFAULT_OI_PERCENT = 10.0

    /** Grösste sinnvolle Funding-Schwelle (Betrag, in %); Börsen deckeln weit darunter. */
    const val MAX_FUNDING_PERCENT = 10.0

    /** Grösste Open-Interest-Schwelle in %. */
    const val MAX_OI_PERCENT = 1000.0

    /** Funding/Open Interest je Paar höchstens so oft abfragen. */
    const val FETCH_INTERVAL_MILLIS = 5 * 60_000L

    private const val HOUR_MILLIS = 3_600_000L

    /** Open-Interest-Verlauf: so lange aufbewahren (deckt 24 Stunden samt Spielraum). */
    const val OI_RETENTION_MILLIS = 26 * HOUR_MILLIS

    /** Jüngere Messungen dicht ([OI_DENSE_SPACING_MILLIS]), ältere ausgedünnt ([OI_SPARSE_SPACING_MILLIS]). */
    const val OI_DENSE_MILLIS = 2 * HOUR_MILLIS
    const val OI_DENSE_SPACING_MILLIS = 4 * 60_000L
    const val OI_SPARSE_SPACING_MILLIS = 25 * 60_000L

    /** Börsen, deren Perpetuals eigene Funding-/Open-Interest-Daten liefern (FuturesDataSource). */
    val MARKETS = setOf("BinanceFutures", "BybitFutures", "OkexFutures")

    /** Paar kann diese Alarme haben: Perpetual an einer Börse mit eigenen Daten. */
    fun supports(marketKey: String, perpetual: Boolean): Boolean = perpetual && marketKey in MARKETS

    /** Gespeichertes Fenster auf 1, 4 oder 24 Stunden abbilden. */
    fun oiWindowHours(hours: Int): Int = when {
        hours <= 1 -> 1
        hours <= 4 -> 4
        else -> 24
    }

    /** Gültige Schwelle? Funding mit Vorzeichen (auch 0), Open Interest > 0. */
    fun isValidThreshold(condition: AlarmCondition, value: Double?): Boolean {
        if (value == null || !value.isFinite()) return false
        return when {
            condition.isFunding -> abs(value) <= MAX_FUNDING_PERCENT
            condition.isOpenInterest -> value > 0.0 && value <= MAX_OI_PERCENT
            else -> false
        }
    }

    /**
     * Funding-Schwelle lesen: wie [ThresholdParser], dazu ein Vorzeichen («-», «−», «+») und
     * die Null («0», «0,00»). Ungültig oder über [MAX_FUNDING_PERCENT] → null.
     */
    fun parseFunding(text: String, decimalSeparator: Char): Double? {
        var rest = ThresholdParser.latinDigits(text).trim()
        var negative = false
        if (rest.startsWith('-') || rest.startsWith('−')) {
            negative = true
            rest = rest.substring(1).trim()
        } else if (rest.startsWith('+')) {
            rest = rest.substring(1).trim()
        }
        val value = ThresholdParser.parse(rest, decimalSeparator) ?: if (isZero(rest)) 0.0 else return null
        val signed = if (negative && value != 0.0) -value else value
        return signed.takeIf { abs(it) <= MAX_FUNDING_PERCENT }
    }

    /** «0», «0,00», «.0»: nur Nullen und höchstens ein Trenner. */
    private fun isZero(text: String): Boolean =
        text.isNotEmpty() && text.any { it == '0' } && text.all { it == '0' || it == '.' || it == ',' } &&
            text.count { it == '.' || it == ',' } <= 1

    /** Ergebnis einer Prüfung. */
    sealed interface Decision {
        /** Nichts zu tun. */
        data object None : Decision

        /** Gemeldeter Alarm: wieder scharf stellen (`referenceAt` = 0). */
        data object Rearm : Decision

        /** Melden; [value] = Funding in % bzw. Open-Interest-Veränderung in %. */
        data class Fire(val value: Double) : Decision
    }

    /**
     * @param value Funding in % (FUNDING_*) bzw. Open-Interest-Veränderung in % über das
     *   Fenster (OI_*); null = keine Daten (nichts tun)
     * @param armed `referenceAt` <= 0
     */
    fun decide(
        condition: AlarmCondition,
        threshold: Double,
        value: Double?,
        armed: Boolean,
        enabled: Boolean,
        lastTriggeredAt: Long,
        now: Long,
        cooldownMinutes: Int,
    ): Decision {
        if (!enabled || !condition.isDerivatives || !threshold.isFinite()) return Decision.None
        val v = value?.takeIf { it.isFinite() } ?: return Decision.None
        if (!armed) return if (rearms(condition, threshold, v)) Decision.Rearm else Decision.None
        if (!crossed(condition, threshold, v)) return Decision.None
        if (lastTriggeredAt > 0 && cooldownMinutes > 0) {
            val elapsed = now - lastTriggeredAt
            if (elapsed in 0 until cooldownMinutes * 60_000L) return Decision.None
        }
        return Decision.Fire(v)
    }

    /** Schwelle erreicht? Open Interest «fällt um x %»: Veränderung <= −x. */
    fun crossed(condition: AlarmCondition, threshold: Double, value: Double): Boolean = when (condition) {
        AlarmCondition.FUNDING_ABOVE, AlarmCondition.OI_UP -> value >= threshold
        AlarmCondition.FUNDING_BELOW -> value <= threshold
        AlarmCondition.OI_DOWN -> value <= -threshold
        else -> false
    }

    /** Um die Hysterese auf die andere Seite zurück? */
    fun rearms(condition: AlarmCondition, threshold: Double, value: Double): Boolean = when (condition) {
        AlarmCondition.FUNDING_ABOVE -> value < threshold - FUNDING_HYSTERESIS
        AlarmCondition.FUNDING_BELOW -> value > threshold + FUNDING_HYSTERESIS
        AlarmCondition.OI_UP -> value < threshold - OI_HYSTERESIS
        AlarmCondition.OI_DOWN -> value > -threshold + OI_HYSTERESIS
        else -> false
    }

    // ---------------- Open-Interest-Verlauf ----------------

    /** Eine Open-Interest-Messung in Coins. */
    data class OiPoint(val units: Double, val time: Long)

    /** Vergleichsmessung darf höchstens so alt sein: N Stunden + max(30 Min., N/4). */
    fun oiMaxAgeMillis(hours: Int): Long {
        val window = hours.coerceAtLeast(1) * HOUR_MILLIS
        return window + maxOf(30 * 60_000L, window / 4)
    }

    /**
     * Veränderung des Open Interest in % gegenüber der jüngsten Messung, die mindestens
     * [hours] Stunden alt ist (und höchstens [oiMaxAgeMillis]). Fehlt sie, zählt die älteste
     * Messung innerhalb der letzten [hours] Stunden (eine Veränderung in kürzerer Zeit ist auch
     * «in N Stunden») — sonst meldete ein 1-Stunden-Alarm bei einer Hintergrund-Aktualisierung
     * im Stundentakt nie (Messungen mal 50, mal 100 Minuten alt). null ohne Vergleichsmessung
     * oder ohne gültigen aktuellen Wert.
     */
    fun oiChangePercent(history: List<OiPoint>, currentUnits: Double?, hours: Int, now: Long): Double? {
        val current = currentUnits?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val window = hours.coerceAtLeast(1) * HOUR_MILLIS
        val maxAge = oiMaxAgeMillis(hours)
        val valid = history.filter { it.units.isFinite() && it.units > 0.0 }
        val past = valid
            .filter { now - it.time >= window && now - it.time <= maxAge }
            .maxByOrNull { it.time }
            ?: valid.filter { now - it.time in 1 until window }.minByOrNull { it.time }
            ?: return null
        return (current / past.units - 1.0) * 100.0
    }

    /**
     * Neue Messung anhängen (nur wenn die letzte mindestens [OI_DENSE_SPACING_MILLIS] älter
     * ist) und den Verlauf aufräumen ([pruneOi]).
     */
    fun appendOi(history: List<OiPoint>, point: OiPoint, now: Long): List<OiPoint> {
        val valid = point.units.isFinite() && point.units > 0.0
        val last = history.maxByOrNull { it.time }
        val add = valid && (last == null || point.time - last.time >= OI_DENSE_SPACING_MILLIS)
        return pruneOi(if (add) history + point else history, now)
    }

    /**
     * Verlauf aufräumen: nach Zeit sortiert, ungültige, zu alte (> [OI_RETENTION_MILLIS]) und
     * künftige (> 1 Min.) Messungen weg; dann ausdünnen — von der ältesten an bleibt eine
     * Messung, wenn sie mindestens [OI_DENSE_SPACING_MILLIS] (jünger als [OI_DENSE_MILLIS])
     * bzw. [OI_SPARSE_SPACING_MILLIS] (älter) nach der zuletzt behaltenen liegt.
     */
    fun pruneOi(history: List<OiPoint>, now: Long): List<OiPoint> {
        val sorted = history
            .filter { it.units.isFinite() && it.units > 0.0 && now - it.time <= OI_RETENTION_MILLIS && it.time - now <= 60_000L }
            .sortedBy { it.time }
        val kept = ArrayList<OiPoint>(sorted.size)
        for (p in sorted) {
            val previous = kept.lastOrNull()
            if (previous == null) {
                kept += p
                continue
            }
            val spacing = if (now - p.time <= OI_DENSE_MILLIS) OI_DENSE_SPACING_MILLIS else OI_SPARSE_SPACING_MILLIS
            if (p.time - previous.time >= spacing) kept += p
        }
        return kept
    }
}
