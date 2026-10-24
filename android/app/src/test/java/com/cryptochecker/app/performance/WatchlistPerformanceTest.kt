package com.cryptochecker.app.performance

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.watch.WatchPulse
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.performance.PerformanceTiming.assertScales
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import com.cryptochecker.app.ui.features.explorer.bulkNewCount
import com.cryptochecker.app.ui.features.explorer.watchKey
import com.cryptochecker.app.ui.features.watchlist.filterByGroup
import com.cryptochecker.app.ui.features.watchlist.matchesSearch
import com.cryptochecker.app.widget.WidgetGroupSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leistung der Merkliste mit bis zu 2000 Paaren (Obergrenze), reine Logik ohne Darstellung: Spaltensortierung,
 * Gruppen- und Schnellfilter, Suche, Puls, Plan der Listen-Widgets und Zählung der Sammelaufnahme. Grenzen und
 * Skalierung: [PerformanceTiming]. iOS: WatchlistPerformanceTests.swift.
 */
class WatchlistPerformanceTest {

    private val view = ChangeView()

    /** Kurs-Spalte wie `rememberColumnSorted`: Kurs über CHF vergleichbar, ohne Kurs ans Ende. */
    private fun sortValue(w: WatchEntity): Double? =
        w.lastPrice?.takeIf { it > 0.0 }?.let { it * PerformanceData.chfPerQuote(w.quoteAsset) }

    @Test
    fun columnSortByNamePriceAndChange() {
        // Erwartet einige ms für alle sechs Sortierungen; Grenze grosszügig
        assertScales("Spaltensortierung Name/Kurs/24h auf- und absteigend", limitMillis = 250.0) { n ->
            val watches = PerformanceData.watches(n)
            medianMillis {
                var checksum = 0L
                for (key in SortKey.entries) {
                    for (descending in listOf(false, true)) {
                        val sorted = ColumnSort.apply(
                            watches, ColumnSort(key, descending),
                            favorite = { it.favorite },
                            name = { it.displayName },
                            value = ::sortValue,
                            change = { it.shownChange(view) },
                        )
                        checksum += sorted.first().id
                    }
                }
                checksum
            }
        }
        // Ergebnis stimmt: Favoriten oben, darunter nach Name
        val sorted = ColumnSort.apply(
            PerformanceData.watches(PerformanceTiming.LARGE), ColumnSort(SortKey.NAME, false),
            { it.favorite }, { it.displayName }, ::sortValue, { it.shownChange(view) },
        )
        assertEquals(PerformanceTiming.LARGE, sorted.size)
        val favorites = sorted.takeWhile { it.favorite }
        assertTrue(favorites.isNotEmpty() && sorted.drop(favorites.size).none { it.favorite })
        val favoriteNames = favorites.map { it.displayName.lowercase() }
        assertEquals(favoriteNames.sorted(), favoriteNames)
    }

    @Test
    fun groupAndQuickFilters() {
        val selections = listOf(null, WatchFilter.FAVORITES, WatchFilter.TOKENS, "Layer 1", "DeFi", "Memes")
        assertScales("Gruppen- und Schnellfilter", limitMillis = 100.0) { n ->
            val watches = PerformanceData.watches(n)
            medianMillis {
                var shown = 0
                for (selection in selections) shown += filterByGroup(watches, selection, emptyMap()).size
                // Schnellansichten: nur deaktivierte, nicht gehandelte, aktive
                shown += DisabledView.only(watches) { it.disabled }.size
                shown += NotTraded.ids(watches, { it.id }, { it.lastError }).size
                shown + DisabledView.active(watches) { it.disabled }.size
            }
        }
    }

    @Test
    fun searchWithCoinNames() {
        val queries = listOf("btc", "usdt kraken", "bitcoin", "layer", "pépé eur", "xyz")
        // Jede Suche faltet je Paar acht Felder (Akzente, Kleinschreibung): erwartet um 100 ms für sechs Suchen
        assertScales("Suche (6 Anfragen, mit Coin-Namen)", limitMillis = 1_000.0) { n ->
            val watches = PerformanceData.watches(n)
            val names = watches.mapNotNull { w -> PerformanceData.COIN_NAMES[w.baseAsset]?.let { w.id to it } }.toMap()
            medianMillis {
                queries.sumOf { q -> watches.count { it.matchesSearch(q, names[it.id]) } }
            }
        }
        val watches = PerformanceData.watches(PerformanceTiming.LARGE)
        val names = watches.mapNotNull { w -> PerformanceData.COIN_NAMES[w.baseAsset]?.let { w.id to it } }.toMap()
        // «bitcoin» findet genau die BTC-Paare (20 Quote-Währungen), «xyz» nichts
        assertEquals(20, watches.count { it.matchesSearch("bitcoin", names[it.id]) })
        assertEquals(0, watches.count { it.matchesSearch("xyz", names[it.id]) })
    }

    @Test
    fun pulseAndBreadth() {
        assertScales("Puls der Merkliste und Marktbreite", limitMillis = 100.0) { n ->
            val watches = PerformanceData.watches(n)
            medianMillis {
                // Wie `rememberWatchPulse`: deaktivierte zählen nicht, nicht gehandelte nicht als «ohne 24h-Wert»
                val counted = DisabledView.active(watches) { it.disabled }
                val pulse = WatchPulse.of(
                    changes = counted.map { it.shownChange(view) },
                    hasPrice = counted.map { it.lastPrice != null && !it.isNotTraded },
                )
                pulse to CryptoPulse.breadth(counted.mapNotNull { it.change24h })
            }
        }
        val counted = DisabledView.active(PerformanceData.watches(PerformanceTiming.LARGE)) { it.disabled }
        val pulse = WatchPulse.of(counted.map { it.shownChange(view) }, counted.map { it.lastPrice != null })
        assertNotNull(pulse)
        assertTrue(pulse!!.up > 0 && pulse.down > 0 && pulse.missing > 0)
    }

    @Test
    fun listWidgetPlan() {
        // Einrichtung (wählbare Einträge) und je Eintrag die Paare des Widgets
        assertScales("Plan der Listen-Widgets (alle Gruppen)", limitMillis = 100.0) { n ->
            val watches = PerformanceData.watches(n)
            medianMillis {
                val options = WidgetGroupSelection.options(watches.map { it.groupName }, watches.map { false })
                options.sumOf { option ->
                    WidgetGroupSelection.pick(option, watches, { it.groupName }, { it.favorite }).second.size
                }
            }
        }
    }

    @Test
    fun bulkAddCountAgainstFullWatchlist() {
        // 2000 Binance-Paare der Auswahl gegen 2000 vorhandene (jedes fünfte davon an Binance)
        assertScales("Zählung der Sammelaufnahme", limitMillis = 100.0) { n ->
            val watches = PerformanceData.watches(n)
            val pairs = PerformanceData.binancePairs(n)
            medianMillis {
                // Wie `ExplorerViewModel.watchedKeys` und `bulkNew`
                val keys = watches.mapTo(LinkedHashSet()) {
                    watchKey(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name)
                }
                bulkNewCount(PerformanceData.BINANCE.first, pairs, keys)
            }
        }
        val n = PerformanceTiming.LARGE
        val keys = PerformanceData.watches(n).mapTo(HashSet()) {
            watchKey(it.marketKey, it.baseAsset, it.quoteAsset, it.contractType.name)
        }
        val pairs = PerformanceData.binancePairs(n)
        assertEquals(n - n / PerformanceData.MARKETS.size, bulkNewCount(PerformanceData.BINANCE.first, pairs, keys))
    }
}
