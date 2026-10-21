// DEX-Pools suchen und hinzufügen (Teil von `ExplorerViewModel`).
// Eigene Datei, weil DexScreener ohne Paarliste anders funktioniert als die Börsen; Erweiterungsfunktionen
// auf dem ViewModel (wie `ExplorerDexActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.market.DexPool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

// =============================================================================================
// DEX
// =============================================================================================

/** Pools zu Token-Name, Symbol oder Adresse suchen. */
fun ExplorerViewModel.searchDex(query: String) {
    if (query.isBlank()) return
    dexJob?.cancel()
    dexJob = viewModelScope.launch {
        dexBusy.value = true
        dexNotice.value = null
        val outcome = runCatchingCancellable { marketRepository.searchDexPools(query) }
        dexBusy.value = false
        val pools = outcome.getOrElse { error ->
            Timber.w(error, "DEX-Suche gescheitert")
            dexPools.value = emptyList()
            // Wie die Merkliste einordnen (kein Netz / Dienst nicht erreichbar), mit «Erneut versuchen»
            val cause = RefreshReportLogic.classify(error.toMarketFailure(), error.toString())
            dexNotice.value = DexMessage.Failed(DexFailure.of(cause), query)
            return@launch
        }
        dexPools.value = pools.distinctBy { it.pairId }.take(MAX_DEX_RESULTS)
        if (pools.isEmpty()) dexNotice.value = DexMessage.NoResults
    }
}

/** Einen Pool in die Merkliste. */
fun ExplorerViewModel.addDexPool(pool: DexPool) {
    val selected = exchange.value ?: return
    // Liquidität aus der Suche gleich merken: Alarm-Editor und Alarme richten sich bis zur ersten Abfrage danach
    dexLiquidityStore.put(pool.pairId, pool.liquidityUsd)
    // Token-Adresse fürs Paar-Blatt (gleichnamige Token unterscheiden); nicht Teil der Paar-Identität
    dexTokenAddressStore.put(pool.pairId, pool.baseAddress)
    launchAdd(selected, MarketPair(pool.baseSymbol, pool.watchQuote, pool.pairId)) { added ->
        dexNotice.value = if (added) DexMessage.Added(pool.baseSymbol) else DexMessage.AlreadyInList(pool.baseSymbol)
    }
}

fun ExplorerViewModel.clearDexMessage() {
    dexNotice.value = null
}

private const val MAX_DEX_RESULTS = 25

/** Rückmeldung der DEX-Suche. */
sealed interface DexMessage {
    data object NoResults : DexMessage
    /** Suche gescheitert: Art für den Text, [query] für «Erneut versuchen». */
    data class Failed(val kind: DexFailure, val query: String) : DexMessage
    data class Added(val symbol: String) : DexMessage
    data class AlreadyInList(val symbol: String) : DexMessage
}

internal const val DEX_KEY = "DexScreener"

/**
 * Art einer gescheiterten DEX-Suche — gleiche Texte wie die Merkliste: kein Netz (auch Zeitüberschreitung, wie
 * `friendlyError`), Dienst gerade nicht erreichbar (HTTP-Fehler, Bremse), sonst «Etwas ist schiefgelaufen».
 * Gleich wie `DexFailure` (iOS).
 */
enum class DexFailure {
    OFFLINE, UNREACHABLE, OTHER;

    companion object {
        fun of(cause: RefreshFailure): DexFailure = when (cause) {
            RefreshFailure.OFFLINE, RefreshFailure.TIMEOUT -> OFFLINE
            RefreshFailure.RATE_LIMIT, RefreshFailure.SERVER -> UNREACHABLE
            RefreshFailure.NO_DATA, RefreshFailure.UNAVAILABLE, RefreshFailure.OTHER -> OTHER
        }
    }
}
