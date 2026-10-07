package com.cryptochecker.app.domain.activity

/**
 * Eine Stundenkerze (Binance-Kline 1h). Listen sind zeitlich aufsteigend
 * sortiert; die LETZTE Kerze ist die laufende, noch nicht abgeschlossene Stunde.
 */
data class HourCandle(
    /** Startzeit der Stunde (Epoch-ms, UTC). */
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** Gehandelte Menge in der Basiswährung. */
    val volume: Double,
)

/** Kennzahlen der letzten abgeschlossenen Stunde. */
data class HourStats(
    /** Rendite der letzten abgeschlossenen Stunde in Prozent (Schluss zu Schluss). */
    val movePercent: Double,
    /** z-Wert dieser Rendite gegenüber den 24 Stunden davor. */
    val zScore: Double,
    /** Volumen dieser Stunde geteilt durch den Schnitt der 24 Stunden davor; null ohne Volumen. */
    val volumeRatio: Double?,
    /** Startzeit der bewerteten Stunde (Epoch-ms). */
    val candleOpenTime: Long,
)

/** Arten von «hier passiert gerade etwas». Reihenfolge = Rang bei gleicher Stärke. */
enum class SignalKind { PRICE_MOVE, VOLUME_SPIKE, OPEN_INTEREST_JUMP, FUNDING_EXTREME }

/** Stärke eines Signals; STRONG wird zuerst genannt. */
enum class SignalSeverity { NOTABLE, STRONG }

/**
 * Ein erkanntes Signal.
 *
 * Bedeutung von [value] / [factor] je Art:
 *  - PRICE_MOVE: value = Bewegung der letzten Stunde in % (mit Vorzeichen), factor = |z|
 *  - VOLUME_SPIKE: value = Volumen-Verhältnis (z. B. 4.2), factor = null
 *  - OPEN_INTEREST_JUMP: value = Veränderung in % (mit Vorzeichen), factor = Minuten seit der Vergleichsmessung
 *  - FUNDING_EXTREME: value = Funding Rate in % je Periode (mit Vorzeichen), factor = null
 */
data class ActivitySignal(
    val kind: SignalKind,
    val severity: SignalSeverity,
    val value: Double,
    val factor: Double? = null,
    /** Zuletzt erkannt (Epoch-ms); das Signal gilt bis [ActivityAnalyzer.SIGNAL_TTL_MILLIS] danach. */
    val seenAt: Long = 0,
)

/** Ergebnis der Prüfung eines Paars. */
data class ActivityReport(
    val signals: List<ActivitySignal>,
    /** Zeitpunkt der letzten Prüfung (Epoch-ms) — Grundlage für den 10-Minuten-Cache. */
    val computedAt: Long,
) {
    /** Noch gültige Signale, stärkstes zuerst. */
    fun active(now: Long): List<ActivitySignal> =
        signals.filter { now - it.seenAt in 0..ActivityAnalyzer.SIGNAL_TTL_MILLIS }
            .sortedWith(ActivityAnalyzer.SIGNAL_ORDER)
}

/** Gespeicherte Open-Interest-Messung (in Coins, nicht USD — Kursbewegungen zählen so nicht mit). */
data class OiSample(val units: Double, val time: Long)

/** Ergebnis des Open-Interest-Vergleichs. */
data class OiUpdate(
    /** Veränderung in %, null wenn kein brauchbarer Vergleichswert (zu jung/zu alt/fehlt). */
    val changePercent: Double?,
    /** Minuten seit der Vergleichsmessung, sofern verglichen. */
    val minutes: Int?,
    /** Neue Messung speichern? */
    val store: Boolean,
)

// ---------------- «Warum bewegt sich das?» ----------------

/**
 * Art eines Grundes. Bedeutung von [Reason.value] / [Reason.secondary]:
 *  - MARKET_WIDE:     value = BTC 24h %, secondary = Coin 24h %
 *  - COIN_ONLY:       value = Coin 24h %, secondary = BTC 24h %
 *  - AGAINST_MARKET:  value = Coin 24h %, secondary = BTC 24h %
 *  - MARKET_CALM:     value = BTC 24h %, secondary = Coin 24h %
 *  - MARKET_LEADER:   value = BTC 24h %, secondary = ETH 24h % (oder null)
 *  - VOLUME_HIGH / VOLUME_LOW / VOLUME_NORMAL: value = Volumen-Verhältnis letzte Stunde
 *  - LEVERAGE_LONGS / LEVERAGE_SHORTS / LEVERAGE_BALANCED: value = Funding %, secondary = Open-Interest-Veränderung % (oder null)
 *  - VOLATILITY_HIGH / VOLATILITY_NORMAL: value = |z| (Faktor gegenüber üblich), secondary = Bewegung letzte Stunde %
 *  - SENTIMENT:       value = Fear & Greed (0–100), secondary = Veränderung gegenüber gestern (oder null)
 */
enum class ReasonKind {
    MARKET_WIDE, COIN_ONLY, AGAINST_MARKET, MARKET_CALM, MARKET_LEADER,
    VOLUME_HIGH, VOLUME_LOW, VOLUME_NORMAL,
    LEVERAGE_LONGS, LEVERAGE_SHORTS, LEVERAGE_BALANCED,
    VOLATILITY_HIGH, VOLATILITY_NORMAL,
    SENTIMENT,
}

/** Grundton für Symbolfarbe: steigend, fallend, neutral oder Vorsicht. */
enum class ReasonTone { UP, DOWN, NEUTRAL, WARNING }

/** Ein Grund als Daten; Text und Zahlenformat macht die Oberfläche. */
data class Reason(
    val kind: ReasonKind,
    val tone: ReasonTone,
    val value: Double,
    val secondary: Double? = null,
    /** Auffällig — wird vor den ruhigen Gründen gezeigt. */
    val strong: Boolean = false,
)

/** Fear-&-Greed-Stufe (wie im Markt-Tab). */
enum class FearGreedLevel { EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED }

/** Eingaben für die Einordnung eines Paars. Alles optional — was fehlt, entfällt. */
data class WhyInput(
    val baseAsset: String,
    /** Stundenkerzen des Paars (letzte = laufende), null wenn Binance es nicht führt. */
    val candles: List<HourCandle>?,
    /** Stundenkerzen der Referenz: BTCUSDT, bei Bitcoin selbst ETHUSDT. */
    val referenceCandles: List<HourCandle>?,
    /** Funding Rate in % je Periode, null ohne Futures. */
    val fundingPercent: Double?,
    /** Open-Interest-Veränderung in %, null ohne Vergleichswert. */
    val openInterestChangePercent: Double?,
    val fearGreed: Int?,
    val fearGreedYesterday: Int?,
    val now: Long,
)

/** Ergebnis für das «Warum»-Blatt. */
data class WhyReport(
    /** Letzter Binance-Kurs (laufende Kerze), null ohne Kerzen. */
    val price: Double?,
    val change1h: Double?,
    val change24h: Double?,
    /** 2–5 Gründe, auffällige zuerst (höchstens [ActivityAnalyzer.MAX_REASONS]). */
    val reasons: List<Reason>,
    /** false = Binance führt das Paar nicht (Leerzustand zeigen). */
    val hasMarketData: Boolean,
    /** Zeitpunkt der Daten (Epoch-ms). */
    val dataTime: Long,
)
