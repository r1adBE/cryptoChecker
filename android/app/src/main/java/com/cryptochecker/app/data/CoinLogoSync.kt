package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.ExchangeLocalDataSource
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.util.appScope
import com.cryptochecker.app.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startet den Abgleich aller Coin-Logos ([CoinLogoRepository.startSync]), sofern Logos irgendwo
 * gebraucht werden ([CoinLogoUse.needed]) — beim Öffnen der App und beim Einschalten. Mit
 * «Namen anzeigen» allein nur die Liste mit den Namen, ohne Bilder. Kamen neue Logos
 * dazu und sind sie für Widgets an, werden die Widgets neu gezeichnet.
 *
 * Dazu hält es fest, welche Paare der Merkliste TradFi sind ([CoinLogoRepository.setTradFiPairs]):
 * aus dem Kennzeichen der gespeicherten Paarliste der Börse. Fehlt die Liste (z. B. nach dem
 * Update, das die Futures-Listen leerte), wird sie einmal geladen — die ganze öffentliche Liste,
 * nie ein einzelnes Paar.
 *
 * Scheiterte der Abgleich am fehlenden Netz (z. B. kein Internet in den ersten Sekunden nach der
 * Installation), wird er nachgeholt, sobald wieder Netz da ist ([CoinLogoRetry.syncNeeded]) — und
 * ohnehin beim nächsten Öffnen der App — mit wachsendem Abstand ([CoinLogoRetry.retryDelayMillis]).
 */
@Singleton
class CoinLogoSync @Inject constructor(
    private val repository: CoinLogoRepository,
    private val settingsRepository: SettingsRepository,
    private val widgetUpdater: WidgetUpdater,
    private val watchRepository: WatchRepository,
    private val exchangeRepository: ExchangeRepository,
    private val exchangeLocalDataSource: ExchangeLocalDataSource,
    private val connectivity: ConnectivityMonitor,
) {
    private val scope = appScope(Dispatchers.IO, "CoinLogoSync")
    private val lock = Any()
    private var tradFiJob: Job? = null
    private var retryJob: Job? = null
    /** Börsen, deren Liste in diesem Lauf schon geladen wurde (nicht bei jeder Änderung erneut). */
    private val fetched = HashSet<String>()

    suspend fun startIfEnabled() {
        // Gespeicherte Alpha-Token (Binance-Alpha-Paare, Namen für «α») gleich beim Start, ohne Netz
        try {
            repository.loadAlphaTokens()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Alpha-Token nicht gelesen")
        }
        val settings = settingsRepository.current()
        val logos = CoinLogoUse.needed(settings)
        // «Namen anzeigen» braucht nur die Liste (Namen stehen darin), keine Bilder
        if (!logos && !settings.watchlistNames) {
            // Nur «inkl. Alpha-Tokens»: allein die Alpha-Liste (ohne Rangliste und Bilder), sonst nichts
            if (CoinLogos.alphaOnly(logos, settings.watchlistNames, settings.includeAlphaTokens)) scope.launch {
                try {
                    repository.refreshAlphaOnly()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Alpha-Liste nicht geladen")
                }
            }
            return
        }
        watchTradFi()
        watchConnectivity()
        // «Namen anzeigen» gerade eingeschaltet: Aktiennamen holen, falls es TradFi-Kürzel gibt
        if (settings.watchlistNames) scope.launch {
            try {
                repository.refreshStockNames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Aktiennamen nicht geladen")
            }
        }
        // Zuerst Binance- und Alpha-Liste (Namen, «α»), dann Rangliste und Bilder
        repository.startSync(images = logos) { added ->
            if (added > 0 && settingsRepository.current().widgetCoinLogos) widgetUpdater.updateAll()
        }
    }

    /**
     * Holt einen am Netz gescheiterten Abgleich nach (läuft einmal je App-Prozess): sobald Netz da
     * ist — beim Wechsel auf «online» und auch, wenn der Abgleich bei scheinbar vorhandenem Netz
     * scheiterte (z. B. Netz direkt nach der Installation noch nicht nutzbar). Zwischen den
     * Versuchen wächst der Abstand ([CoinLogoRetry.retryDelayMillis], 5 s … 15 Min.), auch über
     * Netzwechsel hinweg — keine Flut von Abrufen. Ein erfolgreicher Abgleich beendet das Nachholen;
     * neue Logos zeichnen die Zeilen ([CoinLogoRepository.revision]) und Widgets neu.
     */
    private fun watchConnectivity() {
        synchronized(lock) {
            if (retryJob?.isActive == true) return
            retryJob = scope.launch {
                combine(connectivity.online, repository.retryPending) { online, pending ->
                    CoinLogoRetry.syncNeeded(pending, online)
                }
                    .distinctUntilChanged()
                    .catch { e -> Timber.w(e, "Coin-Logos: Netzbeobachtung beendet") }
                    .collectLatest { needed -> if (needed) retryUntilDone() }
            }
        }
    }

    /** Nachholen mit wachsendem Abstand, bis der Abgleich klappt, das Netz fehlt oder die Schalter aus sind. */
    private suspend fun retryUntilDone() {
        while (true) {
            // Läuft schon einer (z. B. vom Öffnen der App): sein Ergebnis abwarten
            while (repository.isSyncing) delay(SYNC_POLL_MILLIS)
            val wait = CoinLogoRetry.retryDelayMillis(
                repository.networkFailures, repository.lastNetworkFailureAt, System.currentTimeMillis()
            )
            if (wait > 0) delay(wait)
            if (repository.isSyncing) continue
            if (!CoinLogoRetry.syncNeeded(repository.retryPending.value, connectivity.isOnline())) return
            // Schalter für Logos und Namen aus: nichts nachzuholen
            val settings = settingsRepository.current()
            if (!CoinLogoUse.needed(settings) && !settings.watchlistNames) return
            try {
                Timber.i("Coin-Logos: Netz da, Abgleich wird nachgeholt (Fehlschläge bisher: %d)", repository.networkFailures)
                startIfEnabled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Coin-Logos: Nachholen fehlgeschlagen")
            }
        }
    }

    /** Folgt der Merkliste und hält die TradFi-Paare aktuell (läuft einmal je App-Prozess). */
    private fun watchTradFi() {
        synchronized(lock) {
            if (tradFiJob?.isActive == true) return
            tradFiJob = scope.launch {
                watchRepository.observeWatches()
                    .map { watches ->
                        watches.filter { it.exchangeKey in CoinLogos.TRADFI_EXCHANGES }
                            .map { Triple(it.exchangeKey, it.baseAsset to it.quoteAsset, it.contractType.name) }
                            .toSet()
                    }
                    .distinctUntilChanged()
                    // Merkliste nicht lesbar (z. B. Datenbank-Schlüssel) beendet nur diese Beobachtung
                    .catch { e -> Timber.w(e, "TradFi: Merkliste nicht gelesen") }
                    .collectLatest { pairs ->
                        try {
                            val (found, bases) = tradFiPairs(pairs)
                            if (repository.setTradFiPairs(found) && settingsRepository.current().widgetCoinLogos) {
                                widgetUpdater.updateAll()
                            }
                            // Neue TradFi-Kürzel (andere Börse in der Merkliste): Aktien-Logos nachladen
                            val basesChanged = repository.setTradFiBases(bases)
                            // Aktiennamen nur mit «Namen anzeigen» (Nasdaq-Symbolliste, höchstens wöchentlich)
                            if (settingsRepository.current().watchlistNames) repository.refreshStockNames()
                            if (basesChanged && CoinLogoUse.needed(settingsRepository.current())) {
                                repository.startSync(images = true) { added ->
                                    if (added > 0 && settingsRepository.current().widgetCoinLogos) widgetUpdater.updateAll()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "TradFi-Paare nicht bestimmt")
                        }
                    }
            }
        }
    }

    /**
     * TradFi-Paare der Merkliste und alle TradFi-Kürzel der gespeicherten Listen dieser Börsen
     * (für die Aktien-Logos — alle, nicht nur die beobachteten).
     */
    private suspend fun tradFiPairs(pairs: Set<Triple<String, Pair<String, String>, String>>): Pair<Set<String>, Set<String>> {
        val out = HashSet<String>()
        val bases = HashSet<String>()
        for ((exchangeKey, group) in pairs.groupBy { it.first }) {
            val tradFi = storedTradFi(exchangeKey) ?: continue
            tradFi.mapTo(bases) { it.split('|')[1] }
            for ((_, bq, contract) in group) {
                val key = CoinLogos.pairKey(exchangeKey, bq.first, bq.second, contract)
                if (key in tradFi) out += key
            }
        }
        return out to bases
    }

    /** TradFi-Paare der gespeicherten Liste; fehlt sie, einmal laden. null = unbekannt. */
    private suspend fun storedTradFi(exchangeKey: String): Set<String>? {
        var stored = exchangeLocalDataSource.getExchangeData(exchangeKey)
        if (stored == null || stored.lastSyncDate <= 0L) {
            val first = synchronized(lock) { fetched.add(exchangeKey) }
            if (!first) return stored?.let { tradFiOf(exchangeKey, it) }
            val exchange = exchangeRepository.getExchangeList().firstOrNull { it.key == exchangeKey } ?: return null
            try {
                exchangeRepository.updateExchangeCurrencyPairs(exchange)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Paarliste %s nicht geladen: %s", exchangeKey, e.message)
            }
            stored = exchangeLocalDataSource.getExchangeData(exchangeKey)
        }
        return stored?.let { tradFiOf(exchangeKey, it) }
    }

    private fun tradFiOf(exchangeKey: String, info: com.cryptochecker.app.domain.model.ExchangePairsInfo): Set<String> =
        info.pairs.filter { it.tradFi }
            .mapTo(HashSet()) { CoinLogos.pairKey(exchangeKey, it.base, it.quote, it.contractType.name) }

    private companion object {
        /** Abfrage, ob ein laufender Abgleich fertig ist. */
        const val SYNC_POLL_MILLIS = 1_000L
    }
}

/** Wo Logos gezeigt werden (Schalter unter Darstellung › Coin-Logos). */
object CoinLogoUse {
    /** Portfolio-Logos zählen nur mit eingeschaltetem Portfolio-Tab. */
    fun portfolio(settings: com.cryptochecker.app.settings.AppSettings): Boolean =
        settings.portfolioCoinLogos && settings.portfolioEnabled

    fun needed(settings: com.cryptochecker.app.settings.AppSettings): Boolean =
        settings.coinLogos || settings.widgetCoinLogos || portfolio(settings)
}
