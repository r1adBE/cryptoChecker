package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vorschläge und «Meintest du …?» für Wallet-Namen — wie iOS `PortfolioWalletNamesTests`. */
class PortfolioWalletNamesTest {

    private val used = listOf("Binance", "Ledger Nano", "Kraken")

    @Test
    fun `fold ignores case, spaces and accents`() {
        assertEquals("binance", PortfolioWalletNames.fold(" Bin ance "))
        assertEquals("ledger", PortfolioWalletNames.fold("Lédger"))
        assertEquals("kraken", PortfolioWalletNames.fold("KRAKEN"))
    }

    @Test
    fun `known keeps the first spelling and drops empty ones`() {
        assertEquals(
            listOf("Binance", "Kraken"),
            PortfolioWalletNames.known(listOf(" Binance ", null, "", "binance", "Kraken", "BIN ANCE")),
        )
    }

    @Test
    fun `typo is detected`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Binanse", used))
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Bianance", used))
        assertEquals("Kraken", PortfolioWalletNames.nearDuplicate("Krakn", used))
    }

    @Test
    fun `spaces and accents count as almost equal`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Bin ance", used))
        assertEquals("Ledger Nano", PortfolioWalletNames.nearDuplicate("LedgerNano", used))
        assertEquals("Kraken", PortfolioWalletNames.nearDuplicate("Krakén", used))
    }

    @Test
    fun `same wallet or foreign name gives no hint`() {
        // Nur Gross/klein bzw. Rand-Leerzeichen: dieselbe Wallet (PortfolioWallets.key)
        assertNull(PortfolioWalletNames.nearDuplicate("binance", used))
        assertNull(PortfolioWalletNames.nearDuplicate(" Binance ", used))
        assertNull(PortfolioWalletNames.nearDuplicate("", used))
        assertNull(PortfolioWalletNames.nearDuplicate("Trezor", used))
        // Drei Tippfehler: zu weit weg
        assertNull(PortfolioWalletNames.nearDuplicate("Bonanxy", listOf("Binance")))
    }

    @Test
    fun `short names only with few or no typos`() {
        assertNull(PortfolioWalletNames.nearDuplicate("OKX", listOf("BTC")))
        assertNull(PortfolioWalletNames.nearDuplicate("OKX", listOf("OKEX")))
        assertEquals("Bybit", PortfolioWalletNames.nearDuplicate("Bybitt", listOf("Bybit")))
        assertNull(PortfolioWalletNames.nearDuplicate("Bybxtt", listOf("Bybit")))
        assertEquals(0, PortfolioWalletNames.maxDistance(3))
        assertEquals(1, PortfolioWalletNames.maxDistance(5))
        assertEquals(2, PortfolioWalletNames.maxDistance(6))
    }

    @Test
    fun `on a tie the first known name wins`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Binancx", listOf("Binance", "Binancy")))
    }

    @Test
    fun `suggestions without input are the used names`() {
        assertEquals(used, PortfolioWalletNames.suggestions(used, listOf("Bitstamp"), ""))
        assertEquals(listOf("Binance"), PortfolioWalletNames.suggestions(used, emptyList(), " ", limit = 1))
    }

    @Test
    fun `suggestions with input starts-with first then contains`() {
        val catalog = listOf("Bitstamp", "Coinbase", "binance", "Bitget")
        assertEquals(listOf("Binance", "Bitstamp", "Bitget"), PortfolioWalletNames.suggestions(used, catalog, "bi"))
        assertEquals(listOf("Coinbase"), PortfolioWalletNames.suggestions(used, catalog, "base"))
        // Der schon getippte Name selbst nicht
        assertEquals(emptyList<String>(), PortfolioWalletNames.suggestions(used, catalog, "binance"))
    }

    @Test
    fun `Editierdistanz`() {
        assertEquals(0, PortfolioWalletNames.distance("abc", "abc"))
        assertEquals(1, PortfolioWalletNames.distance("binance", "binanse"))
        assertEquals(2, PortfolioWalletNames.distance("kraken", "krkn"))
        assertEquals(3, PortfolioWalletNames.distance("", "abc"))
    }
}
