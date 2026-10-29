package com.cryptochecker.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import kotlinx.coroutines.sync.withLock

/**
 * Gemerkten Stand einer Markt-Meldung (Fear & Greed, eigene Grenzen der Bitcoin-Kennzahlen, Gas, USDT-Depeg) vergessen,
 * sobald ihre Grenze bzw. ihr Schalter in den Einstellungen ausgeschaltet wird. Ist alles aus, läuft keine Prüfung
 * (ZoneCheckWorker abgesagt, keine Hintergrund-Aktualisierung bzw. Depeg-Prüfung) — ohne Lauf während «Aus» bliebe der
 * alte Stand liegen, und nach dem Wiedereinschalten mit derselben Grenze bliebe die Meldung stumm. Jeweils unter der
 * Sperre der Prüfung: eine laufende Prüfung schreibt danach nicht den alten Stand zurück. Aufgerufen von
 * [SettingsRepository] (auch beim Wiederherstellen einer Sicherung). Wie iOS `MarketAlertReset`.
 */
object MarketAlertReset {

    /** Fear & Greed: Grenze «unter» ([below]) bzw. «über» aus — gemerkte Grenze gilt als «aus» (0), die nächste bewertet neu. */
    fun fearGreedOff(prefs: SharedPreferences, below: Boolean) {
        // Kein gemerkter Stand: nichts zu vergessen
        if (!prefs.contains(ZoneCheckPrefs.KEY_FNG)) return
        prefs.edit { putInt(if (below) ZoneCheckPrefs.KEY_FNG_BELOW else ZoneCheckPrefs.KEY_FNG_ABOVE, 0) }
    }

    /** Grenze einer Bitcoin-Kennzahl aus: «schon gemeldet» und die zugehörige Grenze vergessen. */
    fun indicatorOff(prefs: SharedPreferences, kind: IndicatorAlertKind) {
        prefs.edit { remove(ZoneCheckPrefs.crossedKey(kind)); remove(ZoneCheckPrefs.thresholdKey(kind)) }
    }

    /** Gas-Alarm Ethereum ([eth]) bzw. Bitcoin aus: «aus» (0) merken — beim Wiedereinschalten ist er scharf. */
    fun gasOff(prefs: SharedPreferences, eth: Boolean) {
        val key = if (eth) GasAlertPrefs.KEY_ETH_THRESHOLD else GasAlertPrefs.KEY_BTC_THRESHOLD
        prefs.edit { putLong(key, 0.0.toRawBits()) }
    }

    /** USDT-Depeg-Alarm aus: Schwelle «aus» (0) merken — beim Wiedereinschalten ist er scharf (`DepegAlarm.armedFor`). */
    fun depegOff(prefs: SharedPreferences) {
        prefs.edit { putInt(DepegAlarmPrefs.KEY_ARMED_TENTHS, 0) }
    }

    suspend fun fearGreedOff(context: Context, below: Boolean) =
        ZoneCheckPrefs.lock.withLock { fearGreedOff(zonePrefs(context), below) }

    suspend fun indicatorOff(context: Context, kind: IndicatorAlertKind) =
        ZoneCheckPrefs.lock.withLock { indicatorOff(zonePrefs(context), kind) }

    suspend fun gasOff(context: Context, eth: Boolean) =
        gasOffLocked(context.getSharedPreferences(GasAlertPrefs.PREFS, Context.MODE_PRIVATE), eth)

    /** Unter der Sperre der Gas-Prüfung ([GasAlertPrefs.lock]): eine laufende Prüfung überschreibt das «Aus» nicht. */
    suspend fun gasOffLocked(prefs: SharedPreferences, eth: Boolean) =
        GasAlertPrefs.lock.withLock { gasOff(prefs, eth) }

    suspend fun depegOff(context: Context) =
        DepegAlarmPrefs.lock.withLock { depegOff(context.getSharedPreferences(DepegAlarmPrefs.PREFS, Context.MODE_PRIVATE)) }

    private fun zonePrefs(context: Context) = context.getSharedPreferences(ZoneCheckPrefs.PREFS, Context.MODE_PRIVATE)
}
