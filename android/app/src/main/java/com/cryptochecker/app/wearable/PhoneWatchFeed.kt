package com.cryptochecker.app.wearable

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.util.appScope
import com.cryptochecker.app.widget.PortfolioSnapshotStore
import com.cryptochecker.app.domain.watch.RetiredWatch
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchPortfolio
import com.cryptochecker.marketdata.watch.WatchSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was das Handy der Wear-OS-App ausser der Wahl schickt (über [PhoneWatchSync.put], Wear-Daten-Schicht):
 *  - Kurse ([QUOTES_PATH]): für beide Listen der Uhr die Kurse, die die Merkliste schon hat ([WatchPhoneQuotes.pick];
 *    «Wie Handy»: genau das Paar jedes Favoriten, auch Futures/Perp und andere Börsen) — keine eigene Abfrage für die Uhr; höchstens einmal je [MIN_GAP_MS] (Live-Kurse ändern sich laufend). Nicht bei
 *    «Immer selbst abrufen».
 *  - Portfolio-Wert ([PORTFOLIO_PATH]): nur mit «Portfolio-Wert auf der Uhr» (Standard aus) und nur Gesamtwert,
 *    Veränderung heute, Währung und Zeit ([WatchPortfolio]); verborgen bzw. gesperrt ohne Zahlen; Schalter oder Portfolio
 *    aus → Löschen. Neu bei jeder Aufnahme ([portfolioRecorded]) und jeder betroffenen Einstellung; keine eigene Abfrage.
 * Wie iOS `PhoneWatchLink`.
 */
@Singleton
class PhoneWatchFeed @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sync: PhoneWatchSync,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val snapshotStore: PortfolioSnapshotStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = appScope(ioDispatcher, "PhoneWatchFeed")
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val portfolioLock = Mutex()
    private val _portfolioOnWatch = MutableStateFlow(false)
    private var started = false

    /** Zuletzt gesendeter Portfolio-Stand (nur unter [portfolioLock]); gleicher Stand geht nicht noch einmal weg. */
    private var lastPortfolio: String? = null

    /** Schalter «Portfolio-Wert auf der Uhr» (Einstellungen › Portfolio). */
    val portfolioOnWatch: StateFlow<Boolean> = _portfolioOnWatch.asStateFlow()

    /** Einmal beim Start des Prozesses (nach [PhoneWatchSync.start]). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            _portfolioOnWatch.value = prefs.getBoolean(KEY_PORTFOLIO, false)
            publishPortfolio()
            settingsRepository.settings
                .map { Triple(it.portfolioEnabled, it.hidePortfolioAmounts, it.appLock) }
                .distinctUntilChanged()
                .catch { }
                .collect { publishPortfolio() }
        }
        scope.launch {
            combine(watchRepository.observeWatches(), sync.state) { watches, settings -> quotesJson(watches, settings) }
                .filterNotNull()
                .distinctUntilChanged()
                .conflate()
                .catch { }
                .collect { json ->
                    sync.put(QUOTES_PATH, json)
                    delay(MIN_GAP_MS)
                }
        }
    }

    fun setPortfolioOnWatch(on: Boolean) = scope.launch {
        prefs.edit { putBoolean(KEY_PORTFOLIO, on) }
        _portfolioOnWatch.value = on
        publishPortfolio()
    }

    /** Neue Momentaufnahme des Portfolios gespeichert (`PortfolioSnapshotUpdater.record`). */
    fun portfolioRecorded() {
        if (_portfolioOnWatch.value) scope.launch { publishPortfolio() }
    }

    private suspend fun publishPortfolio() {
        portfolioLock.withLock {
            val on = _portfolioOnWatch.value
            val s = runCatchingCancellable { settingsRepository.current() }.getOrNull() ?: return
            val snapshot = if (on && s.portfolioEnabled) snapshotStore.snapshot() else null
            val message = WatchPortfolio.message(
                onWatch = on,
                portfolioEnabled = s.portfolioEnabled,
                hidden = s.hidePortfolioAmounts,
                locked = PortfolioLockPolicy.widgetLocked(s.appLock),
                total = snapshot?.total,
                changePercent = snapshot?.changePercent,
                currency = snapshot?.currency,
                time = snapshot?.time ?: 0L,
            ) ?: return
            val json = WatchPortfolio.toJson(message)
            if (json == lastPortfolio) return
            sync.put(PORTFOLIO_PATH, json)
            lastPortfolio = json
        }
    }

    companion object {
        const val QUOTES_PATH = "/cc/watch/quotes"
        const val PORTFOLIO_PATH = "/cc/watch/portfolio"
        private const val PREFS = "watch_sync"
        private const val KEY_PORTFOLIO = "portfolio_on_watch"
        private const val MIN_GAP_MS = 60_000L

        /** Kurse für beide Listen der Uhr; null bei «Immer selbst abrufen» (dann nichts senden). */
        internal fun quotesJson(watches: List<WatchEntity>, settings: WatchSettings): String? {
            if (settings.source == WatchSettings.Source.SELF) return null
            val rows = watches.map {
                val contract = watchContract(it)
                // Börse stillgelegt (RetiredWatch: Marker oder Ende erreicht): kein alter Kurs an die Uhr, sie zeigt «—»
                // mit Grund — auch bei deaktivierten Paaren
                val retired = RetiredWatch.exchangeOf(it) != null
                WatchPhoneQuotes.Row(
                    base = it.baseAsset,
                    quote = it.quoteAsset,
                    // Spot = kein Kontrakt und kein DexScreener-Pool; die Börsen-Kennung («BTCUSDT») ändert daran nichts
                    spot = it.contractType == FuturesContractType.NONE && contract.isEmpty(),
                    disabled = it.disabled,
                    price = if (retired) null else it.lastPrice,
                    change24h = if (retired) null else it.change24h,
                    exchange = it.exchangeName,
                    time = it.lastUpdate,
                    sortOrder = it.sortOrder,
                    contract = contract,
                )
            }
            return WatchPhoneQuotes.toJson(WatchPhoneQuotes.pick(rows, settings.kept))
        }

        /**
         * Kontrakt-Marke der Uhr ([WatchPair.contractOf]): Kurzform des Kontrakts, Pool nur bei DexScreener
         * ([WatchIdentity.pool], mit der Kennung des Pools), sonst Spot. Wie iOS `PhoneWatchLink.watchContract`.
         */
        internal fun watchContract(w: WatchEntity): String =
            WatchPair.contractOf(w.contractShortName, WatchIdentity.pool(w.exchangeKey, w.pairId))
    }
}
