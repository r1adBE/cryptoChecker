package com.cryptochecker.app.alarm
import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.alarm.StableDepeg
import com.cryptochecker.app.domain.alarm.StableDepegStore
import com.cryptochecker.app.domain.remote.BlockedSources
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_STABLE_EXCHANGE_REQUEST_BASE
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_STABLE_NOTIFICATION_BASE_ID
import com.cryptochecker.app.notification.showDepeg
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.data.settings.DepegAlarmPrefs
import com.cryptochecker.app.domain.util.LocaleNumbers
import com.cryptochecker.app.domain.util.PercentText
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Depeg-Alarm für weitere Stablecoins ([StableDepeg]), von [DepegAlarmChecker] nach der USDT-Messung aufgerufen — nur
 * mit eingeschaltetem Alarm, unter derselben Sperre und im selben Takt (höchstens alle 5 Minuten). USDC immer (Kraken
 * USDC/USD, dazu Binance USDC/USDT aus der USDT-Messung ohne eigene Anfrage); die übrigen nur, wenn sie in Merkliste oder
 * Portfolio stehen, über Paare der gespeicherten Paarlisten von Kraken, Coinbase und Binance (keine Paarliste geladen).
 * Pausierte Börsen werden nicht abgefragt. Wie `StableDepegCheck` (iOS).
 */
@Singleton
class StableDepegChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exchangeRepository: ExchangeRepository,
    private val watchRepository: WatchRepository,
    private val refreshStats: RefreshStats,
    private val notifier: AppNotifier,
    private val portfolioRepository: PortfolioRepository,
    private val store: StableDepegStore,
) {
    private val prefs by lazy { context.getSharedPreferences(DepegAlarmPrefs.PREFS, Context.MODE_PRIVATE) }

    /**
     * @param usdtUsd USDT/USD dieses Laufs aus direkten Quellen (null = nur über USDC/USDT gemessen)
     * @param binanceUsdcUsdt Binance USDC/USDT dieses Laufs (null = fehlt)
     * @param mayFire Meldung jetzt erlaubt und zustellbar ([QuietHours.mayFire])
     */
    suspend fun check(settings: AppSettings, usdtUsd: Double?, binanceUsdcUsdt: Double?, mayFire: Boolean, now: Long) {
        val watches = runCatchingCancellable { watchRepository.getWatches().filterNot { it.disabled } }.getOrDefault(emptyList())
        val holdings = if (settings.portfolioEnabled) {
            runCatchingCancellable { portfolioRepository.holdingCoins() }.getOrDefault(emptyList())
        } else emptyList()
        val watched = StableDepeg.watched(watches.map { it.baseAsset }, holdings)
        val backoff = refreshStats.backoffStates()
        fun available(key: String) = !ExchangeBackoff.isPaused(backoff[key], now) && BlockedSources.groupPausedUntil(key) == null

        val measured = LinkedHashMap<String, StableDepeg.Measurement>()
        val kraken = if (available(DepegAlarm.KRAKEN_KEY)) {
            last(DepegAlarm.KRAKEN_KEY, ExchangePair(StableDepeg.USDC, "USD", StableDepeg.KRAKEN_USDC_PAIR_ID), now)
        } else null
        val cross = StableDepeg.toUsd(binanceUsdcUsdt, "USDT", usdtUsd, null)
        StableDepeg.measure(listOf(StableDepeg.Source(kraken, cross = false), StableDepeg.Source(cross, cross = true)), requireTwo = false)
            ?.let { measured[StableDepeg.USDC] = it }
        val usdcUsd = measured[StableDepeg.USDC]?.takeIf { it.alarmable }?.price
        coroutineScope {
            watched.filter { it != StableDepeg.USDC }.map { coin ->
                async { coin to measureOther(coin, StableDepeg.EXCHANGES.filter(::available), usdtUsd, usdcUsd, now) }
            }.awaitAll()
        }.forEach { (coin, m) -> m?.let { measured[coin] = it } }
        store.save(watched, measured.mapValues { it.value.price }, now)

        val tenths = DepegAlarm.threshold(settings.depegThresholdTenths)
        val keys = watches.map { it.exchangeKey }
        // Je Stablecoin eigener Zustand; nicht alarmfähig (Kreuzkurs allein, zu wenige Quellen): Zustand bleibt
        measured.filterValues { it.alarmable }.forEach { (coin, m) -> evaluate(coin, m.price, tenths, mayFire, keys) }
    }

    /** Wie die USDT-Prüfung: Hysterese und Schwelle ([DepegAlarm.evaluate]), gemerkt je Stablecoin. */
    private suspend fun evaluate(coin: String, price: Double, tenths: Int, mayFire: Boolean, keys: List<String>) {
        val armedKey = DepegAlarmPrefs.STABLE_ARMED_PREFIX + coin
        val tenthsKey = DepegAlarmPrefs.STABLE_ARMED_TENTHS_PREFIX + coin
        val stored = if (prefs.contains(tenthsKey)) prefs.getInt(tenthsKey, 0) else null
        val armedBefore = DepegAlarm.armedFor(prefs.getBoolean(armedKey, true), stored, tenths)
        val deviation = DepegAlarm.deviationPercent(price)
        val decision = DepegAlarm.evaluate(deviation, DepegAlarm.thresholdPercent(tenths), armedBefore, mayFire)
        val posted = decision.fire && notify(coin, price, deviation, keys)
        prefs.edit {
            putBoolean(armedKey, DepegAlarm.armedAfter(decision.fire, posted, armedBefore, decision.armed))
            putInt(tenthsKey, tenths)
        }
    }

    /** Meldung mit dem Kürzel; Knopf «Börse öffnen» (Seite USDC/USDT) nur für USDC. */
    private suspend fun notify(coin: String, price: Double, deviation: Double, keys: List<String>): Boolean {
        val index = StableDepeg.notificationIndex(coin).coerceAtLeast(0)
        val sign = if (deviation < 0) "−" else "+"
        val title = context.getString(
            R.string.notification_depeg_stable_title, coin, LocaleNumbers.decimal(price, 4), sign + PercentText.unsigned(deviation, 2)
        )
        val url = if (coin == StableDepeg.USDC) DepegAlarm.exchangeUrl(keys) else null
        return notifier.showDepeg(
            title, context.getString(R.string.notification_depeg_stable_text, coin), url,
            notificationId = DEPEG_STABLE_NOTIFICATION_BASE_ID + index, requestCode = DEPEG_STABLE_EXCHANGE_REQUEST_BASE + index,
        )
    }

    /** Ein weiterer Stablecoin: je Börse höchstens ein Paar aus der gespeicherten Paarliste, parallel abgefragt. */
    private suspend fun measureOther(
        coin: String, exchanges: List<String>, usdtUsd: Double?, usdcUsd: Double?, now: Long,
    ): StableDepeg.Measurement? = coroutineScope {
        val sources = exchanges.mapNotNull { key -> pairFor(coin, key)?.let { key to it } }.map { (key, pair) ->
            async {
                val usd = StableDepeg.toUsd(last(key, pair, now), pair.quote, usdtUsd, usdcUsd)
                StableDepeg.Source(usd, cross = !pair.quote.equals("USD", ignoreCase = true))
            }
        }.awaitAll()
        StableDepeg.measure(sources, StableDepeg.requiresTwo(coin))
    }

    /** Spot-Paar aus der gespeicherten (bzw. eingebauten) Paarliste der Börse — lädt keine Liste. Fehler still (null). */
    private suspend fun pairFor(coin: String, exchangeKey: String): ExchangePair? {
        val pairs = runCatchingCancellable {
            exchangeRepository.getExchangeCurrencyPairsInfo(ExchangeInfo(exchangeKey, exchangeKey)).pairs
                .filter { it.contractType == FuturesContractType.NONE }
        }.getOrDefault(emptyList())
        val pick = StableDepeg.pickPair(coin, pairs.map { PortfolioExchangeRoute.Pair3(it.base, it.quote, it.pairId) }) ?: return null
        return pairs.firstOrNull { it.base == pick.base && it.quote == pick.quote && it.pairId == pick.pairId }
    }

    /** Letzter Kurs eines Paars; Fehler oder laut Börse älter als 2 Std. ([DepegAlarm.usablePrice]): null. */
    private suspend fun last(exchangeKey: String, pair: ExchangePair, now: Long): Double? =
        runCatching { exchangeRepository.getExchangeTicker(ExchangeInfo(exchangeKey, exchangeKey), pair) }.fold(
            onSuccess = { if (it.error == null) DepegAlarm.usablePrice(it.ticker.last, it.ticker.exchangeTime, now) else null },
            onFailure = {
                currentCoroutineContext().ensureActive()
                Timber.d(it, "Depeg: %s nicht erreichbar", exchangeKey)
                null
            }
        )
}
