package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.activity.WhyMark
import kotlin.math.abs

/**
 * Crypto Pulse: «Was passiert gerade?» in wenigen Zeilen. Reine Regeln ohne
 * Android und ohne Netz — Texte macht die Oberfläche, damit alles übersetzbar
 * und testbar bleibt.
 */

/** Eingaben; was fehlt, ist null. Prozentwerte mit Vorzeichen. */
data class PulseInput(
    /** 24-h-Veränderung in %. */
    val btc24h: Double?,
    val eth24h: Double?,
    val sol24h: Double?,
    /** BTC-Volumen der letzten abgeschlossenen Stunde ÷ Schnitt der 24 h davor. */
    val btcVolumeRatio: Double?,
    /** Fear & Greed 0–100. */
    val fearGreed: Int?,
    /** BTC-Funding-Rate in % je Periode (meist 8 h). */
    val fundingPercent: Double?,
    /** Ethereum-Gebühr (Stufe «normal») in gwei. */
    val ethGasGwei: Double?,
    /** Zeitpunkt der Daten (Epoch-ms). */
    val time: Long,
)

enum class PulseAlts { STRONGER, WEAKER, EVEN }
enum class PulseVolume { HIGH, LOW, NORMAL }
enum class PulseFunding { HIGH, SLIGHT, NEUTRAL, NEGATIVE }
enum class PulseGas { LOW, NORMAL, HIGH }

/** Erster Satz der Zusammenfassung. */
enum class PulseSummary { BROAD_UP_VOLUME, BROAD_UP, BROAD_DOWN_VOLUME, BROAD_DOWN, MIXED, CALM }

/** Zusätzliche Sätze, in dieser Reihenfolge. */
enum class PulseExtra { GREED, FEAR, LEVERAGE }

/** Erster Satz unter der Schlagzeile («Was gerade auffällt»). */
enum class PulseLeadKind { BTC_LEADS, ALTS_STRONGER, BTC_STRONGER, BROAD_UP, BROAD_DOWN, DRIFT_UP, DRIFT_DOWN, MIXED, CALM }

/**
 * Zweiter Satz: Volumen und/oder Funding, je Fall ein ganzer Satz (keine
 * zusammengesetzten Übersetzungsteile). Die VOL_ABOVE- und VOL_BELOW-Fälle
 * tragen [PulseLead.volumePercent] als Platzhalter.
 */
enum class PulseDetail {
    VOL_ABOVE, VOL_ABOVE_FUND_NEUTRAL, VOL_ABOVE_FUND_HIGH, VOL_ABOVE_FUND_NEGATIVE,
    VOL_BELOW, VOL_BELOW_FUND_NEUTRAL, VOL_BELOW_FUND_HIGH, VOL_BELOW_FUND_NEGATIVE,
    VOL_NORMAL, VOL_NORMAL_FUND_NEUTRAL, VOL_NORMAL_FUND_HIGH, VOL_NORMAL_FUND_NEGATIVE,
    FUND_NEUTRAL, FUND_HIGH, FUND_NEGATIVE,
}

/** Schlüssel + Argumente des Leitsatzes; Text macht die Oberfläche. */
data class PulseLead(
    val kind: PulseLeadKind,
    /** null = weder Volumen noch Funding bekannt → nur ein Satz. */
    val detail: PulseDetail?,
    /** Abweichung vom üblichen Volumen in ganzen Prozent, ohne Vorzeichen (1.34 → 34). */
    val volumePercent: Int,
)

/**
 * Zeile der Faktor-Checkliste unter «Warum?». Nur Werte ohne eigene Karte im
 * Markt-Tab: Volumen (Krypto-Markt), Fear & Greed und Gas haben eigene Karten.
 */
enum class PulseFactorKind { FUNDING }

data class PulseFactor(val kind: PulseFactorKind, val mark: WhyMark)

data class PulseReport(
    val btc24h: Double,
    val eth24h: Double,
    val sol24h: Double,
    val alts: PulseAlts,
    /** null = Abschnitt «Aktivität» entfällt. */
    val volumeRatio: Double?,
    val volume: PulseVolume?,
    /** null = Abschnitt «Stimmung» entfällt. */
    val fearGreed: Int?,
    /** null = Abschnitt «Futures» entfällt. */
    val fundingPercent: Double?,
    val funding: PulseFunding?,
    /** null = Abschnitt «Netzwerk» entfällt. */
    val gasGwei: Double?,
    val gas: PulseGas?,
    val summary: PulseSummary,
    val extras: List<PulseExtra>,
    val time: Long,
)

object CryptoPulse {
    /** Altcoins (Schnitt ETH/SOL) gegenüber BTC, Prozentpunkte. */
    const val ALTS_GAP_PP = 1.5

    const val VOLUME_HIGH_RATIO = 1.5
    const val VOLUME_LOW_RATIO = 0.6

    const val FUNDING_HIGH_PERCENT = 0.03
    const val FUNDING_SLIGHT_PERCENT = 0.01
    const val FUNDING_NEUTRAL_FLOOR_PERCENT = -0.005

    const val GAS_LOW_GWEI = 2.0
    const val GAS_NORMAL_MAX_GWEI = 10.0

    /** Breite Bewegung: alle drei mindestens so weit in dieselbe Richtung. */
    const val BROAD_MOVE_PERCENT = 1.5

    /** Ruhig: alle drei betragsmässig darunter. */
    const val CALM_PERCENT = 1.0

    const val GREED_FROM = 70
    const val FEAR_TO = 30

    /** Ergebnis gilt so lange (Zwischenspeicher). */
    const val CACHE_MILLIS = 5 * 60_000L

    /** null = Marktdaten fehlen (BTC, ETH oder SOL) → «nicht verfügbar». */
    fun evaluate(input: PulseInput): PulseReport? {
        val btc = input.btc24h?.takeIf { it.isFinite() } ?: return null
        val eth = input.eth24h?.takeIf { it.isFinite() } ?: return null
        val sol = input.sol24h?.takeIf { it.isFinite() } ?: return null

        val ratio = input.btcVolumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        val volume = ratio?.let { volumeLevel(it) }
        val fearGreed = input.fearGreed?.takeIf { it in 0..100 }
        val funding = input.fundingPercent?.takeIf { it.isFinite() }
        val gas = input.ethGasGwei?.takeIf { it.isFinite() && it >= 0.0 }

        return PulseReport(
            btc24h = btc,
            eth24h = eth,
            sol24h = sol,
            alts = alts(btc, eth, sol),
            volumeRatio = ratio,
            volume = volume,
            fearGreed = fearGreed,
            fundingPercent = funding,
            funding = funding?.let { fundingLevel(it) },
            gasGwei = gas,
            gas = gas?.let { gasLevel(it) },
            summary = summary(btc, eth, sol, ratio),
            extras = extras(fearGreed, funding),
            time = input.time,
        )
    }

    fun alts(btc: Double, eth: Double, sol: Double): PulseAlts {
        val gap = (eth + sol) / 2.0 - btc
        return when {
            gap >= ALTS_GAP_PP - EPS -> PulseAlts.STRONGER
            gap <= -ALTS_GAP_PP + EPS -> PulseAlts.WEAKER
            else -> PulseAlts.EVEN
        }
    }

    fun volumeLevel(ratio: Double): PulseVolume = when {
        ratio >= VOLUME_HIGH_RATIO -> PulseVolume.HIGH
        ratio <= VOLUME_LOW_RATIO -> PulseVolume.LOW
        else -> PulseVolume.NORMAL
    }

    fun fundingLevel(percent: Double): PulseFunding = when {
        percent >= FUNDING_HIGH_PERCENT - EPS -> PulseFunding.HIGH
        percent >= FUNDING_SLIGHT_PERCENT - EPS -> PulseFunding.SLIGHT
        percent > FUNDING_NEUTRAL_FLOOR_PERCENT -> PulseFunding.NEUTRAL
        else -> PulseFunding.NEGATIVE
    }

    fun gasLevel(gwei: Double): PulseGas = when {
        gwei < GAS_LOW_GWEI -> PulseGas.LOW
        gwei <= GAS_NORMAL_MAX_GWEI -> PulseGas.NORMAL
        else -> PulseGas.HIGH
    }

    fun summary(btc: Double, eth: Double, sol: Double, volumeRatio: Double?): PulseSummary {
        val all = listOf(btc, eth, sol)
        val highVolume = volumeRatio != null && volumeRatio >= VOLUME_HIGH_RATIO
        return when {
            all.all { it >= BROAD_MOVE_PERCENT } ->
                if (highVolume) PulseSummary.BROAD_UP_VOLUME else PulseSummary.BROAD_UP
            all.all { it <= -BROAD_MOVE_PERCENT } ->
                if (highVolume) PulseSummary.BROAD_DOWN_VOLUME else PulseSummary.BROAD_DOWN
            all.all { abs(it) < CALM_PERCENT } -> PulseSummary.CALM
            else -> PulseSummary.MIXED
        }
    }

    fun extras(fearGreed: Int?, fundingPercent: Double?): List<PulseExtra> = buildList {
        if (fearGreed != null && fearGreed >= GREED_FROM) add(PulseExtra.GREED)
        if (fearGreed != null && fearGreed <= FEAR_TO) add(PulseExtra.FEAR)
        if (fundingPercent != null && fundingPercent >= FUNDING_HIGH_PERCENT - EPS) add(PulseExtra.LEVERAGE)
    }

    /** Volumen gilt bis ±5 % Abweichung als «normal». */
    const val VOLUME_NORMAL_BAND_PERCENT = 5

    /**
     * Leitsatz unter der Schlagzeile, höchstens zwei kurze Sätze:
     * 1. Wer führt / wie bewegt sich der Markt (Reihenfolge der Regeln zählt):
     *    ruhig → breit fallend → breit steigend mit BTC als grösster Bewegung →
     *    Altcoins stärker / schwächer → breit steigend → leicht steigend/fallend
     *    (alle gleichgerichtet) → auseinander.
     * 2. Volumen (echtes Verhältnis, ±5 % = normal) und Funding, sofern bekannt.
     */
    fun leadSentence(report: PulseReport): PulseLead {
        val btc = report.btc24h
        val eth = report.eth24h
        val sol = report.sol24h
        val all = listOf(btc, eth, sol)
        val broadUp = report.summary == PulseSummary.BROAD_UP || report.summary == PulseSummary.BROAD_UP_VOLUME
        val broadDown = report.summary == PulseSummary.BROAD_DOWN || report.summary == PulseSummary.BROAD_DOWN_VOLUME
        val btcLargest = abs(btc) >= abs(eth) && abs(btc) >= abs(sol)

        val kind = when {
            report.summary == PulseSummary.CALM -> PulseLeadKind.CALM
            broadDown -> PulseLeadKind.BROAD_DOWN
            broadUp && btcLargest -> PulseLeadKind.BTC_LEADS
            report.alts == PulseAlts.STRONGER -> PulseLeadKind.ALTS_STRONGER
            report.alts == PulseAlts.WEAKER -> PulseLeadKind.BTC_STRONGER
            broadUp -> PulseLeadKind.BROAD_UP
            all.all { it >= 0.0 } && all.any { it > 0.0 } -> PulseLeadKind.DRIFT_UP
            all.all { it <= 0.0 } && all.any { it < 0.0 } -> PulseLeadKind.DRIFT_DOWN
            else -> PulseLeadKind.MIXED
        }

        // Volumen: −1 unter, 0 normal, +1 über, null unbekannt
        val ratio = report.volumeRatio
        val deviation = ratio?.let { Math.round((it - 1.0) * 100.0).toInt() }
        val volume = deviation?.let {
            when {
                abs(it) < VOLUME_NORMAL_BAND_PERCENT -> 0
                it > 0 -> 1
                else -> -1
            }
        }
        // Funding: «leicht positiv» ist der übliche Grundsatz der Börsen → neutral
        val funding = when (report.funding) {
            PulseFunding.HIGH -> 1
            PulseFunding.NEGATIVE -> -1
            PulseFunding.SLIGHT, PulseFunding.NEUTRAL -> 0
            null -> null
        }
        val percent = if (deviation != null && volume != 0) abs(deviation) else 0
        return PulseLead(kind, detail(volume, funding), percent)
    }

    private fun detail(volume: Int?, funding: Int?): PulseDetail? = when (volume) {
        1 -> when (funding) {
            0 -> PulseDetail.VOL_ABOVE_FUND_NEUTRAL
            1 -> PulseDetail.VOL_ABOVE_FUND_HIGH
            -1 -> PulseDetail.VOL_ABOVE_FUND_NEGATIVE
            else -> PulseDetail.VOL_ABOVE
        }
        -1 -> when (funding) {
            0 -> PulseDetail.VOL_BELOW_FUND_NEUTRAL
            1 -> PulseDetail.VOL_BELOW_FUND_HIGH
            -1 -> PulseDetail.VOL_BELOW_FUND_NEGATIVE
            else -> PulseDetail.VOL_BELOW
        }
        0 -> when (funding) {
            0 -> PulseDetail.VOL_NORMAL_FUND_NEUTRAL
            1 -> PulseDetail.VOL_NORMAL_FUND_HIGH
            -1 -> PulseDetail.VOL_NORMAL_FUND_NEGATIVE
            else -> PulseDetail.VOL_NORMAL
        }
        else -> when (funding) {
            0 -> PulseDetail.FUND_NEUTRAL
            1 -> PulseDetail.FUND_HIGH
            -1 -> PulseDetail.FUND_NEGATIVE
            else -> null
        }
    }

    /**
     * Faktor-Checkliste (nur bekannte Werte): ! Vorsicht, – neutral. Volumen,
     * Fear & Greed und Gas stehen in eigenen Karten des Markt-Tabs und fehlen
     * hier bewusst; leer = Abschnitt entfällt.
     */
    fun factors(report: PulseReport): List<PulseFactor> = buildList {
        report.funding?.let { level ->
            val caution = level == PulseFunding.HIGH || level == PulseFunding.NEGATIVE
            add(PulseFactor(PulseFactorKind.FUNDING, if (caution) WhyMark.CAUTION else WhyMark.NEUTRAL))
        }
    }

    /** Ist ein Ergebnis noch frisch genug für den Zwischenspeicher? */
    fun isFresh(computedAt: Long, now: Long): Boolean = now - computedAt in 0 until CACHE_MILLIS

    /** Gleitkomma-Toleranz an den Grenzen (0.1 + 0.2 ≠ 0.3). */
    private const val EPS = 1e-9
}
