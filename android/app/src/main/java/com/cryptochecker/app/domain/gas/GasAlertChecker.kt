package com.cryptochecker.app.domain.gas

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
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun checkIfDue() = mutex.withLock {
        val settings = settingsRepository.current()
        val ethThreshold = settings.gasAlertEthTenths / 10.0
        val btcThreshold = settings.gasAlertBtc.toDouble()
        if (ethThreshold <= 0 && btcThreshold <= 0) return@withLock
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
            check(KEY_ETH_ARMED, eth.normalGwei, ethThreshold, deliverable) {
                notifier.showGas(
                    AppNotifier.GAS_ETH_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_eth_title, GasFees.formatGwei(eth.normalGwei)),
                    context.getString(R.string.notification_gas_eth_text, GasFees.formatGwei(ethThreshold),
                        eth.transferUsd?.let(GasFees::formatUsd) ?: "–")
                )
            }
        }
        report.btc?.let { btc ->
            check(KEY_BTC_ARMED, btc.normal, btcThreshold, deliverable) {
                notifier.showGas(
                    AppNotifier.GAS_BTC_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_btc_title, GasFees.formatGwei(btc.normal)),
                    context.getString(R.string.notification_gas_btc_text, GasFees.formatGwei(btcThreshold),
                        btc.transferUsd?.let(GasFees::formatUsd) ?: "–")
                )
            }
        }
    }

    private inline fun check(key: String, value: Double, threshold: Double, deliverable: Boolean, notify: () -> Boolean) {
        val before = prefs.getBoolean(key, true)
        val (fire, next) = GasAlertLogic.evaluate(value, threshold, before, deliverable)
        val posted = fire && notify()
        prefs.edit { putBoolean(key, GasAlertLogic.armedAfter(fire, posted, before, next)) }
    }

    internal companion object {
        /**
         * Fällig, wenn seit [lastCheck] mindestens [MIN_INTERVAL_MILLIS] vergangen sind. Liegt
         * [lastCheck] in der Zukunft (Uhr war vorgestellt und wurde zurückgestellt), gilt die Prüfung
         * ebenfalls als fällig — sonst bliebe sie bis zu jenem Zeitpunkt stumm, womöglich monatelang.
         */
        fun isDue(now: Long, lastCheck: Long): Boolean = now - lastCheck !in 0 until MIN_INTERVAL_MILLIS

        const val PREFS = "gas_alerts"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_ETH_ARMED = "eth_armed"
        const val KEY_BTC_ARMED = "btc_armed"
        const val MIN_INTERVAL_MILLIS = 10 * 60_000L
        /** Nach einem fehlgeschlagenen Abruf: so lange bis zum nächsten Versuch (statt der vollen Pause). */
        const val RETRY_AFTER_FAILURE_MILLIS = 2 * 60_000L
    }
}
