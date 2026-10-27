package com.cryptochecker.app.domain.gas

import com.cryptochecker.app.settings.GasAlertPrefs
import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.GasDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.market.GasFees
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.notification.canShowMarketNotice
import com.cryptochecker.app.notification.showGas

/**
 * Wann ein Gas-Alarm auslöst: sobald die Gebühr auf oder unter die Grenze
 * fällt — aber nur einmal. Erst wenn sie wieder deutlich darüber liegt
 * ([REARM_FACTOR]), ist der Alarm erneut scharf. So gibt es kein Dauerfeuer,
 * wenn die Gebühr um die Grenze pendelt.
 *
 * Gleiche Regel wie bei den Kursalarmen (QuietHours.mayFire): Kann die Meldung gerade nicht
 * ankommen ([evaluate] mit deliverable = false) oder scheitert das Absetzen ([armedAfter]),
 * bleibt der Alarm scharf und meldet sich, sobald die Meldung wieder ankommt.
 */
object GasAlertLogic {
    const val REARM_FACTOR = 1.25

    /** @return (melden?, scharf danach?) — «scharf danach» gilt für eine erfolgreich abgesetzte Meldung. */
    fun evaluate(value: Double, threshold: Double, armed: Boolean, deliverable: Boolean = true): Pair<Boolean, Boolean> = when {
        threshold <= 0 -> false to true
        // Fällig, aber nicht zustellbar: nicht verbrauchen
        armed && value <= threshold -> if (deliverable) true to false else false to true
        !armed && value > threshold * REARM_FACTOR -> false to true
        else -> false to armed
    }

    /** Zu speichernder Zustand: Ging die fällige Meldung nicht ans System, bleibt der alte Zustand. */
    fun armedAfter(fire: Boolean, posted: Boolean, armedBefore: Boolean, next: Boolean): Boolean =
        if (fire && !posted) armedBefore else next

    /**
     * Gespeicherter Zustand für die aktuelle Grenze: Wurde er bei einer anderen Grenze gemerkt
     * (geändert oder zwischendurch aus = 0), ist der Alarm wieder scharf — sonst bliebe er nach
     * einer neuen Grenze stumm, bis die Gebühr einmal deutlich über der alten lag.
     * [storedThreshold] null = Stand vor dieser Regel: Zustand übernehmen (kein Doppelalarm nach dem Update).
     */
    fun armedFor(storedArmed: Boolean, storedThreshold: Double?, threshold: Double): Boolean =
        if (storedThreshold != null && storedThreshold != threshold) true else storedArmed
}

/**
 * Prüft nach einer Kursaktualisierung die Gas-Alarme (höchstens alle 10 Minuten).
 */
@Singleton
class GasAlertChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gasDataSource: GasDataSource,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun checkIfDue() = lock.withLock {
        val settings = settingsRepository.current()
        val ethThreshold = settings.gasAlertEthTenths / 10.0
        val btcThreshold = settings.gasAlertBtc.toDouble()
        if (ethThreshold <= 0 && btcThreshold <= 0) {
            // «Aus» merken, damit beim Wiedereinschalten (auch mit gleicher Grenze) der Alarm scharf ist
            val off = 0.0.toRawBits()
            if (prefs.getLong(KEY_ETH_THRESHOLD, -1) != off || prefs.getLong(KEY_BTC_THRESHOLD, -1) != off) {
                prefs.edit { putThreshold(KEY_ETH_THRESHOLD, 0.0); putThreshold(KEY_BTC_THRESHOLD, 0.0) }
            }
            return@withLock
        }
        val now = System.currentTimeMillis()
        if (!isDue(now, prefs.getLong(KEY_LAST_CHECK, 0))) return@withLock
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        // Abbruch (Job beendet, Zeitgrenze des Hintergrund-Jobs) weiterreichen statt als «keine Daten» zu werten
        val report = runCatchingCancellable { gasDataSource.fetch() }
            .onFailure { Timber.w(it, "Gas-Alarm: keine Daten") }
            .getOrNull()
        if (report == null) {
            // Fehlschlag sperrt nicht die vollen 10 Minuten: nächster Versuch frühestens in 2 Minuten
            prefs.edit { putLong(KEY_LAST_CHECK, now - MIN_INTERVAL_MILLIS + RETRY_AFTER_FAILURE_MILLIS) }
            return@withLock
        }
        // Käme eine Meldung jetzt an (Erlaubnis, Mitteilungen an, Kanal nicht stumm)? Nachtruhe: lautlos wie bisher
        val deliverable = notifier.canShowMarketNotice()

        report.evm.firstOrNull { it.network == GasNetwork.ETHEREUM }?.let { eth ->
            check(KEY_ETH_ARMED, KEY_ETH_THRESHOLD, eth.normalGwei, ethThreshold, deliverable) {
                notifier.showGas(
                    AppNotifier.GAS_ETH_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_eth_title, GasFees.formatGwei(eth.normalGwei)),
                    context.getString(R.string.notification_gas_eth_text, GasFees.formatGwei(ethThreshold),
                        eth.transferUsd?.let(GasFees::formatUsd) ?: "–"),
                    iconRes = R.drawable.ic_stat_gas_eth,
                )
            }
        }
        report.btc?.let { btc ->
            check(KEY_BTC_ARMED, KEY_BTC_THRESHOLD, btc.normal, btcThreshold, deliverable) {
                notifier.showGas(
                    AppNotifier.GAS_BTC_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_btc_title, GasFees.formatGwei(btc.normal)),
                    context.getString(R.string.notification_gas_btc_text, GasFees.formatGwei(btcThreshold),
                        btc.transferUsd?.let(GasFees::formatUsd) ?: "–"),
                    iconRes = R.drawable.ic_stat_fee_btc,
                )
            }
        }
    }

    private inline fun check(
        key: String, thresholdKey: String, value: Double, threshold: Double, deliverable: Boolean, notify: () -> Boolean,
    ) {
        val stored = if (prefs.contains(thresholdKey)) Double.fromBits(prefs.getLong(thresholdKey, 0)) else null
        val before = GasAlertLogic.armedFor(prefs.getBoolean(key, true), stored, threshold)
        val (fire, next) = GasAlertLogic.evaluate(value, threshold, before, deliverable)
        val posted = fire && notify()
        prefs.edit {
            putBoolean(key, GasAlertLogic.armedAfter(fire, posted, before, next))
            putThreshold(thresholdKey, threshold)
        }
    }

    private fun android.content.SharedPreferences.Editor.putThreshold(key: String, value: Double) {
        putLong(key, value.toRawBits())
    }

    internal companion object {
        /**
         * Sperre für Prüfung und Zurücksetzen ([com.cryptochecker.app.settings.MarketAlertReset.gasOff]): Eine laufende
         * Prüfung schreibt am Ende die Grenze, mit der sie begann — ein «Aus» während des Abrufs ginge sonst verloren,
         * und das Wiedereinschalten mit derselben Grenze bliebe stumm. Prozessweit (die Klasse ist ohnehin einmalig).
         */
        val lock: Mutex get() = GasAlertPrefs.lock

        /**
         * Fällig, wenn seit [lastCheck] mindestens [MIN_INTERVAL_MILLIS] vergangen sind. Liegt
         * [lastCheck] in der Zukunft (Uhr war vorgestellt und wurde zurückgestellt), gilt die Prüfung
         * ebenfalls als fällig — sonst bliebe sie bis zu jenem Zeitpunkt stumm, womöglich monatelang.
         */
        fun isDue(now: Long, lastCheck: Long): Boolean = now - lastCheck !in 0 until MIN_INTERVAL_MILLIS

        const val PREFS = GasAlertPrefs.PREFS
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_ETH_ARMED = "eth_armed"
        const val KEY_BTC_ARMED = "btc_armed"
        /** Grenze, zu der der gemerkte Zustand gehört (Double als Bits). */
        const val KEY_ETH_THRESHOLD = GasAlertPrefs.KEY_ETH_THRESHOLD
        const val KEY_BTC_THRESHOLD = GasAlertPrefs.KEY_BTC_THRESHOLD
        const val MIN_INTERVAL_MILLIS = 10 * 60_000L
        /** Nach einem fehlgeschlagenen Abruf: so lange bis zum nächsten Versuch (statt der vollen Pause). */
        const val RETRY_AFTER_FAILURE_MILLIS = 2 * 60_000L
    }
}
