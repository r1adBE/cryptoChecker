package com.cryptochecker.app.domain.market

import java.time.LocalDate
import kotlin.math.max

/**
 * Rohdaten für die Bitcoin-Kennzahlen. Fehlende Werte (null) ergeben einfach
 * eine Kennzahl ohne Wert («zu wenig Daten»).
 */
data class CycleInputs(
    /** Aktueller BTC-Kurs in USD. */
    val price: Double,
    /** Durchschnitt der letzten 200 Tagesschlusskurse. */
    val sma200d: Double?,
    /** Durchschnitt der letzten 111 bzw. 350 Tagesschlusskurse (nur noch im Zwischenspeicher, nicht angezeigt). */
    val sma111d: Double?,
    val sma350d: Double?,
    /** Schlusskurs vor 30 Tagen. */
    val price30dAgo: Double?,
    /** Durchschnitt der letzten 200 Wochenschlusskurse. */
    val sma200w: Double?,
    /** Allzeithoch und wann es war. */
    val ath: Double?,
    val athDate: LocalDate?,
    /** On-Chain (Coin Metrics): Marktwert / realisierter Wert. */
    val mvrv: Double?,
    /** Mining-Einnahmen heute geteilt durch ihren 365-Tage-Schnitt. */
    val puell: Double?,
    /** Hashrate: 30- und 60-Tage-Schnitt. */
    val hash30d: Double?,
    val hash60d: Double?,
    val today: LocalDate = LocalDate.now(),
    /** Einordnung in den eigenen Verlauf seit 2016 ([IndicatorHistoryMath]); null = Verlauf nicht geladen. */
    val history: IndicatorHistory? = null,
)

/**
 * Neutrale Kennzahl: nur der Messwert, ohne Wertung, Punkte oder Schwellen (Einheit je Eintrag).
 */
enum class IndicatorId {
    /** Kurs geteilt durch den 200-Tage-Schnitt (Faktor). */
    MAYER,

    /** 200-Wochen-Schnitt (in der Kurswährung). */
    MA200W,

    /** Abstand zum bisherigen Allzeithoch in Prozent (≥ 0 = so weit darunter). */
    ATH_DISTANCE,

    /** Kursveränderung in 30 Tagen in Prozent. */
    CHANGE_30D,

    /** 50-Tage-Schnitt gegenüber dem 200-Tage-Schnitt in Prozent. */
    SMA50_VS_200,

    /** RSI der Wochen- bzw. Tageskerzen (0–100). */
    RSI_WEEKLY,
    RSI_DAILY,

    /** Kursveränderung gegenüber Bitcoin in 90 Tagen in Prozent. */
    VS_BTC,

    /** On-Chain: Marktwert geteilt durch den realisierten Wert (Faktor). */
    MVRV,

    /** On-Chain: Miner-Einnahmen geteilt durch ihren 365-Tage-Schnitt (Faktor). */
    PUELL,

    /** On-Chain: 30-Tage-Schnitt der Hashrate gegenüber dem 60-Tage-Schnitt in Prozent. */
    HASHRATE_30_60,

    /**
     * 111-Tage-Schnitt gegenüber dem Doppelten des 350-Tage-Schnitts in Prozent (Abstand der beiden Linien
     * des «Pi Cycle»; gemessen, ohne Signal).
     */
    PI_CYCLE_GAP,
}

/**
 * Einordnung einer Kennzahl in ihren eigenen Verlauf — reine Rechnung, keine Prognose:
 * [percentileBelow] = an so viel Prozent der Tage seit [IndicatorHistory.since] lag der Wert tiefer;
 * [flat30] = Wert in 30 Tagen, falls der Kurs bis dahin genau gleich bleibt (die Durchschnitte laufen weiter).
 */
data class IndicatorStat(val percentileBelow: Double?, val flat30: Double?)

/** Einordnungen je Kennzahl; [since] = erster Tag mit Wert im Verlauf. */
data class IndicatorHistory(val since: LocalDate, val stats: Map<IndicatorId, IndicatorStat>)

/**
 * Eine Kennzahl; [value] null heisst: zu wenig Daten bzw. nicht verfügbar. [stat] = Einordnung in den Verlauf
 * ([IndicatorStat]); null = ohne Verlauf.
 */
data class Indicator(val id: IndicatorId, val value: Double?, val stat: IndicatorStat? = null)

/** Bitcoin-Kennzahlen in fester Reihenfolge, dazu ob On-Chain-Daten (Coin Metrics) vorlagen. */
data class BitcoinReport(
    val price: Double,
    val indicators: List<Indicator>,
    val onChainAvailable: Boolean,
    /** Erster Tag des Verlaufs der Einordnung ([IndicatorStat]); null = ohne Verlauf. */
    val historySince: LocalDate? = null,
)

/**
 * Bitcoin-Kennzahlen als reine Messwerte — bewusst ohne Zonen, Scores, Phasen oder
 * Deutung («überhitzt», «günstig» usw.). Jede Zahl wird mit einem Satz erklärt, was sie
 * misst; die App zieht daraus keinen Schluss. Wie `BitcoinIndicatorModel` (iOS).
 */
object BitcoinIndicatorModel {

    fun evaluate(input: CycleInputs): BitcoinReport {
        val price = input.price
        val mvrv = input.mvrv?.takeIf { it.isFinite() }
        val puell = input.puell?.takeIf { it.isFinite() }
        val stats = input.history?.stats.orEmpty()
        fun ind(id: IndicatorId, value: Double?) = Indicator(id, value, value?.let { stats[id] })
        val indicators = listOf(
            ind(IndicatorId.MAYER, IndicatorMath.ratio(price, input.sma200d)),
            ind(IndicatorId.MA200W, input.sma200w?.takeIf { it > 0 }),
            ind(IndicatorId.ATH_DISTANCE, IndicatorMath.athDistance(price, input.ath)),
            ind(IndicatorId.CHANGE_30D, IndicatorMath.changePercent(price, input.price30dAgo)),
            ind(IndicatorId.PI_CYCLE_GAP, IndicatorMath.piGap(input.sma111d, input.sma350d)),
            ind(IndicatorId.MVRV, mvrv),
            ind(IndicatorId.PUELL, puell),
            ind(IndicatorId.HASHRATE_30_60, IndicatorMath.changePercent(input.hash30d, input.hash60d)),
        )
        return BitcoinReport(
            price, indicators, onChainAvailable = mvrv != null || puell != null,
            historySince = input.history?.since,
        )
    }
}

/** Gemeinsame Rechnungen von Bitcoin- und Coin-Kennzahlen. */
object IndicatorMath {
    /** [value] / [base]; null ohne gültige Basis. */
    fun ratio(value: Double?, base: Double?): Double? =
        if (value != null && base != null && base > 0 && value.isFinite()) value / base else null

    /** Veränderung von [base] zu [value] in Prozent; null ohne gültige Werte. */
    fun changePercent(value: Double?, base: Double?): Double? = ratio(value, base)?.let { (it - 1.0) * 100.0 }

    /** Abstand unter dem Allzeithoch in Prozent (0, wenn der Kurs darüber liegt). */
    fun athDistance(price: Double, ath: Double?): Double? = ratio(price, ath)?.let { max(0.0, (1.0 - it) * 100.0) }

    /** 111-Tage-Schnitt gegenüber dem Doppelten des 350-Tage-Schnitts in Prozent. */
    fun piGap(sma111: Double?, sma350: Double?): Double? = changePercent(sma111, sma350?.times(2.0))
}
