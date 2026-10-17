package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Build 46: neue Arten und Felder in Sicherung und Systemsicherungs-Kopie ([PortfolioJson]). */
class PortfolioJsonTypesTest {

    private val transfer = PortfolioTxEntity(
        id = 5, coin = "BTC", type = PortfolioTxType.TRANSFER, amount = BigDecimal("0.5"), time = 1_000L,
        fee = BigDecimal("0.0001"), feeUnit = PortfolioFeeUnit.COIN.name,
        transferFrom = "Binance", transferTo = "Kraken",
        feeCoin = "BNB", feeCoinAmount = BigDecimal("0.00000001"), feeCoinPriceUsdt = BigDecimal("612.5"),
    )

    @Test
    fun roundTripKeepsNewFieldsExactly() {
        val back = PortfolioJson.jsonToTx(JSONObject(PortfolioJson.txToJson(transfer).toString()))
        assertEquals(transfer, back)
        // Systemsicherungs-Kopie: gleiches Format
        val content = PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(listOf(transfer), emptyList()))
        assertEquals(listOf(transfer), content.txs)
        assertEquals(0, content.invalid)
    }

    @Test
    fun olderBackupsWithoutNewFieldsReadAsBefore() {
        val o = JSONObject().put("id", 1).put("coin", "eth").put("type", "BUY").put("amount", 2.0).put("time", 7L)
        val t = PortfolioJson.jsonToTx(o)!!
        assertNull(t.transferFrom); assertNull(t.transferTo); assertNull(t.feeCoin); assertNull(t.feeCoinAmount)
        assertEquals("ETH", t.coin)
    }

    @Test
    fun unknownTypeIsCountedAsInvalidNotDropped() {
        val arr = JSONArray()
            .put(PortfolioJson.txToJson(transfer))
            .put(JSONObject().put("coin", "BTC").put("type", "LOAN").put("amountText", "1").put("time", 1L))
        val parsed = PortfolioJson.parseTxs(arr)
        assertEquals(1, parsed.items.size)
        assertEquals(1, parsed.invalid)
    }

    @Test
    fun incompleteOrSameCoinFeeIsDropped() {
        val sameCoin = PortfolioJson.txToJson(transfer.copy(feeCoin = "btc"))
        val t = PortfolioJson.jsonToTx(sameCoin)!!
        assertNull(t.feeCoin); assertNull(t.feeCoinAmount); assertNull(t.feeCoinPriceUsdt)
        val negativePrice = PortfolioJson.txToJson(transfer).put("feeCoinPriceText", "-1")
        assertNull(PortfolioJson.jsonToTx(negativePrice)!!.feeCoinPriceUsdt)
        assertEquals("BNB" to BigDecimal("2"), PortfolioJson.cleanFeeCoin(" bnb ", "BTC", BigDecimal("2")))
        assertNull(PortfolioJson.cleanFeeCoin("BNB", "BTC", BigDecimal.ZERO))
    }

    @Test
    fun originalPurchaseDateRoundTripsOnlyForDeposits() {
        val deposit = PortfolioTxEntity(id = 4, coin = "BTC", type = PortfolioTxType.DEPOSIT, amount = BigDecimal("1"), time = 9L, acquiredTime = 3L)
        assertEquals(3L, PortfolioJson.jsonToTx(PortfolioJson.txToJson(deposit))!!.acquiredTime)
        assertEquals(3L, deposit.toTrade().acquiredTime)
        // Andere Arten: verworfen
        val buy = deposit.copy(type = PortfolioTxType.BUY)
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(buy))!!.acquiredTime)
        assertNull(buy.toTrade().acquiredTime)
        assertNull(PortfolioJson.cleanAcquiredTime(PortfolioTxType.DEPOSIT, 0L))
        // Ältere Sicherung ohne Feld
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(deposit).apply { remove("acquiredTime") })!!.acquiredTime)
    }

    /** Build 58: Schenkung an Ehe-/Lebenspartner als eigenes Feld — übersteht Sicherung und Systemsicherungs-Kopie. Wie iOS. */
    @Test
    fun spouseGiftFlagRoundTrips() {
        val gift = PortfolioTxEntity(
            id = 8, coin = "BTC", type = PortfolioTxType.GIFT, amount = BigDecimal("0.1"), time = 12L, exchange = "Ledger",
            giftToSpouse = true,
        )
        val json = PortfolioJson.txToJson(gift)
        assertEquals(true, json.getBoolean("giftToSpouse"))
        assertTrue(json.isNull("transferTo"))
        val back = PortfolioJson.jsonToTx(JSONObject(json.toString()))!!
        assertEquals(gift, back)
        assertTrue(back.toTrade().isGiftToSpouse)
        assertEquals(listOf(gift), PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(listOf(gift), emptyList())).txs)
        // Bei einer anderen Art gibt es den Merker nicht (Sicherung von aussen)
        val withdrawal = PortfolioJson.jsonToTx(JSONObject(json.toString()).put("type", "WITHDRAWAL"))!!
        assertFalse(withdrawal.giftToSpouse)
        assertFalse(gift.copy(type = PortfolioTxType.WITHDRAWAL).toTrade().isGiftToSpouse)
    }

    /** Sicherung bis Build 57: Merker «@spouse» und gepackte Verknüpfung werden beim Wiederherstellen Felder. */
    @Test
    fun oldBackupFormsBecomeFields() {
        val oldGift = JSONObject(PortfolioJson.txToJson(PortfolioTxEntity(id = 9, coin = "BTC", type = PortfolioTxType.GIFT,
            amount = BigDecimal("0.1"), time = 12L)).toString())
            .put("transferTo", "@spouse").apply { remove("giftToSpouse") }
        val gift = PortfolioJson.jsonToTx(oldGift)!!
        assertTrue(gift.giftToSpouse)
        assertNull(gift.transferTo)
        val oldLink = JSONObject(PortfolioJson.txToJson(transfer).toString())
            .put("importKey", "BINANCE|W ⇄ COINBASE|D ⟲ 2|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000" +
                "||||||||| ⟲ DEPOSIT|0.0049||1704989100000||||")
            .put("importBatch", "COINBASE:1729100000000:1b2c3d4e")
            .apply { remove("linkedImportKey"); remove("importRestoreState") }
        val linked = PortfolioJson.jsonToTx(oldLink)!!
        assertEquals("BINANCE|W", linked.importKey)
        assertEquals("COINBASE|D", linked.linkedImportKey)
        assertEquals("BINANCE:1729000000000:0a1b2c3d", linked.importRestoreState!!.importBatch)
        assertEquals("COINBASE|D", linked.importRestoreState!!.absorbed!!.importKey)
        // Neue Form übersteht Sicherung und Systemsicherungs-Kopie unverändert
        assertEquals(linked, PortfolioJson.jsonToTx(JSONObject(PortfolioJson.txToJson(linked).toString())))
        assertEquals(listOf(linked), PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(listOf(linked), emptyList())).txs)
        // Zweiter Schlüssel ohne ersten bzw. Zustand ohne Verknüpfung: weggelassen
        val orphan = PortfolioJson.jsonToTx(JSONObject(PortfolioJson.txToJson(linked).toString()).put("importKey", JSONObject.NULL))!!
        assertNull(orphan.linkedImportKey)
        assertNull(orphan.importRestoreState)
    }

    private val swap = PortfolioTxEntity(
        id = 7, coin = "BTC", type = PortfolioTxType.SWAP, amount = BigDecimal("0.01"), time = 11L, exchange = "Kraken",
        toCoin = "ETH", toAmount = BigDecimal("0.25000001"), swapValueUsdt = BigDecimal("600.5"),
        feeCoin = "ETH", feeCoinAmount = BigDecimal("0.001"), importBatch = "RELAI:5:0000abcd",
    )

    @Test
    fun swapAndImportBatchRoundTripExactly() {
        val back = PortfolioJson.jsonToTx(JSONObject(PortfolioJson.txToJson(swap).toString()))
        assertEquals(swap, back)
        val content = PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(listOf(swap), emptyList()))
        assertEquals(listOf(swap), content.txs)
        // Gebühr im erhaltenen Coin ist keine dritte Gebühr; Tausch geht in beide Coins
        val trade = swap.toTrade()
        assertNull(trade.thirdFeeCoin)
        assertEquals(0, BigDecimal("0.24900001").compareTo(trade.swapReceived))
        assertEquals(listOf("BTC", "ETH"), swap.coins())
        assertEquals(listOf("BTC", "ETH"), swap.routeEntries().map { it.coin })
        // Unbekannter Wert bleibt unbekannt; Wert nur beim Tausch
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(swap.copy(swapValueUsdt = null)))!!.swapValueUsdt)
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(swap.copy(type = PortfolioTxType.SELL)))!!.toCoin)
        assertNull(swap.copy(type = PortfolioTxType.BUY).toTrade().toCoin)
    }

    @Test
    fun swapWithoutReceivedCoinIsInvalidAndCounted() {
        val arr = JSONArray()
            .put(PortfolioJson.txToJson(swap).put("toCoin", JSONObject.NULL))
            .put(PortfolioJson.txToJson(swap).put("toCoin", "btc"))
            .put(PortfolioJson.txToJson(swap).put("toAmountText", "0"))
            .put(PortfolioJson.txToJson(swap))
        val parsed = PortfolioJson.parseTxs(arr)
        assertEquals(1, parsed.items.size)
        assertEquals(3, parsed.invalid)
        // Ältere Sicherung ohne Importlauf; zu lange Kennung wird weggelassen
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(transfer))!!.importBatch)
        assertNull(PortfolioJson.jsonToTx(PortfolioJson.txToJson(swap).put("importBatch", "x".repeat(65)))!!.importBatch)
    }
}
