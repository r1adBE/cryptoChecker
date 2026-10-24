package com.cryptochecker.app.data

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.watch.WatchIdentity
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.MarketPair

/**
 * Merkliste im Speicher für Verhaltenstests der Schreibwege (Room läuft nur auf dem Gerät): gleiche Paar-Identität wie
 * `WatchDao.findWatchId` ([WatchIdentity], Vertragsart angeglichen) und dieselben Regeln wie das Repository —
 * [addBlocker] beim Anlegen, [restoreBlocker] beim Zurückholen. Ids steigen und werden nie neu vergeben.
 */
internal class FakeWatchStore(filler: Int = 0) {

    /** Id → Schlüssel des Paars, in Einfügereihenfolge. */
    val entries = LinkedHashMap<Long, WatchIdentity.Key>()

    /** Jeder Schreibvorgang (Anlegen, Löschen, Zurückholen) in Reihenfolge — leer = nichts geschrieben. */
    val writes = mutableListOf<String>()

    private var nextId = 1L

    init {
        // Schon belegte Plätze (z. B. fast volle Merkliste), ohne als Schreibvorgang des Tests zu zählen
        repeat(filler) { entries[nextId++] = key(FILLER, MarketPair("F$it", "USDT", "F${it}USDT")) }
    }

    val count: Int get() = entries.size

    fun contains(market: MarketInfo, pair: MarketPair): Boolean = key(market, pair) in entries.values

    /** Wie [tryAddWatch] nach der Sperre: Dublette vor «voll», sonst neue Id. */
    fun add(market: MarketInfo, pair: MarketPair): AddWatchOutcome {
        val key = key(market, pair)
        addBlocker(key in entries.values) { entries.size }?.let { return it }
        val id = nextId++
        entries[id] = key
        writes += "add $id"
        return AddWatchOutcome.Added(id)
    }

    fun delete(ids: Collection<Long>) {
        for (id in ids) if (entries.remove(id) != null) writes += "delete $id"
    }

    /** Wie [restore] nach der Sperre: gleiche Id; vorhanden (Id oder Paar) geht vor «voll». */
    fun restore(id: Long, key: WatchIdentity.Key): RestoreOutcome {
        restoreBlocker(id in entries || key in entries.values) { entries.size }?.let { return it }
        entries[id] = key
        writes += "restore $id"
        return RestoreOutcome.RESTORED
    }

    companion object {
        val BINANCE = MarketInfo("Binance", "Binance")
        private val FILLER = MarketInfo("Filler", "Filler")

        fun pair(base: String, quote: String = "USDT") = MarketPair(base, quote, "$base$quote")

        fun key(market: MarketInfo, pair: MarketPair): WatchIdentity.Key = WatchIdentity.key(
            market.key, pair.base, pair.quote,
            FuturesContractType.normalized(market.key, pair.pairId, pair.contractType).name, pair.pairId,
        )
    }
}
