package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Worin die Suche über alle Börsen sucht (nur gespeicherte Listen) und was dafür gerade läuft: Paarlisten je Börse,
 * Namensverzeichnis, Stand der Listen und die laufenden Aufträge. Zustand von [ExplorerViewModel], dort einmal angelegt;
 * gelesen und geschrieben von den Aktionen in `ExplorerSearchActions.kt`.
 */
internal class ExplorerSearchData {
    val pairsByExchange = ConcurrentHashMap<String, ExchangePairsInfo>()
    var searchableExchanges: List<ExchangeInfo> = emptyList()

    /** Börsen, deren Liste noch nicht auf dem Gerät liegt, die sie aber laden können. */
    var unloadedExchanges: List<ExchangeInfo> = emptyList()
    var storedListsJob: Job? = null
    var missingListsJob: Job? = null
    var searchJob: Job? = null

    /** Stand der Paarlisten für die Suche (fehlende, Laden). */
    val lists = MutableStateFlow(SearchLists())

    /** Gefaltete Coin-Namen («bitcoin» → BTC), einmal je Namensliste gebaut. */
    @Volatile var names: NameIndex = NameIndex.EMPTY
    var namesJob: Job? = null
}
