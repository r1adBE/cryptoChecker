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
    /**
     * Umsatz in der Quote (Binance-Kline Feld 7, «quote asset volume»); null, wenn die Quelle
     * ihn nicht liefert (Coinbase) — dann nähert [com.cryptochecker.app.domain.watch.SheetChart.quoteVolume].
     */
    val quoteVolume: Double? = null,
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
    /**
     * Volumen-Verhältnis nur genähert: Die Quelle liefert keinen Quote-Umsatz (z. B. Coinbase),
     * verglichen wird dann die gehandelte Menge in Coins statt des Umsatzes ([HourCandle.quoteVolume]).
     */
    val volumeApproximate: Boolean = false,
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
 *  - FUNDING_EXTREME: value = Funding Rate in % je Periode der Börse (mit Vorzeichen, so angezeigt),
 *    factor = Abrechnungsintervall in Stunden, wenn nicht 8 (Hyperliquid: 1), sonst null — die Schwellen
 *    vergleichen auf 8 Stunden umgerechnet ([ActivityAnalyzer.fundingPer8h])
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

/**
 * Gespeicherte Open-Interest-Messung (in Coins, nicht USD — Kursbewegungen zählen so nicht mit).
 * [source] = Futures-Quelle («Binance Futures», «Bybit», «OKX»).
 */
data class OiSample(val units: Double, val time: Long, val source: String)

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
 *  - PEG:             value = Abweichung des Stablecoins von 1:1 in % (mit Vorzeichen, −0.12 = 0,12 % darunter)
 *
 * Bei den Markt-Gründen sagt [Reason.ref], welche Referenz «BTC» oben meint (BTC, ETH oder der
 * breite Markt); [Reason.threshold] ist die Schwelle, gegen die verglichen wurde.
 */
enum class ReasonKind {
    MARKET_WIDE, COIN_ONLY, AGAINST_MARKET, MARKET_CALM, MARKET_LEADER,
    VOLUME_HIGH, VOLUME_LOW, VOLUME_NORMAL,
    LEVERAGE_LONGS, LEVERAGE_SHORTS, LEVERAGE_BALANCED,
    VOLATILITY_HIGH, VOLATILITY_NORMAL,
    SENTIMENT,
    PEG,
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
    /** Markt-Gründe: welche Referenz der Marktwert meint; null = BTC (wie bisher). */
    val ref: MarketRef? = null,
    /**
     * Schwelle, gegen die [value] (Markt: die Referenz, Coin allein/gegen den Markt: der Coin)
     * verglichen wurde — relativ zur eigenen Schwankung ([RelativeMove]); null = feste Schwelle.
     */
    val threshold: Double? = null,
    /** Volumen: nur genähert ([HourStats.volumeApproximate]). */
    val approximate: Boolean = false,
    /**
     * LEVERAGE_*: Abrechnungsintervall des Fundings in Stunden, wenn nicht 8 (Hyperliquid: 1); null = 8.
     * [value] bleibt der Satz der Börse, die Schwellen vergleichen auf 8 Stunden umgerechnet.
     */
    val intervalHours: Int? = null,
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
    /**
     * 24-h-Veränderung in % aus dem Ticker des Paars (dieselbe wie Änderung und Merkliste);
     * null = unbekannt, dann aus den Kerzen.
     */
    val tickerChange24h: Double? = null,
    /**
     * false = das Paar wird an seiner Börse nicht mehr gehandelt ([com.cryptochecker.app.domain.watch.NotTraded]):
     * dann gar keine Einordnung — nie ein Urteil auf alten Daten.
     */
    val marketLive: Boolean = true,
    /** Tageskerzen des Paars (letzte = laufender Tag) für das 30-Tage-Hoch; null = keine. */
    val dailyCandles: List<HourCandle>? = null,
    /** Quote des Paars (für die Stablecoin-Erkennung [StablePeg]); null = unbekannt. */
    val quoteAsset: String? = null,
    /** Stundenkerzen von ETHUSDT als zweite Markt-Referenz (nicht für BTC und ETH selbst); null = keine. */
    val ethCandles: List<HourCandle>? = null,
    /** Breiter Markt (Median der CoinGecko-Top-Coins, [BroadMarket]); null = keiner. */
    val broadMarket: BroadMarketSnapshot? = null,
    /** Minuten seit der ältesten verglichenen Open-Interest-Messung ([OiSeries]); null = kein Vergleich. */
    val openInterestMinutes: Int? = null,
    /** false = Open Interest bewegte sich gegenüber den älteren Messungen nicht einheitlich ([OiSeries]). */
    val openInterestConsistent: Boolean = true,
    /** Abgerechnete Funding Rates der letzten Perioden ([FundingTrendRules]); null = keine. */
    val fundingHistory: FundingHistory? = null,
    /** Quelle der aktuellen Funding Rate («Binance Futures», «Bybit», «OKX»); null = unbekannt. */
    val fundingSource: String? = null,
    /** Abrechnungsintervall der aktuellen Rate laut Börse in Stunden; null = nicht gemeldet. */
    val fundingIntervalHours: Int? = null,
    /** Herkunft der Daten für die Zeile «Datenstand» und die Quellen ([WhySources]). */
    val sources: WhySources? = null,
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
    /** Höchster Kurs der letzten 30 Tage ([ActivityAnalyzer.high30d]); null ohne Tageskerzen. */
    val high30d: Double? = null,
    /** Läuft der Coin gerade eng mit Bitcoin bzw. unabhängig ([BtcCorrelation]); null = kein Satz. */
    val btcLink: BtcLink? = null,
    /** Tiefster Kurs der letzten 30 Tage ([ActivityAnalyzer.low30d]); null ohne Tageskerzen. */
    val low30d: Double? = null,
    /** Lage in den letzten drei Tagen, nur wenn auffällig ([Position3d]); null = nichts zu sagen. */
    val position3d: Position3d? = null,
    /** Verlauf der Funding Rate ([FundingTrendRules]); null = keiner. */
    val fundingTrend: FundingTrend? = null,
    /** Minuten seit der ältesten verglichenen Open-Interest-Messung; null = kein Vergleich. */
    val oiMinutes: Int? = null,
    /** false = Open Interest bewegte sich gegenüber den älteren Messungen nicht einheitlich. */
    val oiConsistent: Boolean = true,
    /** Start der laufenden Stundenkerze = Ende der letzten abgeschlossenen (Epoch-ms); null ohne Kerzen. */
    val candleTime: Long? = null,
    /** Stablecoin gegen USD/Stablecoin ([StablePeg]): Abweichung von 1:1 statt Markt und Schwankung. */
    val stable: Boolean = false,
    /** Herkunft der Daten ([WhySources]); null = unbekannt (dann der feste Quellen-Satz). */
    val sources: WhySources? = null,
)
