package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.market.IndicatorAlertKind
import kotlinx.coroutines.sync.Mutex

/**
 * Gemerkter Stand der Markt-Meldungen (Fear & Greed, eigene Grenzen der Bitcoin-Kennzahlen): Ablage und Schlüssel, die
 * `ZoneCheckWorker` schreibt und [MarketAlertReset] beim Ausschalten einer Grenze zurücksetzt, dazu ihre gemeinsame
 * Sperre (eine laufende Prüfung überschreibt ein «Aus» nicht). Eigenes Objekt, damit die Einstellungen (Datenschicht)
 * den Worker der App nicht kennen müssen.
 */
object ZoneCheckPrefs {
    const val PREFS = "market_zone"
    const val KEY_FNG = "last_fear_greed"
    /** Grenzen, zu denen der gemerkte Stand gehört. */
    const val KEY_FNG_BELOW = "last_fear_greed_below"
    const val KEY_FNG_ABOVE = "last_fear_greed_above"

    fun crossedKey(kind: IndicatorAlertKind) = "indicator_crossed_" + kind.key.lowercase()

    /** Grenze (gespeicherte Ganzzahl), zu der der gemerkte Zustand «jenseits» gehört. */
    fun thresholdKey(kind: IndicatorAlertKind) = "indicator_threshold_" + kind.key.lowercase()

    val lock = Mutex()
}

/**
 * Gemerkter Stand des Gas-Alarms: Ablage, Schlüssel der Grenzen und die gemeinsame Sperre von `GasAlertChecker` und
 * [MarketAlertReset.gasOff] (eine laufende Prüfung überschreibt ein «Aus» nicht).
 */
object GasAlertPrefs {
    const val PREFS = "gas_alerts"
    /** Grenze, zu der der gemerkte Zustand gehört (Double als Bits). */
    const val KEY_ETH_THRESHOLD = "eth_threshold"
    const val KEY_BTC_THRESHOLD = "btc_threshold"
    val lock = Mutex()
}

/**
 * Gemerkter Stand des USDT-Depeg-Alarms: Ablage, Schlüssel und die gemeinsame Sperre von `DepegAlarmChecker` und
 * [MarketAlertReset.depegOff] (eine laufende Prüfung überschreibt ein «Aus» nicht).
 */
object DepegAlarmPrefs {
    const val PREFS = "depeg_alarm"
    /** Schwelle in Zehntelprozent, zu der der gemerkte Zustand «scharf» gehört; 0 = zwischendurch ausgeschaltet. */
    const val KEY_ARMED_TENTHS = "armed_tenths"
    val lock = Mutex()
}
