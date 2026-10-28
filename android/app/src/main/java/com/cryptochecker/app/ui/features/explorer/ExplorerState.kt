package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

// Zustand der Seite «Paar hinzufügen» in Gruppen, damit [ExplorerViewModel] überschaubar bleibt. Gelesen und
// geschrieben wird nur über das ViewModel und seine Erweiterungen (ExplorerBulkActions, ExplorerSearchActions,
// ExplorerDexActions, ExplorerSelectionActions); die Oberfläche sieht die schreibgeschützten StateFlows.

/** «Alle …-Paare» der gewählten Börse. */
internal class ExplorerBulkState {
    /** Paare zur gewählten Gegenwährung [quote]. */
    val pairs = MutableStateFlow<List<ExchangePair>>(emptyList())

    /** true: «Alle …-Paare» angewendet, aber zur Gegenwährung gibt es keine. */
    val empty = MutableStateFlow(false)

    /** Gewählte Gegenwährung (z. B. «USDT»); null = keine. */
    val quote = MutableStateFlow<String?>(null)

    /** Stand der Aufnahme in die Merkliste; null = keine läuft und keine Rückmeldung offen. */
    val add = MutableStateFlow<BulkAddResult?>(null)

    /**
     * Kennzahlen je Paar-Kennung für die Schnellfilter — aus einer einzigen Sammelabfrage der Börse, nie je Paar.
     * Ohne Sammelabfrage bleibt die Karte leer und die Filter fehlen.
     */
    val stats = MutableStateFlow<Map<String, PairStats>>(emptyMap())

    /** Laufende Sammelabfrage für [stats]. */
    var statsJob: Job? = null

    /** Börse und Gegenwährung, für die [stats] gilt. */
    var statsFor: String? = null

    /** Zeitpunkt der letzten Sammelabfrage in ms (zu alte Kennzahlen werden neu geladen). */
    var statsLoadedAt = 0L
}

/** Treffer der Suche über alle Börsen und der darin gewählte Coin. */
internal class ExplorerSearchHits {
    /** Gefundene Paare. */
    val pairs = MutableStateFlow<List<SearchHit>>(emptyList())

    /** Gefundene Coins. */
    val coins = MutableStateFlow<List<CoinHit>>(emptyList())

    /** true: die Paar-Treffer sind aufgeklappt. */
    val pairsShown = MutableStateFlow(false)

    /** Angetippter Coin; null = keiner. */
    val chosenCoin = MutableStateFlow<CoinHit?>(null)

    /** Paare des angetippten Coins über alle Börsen. */
    val chosenCoinPairs = MutableStateFlow<List<SearchHit>>(emptyList())
}

/** DEX-Suche über DexScreener. */
internal class ExplorerDexState {
    /** Gefundene Pools. */
    val pools = MutableStateFlow<List<DexPool>>(emptyList())

    /** true: eine Suche läuft. */
    val busy = MutableStateFlow(false)

    /** Hinweis zur letzten Suche (keine Treffer, Fehler); null = keiner. */
    val notice = MutableStateFlow<DexMessage?>(null)

    /** Laufende Suche. */
    var job: Job? = null
}
