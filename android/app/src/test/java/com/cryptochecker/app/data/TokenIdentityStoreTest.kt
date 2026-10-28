package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.market.DataFreshness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Namensliste für «gleicher Token» ([TokenIdentityStore], K-8): Namen je Börse und von Binance, bereinigt,
 * in einer Datei ohne Sicherung gespeichert und nach einem Neustart wieder gelesen; dazu die Sperren der
 * Ausweich-Reihen und der Konsens-Name aus der Merkliste.
 */
class TokenIdentityStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: File) = File(root, "no_backup/token_names.json")

    @Test
    fun namesAreCleanedStoredAndReadAfterRestart() {
        val root = temp.newFolder()
        val store = testTokenIdentityStore(root)
        assertNull(store.pairName("Kraken", "BTC"))
        assertTrue(store.exchangeNames("Kraken").isEmpty())

        // Kürzel gross, Leerzeichen weg; leere oder zu lange Namen fallen weg
        store.saveExchange("Kraken", mapOf(" btc " to " Bitcoin ", "ETH" to "", "LONG" to "x".repeat(81)))
        store.saveBinance(mapOf("btc" to "Bitcoin", "CAT" to "Catcoin"))
        assertEquals("Bitcoin", store.pairName("Kraken", "btc"))
        assertEquals(mapOf("BTC" to "Bitcoin"), store.exchangeNames("Kraken"))
        assertEquals("Catcoin", store.binanceName("cat"))
        assertTrue(file(root).isFile)
        assertFalse("Hilfsdatei umbenannt", File(root, "no_backup/token_names.json.tmp").exists())

        // Neue Instanz (Neustart) liest die Datei
        val restarted = testTokenIdentityStore(root)
        assertEquals("Bitcoin", restarted.pairName("Kraken", "BTC"))
        assertEquals("Catcoin", restarted.binanceName("CAT"))
        assertNull(restarted.pairName("Kraken", "LONG"))
    }

    @Test
    fun emptyListsKeepTheStoredNames() {
        val root = temp.newFolder()
        val store = testTokenIdentityStore(root)
        store.saveExchange("Kraken", mapOf("BTC" to "Bitcoin"))
        store.saveBinance(mapOf("BTC" to "Bitcoin"))
        val before = file(root).readText()

        store.saveExchange("Kraken", emptyMap())
        store.saveExchange("Kraken", mapOf("" to "Leer"))
        store.saveBinance(mapOf("BTC" to " "))
        // Gleicher Stand: keine neue Datei geschrieben
        store.saveExchange("Kraken", mapOf("BTC" to "Bitcoin"))
        assertEquals(before, file(root).readText())
        assertEquals("Bitcoin", store.pairName("Kraken", "BTC"))
        assertEquals("Bitcoin", store.binanceName("BTC"))
    }

    @Test
    fun unreadableFileCountsAsEmpty() {
        val root = temp.newFolder()
        file(root).apply { parentFile!!.mkdirs() }.writeText("{kaputt")
        val store = testTokenIdentityStore(root)
        assertNull(store.binanceName("BTC"))
        // Erster Schreibvorgang ersetzt die kaputte Datei
        store.saveBinance(mapOf("BTC" to "Bitcoin"))
        assertEquals("Bitcoin", testTokenIdentityStore(root).binanceName("BTC"))
    }

    @Test
    fun otherNamesListsEveryExchange() {
        val store = testTokenIdentityStore(temp.newFolder())
        store.saveExchange("Kraken", mapOf("CAT" to "Simon's Cat"))
        store.saveExchange("Mexc", mapOf("CAT" to "Catcoin", "BTC" to "Bitcoin"))
        assertEquals(setOf("Simon's Cat", "Catcoin"), store.otherNames("cat").toSet())
        assertEquals(listOf<String?>("Bitcoin"), store.otherNames("BTC"))
        assertTrue(store.otherNames("ETH").isEmpty())
    }

    @Test
    fun binanceAndChainSourcesFollowTheNames() {
        val store = testTokenIdentityStore(temp.newFolder())
        // Unbekannte Namen: erlaubt (Kursband entscheidet)
        assertTrue(store.binanceAllowed("CAT", null))
        assertTrue(store.binanceAllowed("CAT", "Simon's Cat"))
        assertTrue(store.chainSourceAllowed("CAT", "Simon's Cat", "data-api.binance.vision"))

        store.saveBinance(mapOf("CAT" to "Catcoin"))
        assertTrue(store.binanceAllowed("CAT", null))
        assertFalse(store.binanceAllowed("CAT", "Simon's Cat"))
        assertTrue(store.binanceAllowed("CAT", "Catcoin"))
        // Widerspruch zum Binance-Namen sperrt die ganze Kette, auch Coinbase
        assertFalse(store.chainSourceAllowed("CAT", "Simon's Cat", "data-api.binance.vision"))
        assertFalse(store.chainSourceAllowed("CAT", "Simon's Cat", "api.exchange.coinbase.com"))
        assertTrue(store.chainSourceAllowed("CAT", "Catcoin", "api.binance.us"))
    }

    @Test
    fun fallbackSeriesAndChangeUseThePairName() {
        val store = testTokenIdentityStore(temp.newFolder())
        store.saveExchange("Kraken", mapOf("CAT" to "Simon's Cat"))
        store.saveBinance(mapOf("CAT" to "Catcoin"))
        // Ohne Anbieter keine Ausweich-Reihe zu prüfen
        assertTrue(store.fallbackSeriesAllowed("Kraken", "CAT", null))
        assertFalse(store.fallbackSeriesAllowed("Kraken", "CAT", DataFreshness.BINANCE))
        assertTrue("Börse ohne Namen", store.fallbackSeriesAllowed("Mexc", "CAT", DataFreshness.BINANCE))

        // Coinbase-Name aus der Paarliste «Coinbase» gilt als Name des Anbieters
        store.saveExchange("Coinbase", mapOf("ETH" to "Ethereum"))
        store.saveExchange("Kraken", mapOf("ETH" to "Ethereum", "CAT" to "Simon's Cat"))
        assertTrue(store.fallbackSeriesAllowed("Kraken", "ETH", DataFreshness.COINBASE))

        // %-Veränderung: ohne Anbieter nie; fremde Börse nur mit belegt gleichem Namen
        assertFalse(store.fallbackChangeAllowed("Kraken", "Kraken", "ETH", null))
        assertFalse(store.fallbackChangeAllowed("Kraken", "Kraken", "ETH", " "))
        assertTrue(store.fallbackChangeAllowed("Kraken", "Kraken", "ETH", DataFreshness.COINBASE))
        assertFalse("Name unbekannt", store.fallbackChangeAllowed("Mexc", "MEXC", "ETH", DataFreshness.COINBASE))
        assertFalse(store.fallbackChangeAllowed("Kraken", "Kraken", "CAT", DataFreshness.BINANCE))
        // Kerzen derselben Börse: Regel der Ausweich-Reihe
        assertTrue(store.fallbackChangeAllowed("Coinbase", "Coinbase", "ETH", DataFreshness.COINBASE))
    }

    @Test
    fun watchNameIsTheConsensusOfActivePairs() = runBlocking {
        val root = temp.newFolder()
        var watches = listOf(
            watch("Kraken", "TON"),
            watch("Mexc", "TON"),
            watch("Gate", "TON", disabled = true),
            watch("Kraken", "BTC"),
        )
        val store = testTokenIdentityStore(root) { watches }
        store.saveExchange("Kraken", mapOf("TON" to "Toncoin", "BTC" to "Bitcoin"))
        store.saveExchange("Mexc", mapOf("TON" to "The Open Network"))
        store.saveExchange("Gate", mapOf("TON" to "Tokamak Network"))
        // Gleiche Gruppe (Toncoin = The Open Network); das deaktivierte Paar zählt nicht
        assertEquals("Toncoin", store.watchName("ton"))

        watches = watches.map { if (it.exchangeKey == "Gate") it.copy(disabled = false) else it }
        assertNull("Widerspruch → kein Name", store.watchName("TON"))
        assertNull("nicht in der Merkliste", store.watchName("ETH"))
    }

    @Test
    fun unreadableWatchlistGivesNoName() = runBlocking {
        val store = testTokenIdentityStore(temp.newFolder())
        store.saveExchange("Kraken", mapOf("TON" to "Toncoin"))
        assertNull(store.watchName("TON"))
    }

    private fun watch(exchangeKey: String, base: String, disabled: Boolean = false) = WatchEntity(
        exchangeKey = exchangeKey, exchangeName = exchangeKey, baseAsset = base, quoteAsset = "USDT", disabled = disabled,
    )
}
