// Live-Stand der Merkliste (Teil von `WatchlistViewModel`): Aktualisierung, Netz, Live-Strom, Faktoren der
// «≈ Umrechnung» und fürs Sortieren nach Kurs sowie der Stempel der Änderungen, gebündelt zu [WatchlistLive].
// Eigene Klasse, weil das Nachladen der Umrechnungsfaktoren eine eigene Aufgabe ist; das ViewModel besitzt
// sie und gibt [scope] (seinen viewModelScope) mit.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.settings.WatchlistSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

internal class WatchlistLiveFlows(
    scope: CoroutineScope,
    watchList: SharedFlow<List<WatchEntity>>,
    columnSort: StateFlow<ColumnSort?>,
    watchlistSettings: Flow<WatchlistSettings>,
    private val currencyConverter: CurrencyConverter,
    private val refresh: WatchlistRefreshController,
    /** Gerät hat Internet? Ohne: ruhige Statuszeile «Offline · Stand …», keine Aktualisierung. */
    private val online: StateFlow<Boolean>,
    /** Börsen, deren Live-Strom gerade Kurse liefert («LIVE» in der Status-Pille, Bericht). */
    private val liveExchanges: StateFlow<List<String>>,
    private val liveWatchIds: StateFlow<Set<Long>>,
) {
    /** Quote-Währungen der Merkliste (Grossbuchstaben), für die Umrechnungsfaktoren unten. */
    private val quoteCurrencies = watchList
        .map { list -> list.map { CurrencyConversion.normalize(it.quoteAsset) }.filter { it.isNotEmpty() }.toSet() }
        .distinctUntilChanged()

    /**
     * Faktor je Quote-Währung in CHF, nur fürs Sortieren nach Kurs — im Hintergrund, auch ohne
     * eingeschaltete «≈ Umrechnung». Geholt nur, solange nach Kurs sortiert wird (sonst keine
     * Abfrage); danach höchstens alle 60 s neu.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sortRates: StateFlow<Map<String, Double>> = combine(
        columnSort.map { it?.key == SortKey.PRICE }.distinctUntilChanged(),
        quoteCurrencies,
    ) { byPrice, quotes -> byPrice to quotes }
        .transformLatest { (byPrice, quotes) ->
            val wanted = quotes.filterNot { CurrencyConversion.sameCurrency(it, SORT_CURRENCY) }
            if (!byPrice || wanted.isEmpty()) {
                emit(emptyMap())
                return@transformLatest
            }
            var known = currencyConverter.cachedRates(wanted, SORT_CURRENCY)
            emit(known)
            while (true) {
                val fresh = runCatching { currencyConverter.rates(wanted, SORT_CURRENCY) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyMap())
                known = known + fresh
                emit(known)
                delay(CONVERT_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** «≈ Umrechnung» eingeschaltet und Zielwährung — ändert sich nur mit den Einstellungen. */
    private val conversionSetting = watchlistSettings
        .map { it.showConverted to it.portfolioCurrency }
        .distinctUntilChanged()

    private val convertFxTimesState = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Je Quote-Währung (Grossbuchstaben) die Abrufzeit des Devisenkurses im Faktor von [convertRates],
     * nur wenn älter als 3 h ([CurrencyConversion.FX_STALE_MILLIS]) — die Zeile zeigt dann «· Kurs 08:15».
     * Aufgefrischt mit [convertRates].
     */
    private val convertFxTimes: StateFlow<Map<String, Long>> = convertFxTimesState.asStateFlow()

    /**
     * Faktor je Quote-Währung (Grossbuchstaben) in die Zielwährung, z. B.
     * «USDT» → 0.8. Zuerst aus den Zwischenspeichern, dann frisch; danach
     * höchstens alle 60 s neu — ausser die Menge der Quote-Währungen ändert sich.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val convertRates: StateFlow<Map<String, Double>> = combine(
        conversionSetting,
        quoteCurrencies,
    ) { setting, quotes -> setting to quotes }
        .transformLatest { (setting, quotes) ->
            val (show, target) = setting
            val wanted = quotes.filterNot { CurrencyConversion.sameCurrency(it, target) }
            if (!show || wanted.isEmpty()) {
                convertFxTimesState.value = emptyMap()
                emit(emptyMap())
                return@transformLatest
            }
            var known = currencyConverter.cachedRates(wanted, target)
            convertFxTimesState.value = currencyConverter.staleFxTimes(wanted, target)
            emit(known)
            while (true) {
                val fresh = runCatching { currencyConverter.rates(wanted, target) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrDefault(emptyMap())
                // Fehlt ein Kurs diesmal, bleibt der letzte bekannte stehen
                known = known + fresh
                // Alter der Devisenkurse nach dem Auffrischen (älter als 3 h: Uhrzeit in der Zeile)
                convertFxTimesState.value = currencyConverter.staleFxTimes(wanted, target)
                emit(known)
                delay(CONVERT_REFRESH_MILLIS)
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Aktualisierung, Netz, Live-Strom, Umrechnung und Stempel der Änderungen in einem Stand. */
    val live: StateFlow<WatchlistLive> = combine(
        combine(refresh.refreshing, online, liveExchanges, liveWatchIds) { refreshing, online, exchanges, ids ->
            WatchlistLive(
                refreshing = refreshing,
                online = online,
                liveExchanges = exchanges,
                liveWatchIds = ids,
                convertRates = emptyMap(),
                convertFxTimes = emptyMap(),
                sortRates = emptyMap(),
                changeStamp = null,
            )
        },
        convertRates,
        convertFxTimes,
        sortRates,
        refresh.changeStamp,
    ) { base, rates, fxTimes, sorting, stamp ->
        base.copy(convertRates = rates, convertFxTimes = fxTimes, sortRates = sorting, changeStamp = stamp)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentLive())

    private fun currentLive() = WatchlistLive(
        refreshing = refresh.refreshing.value,
        online = online.value,
        liveExchanges = liveExchanges.value,
        liveWatchIds = liveWatchIds.value,
        convertRates = convertRates.value,
        convertFxTimes = convertFxTimes.value,
        sortRates = sortRates.value,
        changeStamp = refresh.changeStamp.value,
    )
}

/** Umrechnungsfaktoren der Merkliste höchstens so oft neu holen. */
private const val CONVERT_REFRESH_MILLIS = 60_000L
