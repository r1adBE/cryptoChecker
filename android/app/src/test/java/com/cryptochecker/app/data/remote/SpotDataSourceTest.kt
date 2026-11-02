package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.spot.SpotMarket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Spot-Kennzahlen ([SpotDataSource]): zuerst die CoinGecko-Top-40 (für alle gleich), sonst EINE Anfrage nach
 * Kürzel; Ergebnisse und die Rangliste (auch «fehlt») bleiben ~10 Min. im Speicher. Dazu der breite Markt für
 * «Warum?» aus derselben Rangliste.
 */
class SpotDataSourceTest {

    private val updated = Instant.ofEpochMilli(System.currentTimeMillis() - 60_000L).toString()

    private fun coin(id: String, symbol: String, cap: Double, change: Double = 1.0, name: String = id) =
        """{"id":"$id","symbol":"$symbol","name":"$name","current_price":10.5,"market_cap":$cap,""" +
            """"total_volume":5e8,"price_change_percentage_24h":$change,"last_updated":"$updated"}"""

    private val top = listOf(
        coin("bitcoin", "btc", 1.2e12),
        coin("ethereum", "eth", 4e11),
        // Zweiter Coin mit Kürzel «ETH» und kleinerer Marktkapitalisierung: unter gleichem Namen nie gewählt
        coin("ethereum-wormhole", "eth", 1e6),
    ).joinToString(",", "[", "]")

    private val singleUrl = SpotMarket.SYMBOL_URL_PREFIX

    @Test
    fun topListAnswersAndIsKept() = runBlocking {
        val http = RouteHttp { url -> if (url == SpotMarket.TOP_URL) Reply(top) else null }
        val spot = SpotDataSource(http.client)
        val ethereum = SpotMarket.Identity(ownName = "Ethereum")
        val eth = spot.fetch("ETH", ethereum)!!
        assertEquals("ethereum", eth.id)
        assertEquals(4e11, eth.marketCap!!, 0.0)
        assertEquals("bitcoin", spot.fetch("BTC")!!.id)
        // Zweiter Aufruf aus dem Speicher: eine einzige Anfrage (die Rangliste)
        assertEquals("ethereum", spot.fetch("ETH", ethereum)!!.id)
        assertEquals(listOf(SpotMarket.TOP_URL), http.requested)
    }

    @Test
    fun coinOutsideTheTopListIsLookedUpOnce() = runBlocking {
        val http = RouteHttp { url ->
            when (url) {
                SpotMarket.TOP_URL -> Reply(top)
                SpotMarket.url("PEPE") -> Reply("[" + coin("pepe", "pepe", 3e9) + "]")
                SpotMarket.url("NONE") -> Reply("[]")
                else -> null
            }
        }
        val spot = SpotDataSource(http.client)
        assertEquals("pepe", spot.fetch("PEPE")!!.id)
        assertEquals("pepe", spot.fetch("PEPE")!!.id)
        assertEquals(1, http.count(singleUrl + "pepe"))
        // «Nichts gefunden» wird ebenfalls gemerkt
        assertNull(spot.fetch("NONE"))
        assertNull(spot.fetch("NONE"))
        assertEquals(1, http.count(singleUrl + "none"))
    }

    /**
     * PP3: Unter einem Kürzel zählt der Name des Paars, nicht die grösste Marktkapitalisierung. Passt in der Rangliste
     * kein Treffer, fragt es einmal nach dem Kürzel; passt auch dort keiner, keine Spot-Daten statt eines fremden Tokens.
     */
    @Test
    fun hitIsChosenByNameNotBySize() = runBlocking {
        val tonTop = "[" + coin("the-open-network", "ton", 1.7e10, name = "Toncoin") + "]"
        val tonAll = "[" + coin("the-open-network", "ton", 1.7e10, name = "Toncoin") + "," +
            coin("tokamak-network", "ton", 3e7, name = "Tokamak Network") + "]"
        val http = RouteHttp { url ->
            when (url) {
                SpotMarket.TOP_URL -> Reply(tonTop)
                SpotMarket.url("TON") -> Reply(tonAll)
                else -> null
            }
        }
        val spot = SpotDataSource(http.client)
        assertEquals("the-open-network", spot.fetch("TON", SpotMarket.Identity(ownName = "Toncoin"))!!.id)
        assertEquals(0, http.count(singleUrl + "ton"))
        // Tokamak: Rangliste führt nur Toncoin → Einzelabfrage, dort der kleinere Token mit gleichem Namen
        assertEquals("tokamak-network", spot.fetch("TON", SpotMarket.Identity(ownName = "Tokamak Network"))!!.id)
        // Anderer Name: nichts (aus dem Speicher, keine weitere Anfrage)
        assertNull(spot.fetch("TON", SpotMarket.Identity(ownName = "Something Else")))
        // Ohne Namen und mit mehreren Treffern (bekanntes Doppel-Kürzel): nichts statt des grössten
        assertNull(spot.fetch("TON"))
        assertEquals(1, http.count(singleUrl + "ton"))
        // Ohne Namen, mehrere ETH-Treffer in der Rangliste: nichts
        val eth = RouteHttp { url -> if (url == SpotMarket.TOP_URL) Reply(top) else null }
        assertNull(SpotDataSource(eth.client).fetch("ETH"))
    }

    @Test
    fun failuresAreNotKept() = runBlocking {
        val http = RouteHttp { url -> if (url.startsWith(singleUrl)) Reply("""{"status":{"error_code":429}}""", 429) else null }
        val spot = SpotDataSource(http.client)
        assertNull(spot.fetch("SOL"))
        assertNull(spot.fetch("SOL"))
        // Rangliste fehlt: einmal gefragt und als «fehlt» gemerkt; die Einzelabfrage jedes Mal neu
        assertEquals(1, http.count(SpotMarket.TOP_URL))
        assertEquals(2, http.count(singleUrl + "sol"))
    }

    @Test
    fun broadMarketFromTheTopList() = runBlocking {
        // Zwölf zählende Coins mit Veränderung 1…12 %, dazu BTC/USDT (zählen nie mit)
        val coins = (1..12).map { coin("c$it", "c$it", 1e9, change = it.toDouble()) } +
            coin("bitcoin", "btc", 1e12, change = 50.0) + coin("tether", "usdt", 1e11, change = 0.0)
        val http = RouteHttp { url -> if (url == SpotMarket.TOP_URL) Reply(coins.joinToString(",", "[", "]")) else null }
        val spot = SpotDataSource(http.client)
        val all = spot.broadMarket("ETH")!!
        assertEquals(12, all.coins)
        assertEquals(6.5, all.change24h, 1e-9)
        // Ohne den eigenen Coin (C12): Median von 1…11
        val without = spot.broadMarket("c12")
        assertNotNull(without)
        assertEquals(6.0, without!!.change24h, 1e-9)
        assertEquals(1, http.requested.size)

        // Leere Rangliste: kein breiter Markt
        assertNull(SpotDataSource(RouteHttp { Reply(" ") }.client).broadMarket("BTC"))
    }
}
