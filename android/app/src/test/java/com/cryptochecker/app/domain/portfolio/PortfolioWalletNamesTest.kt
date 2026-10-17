package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Build 46: Vorschläge und «Meintest du …?» für Wallet-Namen — wie iOS `PortfolioWalletNamesTests`. */
class PortfolioWalletNamesTest {

    private val used = listOf("Binance", "Ledger Nano", "Kraken")

    @Test
    fun `fold ignoriert Gross-klein Leerzeichen und Akzente`() {
        assertEquals("binance", PortfolioWalletNames.fold(" Bin ance "))
        assertEquals("ledger", PortfolioWalletNames.fold("Lédger"))
        assertEquals("kraken", PortfolioWalletNames.fold("KRAKEN"))
    }

    @Test
    fun `known behaelt die erste Schreibweise und laesst leere weg`() {
        assertEquals(
            listOf("Binance", "Kraken"),
            PortfolioWalletNames.known(listOf(" Binance ", null, "", "binance", "Kraken", "BIN ANCE")),
        )
    }

    @Test
    fun `Tippfehler wird erkannt`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Binanse", used))
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Bianance", used))
        assertEquals("Kraken", PortfolioWalletNames.nearDuplicate("Krakn", used))
    }

    @Test
    fun `Leerzeichen und Akzente gelten als fast gleich`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Bin ance", used))
        assertEquals("Ledger Nano", PortfolioWalletNames.nearDuplicate("LedgerNano", used))
        assertEquals("Kraken", PortfolioWalletNames.nearDuplicate("Krakén", used))
    }

    @Test
    fun `gleiche Wallet oder fremder Name ergibt keinen Hinweis`() {
        // Nur Gross/klein bzw. Rand-Leerzeichen: dieselbe Wallet (PortfolioWallets.key)
        assertNull(PortfolioWalletNames.nearDuplicate("binance", used))
        assertNull(PortfolioWalletNames.nearDuplicate(" Binance ", used))
        assertNull(PortfolioWalletNames.nearDuplicate("", used))
        assertNull(PortfolioWalletNames.nearDuplicate("Trezor", used))
        // Drei Tippfehler: zu weit weg
        assertNull(PortfolioWalletNames.nearDuplicate("Bonanxy", listOf("Binance")))
    }

    @Test
    fun `kurze Namen nur mit wenigen oder keinen Tippfehlern`() {
        assertNull(PortfolioWalletNames.nearDuplicate("OKX", listOf("BTC")))
        assertNull(PortfolioWalletNames.nearDuplicate("OKX", listOf("OKEX")))
        assertEquals("Bybit", PortfolioWalletNames.nearDuplicate("Bybitt", listOf("Bybit")))
        assertNull(PortfolioWalletNames.nearDuplicate("Bybxtt", listOf("Bybit")))
        assertEquals(0, PortfolioWalletNames.maxDistance(3))
        assertEquals(1, PortfolioWalletNames.maxDistance(5))
        assertEquals(2, PortfolioWalletNames.maxDistance(6))
    }

    @Test
    fun `bei Gleichstand gewinnt der erste bekannte Name`() {
        assertEquals("Binance", PortfolioWalletNames.nearDuplicate("Binancx", listOf("Binance", "Binancy")))
    }

    @Test
    fun `Vorschlaege ohne Eingabe sind die benutzten Namen`() {
        assertEquals(used, PortfolioWalletNames.suggestions(used, listOf("Bitstamp"), ""))
        assertEquals(listOf("Binance"), PortfolioWalletNames.suggestions(used, emptyList(), " ", limit = 1))
    }

    @Test
    fun `Vorschlaege mit Eingabe zuerst beginnt-mit dann enthaelt`() {
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
