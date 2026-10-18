package com.cryptochecker.app.domain.alarm

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.R
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.marketdata.model.MarketPair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Misst nach einer Kursaktualisierung (Vordergrund und Hintergrund) USDT in USD und prüft den USDT-Depeg-Alarm
 * ([DepegAlarm]): gemessen wird mit eingeschaltetem Alarm ODER sobald ein Portfolio da ist
 * ([DepegAlarm.shouldMeasure] — die Messung ist der Live-Kurs USDT/USD der Bewertung), höchstens alle
 * [MIN_INTERVAL_MILLIS], mit den bestehenden Ticker-Abrufen von Kraken (USDT/USD), Coinbase (USDT-USD) und Binance
 * (USDC/USDT, nur dritte Quelle — [DepegAlarm.measure]). Gemeldet wird nur mit eingeschaltetem Alarm und nie aus
 * USDC/USDT allein ([DepegAlarm.mayAlarm]). Pausierte Börsen (Backoff, gemeinsame Binance-Pause) werden nicht abgefragt.
 * Wie `DepegAlarmCheck` (iOS).
 */
@Singleton
class DepegAlarmChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val marketRepository: MarketRepository,
    private val watchRepository: WatchRepository,
    private val refreshStats: RefreshStats,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
    /** Letzte Messung: Live-Kurs USDT/USD der Bewertung ([DepegAlarm.liveUsdtUsd]). */
    private val readingStore: DepegReadingStore,
    /** Portfolio vorhanden? Dann wird auch ohne Alarm gemessen. */
    private val portfolioRepository: com.cryptochecker.app.data.portfolio.PortfolioRepository,
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun checkIfDue() = mutex.withLock {
        val settings = settingsRepository.current()
        // Datenbank nur fragen, wenn das Portfolio eingeschaltet ist und der Alarm allein nicht genügt
        val hasPortfolio = !settings.depegAlarm && settings.portfolioEnabled && hasPortfolioData()
        if (!DepegAlarm.shouldMeasure(settings.depegAlarm, settings.portfolioEnabled, hasPortfolio)) return@withLock
        val now = System.currentTimeMillis()
        if (!isDue(now, prefs.getLong(KEY_LAST_CHECK, 0))) return@withLock
        prefs.edit { putLong(KEY_LAST_CHECK, now) }

        val measurement = fetchUsdt(now)
        if (measurement == null) {
            // Keine Quelle: nächster Versuch frühestens in 2 Minuten
            prefs.edit { putLong(KEY_LAST_CHECK, now - MIN_INTERVAL_MILLIS + RETRY_AFTER_FAILURE_MILLIS) }
            return@withLock
        }
        // Messung merken: Live-Kurs USDT/USD der Bewertung (DepegAlarm.liveUsdtUsd)
        readingStore.save(measurement.price, now, measurement.viaUsdc)
        // Alarm aus: nur gemessen (Portfolio), nichts melden
        if (!settings.depegAlarm) return@withLock
        // Nur USDC/USDT: weicht USDT oder USDC ab? Nicht unterscheidbar — kein Alarm, Zustand bleibt
        if (!DepegAlarm.mayAlarm(measurement)) return@withLock
        val price = measurement.price
        val deviation = DepegAlarm.deviationPercent(price)
        // Wie Kursalarme: in der Nachtruhe nicht auslösen, ohne zustellbare Meldung scharf bleiben
        val hold = QuietHours.holdsAlarms(settings.quietHoursEnabled, settings.quietHoursStart, settings.quietHoursEnd, QuietHours.minuteOfDay())
        val mayFire = QuietHours.mayFire(hold, notifier.canShowDepegAlarm())
        val armedBefore = prefs.getBoolean(KEY_ARMED, true)
        val decision = DepegAlarm.evaluate(deviation, DepegAlarm.thresholdPercent(settings.depegThresholdTenths), armedBefore, mayFire)
        val posted = decision.fire && notify(price, deviation)
        prefs.edit { putBoolean(KEY_ARMED, DepegAlarm.armedAfter(decision.fire, posted, armedBefore, decision.armed)) }
    }

    /** Transaktionen oder beobachtete Adressen vorhanden? Fehler still (false). */
    private suspend fun hasPortfolioData(): Boolean = try {
        portfolioRepository.hasData()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Depeg: Portfolio nicht lesbar")
        false
    }

    private suspend fun notify(price: Double, deviation: Double): Boolean {
        val keys = runCatchingCancellable { watchRepository.getWatches().filterNot { it.disabled }.map { it.marketKey } }.getOrDefault(emptyList())
        val sign = if (deviation < 0) "−" else "+"
        val title = context.getString(
            R.string.notification_depeg_title,
            LocaleNumbers.decimal(price, 4),
            sign + LocaleNumbers.decimal(abs(deviation), 2) + " %"
        )
        return notifier.showDepeg(title, context.getString(R.string.notification_depeg_text), DepegAlarm.exchangeUrl(keys))
    }

    /** USDT in USD aus den drei Quellen (parallel, [DepegAlarm.measure]); pausierte Börsen bleiben aussen vor. */
    private suspend fun fetchUsdt(now: Long): DepegAlarm.Measurement? = coroutineScope {
        val backoff = refreshStats.backoffStates()
        fun available(key: String) = !ExchangeBackoff.isPaused(backoff[key], now) && BlockedSources.groupPausedUntil(key) == null
        val kraken = async {
            if (available(DepegAlarm.KRAKEN_KEY)) last(DepegAlarm.KRAKEN_KEY, "USDT", "USD", DepegAlarm.KRAKEN_PAIR_ID) else null
        }
        val coinbase = async {
            if (available(DepegAlarm.COINBASE_KEY)) last(DepegAlarm.COINBASE_KEY, "USDT", "USD", DepegAlarm.COINBASE_PAIR_ID) else null
        }
        val binance = async {
            if (available(DepegAlarm.BINANCE_KEY)) last(DepegAlarm.BINANCE_KEY, "USDC", "USDT", DepegAlarm.BINANCE_PAIR_ID) else null
        }
        DepegAlarm.measure(kraken.await(), coinbase.await(), binance.await())
    }

    /** Letzter Kurs eines Paars; Fehler still (null). */
    private suspend fun last(marketKey: String, base: String, quote: String, pairId: String): Double? =
        runCatching {
            marketRepository.getMarketTicker(MarketInfo(marketKey, marketKey), MarketPair(base, quote, pairId))
        }.fold(
            onSuccess = { if (it.error == null) it.ticker.last else null },
            onFailure = {
                // Zeitüberschreitung kommt als CancellationException: nur abbrechen, wenn der Lauf selbst abbricht
                currentCoroutineContext().ensureActive()
                Timber.d(it, "Depeg: %s nicht erreichbar", marketKey)
                null
            }
        )

    internal companion object {
        /**
         * Fällig, wenn seit [lastCheck] mindestens [MIN_INTERVAL_MILLIS] vergangen sind. Liegt
         * [lastCheck] in der Zukunft (Uhr war vorgestellt und wurde zurückgestellt), gilt die Prüfung
         * ebenfalls als fällig — sonst bliebe sie bis zu jenem Zeitpunkt stumm, womöglich monatelang.
         */
        fun isDue(now: Long, lastCheck: Long): Boolean = now - lastCheck !in 0 until MIN_INTERVAL_MILLIS

        const val PREFS = "depeg_alarm"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_ARMED = "armed"
        /** Höchstens alle 5 Minuten (Vordergrund kann viel öfter aktualisieren). */
        const val MIN_INTERVAL_MILLIS = 5 * 60_000L
        const val RETRY_AFTER_FAILURE_MILLIS = 2 * 60_000L
    }
}
