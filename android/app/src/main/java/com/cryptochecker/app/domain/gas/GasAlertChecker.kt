package com.cryptochecker.app.domain.gas

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.GasDataSource
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
 */
object GasAlertLogic {
    const val REARM_FACTOR = 1.25

    /** @return (melden?, scharf danach?) */
    fun evaluate(value: Double, threshold: Double, armed: Boolean): Pair<Boolean, Boolean> = when {
        threshold <= 0 -> false to true
        armed && value <= threshold -> true to false
        !armed && value > threshold * REARM_FACTOR -> false to true
        else -> false to armed
    }
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
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < MIN_INTERVAL_MILLIS) return@withLock
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        val report = runCatching { gasDataSource.fetch() }
            .onFailure { Timber.w(it, "Gas-Alarm: keine Daten") }
            .getOrNull() ?: return@withLock

        report.evm.firstOrNull { it.network == GasNetwork.ETHEREUM }?.let { eth ->
            check(KEY_ETH_ARMED, eth.normalGwei, ethThreshold) {
                notifier.showGas(
                    AppNotifier.GAS_ETH_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_eth_title, GasFees.formatGwei(eth.normalGwei)),
                    context.getString(R.string.notification_gas_eth_text, GasFees.formatGwei(ethThreshold),
                        eth.transferUsd?.let(GasFees::formatUsd) ?: "–")
                )
            }
        }
        report.btc?.let { btc ->
            check(KEY_BTC_ARMED, btc.normal, btcThreshold) {
                notifier.showGas(
                    AppNotifier.GAS_BTC_NOTIFICATION_ID,
                    context.getString(R.string.notification_gas_btc_title, GasFees.formatGwei(btc.normal)),
                    context.getString(R.string.notification_gas_btc_text, GasFees.formatGwei(btcThreshold),
                        btc.transferUsd?.let(GasFees::formatUsd) ?: "–")
                )
            }
        }
    }

    private inline fun check(key: String, value: Double, threshold: Double, notify: () -> Unit) {
        val (fire, armed) = GasAlertLogic.evaluate(value, threshold, prefs.getBoolean(key, true))
        if (fire) notify()
        prefs.edit { putBoolean(key, armed) }
    }

    private companion object {
        const val PREFS = "gas_alerts"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_ETH_ARMED = "eth_armed"
        const val KEY_BTC_ARMED = "btc_armed"
        const val MIN_INTERVAL_MILLIS = 10 * 60_000L
    }
}
