package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Build 46: Herkunftsschlüssel (CSV-Import) und beobachtete Adressen in Sicherung und Systemsicherungs-Kopie. */
class PortfolioBackupWatchedTest {

    private val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val eth = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    private val imported = PortfolioTxEntity(
        id = 3, coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal("0.001"),
        priceUsdt = BigDecimal("60000"), time = 1_000L, importKey = "relai:2024-01-05T10:00:00:0.001",
    )

    @Test
    fun importKeyRoundTripsAndOldBackupsHaveNone() {
        assertEquals(imported, PortfolioJson.jsonToTx(JSONObject(PortfolioJson.txToJson(imported).toString())))
        val old = JSONObject().put("coin", "BTC").put("type", "BUY").put("amountText", "1").put("time", 1L)
        assertNull(PortfolioJson.jsonToTx(old)!!.importKey)
        // Leer oder absurd lang: keiner
        assertNull(PortfolioJson.cleanImportKey("  "))
        assertNull(PortfolioJson.cleanImportKey("x".repeat(PortfolioJson.MAX_IMPORT_KEY + 1)))
        assertEquals("a", PortfolioJson.cleanImportKey(" a "))
    }

    @Test
    fun mirrorKeepsAddressesWithoutBalance() {
        val entries = listOf(
            WatchedAddressBackupEntry(AddressChain.BTC, btc, "Cold", 5),
            WatchedAddressBackupEntry(AddressChain.ETH, eth.lowercase(), null, 6),
        )
        val text = PortfolioBackupMirror.encode(listOf(imported), emptyList(), entries)
        assertFalse(text.contains("lastBalance"))
        val content = PortfolioBackupMirror.parse(text)
        assertEquals(listOf(imported), content.txs)
        assertEquals(listOf(btc, eth), content.addresses.map { it.address }) // Ethereum mit Prüfsumme
        assertEquals("Cold", content.addresses[0].label)
        assertEquals(6L, content.addresses[1].addedAt)
        assertNull(content.addresses[0].lastBalance)
    }

    @Test
    fun mirrorDropsInvalidAndDuplicateAddresses() {
        val entries = listOf(
            WatchedAddressBackupEntry(AddressChain.BTC, btc),
            WatchedAddressBackupEntry(AddressChain.BTC, btc.uppercase()),
            WatchedAddressBackupEntry(AddressChain.ETH, btc), // falsches Netz
            WatchedAddressBackupEntry(AddressChain.BTC, "bc1qnotanaddress"),
        )
        val content = PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(emptyList(), emptyList(), entries))
        assertEquals(1, content.addresses.size)
    }

    @Test
    fun olderMirrorWithoutAddressesReadsFine() {
        val old = JSONObject()
            .put("format", "cryptochecker-portfolio-system-backup").put("version", 1)
            .put("portfolio", org.json.JSONArray().put(PortfolioJson.txToJson(imported).apply { remove("importKey") }))
            .toString()
        val content = PortfolioBackupMirror.parse(old)
        assertTrue(content.addresses.isEmpty())
        assertNull(content.txs.single().importKey)
    }

    @Test
    fun appBackupFormatMatchesRules() {
        val json = WatchedAddressRules.toJson(listOf(WatchedAddressBackupEntry(AddressChain.BTC, btc, "L", 9)))
        val o = json.getJSONObject(0)
        assertEquals(setOf("chain", "address", "label", "addedAt"), o.keys().asSequence().toSet())
        assertEquals(listOf(WatchedAddressBackupEntry(AddressChain.BTC, btc, "L", 9)), WatchedAddressRules.fromJson(json))
    }
}
